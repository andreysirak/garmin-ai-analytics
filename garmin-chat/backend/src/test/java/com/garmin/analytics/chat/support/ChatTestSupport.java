package com.garmin.analytics.chat.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.garmin.analytics.chat.ai.ChatCompletion;
import com.garmin.analytics.chat.ai.ChatModelAvailability;
import com.garmin.analytics.chat.ai.ChatModelTurnAdvisor;
import com.garmin.analytics.chat.ai.impl.GarminChatConversationSpringAiImpl;
import com.garmin.analytics.chat.ai.prompt.GarminChatPromptFactory;
import com.garmin.analytics.chat.ai.prompt.PromptBudget;
import com.garmin.analytics.chat.ai.routing.GarminRequestRouter;
import com.garmin.analytics.chat.ai.routing.RoutingDecisionValidator;
import com.garmin.analytics.chat.ai.tool.GarminAnalysisWorkflow;
import com.garmin.analytics.chat.ai.tool.GarminAnalyticsToolFactory;
import com.garmin.analytics.chat.ai.tool.GarminToolArguments;
import com.garmin.analytics.chat.analytics.GarminAnalyticsService;
import com.garmin.analytics.chat.analytics.impl.GarminAnalyticsServiceImpl;
import com.garmin.analytics.chat.config.ChatExecutionProperties;
import com.garmin.analytics.chat.config.GarminChatProperties;
import com.garmin.analytics.chat.diagnostics.ChatDiagnostics;
import com.garmin.analytics.chat.execution.ChatRequestExecutor;
import com.garmin.analytics.chat.model.DailySummary;
import com.garmin.analytics.chat.model.DataCoverage;
import com.garmin.analytics.chat.model.MetricAggregation;
import com.garmin.analytics.chat.model.QueryIntent;
import com.garmin.analytics.chat.repository.DailySummaryRepository;
import com.garmin.analytics.chat.service.GarminChatService;
import com.garmin.analytics.chat.service.impl.GarminChatServiceImpl;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.ClassPathResource;

public final class ChatTestSupport {

  public static final String TOOL_NAME = "aggregate_garmin_metrics";
  public static final String WEEK = arguments("[\"totalSteps\"]", "SUM",
      "{\"type\":\"LAST_WEEK\",\"startDate\":\"\",\"endDate\":\"\",\"days\":0}");
  public static final String LAST_SEVEN = """
      {"type":"LAST_N_DAYS","startDate":"","endDate":"","days":7}
      """;
  public static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
  public static final GarminChatProperties PROPERTIES = new GarminChatProperties(
      123L, ZoneId.of("Europe/Oslo"), 8192, 1024, 31);
  public static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  private ChatTestSupport() {}

  public static GarminAnalyticsService analytics(DailySummaryRepository repository,
      GarminChatProperties properties, Clock clock) {
    return new GarminAnalyticsServiceImpl(repository, properties, clock, new ChatDiagnostics(JSON));
  }

  public static GarminChatService service(DailySummaryRepository repository, ChatModel model)
      throws IOException {
    return service(repository, model, () -> ChatModelAvailability.Status.AVAILABLE,
        PROPERTIES, Duration.ofSeconds(10));
  }

  public static GarminChatService service(DailySummaryRepository repository, ChatModel model,
      ChatModelAvailability availability, GarminChatProperties properties, Duration timeout)
      throws IOException {
    var budget = new PromptBudget(properties);
    var prompts = new GarminChatPromptFactory(properties,
        new ClassPathResource("prompts/garmin-chat-system.txt"),
        new ClassPathResource("prompts/period-selection-rules.txt"),
        new ClassPathResource("prompts/garmin-routing-system.txt"),
        new ClassPathResource("prompts/garmin-answer-instructions.txt"));
    var analytics = analytics(repository, properties, CLOCK);
    var tools = new GarminAnalyticsToolFactory(analytics);
    var modelTurn = new ChatModelTurnAdvisor(budget, properties, new ChatDiagnostics(JSON));
    var completion = new ChatCompletion();
    var router = new GarminRequestRouter(ChatClient.builder(model), modelTurn,
        new RoutingDecisionValidator(analytics), JSON);
    var workflow = new GarminAnalysisWorkflow(ChatClient.builder(model), prompts, tools, budget,
        new GarminToolArguments(JSON), modelTurn, completion);
    var conversation = new GarminChatConversationSpringAiImpl(availability, prompts, analytics,
        budget, router, workflow, completion);
    return new GarminChatServiceImpl(new ChatRequestExecutor(new ChatExecutionProperties(timeout)),
        conversation);
  }

  public static ChatModel model(String arguments, Function<Prompt, ChatResponse> answer) {
    return routed(arguments, prompt -> prompt.getInstructions().getLast()
        instanceof ToolResponseMessage ? answer.apply(prompt) : toolCall(arguments));
  }

  public static ChatModel routed(String selection, ChatModel model) {
    return prompt -> routing(prompt) ? response(analyze(selection)) : model.call(prompt);
  }

  public static boolean routing(Prompt prompt) {
    return prompt.getOptions() instanceof OllamaChatOptions options && options.getFormat() != null;
  }

  public static String analyze(String selection) {
    return "{\"route\":\"ANALYZE\",\"selection\":" + selection + "}";
  }

  public static String clarify(String target, String question) {
    return JSON.createObjectNode().put("route", "CLARIFY").put("target", target)
        .put("question", question).toString();
  }

  public static String unsupported(String reason) {
    return JSON.createObjectNode().put("route", "UNSUPPORTED").put("reason", reason).toString();
  }

  public static ChatResponse toolCall(String arguments) {
    return toolCalls(new AssistantMessage.ToolCall("1", "function", TOOL_NAME, arguments));
  }

  public static ChatResponse toolCalls(AssistantMessage.ToolCall... calls) {
    return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
        .content("").toolCalls(Arrays.asList(calls)).build())));
  }

  public static String toolEvidence(Prompt prompt) {
    var toolResponse = (ToolResponseMessage) prompt.getInstructions().getLast();
    return toolResponse.getResponses().getFirst().responseData();
  }

  public static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  public static String arguments(String metrics, String aggregation, String period) {
    return "{\"metrics\":" + metrics + ",\"aggregation\":\"" + aggregation
        + "\",\"period\":" + period + "}";
  }

  public static MetricAggregation result(QueryIntent intent, String value) {
    int days = (int) ChronoUnit.DAYS.between(intent.range().startDate(), intent.range().endDate()) + 1;
    var number = value == null ? null : new BigDecimal(value);
    var coverage = number == null ? new DataCoverage(days, days, 0, 0, 0, 0, 0)
        : new DataCoverage(days, 0, 0, 0, 0, days, number.signum() == 0 ? days : 0);
    var metric = intent.metrics().getFirst();
    return new MetricAggregation(metric, intent.aggregation(),
        number, metric.unit(), intent.range(), coverage);
  }

  /** Service fixtures must only use aggregation. */
  @FunctionalInterface
  public interface AggregationLoader extends DailySummaryRepository {

    @Override
    default List<DailySummary> findSummaries(long profile, LocalDate start, LocalDate end) {
      throw new AssertionError("Chat must use aggregation");
    }
  }
}
