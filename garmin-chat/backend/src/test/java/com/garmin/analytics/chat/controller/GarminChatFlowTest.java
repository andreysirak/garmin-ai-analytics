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
import com.garmin.analytics.chat.ai.PromptBudget;
import com.garmin.analytics.chat.repository.PostgresDailySummaryRepository;
import com.garmin.analytics.chat.service.GarminChatService;
import com.garmin.analytics.chat.support.ChatTestSupport;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

  @Test
  void routesValidatedIntentThroughAggregationAndOnlyCompactEvidenceToExplanation()
      throws Exception {
    String question = "  Средняя длительность сна за последние семь дней? {keep}  ";
    var row = mock(ResultSet.class);
    when(row.getBigDecimal("value")).thenReturn(new BigDecimal("1.5"));
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
          assertThat(parameters.getValue("metric")).isEqualTo("sleepingSeconds");
          assertThat(parameters.getValue("aggregation")).isEqualTo("AVG");
          assertThat(parameters.getValue("divisor")).isEqualTo(3600);
          RowMapper<?> mapper = call.getArgument(2);
          return mapper.mapRow(row, 0);
        });
    server.expect(requestTo("http://ollama:11434/api/chat")).andExpect(request -> {
      var body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
      assertThat(body.at("/messages/1/content").asText()).isEqualTo(question);
      assertThat(body.at("/messages/0/content").asText()).contains("2026-11-16", "Europe/Oslo");
      assertThat(body.at("/format/properties/metrics/items/enum").size()).isEqualTo(4);
      assertThat(body.at("/format/properties/status").isObject()).isTrue();
      assertThat(body.at("/options/num_predict").asInt()).isEqualTo(384);
    }).andRespond(withSuccess(modelResponse(ChatTestSupport.intent("[\"sleepingSeconds\"]", "AVG",
        ChatTestSupport.LAST_SEVEN)), MediaType.APPLICATION_JSON));
    String explanation =
        "Recorded sleep averaged 1.5 hours over 3 of 7 days. Coverage is incomplete.";
    server.expect(requestTo("http://ollama:11434/api/chat")).andExpect(request -> {
      var body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
      assertThat(body.get("messages").size()).isEqualTo(2);
      assertThat(body.at("/format").isMissingNode() || body.at("/format").isNull()).isTrue();
      assertThat(body.at("/options/num_predict").asInt()).isEqualTo(1024);
      var data = json.readTree(body.at("/messages/1/content").asText());
      assertThat(data.get("question").asText()).isEqualTo(question);
      assertThat(data.get("aggregation").asText()).isEqualTo("AVG");
      assertThat(data.at("/dateRange/startDate").asText()).isEqualTo("2026-11-09");
      assertThat(data.at("/metrics/0/value").decimalValue()).isEqualByComparingTo("1.5");
      assertThat(data.at("/metrics/0/unit").asText()).isEqualTo("hours");
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
  @ValueSource(strings = {"CLARIFICATION", "UNSUPPORTED"})
  void refusalsStopAfterTheStructuredCallWithoutDatabaseAccess(String selection) throws Exception {
    server.expect(requestTo("http://ollama:11434/api/chat"))
        .andRespond(withSuccess(modelResponse(ChatTestSupport.rejected(selection)),
            MediaType.APPLICATION_JSON));
    mvc().perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"Was my sleep healthy last week?\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.startDate").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.endDate").value(org.hamcrest.Matchers.nullValue()));
    verifyNoInteractions(jdbc);
    server.verify();
  }

  private String modelResponse(String content) throws Exception {
    return json.writeValueAsString(Map.of("model", "synthetic-model", "done", true,
        "message", Map.of("role", "assistant", "content", content)));
  }

  private MockMvc mvc() throws Exception {
    var repository = new PostgresDailySummaryRepository(jdbc,
        new ClassPathResource("sql/daily-summaries.sql"),
        new ClassPathResource("sql/metric-aggregation.sql"), ChatTestSupport.PROPERTIES);
    var model = OllamaChatModel.builder().ollamaApi(OllamaApi.builder()
        .baseUrl("http://ollama:11434").restClientBuilder(client).build())
        .defaultOptions(OllamaChatOptions.builder().model("synthetic-model").build()).build();
    var service = new GarminChatService(repository, model, () -> true,
        ChatTestSupport.dates(ChatTestSupport.CLOCK, ChatTestSupport.PROPERTIES),
        new PromptBudget(ChatTestSupport.PROPERTIES), ChatTestSupport.PROPERTIES, json,
        new ClassPathResource("prompts/garmin-evidence-system.txt"));
    return MockMvcBuilders.standaloneSetup(new GarminChatController(service))
        .setControllerAdvice(new ChatExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
  }
}
