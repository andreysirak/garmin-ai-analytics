package com.garmin.analytics.chat.ai.routing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.garmin.analytics.chat.ai.ChatModelTurnAdvisor;
import com.garmin.analytics.chat.analytics.exception.InvalidAnalyticsRequest;
import com.garmin.analytics.chat.execution.ChatDeadline;
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
  private final ChatModelTurnAdvisor modelTurn;
  private final RoutingDecisionValidator validator;
  private final ObjectReader reader;

  public GarminRequestRouter(ChatClient.Builder client, ChatModelTurnAdvisor modelTurn,
      RoutingDecisionValidator validator, ObjectMapper mapper) {
    this.client = client.build();
    this.modelTurn = modelTurn;
    this.validator = validator;
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
      var response = client.prompt(prompt)
          .advisors(spec -> spec.advisors(modelTurn).param(ChatModelTurnAdvisor.DEADLINE, deadline))
          .call().chatResponse();
      deadline.check();
      var result = response.getResult();
      if (response.hasToolCalls() || "length".equalsIgnoreCase(result.getMetadata().getFinishReason())) {
        throw invalidOutput();
      }
      var output = parse(result.getOutput().getText());
      route = output != null && output.route() != null ? output.route().name() : "unknown";
      var decision = validator.validate(output, prompt.getUserMessage().getText(), referenceDate);
      outcome = "accepted";
      log.info("chat_route_selected route={}", decision.getClass().getSimpleName());
      return decision;
    } catch (InvalidAnalyticsRequest invalid) {
      outcome = "rejected";
      throw invalid;
    } finally {
      log.info("chat_routing route={} outcome={} elapsed_ms={}", route, outcome,
          (System.nanoTime() - started) / 1_000_000);
    }
  }

  private RoutingOutput parse(String content) {
    try {
      // Checking duplicates requires reading a tree before mapping to the typed DTO.
      if (content != null && !content.isBlank()) {
        return reader.readValue(reader.readTree(content));
      }
    } catch (IOException | IllegalArgumentException malformed) {
      // Only application-authored guidance leaves the routing boundary.
    }
    throw invalidOutput();
  }

  private InvalidAnalyticsRequest invalidOutput() {
    return new InvalidAnalyticsRequest("I couldn't interpret the question reliably. "
        + "Please rephrase it with a metric, calculation, and period.");
  }
}
