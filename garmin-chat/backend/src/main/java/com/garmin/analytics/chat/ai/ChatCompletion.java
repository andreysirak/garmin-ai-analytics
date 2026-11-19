package com.garmin.analytics.chat.ai;

import com.garmin.analytics.chat.ai.routing.RoutingDecision.UnsupportedReason;
import com.garmin.analytics.chat.model.ChatAnswer;
import com.garmin.analytics.chat.model.QueryEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ChatCompletion {

  private static final Logger log = LoggerFactory.getLogger(ChatCompletion.class);

  public ChatAnswer clarify(String question) {
    return complete("clarification", question, null);
  }

  public ChatAnswer unsupported(UnsupportedReason reason) {
    String message = switch (reason) {
      case RELATIONSHIP -> "Garmin chat can summarize individual metrics over a period. "
          + "It cannot determine relationships between metrics.";
      case UNSUPPORTED_METRIC -> "Garmin chat does not have a supported metric for that question.";
      case UNSUPPORTED_CALCULATION, OTHER ->
          "Garmin chat supports scalar summaries over one period. This analysis is unavailable.";
      case HEALTH_ADVICE -> "Garmin summaries cannot answer a medical or health advice question.";
    };
    return complete("unsupported", message, null);
  }

  public ChatAnswer analysis(String answer, QueryEvidence evidence, boolean truncated) {
    if (evidence == null) {
      return failed("I couldn't complete the analysis. No Garmin readings were returned "
          + "by the analytical tool.", null);
    }
    if (evidence.metrics().stream().allMatch(metric -> metric.value() == null)) {
      boolean noSummaries = evidence.metrics().stream()
          .allMatch(metric -> metric.coverage().summaryDays() == 0);
      return complete("no_data", noSummaries
          ? "No Garmin daily summaries are available for this period."
          : "Garmin summaries are available, but none contain a numeric reading "
              + "for the requested metrics. Coverage is 0 of "
              + evidence.metrics().getFirst().coverage().expectedDays() + " days.", evidence);
    }
    if (answer == null || answer.isBlank() || truncated) {
      return failed("I retrieved Garmin readings but couldn't complete the answer.", evidence);
    }
    return complete("analyzed", answer, evidence);
  }

  public ChatAnswer contextLimit() {
    return failed("Your question is too long for the model context. "
        + "Please shorten it and specify a metric, calculation and clear period.", null);
  }

  public ChatAnswer failed(String message, QueryEvidence evidence) {
    return complete("incomplete", message, evidence);
  }

  public void executionFailed(boolean evidenceAvailable) {
    log.info("chat_completion status=failed evidence_available={}", evidenceAvailable);
  }

  private static ChatAnswer complete(String outcome, String text, QueryEvidence evidence) {
    log.info("chat_completion status={} evidence_available={}", outcome, evidence != null);
    return evidence == null ? new ChatAnswer(text, null, null)
        : new ChatAnswer(text, evidence.dateRange().startDate(), evidence.dateRange().endDate());
  }
}
