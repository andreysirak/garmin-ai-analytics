package com.garmin.analytics.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.garmin.analytics.chat.repository.impl.DailySummaryRepositoryPostgresImpl;
import com.garmin.analytics.chat.support.ChatTestSupport;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

/** Real MVC, service, repository mapping and Spring AI; JDBC/HTTP boundaries remain offline. */
class GarminChatFlowTest {

  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
  private final RestClient.Builder client = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();

  @ParameterizedTest
  @CsvSource({
      "totalSteps, SUM, 300, steps, 1, "
          + "How many steps did I record in total over the last seven days?",
      "restingHeartRate, AVG, 36, bpm, 1, "
          + "What was my average resting heart rate over the last seven days?",
      "activeKilocalories, MAX, 200.25, kcal, 1, "
          + "What was my maximum daily active calorie burn over the last seven days?",
      "sleepingSeconds, MIN, 0, hours, 3600, "
          + "What was my minimum daily recorded sleep time over the last seven days?",
      "sleepingSeconds, AVG, 1.5, hours, 3600, "
          + "Средняя длительность сна за последние семь дней?"
  })
  void sendsCompactEvidenceInToolMessages(
      String field, String operation, String expectedValue, String unit, int divisor,
      String questionText)
      throws Exception {
    String question = "  " + questionText + " {keep}  ";
    var row = mock(ResultSet.class);
    when(row.getBigDecimal("value")).thenReturn(new BigDecimal(expectedValue));
    when(row.getInt("expected_days")).thenReturn(7);
    when(row.getInt("days_with_data")).thenReturn(3);
    for (String category : new String[] {"missing_summary_days", "missing_metric_days",
        "null_metric_days", "invalid_type_days", "zero_days"}) {
      when(row.getInt(category)).thenReturn(1);
    }
    when(jdbc.queryForObject(anyString(), ArgumentMatchers.any(SqlParameterSource.class),
        ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(call -> {
          SqlParameterSource parameters = call.getArgument(1);
          assertThat(parameters.getValue("profileId")).isEqualTo(123L);
          assertThat(parameters.getValue("startDate")).isEqualTo(Date.valueOf("2026-11-09"));
          assertThat(parameters.getValue("endDate")).isEqualTo(Date.valueOf("2026-11-15"));
          assertThat(parameters.getValue("metric")).isEqualTo(field);
          assertThat(parameters.getValue("aggregation")).isEqualTo(operation);
          assertThat(parameters.getValue("divisor")).isEqualTo(divisor);
          RowMapper<?> mapper = call.getArgument(2);
          return mapper.mapRow(row, 0);
        });
    var arguments = ChatTestSupport.arguments("[\"" + field + "\"]",
        operation, ChatTestSupport.LAST_SEVEN);
    server.expect(requestTo("http://ollama:11434/api/chat")).andExpect(request -> {
      var body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
      assertThat(body.at("/messages/1/content").asText()).isEqualTo(question);
      assertThat(body.at("/messages/0/content").asText()).contains("2026-11-16", "Europe/Oslo");
      assertThat(body.at("/format/properties/route").isObject()).isTrue();
      assertThat(body.at("/format/required")).map(fieldName -> fieldName.asText())
          .containsExactly("route");
      assertThat(body.at("/tools").size()).isZero();
    }).andRespond(withSuccess(modelResponse(ChatTestSupport.analyze(arguments)),
        MediaType.APPLICATION_JSON));
    server.expect(requestTo("http://ollama:11434/api/chat")).andExpect(request -> {
      var body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
      assertThat(body.at("/messages/1/content").asText()).isEqualTo(question);
      assertThat(body.hasNonNull("format")).isFalse();
      assertThat(body.at("/tools/0/function/name").asText()).isEqualTo(ChatTestSupport.TOOL_NAME);
      var period = body.at("/tools/0/function/parameters/properties/period");
      assertThat(period.at("/properties/days/type").asText()).isEqualTo("integer");
      assertThat(period.get("required")).map(fieldName -> fieldName.asText())
          .containsExactlyInAnyOrder("type", "startDate", "endDate", "days");
      assertThat(body.at("/options/num_predict").asInt()).isEqualTo(1024);
    }).andRespond(withSuccess(modelToolResponse(arguments), MediaType.APPLICATION_JSON));
    String explanation = operation + " " + field + ": " + expectedValue + " " + unit
        + ". Coverage is incomplete: 3 of 7 days have numeric readings.";
    server.expect(requestTo("http://ollama:11434/api/chat")).andExpect(request -> {
      var body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
      assertThat(body.get("messages").size()).isEqualTo(4);
      assertThat(body.at("/format").isMissingNode() || body.at("/format").isNull()).isTrue();
      assertThat(body.at("/options/num_predict").asInt()).isEqualTo(1024);
      assertThat(body.at("/messages/1/content").asText()).isEqualTo(question);
      assertThat(body.at("/messages/2/tool_calls/0/function/name").asText())
          .isEqualTo(ChatTestSupport.TOOL_NAME);
      assertThat(body.at("/messages/3/role").asText()).isEqualTo("tool");
      var data = json.readTree(body.at("/messages/3/content").asText());
      assertThat(data.has("question")).isFalse();
      assertThat(data.get("aggregation").asText()).isEqualTo(operation);
      assertThat(data.at("/dateRange/startDate").asText()).isEqualTo("2026-11-09");
      assertThat(data.at("/metrics/0/metric").asText()).isEqualTo(field);
      assertThat(data.at("/metrics/0/value").decimalValue()).isEqualByComparingTo(expectedValue);
      assertThat(data.at("/metrics/0/unit").asText()).isEqualTo(unit);
      assertThat(data.at("/metrics/0/coverage/invalidTypeDays").asInt()).isEqualTo(1);
      assertThat(data.at("/metrics/0/coverage/complete").asBoolean()).isFalse();
      assertThat(data.toString()).doesNotContain("profileId", "user_profile_id", "payload");
    }).andRespond(withSuccess(modelResponse(explanation), MediaType.APPLICATION_JSON));

    mvc().perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("message", question))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value(explanation))
        .andExpect(jsonPath("$.startDate").value("2026-11-09"))
        .andExpect(jsonPath("$.endDate").value("2026-11-15"));
    server.verify();
  }

