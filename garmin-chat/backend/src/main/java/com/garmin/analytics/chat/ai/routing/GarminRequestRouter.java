package com.garmin.analytics.chat.ai.routing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.garmin.analytics.chat.ai.ChatModelAvailability;
import com.garmin.analytics.chat.ai.ChatModelTurnAdvisor;
import com.garmin.analytics.chat.analytics.GarminAnalyticsService;
import com.garmin.analytics.chat.analytics.exception.InvalidAnalyticsRequest;
import com.garmin.analytics.chat.execution.ChatDeadline;
import com.garmin.analytics.chat.model.AnalyticsSelection;
import java.io.IOException;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

@Component
public class GarminRequestRouter {

  private static final Logger log = LoggerFactory.getLogger(GarminRequestRouter.class);
  private final ChatClient client;
  private final ChatModelAvailability modelAvailability;
  private final ChatModelTurnAdvisor modelTurn;
  private final RoutingDecisionValidator validator;
  private final ObjectReader reader;
  private final GarminAnalyticsService analytics;

  public GarminRequestRouter(ChatClient.Builder client, ChatModelAvailability modelAvailability,
      ChatModelTurnAdvisor modelTurn,
      RoutingDecisionValidator validator, GarminAnalyticsService analytics, ObjectMapper mapper) {
    this.client = client.build();
    this.modelAvailability = modelAvailability;
    this.modelTurn = modelTurn;
    this.validator = validator;
    this.analytics = analytics;
    var strict = mapper.copy();
    strict.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    this.reader = strict.readerFor(RoutingOutput.class)
        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
            DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
  }

  public RoutingDecision route(Prompt prompt, LocalDate referenceDate, ChatDeadline deadline) {
    long started = System.nanoTime();
    String route = "unknown";
    String outcome = "failed";
    try {
      deadline.check();
      modelAvailability.requireAvailable();
      var response = client.prompt(prompt)
          .advisors(spec -> spec.advisors(modelTurn).param(ChatModelTurnAdvisor.DEADLINE, deadline))
          .call().chatResponse();
      deadline.check();
      outcome = "malformed_output";
      var result = response.getResult();
      if (response.hasToolCalls() || "length".equalsIgnoreCase(result.getMetadata().getFinishReason())) {
        throw invalidOutput();
      }
      var output = parse(result.getOutput().getText());
      route = output.route().name();
      outcome = "inconsistent_decision";
      var decision = validator.validate(output);
      if (decision instanceof RoutingDecision.Analyze analyze) {
        outcome = "invalid_analytics_arguments";
        var selection = analyze.selection();
        validateSelection(selection, referenceDate);
      }
      outcome = "accepted";
      log.info("chat_route_selected route={}", decision.getClass().getSimpleName());
      return decision;
    } finally {
      log.info("chat_routing route={} outcome={} elapsed_ms={}", route, outcome,
          (System.nanoTime() - started) / 1_000_000);
    }
  }

  private void validateSelection(AnalyticsSelection selection, LocalDate referenceDate) {
    try {
      analytics.validateRequest(selection.metrics(), selection.aggregation(), selection.period(),
          referenceDate);
    } catch (InvalidAnalyticsRequest invalid) {
      log.info("chat_routing_validation_rejected reason={}", invalid.getMessage());
      throw new InvalidAnalyticsRequest("The model selected an invalid analysis. "
          + "Please try rephrasing your question.", invalid);
    }
  }

  private RoutingOutput parse(String content) {
    try {
      // Checking duplicates requires reading a tree before mapping to the typed DTO.
      if (content != null && !content.isBlank()) {
        RoutingOutput output = reader.readValue(reader.readTree(content));
        if (output != null && output.route() != null) {
          return output;
        }
      }
    } catch (IOException | IllegalArgumentException malformed) {
      // Only application-authored guidance leaves the routing boundary.
    }
    throw invalidOutput();
  }

  private InvalidAnalyticsRequest invalidOutput() {
    return new InvalidAnalyticsRequest("The model did not return a valid routing response. "
        + "Please try again.");
  }
}
