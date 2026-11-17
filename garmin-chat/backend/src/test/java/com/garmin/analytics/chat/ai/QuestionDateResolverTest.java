package com.garmin.analytics.chat.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.garmin.analytics.chat.config.GarminChatProperties;
import com.garmin.analytics.chat.model.DateRange;
import com.garmin.analytics.chat.support.ChatTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class QuestionDateResolverTest {

  @ParameterizedTest
  @CsvSource({
      "EXPLICIT, 2026-11-09, 2026-11-15, 0, 2026-11-09, 2026-11-15",
      "EXPLICIT, 2024-02-29, 2024-02-29, 0, 2024-02-29, 2024-02-29",
      "LAST_N_DAYS, '', '', 7, 2026-11-11, 2026-11-17",
      "LAST_N_DAYS, '', '', 14, 2026-11-04, 2026-11-17",
      "LAST_N_DAYS, '', '', 1, 2026-11-17, 2026-11-17",
      "LAST_WEEK, '', '', 0, 2026-11-09, 2026-11-15",
      "THIS_WEEK, '', '', 0, 2026-11-16, 2026-11-18",
      "LAST_MONTH, '', '', 0, 2026-10-01, 2026-10-31",
      "THIS_MONTH, '', '', 0, 2026-11-01, 2026-11-18",
      "YESTERDAY, '', '', 0, 2026-11-17, 2026-11-17",
      "TODAY, '', '', 0, 2026-11-18, 2026-11-18"
  })
  void resolvesStructuredSelectionsWithoutModelDateArithmetic(
      String type, String from, String to, int days, LocalDate expectedStart, LocalDate expectedEnd)
      throws Exception {
    var resolver = resolver("2026-11-18T12:00:00Z", "Europe/Oslo");
    var result = resolver.resolve(selection(type, from, to, days), resolver.referenceDate());
    assertThat(result).contains(new DateRange(expectedStart, expectedEnd));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"type\":\"EXPLICIT\",\"startDate\":\"2026-02-29\",\"endDate\":\"2026-03-01\",\"days\":0}",
      "{\"type\":\"EXPLICIT\",\"startDate\":\"2026-11-15\",\"endDate\":\"2026-11-09\",\"days\":0}",
      "{\"type\":\"EXPLICIT\",\"startDate\":\"2026-13-01\",\"endDate\":\"2026-13-02\",\"days\":0}",
      "{\"type\":\"EXPLICIT\",\"startDate\":\"2027-01-01\",\"endDate\":\"2027-01-02\",\"days\":0}",
      "{\"type\":\"EXPLICIT\",\"startDate\":\"03/04/26\",\"endDate\":\"03/05/26\",\"days\":0}",
      "{\"type\":\"EXPLICIT\",\"startDate\":\"2026-11-09; DROP TABLE x\","
          + "\"endDate\":\"2026-11-15\",\"days\":0}",
      "{\"type\":\"LAST_N_DAYS\",\"startDate\":\"\",\"endDate\":\"\",\"days\":0}",
      "{\"type\":\"LAST_N_DAYS\",\"startDate\":\"\",\"endDate\":\"\",\"days\":-1}",
      "{\"type\":\"LAST_N_DAYS\",\"startDate\":\"\",\"endDate\":\"\",\"days\":7.5}",
      "{\"type\":\"LAST_N_DAYS\",\"startDate\":\"2026-11-01\",\"endDate\":\"\",\"days\":7}",
      "{\"type\":\"LAST_N_DAYS\",\"startDate\":\"\",\"endDate\":\"\",\"days\":2147483647}",
      "{\"type\":\"LAST_WEEK\",\"startDate\":\"\",\"endDate\":\"\",\"days\":7}",
      "{\"type\":\"CLARIFICATION\",\"startDate\":\"\",\"endDate\":\"\",\"days\":0}",
      "{\"type\":\"LAST_WEEK\"}", "{\"type\":\"UNKNOWN\"}", "{}", "null", "not JSON",
      "{\"type\":\"TODAY\",\"startDate\":\"\",\"endDate\":\"\",\"days\":0} {}",
      "{\"type\":\"LAST_N_DAYS\",\"startDate\":\"\",\"endDate\":\"\",\"days\":\"7\"}",
      "{\"type\":\"TODAY\",\"type\":\"YESTERDAY\",\"startDate\":\"\",\"endDate\":\"\",\"days\":0}",
      "{\"type\":0,\"startDate\":\"\",\"endDate\":\"\",\"days\":0}"
  })
  void rejectsInvalidMissingAndAmbiguousSelections(String json) throws Exception {
    var resolver = resolver("2026-11-18T12:00:00Z", "Europe/Oslo");
    assertThat(resolver.resolve(json, resolver.referenceDate())).isEmpty();
  }

  @Test
  void usesConfiguredTimezoneWhenUtcAndLocalDatesDiffer() throws Exception {
    var resolver = resolver("2026-11-16T23:30:00Z", "Europe/Oslo");
    assertThat(resolver.referenceDate()).isEqualTo(LocalDate.of(2026, 11, 17));
    assertThat(resolver.resolve(ChatTestSupport.LAST_SEVEN, resolver.referenceDate()))
        .contains(new DateRange(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 16)));
  }

  @Test
  void usesSevenLocalDatesAcrossDaylightSavingTime() throws Exception {
    var resolver = resolver("2026-03-30T00:30:00Z", "Europe/Oslo");
    assertThat(resolver.resolve(ChatTestSupport.LAST_SEVEN, resolver.referenceDate()))
        .contains(new DateRange(LocalDate.of(2026, 3, 23), LocalDate.of(2026, 3, 29)));
  }

  @Test
  void handlesPreviousMonthAcrossTheYearBoundary() throws Exception {
    var resolver = resolver("2027-01-01T12:00:00Z", "Europe/Oslo");
    assertThat(resolver.resolve(selection("LAST_MONTH", "", "", 0), resolver.referenceDate()))
        .contains(new DateRange(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)));
  }

  @Test
  void requestsNativeStructuredOutputAndSuppliesAnExplicitLocalReferenceDate() throws Exception {
    var resolver = resolver("2026-11-16T23:30:00Z", "Europe/Oslo");
    String question = "  Як я спав за останні сім днів? {verbatim}  ";
    var prompt = resolver.prompt(question, resolver.referenceDate());
    assertThat(prompt.getInstructions().getFirst().getText())
        .contains("2026-11-17", "Europe/Oslo");
    assertThat(prompt.getInstructions().getLast().getText()).isEqualTo(question);
    var options = (OllamaChatOptions) prompt.getOptions();
    assertThat(options.getNumPredict()).isEqualTo(256);
    assertThat(options.getFormat()).isInstanceOf(Map.class);
    var schema = (Map<?, ?>) options.getFormat();
    assertThat(schema.get("additionalProperties")).isEqualTo(false);
    assertThat(schema.get("required").toString()).contains("type", "startDate", "endDate", "days");
    assertThat(((Map<?, ?>) schema.get("properties")).keySet().stream().map(Object::toString))
        .containsExactly("type", "days", "startDate", "endDate");
  }

  private QuestionDateResolver resolver(String instant, String zone) throws Exception {
    var properties = new GarminChatProperties(123L, ZoneId.of(zone), 8192, 1024, 31);
    return ChatTestSupport.dates(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), properties);
  }

  @Test
  void springAiPreservesQuestionIsolationAndTypeFirstSchemaOnTheOllamaWire() throws Exception {
    var json = new ObjectMapper();
    var client = RestClient.builder();
    var server = MockRestServiceServer.bindTo(client).build();
    String question = "как хорошо я спал последнюю неделю";
    var resolver = resolver("2026-11-18T12:00:00Z", "Europe/Oslo");
    server.expect(requestTo("http://ollama:11434/api/chat")).andExpect(request -> {
      var body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
      assertThat(body.at("/messages/0/content").asText()).contains("2026-11-18", "Europe/Oslo");
      assertThat(body.at("/messages/1/content").asText()).isEqualTo(question);
      var fields = new java.util.ArrayList<String>();
      body.at("/format/properties").fieldNames().forEachRemaining(fields::add);
      assertThat(fields).containsExactly("type", "days", "startDate", "endDate");
    }).andRespond(withSuccess(json.writeValueAsString(Map.of(
        "model", "qwen2.5:1.5b-instruct-q4_K_M", "created_at", "2026-11-18T12:00:00Z",
        "done", true, "done_reason", "stop", "message", Map.of(
            "role", "assistant", "content", ChatTestSupport.LAST_SEVEN))),
        MediaType.APPLICATION_JSON));
    var model = OllamaChatModel.builder()
        .ollamaApi(OllamaApi.builder().baseUrl("http://ollama:11434")
            .restClientBuilder(client).build())
        .defaultOptions(OllamaChatOptions.builder().model("qwen2.5:1.5b-instruct-q4_K_M").build())
        .build();

    var reference = resolver.referenceDate();
    var response = model.call(resolver.prompt(question, reference));
    assertThat(resolver.resolve(response.getResult().getOutput().getText(), reference))
        .contains(new DateRange(LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 17)));
    server.verify();
  }

  private String selection(String type, String start, String end, int days) {
    return "{\"type\":\"" + type + "\",\"startDate\":\"" + start
        + "\",\"endDate\":\"" + end + "\",\"days\":" + days + "}";
  }
}
