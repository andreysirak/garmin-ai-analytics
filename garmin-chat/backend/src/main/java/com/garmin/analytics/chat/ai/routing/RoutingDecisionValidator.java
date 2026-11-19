package com.garmin.analytics.chat.ai.routing;

import com.garmin.analytics.chat.analytics.exception.InvalidAnalyticsRequest;
import org.springframework.stereotype.Component;

/** Validates route-specific fields without interpreting the user's question. */
@Component
public class RoutingDecisionValidator {

  private static final int MAX_CLARIFICATION_LENGTH = 240;

  public RoutingDecision validate(RoutingOutput output) {
    require(output != null && output.route() != null);
    return switch (output.route()) {
      case ANALYZE -> {
        require(output.selection() != null && output.target() == null
            && output.question() == null && output.reason() == null);
        yield new RoutingDecision.Analyze(output.selection());
      }
      case CLARIFY -> {
        require(output.selection() == null && output.target() != null
            && output.question() != null && !output.question().isBlank()
            && output.question().length() <= MAX_CLARIFICATION_LENGTH
            && output.reason() == null);
        yield new RoutingDecision.Clarify(output.question().strip());
      }
      case UNSUPPORTED -> {
        require(output.selection() == null && output.target() == null
            && output.question() == null && output.reason() != null);
        yield new RoutingDecision.Unsupported(output.reason());
      }
    };
  }

  private static void require(boolean valid) {
    if (!valid) {
      throw new InvalidAnalyticsRequest("The model returned an inconsistent routing decision. "
          + "Please try rephrasing your question.");
    }
  }
}
