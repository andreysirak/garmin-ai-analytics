package com.garmin.analytics.chat.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.garmin.analytics.chat.ai.ChatModelAvailability;
import com.garmin.analytics.chat.ai.ChatModelTurnAdvisor;
import com.garmin.analytics.chat.ai.prompt.GarminChatPromptFactory;
import com.garmin.analytics.chat.ai.prompt.PromptBudget;
import com.garmin.analytics.chat.ai.routing.GarminRequestRouter;
import com.garmin.analytics.chat.ai.routing.RoutingDecision;
import com.garmin.analytics.chat.ai.routing.RoutingDecisionValidator;
import com.garmin.analytics.chat.analytics.impl.GarminAnalyticsServiceImpl;
import com.garmin.analytics.chat.analytics.exception.InvalidAnalyticsRequest;
import com.garmin.analytics.chat.config.ChatExecutionProperties;
import com.garmin.analytics.chat.config.GarminChatProperties;
import com.garmin.analytics.chat.diagnostics.ChatDiagnostics;
import com.garmin.analytics.chat.execution.ChatRequestExecutor;
import com.garmin.analytics.chat.model.QueryEvidence;
import com.garmin.analytics.chat.model.DailySummary;
import com.garmin.analytics.chat.model.MetricAggregation;
import com.garmin.analytics.chat.model.QueryIntent;
import com.garmin.analytics.chat.repository.DailySummaryRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.ClassPathResource;

/** Test-only adapter: exports production prompts and replays output through the real router. */
public final class EvaluationBridge {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private EvaluationBridge() {}

  public static void main(String[] args) throws IOException {
    var protocol = System.out;
    // Application logging must not corrupt the stdout JSON protocol.
    System.setOut(System.err);
    var request = JSON.readTree(System.in);
    protocol.println(JSON.writeValueAsString(execute(request)));
  }

  static List<Object> execute(JsonNode request) throws IOException {
    var properties = new GarminChatProperties(123L,
        ZoneId.of(request.required("timezone").asText()),
        request.required("context_tokens").asInt(), request.required("response_tokens").asInt(),
        request.required("max_query_days").asInt());
    var date = LocalDate.parse(request.required("reference_date").asText());
    var prompts = new GarminChatPromptFactory(properties,
        new ClassPathResource("prompts/garmin-chat-system.txt"),
        new ClassPathResource("prompts/period-selection-rules.txt"),
        new ClassPathResource("prompts/garmin-routing-system.txt"),
        new ClassPathResource("prompts/garmin-answer-instructions.txt"));
    var results = new ArrayList<Object>();
    for (var item : request.required("items")) {
      if (request.required("action").asText().equals("export")) {
        Prompt prompt = item.has("evidence")
            ? prompts.analysis(item.required("question").asText(),
                readEvidence(item.get("evidence")))
            : prompts.routing(item.required("question").asText(), date);
        var messages = prompt.getInstructions().stream().map(message ->
            Map.of("role", message.getMessageType().getValue(), "content", message.getText()))
            .toList();
        var result = JSON.createObjectNode();
        result.set("messages", JSON.valueToTree(messages));
        var options = (OllamaChatOptions) prompt.getOptions();
        if (options.getFormat() != null) {
          result.set("format", JSON.valueToTree(options.getFormat()));
        }
        results.add(result);
      } else if (request.required("action").asText().equals("validate")) {
        results.add(validate(item.required("output").asText(), date, properties, prompts));
      } else {
        throw new IllegalArgumentException("Unknown bridge action");
      }
    }
    return results;
  }

  private static QueryEvidence readEvidence(JsonNode input) throws IOException {
    var copy = input.deepCopy();
    for (var metric : copy.required("metrics")) {
      var coverage = (ObjectNode) metric.required("coverage");
      // These are derived @JsonProperty accessors, not constructor arguments.
      coverage.remove(List.of("summaryDays", "missingDays", "complete"));
    }
    return JSON.treeToValue(copy, QueryEvidence.class);
  }

  private static Object validate(String output, LocalDate date, GarminChatProperties properties,
      GarminChatPromptFactory prompts) {
    var diagnostics = new ChatDiagnostics(JSON);
    var analytics = new GarminAnalyticsServiceImpl(new NoDatabase(), properties, Clock.systemUTC(),
        diagnostics);
    ChatModel model = prompt -> new ChatResponse(
        List.of(new Generation(new AssistantMessage(output))));
    var router = new GarminRequestRouter(ChatClient.builder(model),
        () -> ChatModelAvailability.Status.AVAILABLE,
        new ChatModelTurnAdvisor(new PromptBudget(properties), properties, diagnostics),
        new RoutingDecisionValidator(), analytics, JSON);
    var executor = new ChatRequestExecutor(new ChatExecutionProperties(Duration.ofSeconds(10)));
    try {
      var decision = executor.execute(deadline -> router.route(
          prompts.routing("Evaluation replay", date), date, deadline));
      var result = JSON.createObjectNode().put("accepted", true);
      switch (decision) {
        case RoutingDecision.Analyze analyze -> {
          result.put("route", "ANALYZE");
          result.set("selection", JSON.valueToTree(analyze.selection()));
          var selection = analyze.selection();
          result.set("intent", JSON.valueToTree(analytics.validateRequest(selection.metrics(),
              selection.aggregation(), selection.period(), date)));
        }
        case RoutingDecision.Clarify clarify -> {
          result.put("route", "CLARIFY");
          result.put("question", clarify.question());
        }
        case RoutingDecision.Unsupported unsupported -> {
          result.put("route", "UNSUPPORTED");
          result.put("reason", unsupported.reason().name());
        }
      }
      return result;
    } catch (InvalidAnalyticsRequest rejected) {
      return Map.of("accepted", false, "reason", rejected.getMessage());
    }
  }

  /** A validator replay must never open a database or manufacture SQL evidence. */
  private static final class NoDatabase implements DailySummaryRepository {
    @Override
    public List<DailySummary> findSummaries(
        long profileId, LocalDate start, LocalDate end) {
      throw new AssertionError("Evaluation validation must not read data");
    }

    @Override
    public List<MetricAggregation> aggregate(
        QueryIntent intent) {
      throw new AssertionError("Evaluation validation must not execute SQL");
    }
  }
}
