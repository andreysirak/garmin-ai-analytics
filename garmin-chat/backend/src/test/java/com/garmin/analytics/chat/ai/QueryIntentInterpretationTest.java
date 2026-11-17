package com.garmin.analytics.chat.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.garmin.analytics.chat.config.GarminChatProperties;
import com.garmin.analytics.chat.model.DateRange;
import com.garmin.analytics.chat.model.QueryIntent;
import com.garmin.analytics.chat.support.ChatTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class QueryIntentInterpretationTest {

  private static final String LAST_WEEK =
      "{\"type\":\"LAST_WEEK\",\"days\":0,\"startDate\":\"\",\"endDate\":\"\"}";
  private static final String CLARIFICATION =
      LAST_WEEK.replace("LAST_WEEK", "CLARIFICATION");
  private static final String VALID = intent("OK", "[\"restingHeartRate\"]", "AVG", LAST_WEEK);
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);

  @ParameterizedTest
  @CsvSource({
      "totalSteps, SUM", "totalSteps, AVG", "totalSteps, MIN", "totalSteps, MAX",
      "restingHeartRate, AVG", "restingHeartRate, MIN", "restingHeartRate, MAX",
      "activeKilocalories, SUM", "activeKilocalories, AVG",
      "activeKilocalories, MIN", "activeKilocalories, MAX",
      "sleepingSeconds, SUM", "sleepingSeconds, AVG",
      "sleepingSeconds, MIN", "sleepingSeconds, MAX"
  })
  void acceptsOnlyTheApprovedMetricAggregationPairs(String field, String aggregation)
      throws Exception {
    var result = resolver(31).resolveIntent(
        intent("OK", "[\"" + field + "\"]", aggregation, LAST_WEEK), TODAY);

    assertThat(result.accepted()).isTrue();
    assertThat(result.message()).isEmpty();
    assertThat(result.intent().metrics())
        .containsExactly(QueryIntent.Metric.fromField(field).get());
    assertThat(result.intent().aggregation())
        .isEqualTo(QueryIntent.Aggregation.valueOf(aggregation));
    assertThat(result.intent().range()).isEqualTo(
        new DateRange(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15)));
  }

  @Test
  void resolvesMultipleMetricsToAnImmutableCatalogSelectedQuery() throws Exception {
    var result = resolver(31).resolveIntent(intent("OK",
        "[\"totalSteps\",\"restingHeartRate\",\"activeKilocalories\",\"sleepingSeconds\"]",
        "AVG", LAST_WEEK), TODAY);

    assertThat(result.accepted()).isTrue();
    assertThat(result.intent().metrics()).containsExactly(QueryIntent.Metric.TOTAL_STEPS,
        QueryIntent.Metric.RESTING_HEART_RATE, QueryIntent.Metric.ACTIVE_KILOCALORIES,
        QueryIntent.Metric.SLEEPING_SECONDS);
    assertThat(result.intent().metrics().getLast().unit()).isEqualTo("hours");
    assertThat(result.intent().metrics().getLast().divisor()).isEqualTo(3600);
    assertThatThrownBy(() -> result.intent().metrics().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"sleepScore", "bodyBattery", "totalSteps; DROP TABLE x",
      "payload->restingHeartRate", "TotalSteps", "userProfileId", "", "restingHeartRate "})
  void rejectsUnknownFieldsInsteadOfAllowingPathsOrIdentifiers(String field) throws Exception {
    var result = resolver(31).resolveIntent(
        intent("OK", "[\"" + field + "\"]", "AVG", LAST_WEEK), TODAY);
    assertThat(result.accepted()).isFalse();
    assertThat(result.intent()).isNull();
    assertThat(result.message()).contains("Unsupported metric", "totalSteps", "sleepingSeconds");
    assertThat(result.message()).doesNotContain("DROP TABLE", "payload->", "userProfileId");
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUM", "CORR", "DIAGNOSE", "TREND", "TOP_K", "NONE", "avg",
      "AVG(value); DROP TABLE x", ""})
  void rejectsUnsupportedHeartRateOperations(String aggregation) throws Exception {
    var result = resolver(31).resolveIntent(
        intent("OK", "[\"restingHeartRate\"]", aggregation, LAST_WEEK), TODAY);
    assertThat(result.accepted()).isFalse();
    assertThat(result.message()).contains("Unsupported aggregation", "AVG, MIN and MAX only");
  }

  @Test
  void rejectsAnOperationIfEvenOneSelectedMetricDoesNotSupportIt() throws Exception {
    var result = resolver(31).resolveIntent(
        intent("OK", "[\"totalSteps\",\"restingHeartRate\"]", "SUM", LAST_WEEK), TODAY);
    assertThat(result.accepted()).isFalse();
    assertThat(result.message()).contains("Unsupported aggregation");
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNSUPPORTED", "CLARIFICATION"})
  void handlesModelRefusalsAsApplicationMessagesWithoutExecutableIntent(String status)
      throws Exception {
    var result = resolver(31).resolveIntent(intent(status, "[]", "NONE", CLARIFICATION), TODAY);
    assertThat(result.accepted()).isFalse();
    assertThat(result.intent()).isNull();
    assertThat(result.message()).isNotBlank();
    if (status.equals("UNSUPPORTED")) {
      assertThat(result.message()).contains("Sleep quality", "medical", "correlations");
    }
  }

  @ParameterizedTest
  @MethodSource("invalidIntents")
  void strictlyRejectsMalformedContradictoryOrInjectedJson(String text) throws Exception {
    var result = resolver(31).resolveIntent(text, TODAY);
    assertThat(result.accepted()).isFalse();
    assertThat(result.intent()).isNull();
    assertThat(result.message()).contains("could not be interpreted safely");
  }

  private static Stream<String> invalidIntents() throws Exception {
    var invalid = new ArrayList<String>(List.of("", " ", "{}", "null", "[]", "false", "\"text\"",
        "not JSON", VALID + " {}", VALID + " trailing", VALID.replace("\"OK\"", "\"UNKNOWN\""),
        VALID.replace("\"status\":\"OK\"", "\"status\":\"UNSUPPORTED\",\"status\":\"OK\""),
        VALID.replace("\"OK\"", "\"ok\""),
        VALID.replace("\"days\":0", "\"days\":0,\"days\":7"),
        VALID.replace("\"metrics\":[\"restingHeartRate\"]", "\"metrics\":[]"),
        VALID.replace("\"metrics\":[\"restingHeartRate\"]",
            "\"metrics\":[\"totalSteps\",\"totalSteps\"]"),
        intent("UNSUPPORTED", "[\"totalSteps\"]", "NONE", CLARIFICATION),
        intent("CLARIFICATION", "[]", "AVG", CLARIFICATION),
        intent("UNSUPPORTED", "[]", "NONE", LAST_WEEK)));
    var mapper = new ObjectMapper();
    for (String field : List.of("status", "metrics", "aggregation", "period")) {
      var missing = (ObjectNode) mapper.readTree(VALID);
      missing.remove(field);
      invalid.add(missing.toString());
      var nullable = (ObjectNode) mapper.readTree(VALID);
      nullable.putNull(field);
      invalid.add(nullable.toString());
    }
    for (String field : List.of("type", "days", "startDate", "endDate")) {
      var missing = (ObjectNode) mapper.readTree(VALID);
      ((ObjectNode) missing.get("period")).remove(field);
      invalid.add(missing.toString());
      var nullable = (ObjectNode) mapper.readTree(VALID);
      ((ObjectNode) nullable.get("period")).putNull(field);
      invalid.add(nullable.toString());
    }
    for (String key : List.of("profileId", "sql", "unit", "explanation")) {
      var extra = (ObjectNode) mapper.readTree(VALID);
      extra.put(key, "untrusted");
      invalid.add(extra.toString());
    }
    for (String replacement : List.of("\"0\"", "0.0", "false", "2147483648")) {
      invalid.add(VALID.replace("\"days\":0", "\"days\":" + replacement));
    }
    invalid.add(VALID.replace("\"status\":\"OK\"", "\"status\":0"));
    invalid.add(VALID.replace("\"aggregation\":\"AVG\"", "\"aggregation\":0"));
    invalid.add(VALID.replace("[\"restingHeartRate\"]", "[null]"));
    invalid.add(VALID.replace("[\"restingHeartRate\"]", "[123]"));
    invalid.add(VALID.replace("[\"restingHeartRate\"]", "\"restingHeartRate\""));
    invalid.add(VALID.replace("\"startDate\":\"\"", "\"startDate\":0"));
    invalid.add(VALID.replace("\"endDate\":\"\"", "\"endDate\":false"));
    invalid.add(VALID.replace("\"type\":\"LAST_WEEK\"", "\"type\":2"));
    invalid.add(VALID.replace("\"days\":0", "\"profileId\":123,\"days\":0"));
    invalid.add(null);
    return invalid.stream();
  }

  @ParameterizedTest
  @CsvSource({
      "EXPLICIT, 2024-02-29, 2024-02-29, 0, 2024-02-29, 2024-02-29",
      "LAST_N_DAYS, '', '', 7, 2026-11-11, 2026-11-17",
      "LAST_WEEK, '', '', 0, 2026-11-09, 2026-11-15",
      "LAST_MONTH, '', '', 0, 2026-10-01, 2026-10-31",
      "TODAY, '', '', 0, 2026-11-18, 2026-11-18"
  })
  void reusesDeterministicDateResolution(
      String type, String from, String to, int days, LocalDate expectedStart, LocalDate expectedEnd)
      throws Exception {
    var result = resolver(31).resolveIntent(
        intent("OK", "[\"totalSteps\"]", "AVG", period(type, from, to, days)), TODAY);
    assertThat(result.accepted()).isTrue();
    assertThat(result.intent().range()).isEqualTo(new DateRange(expectedStart, expectedEnd));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"type\":\"EXPLICIT\",\"days\":0,\"startDate\":\"2026-02-29\",\"endDate\":\"2026-03-01\"}",
      "{\"type\":\"EXPLICIT\",\"days\":0,\"startDate\":\"2026-11-19\","
          + "\"endDate\":\"2026-11-20\"}",
      "{\"type\":\"EXPLICIT\",\"days\":0,\"startDate\":\"2026-11-15\",\"endDate\":\"2026-11-09\"}",
      "{\"type\":\"EXPLICIT\",\"days\":0,\"startDate\":\"0000-01-01\",\"endDate\":\"0000-01-01\"}",
      "{\"type\":\"EXPLICIT\",\"days\":0,\"startDate\":\"+02026-11-09\","
          + "\"endDate\":\"2026-11-15\"}",
      "{\"type\":\"LAST_N_DAYS\",\"days\":0,\"startDate\":\"\",\"endDate\":\"\"}",
      "{\"type\":\"LAST_N_DAYS\",\"days\":7,\"startDate\":\"2026-11-01\",\"endDate\":\"\"}",
      "{\"type\":\"LAST_WEEK\",\"days\":7,\"startDate\":\"\",\"endDate\":\"\"}",
      "{\"type\":\"CLARIFICATION\",\"days\":0,\"startDate\":\"\",\"endDate\":\"\"}"
  })
  void rejectsInvalidOrMissingPeriods(String period) throws Exception {
    var result = resolver(31).resolveIntent(intent("OK", "[\"totalSteps\"]", "AVG", period), TODAY);
    assertThat(result.accepted()).isFalse();
    assertThat(result.message()).contains("valid, ordered dates", "clear relative period");
  }

  @Test
  void enforcesTheConfiguredInclusiveLimitWithoutTruncating() throws Exception {
    var resolver = resolver(7);
    assertThat(resolver.resolveIntent(intent("OK", "[\"totalSteps\"]", "SUM",
        period("LAST_N_DAYS", "", "", 7)), TODAY).accepted()).isTrue();
    var tooLong = resolver.resolveIntent(intent("OK", "[\"totalSteps\"]", "SUM",
        period("LAST_N_DAYS", "", "", 8)), TODAY);
    assertThat(tooLong.accepted()).isFalse();
    assertThat(tooLong.intent()).isNull();
    assertThat(tooLong.message()).contains("at most 7 calendar days (inclusive)");
    assertThat(resolver(31).resolveIntent(intent("OK", "[\"totalSteps\"]", "SUM",
        period("EXPLICIT", "2026-10-19", "2026-11-18", 0)), TODAY).accepted()).isTrue();
    assertThat(resolver(31).resolveIntent(intent("OK", "[\"totalSteps\"]", "SUM",
        period("EXPLICIT", "2026-10-18", "2026-11-18", 0)), TODAY).accepted()).isFalse();
    assertThat(resolver(30).resolveIntent(VALID.replace("LAST_WEEK", "LAST_MONTH"), TODAY)
        .accepted()).isFalse();
  }

  @Test
  void usesLocalCalendarDatesAcrossDstRatherThanUtcOrElapsedHours() throws Exception {
    var properties = new GarminChatProperties(123L, ZoneId.of("Europe/Oslo"), 8192, 1024, 7);
    var resolver = ChatTestSupport.dates(
        Clock.fixed(Instant.parse("2026-03-29T23:30:00Z"), ZoneOffset.UTC), properties);
    assertThat(resolver.referenceDate()).isEqualTo(LocalDate.of(2026, 3, 30));
    var result = resolver.resolveIntent(intent("OK", "[\"sleepingSeconds\"]", "AVG",
        period("LAST_N_DAYS", "", "", 7)), resolver.referenceDate());
    assertThat(result.intent().range()).isEqualTo(
        new DateRange(LocalDate.of(2026, 3, 23), LocalDate.of(2026, 3, 29)));
  }

  @Test
  void sendsVerbatimQuestionAndClosedNativeSchemaThroughSpringAiWithoutNetwork() throws Exception {
    var mapper = new ObjectMapper();
    var resolver = resolver(31);
    var client = RestClient.builder();
    var server = MockRestServiceServer.bindTo(client).build();
    String question = "  Какова средняя ЧСС покоя за прошлую неделю? {keep}  ";
    var prompt = resolver.intentPrompt(question, TODAY);
    assertThat(new PromptBudget(new GarminChatProperties(
        123L, ZoneId.of("Europe/Oslo"), 8192, 1024, 31)).fits(prompt)).isTrue();
    assertThat(prompt.getInstructions().getFirst().getText()).contains(
        "Medical conclusions", "sleep quality", "not total energy", "31 calendar days");
    server.expect(requestTo("http://ollama:11434/api/chat")).andExpect(request -> {
      var body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
      assertThat(body.at("/messages/1/content").asText()).isEqualTo(question);
      assertThat(body.at("/messages/0/content").asText()).contains("2026-11-18", "Europe/Oslo");
      assertThat(body.at("/format/additionalProperties").asBoolean()).isFalse();
      assertThat(body.at("/format/properties/metrics/items/enum").toString()).isEqualTo(
          "[\"totalSteps\",\"restingHeartRate\",\"activeKilocalories\",\"sleepingSeconds\"]");
      assertThat(body.at("/format/properties/aggregation/enum").toString())
          .isEqualTo("[\"AVG\",\"SUM\",\"MIN\",\"MAX\",\"NONE\"]");
      assertThat(body.at("/format/properties/metrics/uniqueItems").asBoolean()).isTrue();
      var order = new ArrayList<String>();
      body.at("/format/properties/period/properties").fieldNames().forEachRemaining(order::add);
      assertThat(order).containsExactly("type", "days", "startDate", "endDate");
      assertThat(body.at("/format/properties/period/required").size()).isEqualTo(4);
      assertThat(body.at("/options/num_predict").asInt()).isEqualTo(384);
    }).andRespond(withSuccess(mapper.writeValueAsString(Map.of(
        "model", "synthetic-model", "created_at", "2026-11-18T12:00:00Z", "done", true,
        "message", Map.of("role", "assistant", "content", VALID))), MediaType.APPLICATION_JSON));
    var model = OllamaChatModel.builder().ollamaApi(OllamaApi.builder()
        .baseUrl("http://ollama:11434").restClientBuilder(client).build())
        .defaultOptions(OllamaChatOptions.builder().model("synthetic-model").build()).build();
    var response = model.call(prompt);
    assertThat(resolver.resolveIntent(response.getResult().getOutput().getText(), TODAY).accepted())
        .isTrue();
    server.verify();
  }

  private QuestionDateResolver resolver(int maximumDays) throws Exception {
    return ChatTestSupport.dates(Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC),
        new GarminChatProperties(123L, ZoneId.of("Europe/Oslo"), 8192, 1024, maximumDays));
  }

  private static String intent(String status, String metrics, String aggregation, String period) {
    return "{\"status\":\"" + status + "\",\"metrics\":" + metrics
        + ",\"aggregation\":\"" + aggregation + "\",\"period\":" + period + "}";
  }

  private static String period(String type, String start, String end, int days) {
    return "{\"type\":\"" + type + "\",\"days\":" + days
        + ",\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}";
  }
}
