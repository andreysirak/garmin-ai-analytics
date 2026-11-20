package com.garmin.analytics.chat.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.garmin.analytics.chat.ai.ChatModelAvailability;
import com.garmin.analytics.chat.ai.ChatModelTurnAdvisor;
import com.garmin.analytics.chat.ai.prompt.GarminChatPromptFactory;
import com.garmin.analytics.chat.ai.prompt.PromptBudget;
import com.garmin.analytics.chat.ai.routing.GarminRequestRouter;
import com.garmin.analytics.chat.ai.routing.RoutingDecision;
import com.garmin.analytics.chat.ai.routing.RoutingDecisionValidator;
import com.garmin.analytics.chat.analytics.exception.InvalidAnalyticsRequest;
import com.garmin.analytics.chat.analytics.impl.GarminAnalyticsServiceImpl;
import com.garmin.analytics.chat.config.ChatExecutionProperties;
import com.garmin.analytics.chat.config.GarminChatProperties;
import com.garmin.analytics.chat.diagnostics.ChatDiagnostics;
import com.garmin.analytics.chat.execution.ChatRequestExecutor;
import com.garmin.analytics.chat.model.QueryEvidence;
import com.garmin.analytics.chat.repository.DailySummaryRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.ClassPathResource;

/** Optional compatibility check for the production components against the standalone v1 prompts. */
class ProductionEvaluationContractTest {
  private static final LocalDate REFERENCE = LocalDate.of(2026, 11, 19);
  private static final GarminChatProperties PROPERTIES =
      new GarminChatProperties(123L, ZoneId.of("Europe/Oslo"), 32768, 1024, 31);
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final GarminChatPromptFactory prompts = new GarminChatPromptFactory(PROPERTIES,
      prompt("garmin-chat-system"), prompt("period-selection-rules"), prompt("garmin-routing-system"),
      prompt("garmin-answer-instructions"));

  ProductionEvaluationContractTest() throws Exception {}

  @Test
  void productionPromptsMatchFrozenTemplatesAndRoutingSchema() throws Exception {
    var fixture = Path.of("../../ai-evaluation-framework/src/main/resources/dataset/v1/prompts.json");
    try (var input = Files.newInputStream(fixture)) {
      var frozen = JSON.readTree(input);
      var route = prompts.routing("Total steps yesterday?", REFERENCE);
      assertThat(route.getSystemMessage().getText())
          .isEqualTo(frozen.get("routing_system").asText()
              .replace("${reference_date}", REFERENCE.toString())
              .replace("${timezone}", PROPERTIES.timezone().toString())
              .replace("${max_query_days}", Integer.toString(PROPERTIES.maxQueryDays())));
      assertThat(JSON.<JsonNode>valueToTree(((OllamaChatOptions) route.getOptions()).getFormat()))
          .isEqualTo(frozen.get("routing_schema"));

      var evidence = JSON.readValue("""
          {"aggregation":"SUM","dateRange":{"startDate":"2026-11-09","endDate":"2026-11-15"},
           "metrics":[{"metric":"totalSteps","value":null,"unit":"steps","coverage":{
           "expectedDays":7,"missingSummaryDays":7,"missingMetricDays":0,"nullMetricDays":0,
           "invalidTypeDays":0,"daysWithData":0,"zeroDays":0}}]}
          """, QueryEvidence.class);
      var explanation = prompts.analysis("Total steps last week?", evidence);
      assertThat(explanation.getInstructions().getFirst().getText())
          .isEqualTo(frozen.get("explanation_system").asText());
      var prefix = frozen.get("evidence_prefix").asText();
      var content = explanation.getInstructions().getLast().getText();
      assertThat(content).startsWith(prefix);
      var rendered = JSON.readTree(content.substring(prefix.length()));
      assertThat(rendered.at("/metrics/0/value").isNull()).isTrue();
      assertThat(rendered.at("/metrics/0/coverage/missingDays").asInt()).isEqualTo(7);
      assertThat(rendered.at("/dateRange/startDate").asText()).isEqualTo("2026-11-09");
    }
  }

  @Test
  void productionRouterUsesAdvisorParserCalendarAndCatalogWithoutReadingData() {
    var repository = mock(DailySummaryRepository.class);
    var diagnostics = new ChatDiagnostics(JSON);
    var analytics = new GarminAnalyticsServiceImpl(repository, PROPERTIES, Clock.systemUTC(), diagnostics);
    String rolling = """
        {"route":"ANALYZE","selection":{"metrics":["totalSteps"],"aggregation":"SUM",
         "period":{"type":"LAST_N_DAYS","days":7}}}
        """;
    var analysis = (RoutingDecision.Analyze) route(rolling, analytics, diagnostics);
    var selection = analysis.selection();
    var range = analytics.validateRequest(selection.metrics(), selection.aggregation(), selection.period(), REFERENCE).range();
    assertThat(range.startDate()).isEqualTo(LocalDate.of(2026, 11, 12));
    assertThat(range.endDate()).isEqualTo(LocalDate.of(2026, 11, 18));
    for (String invalid : List.of("{\"route\":\"CLARIFY\",\"route\":\"ANALYZE\"}",
        rolling.replace("totalSteps", "restingHeartRate"), rolling.replace("days\":7", "days\":32"))) {
      assertThatThrownBy(() -> route(invalid, analytics, diagnostics))
          .isInstanceOf(InvalidAnalyticsRequest.class);
    }
    verifyNoInteractions(repository);
  }

  private RoutingDecision route(String text, GarminAnalyticsServiceImpl analytics, ChatDiagnostics diagnostics) {
    ChatModel model = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    var router = new GarminRequestRouter(ChatClient.builder(model),
        () -> ChatModelAvailability.Status.AVAILABLE,
        new ChatModelTurnAdvisor(new PromptBudget(PROPERTIES), PROPERTIES, diagnostics),
        new RoutingDecisionValidator(), analytics, JSON);
    var executor = new ChatRequestExecutor(new ChatExecutionProperties(Duration.ofSeconds(10)));
    return executor.execute(deadline -> router.route(prompts.routing("Routing replay", REFERENCE), REFERENCE, deadline));
  }

  private static ClassPathResource prompt(String name) {
    return new ClassPathResource("prompts/" + name + ".txt");
  }
}