  @ParameterizedTest
  @CsvSource({
      "Do days with more physical activity tend to be followed by longer sleep?, "
          + "Garmin chat can summarize individual metrics over a period. "
          + "It cannot determine whether activity is related to later sleep.",
      "Average resting heart rate?, Which time period should I use?",
      "How productively did I work yesterday?, Do you mean work productivity or physical activity?"
  })
  void answersWithoutDatabaseAccessWhenOllamaDoesNotCallATool(String question, String answer)
      throws Exception {
    String decision = question.contains("followed by") ? ChatTestSupport.unsupported("RELATIONSHIP")
        : ChatTestSupport.clarify(question.contains("productively") ? "GOAL" : "PERIOD", answer);
    server.expect(requestTo("http://ollama:11434/api/chat"))
        .andRespond(withSuccess(modelResponse(decision), MediaType.APPLICATION_JSON));
    mvc().perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("message", question))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value(answer))
        .andExpect(jsonPath("$.startDate").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.endDate").value(org.hamcrest.Matchers.nullValue()));
    verifyNoInteractions(jdbc);
    server.verify();
  }

  private String modelToolResponse(String arguments) throws Exception {
    return json.writeValueAsString(Map.of("model", "synthetic-model", "done", true,
        "message", Map.of("role", "assistant", "content", "", "tool_calls",
            List.of(Map.of("function",
                Map.of("name", ChatTestSupport.TOOL_NAME,
                    "arguments", json.readTree(arguments)))))));
  }

  private String modelResponse(String content) throws Exception {
    return json.writeValueAsString(Map.of("model", "synthetic-model", "done", true,
        "message", Map.of("role", "assistant", "content", content)));
  }

  private MockMvc mvc() throws Exception {
    var repository = new DailySummaryRepositoryPostgresImpl(jdbc,
        new ClassPathResource("sql/daily-summaries.sql"),
        new ClassPathResource("sql/metric-aggregation.sql"), ChatTestSupport.PROPERTIES);
    var model = OllamaChatModel.builder().ollamaApi(OllamaApi.builder()
        .baseUrl("http://ollama:11434").restClientBuilder(client).build())
        .defaultOptions(OllamaChatOptions.builder().model("synthetic-model").build()).build();
    var service = ChatTestSupport.service(repository, model);
    return MockMvcBuilders.standaloneSetup(new GarminChatController(service))
        .setControllerAdvice(new ChatExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
  }
}
