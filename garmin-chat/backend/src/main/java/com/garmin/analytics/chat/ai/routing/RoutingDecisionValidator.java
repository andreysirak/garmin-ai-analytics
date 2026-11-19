package com.garmin.analytics.chat.ai.routing;

import com.garmin.analytics.chat.ai.routing.RoutingDecision.UnsupportedReason;
import com.garmin.analytics.chat.ai.routing.RoutingOutput.ClarificationTarget;
import com.garmin.analytics.chat.analytics.GarminAnalyticsService;
import com.garmin.analytics.chat.analytics.exception.InvalidAnalyticsRequest;
import com.garmin.analytics.chat.model.PeriodSelection.Type;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RoutingDecisionValidator {

  private static final Pattern WORK_PRODUCTIVITY = Pattern.compile(
      "\\b(?:how productively did i work|(?:my|measure|calculate) work productivity)\\b",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern ACTIVITY = Pattern.compile("\\b(activity|steps|exercise)\\b",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern SLEEP = Pattern.compile("\\bsleep\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern RELATIONSHIP = Pattern.compile(
      "\\b(correlat\\w*|related to|associated with|followed by|lead to)\\b",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern NAMED_PERIOD = Pattern.compile(
      "\\b(last week|this week|last month|this month|yesterday|today)\\b",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern OTHER_PERIOD = Pattern.compile(
      "\\b(past|since|through)\\b|\\d", Pattern.CASE_INSENSITIVE);

  private final GarminAnalyticsService analytics;

  public RoutingDecisionValidator(GarminAnalyticsService analytics) {
    this.analytics = analytics;
  }

  public RoutingDecision validate(RoutingOutput output, String question, LocalDate referenceDate) {
    require(output != null && output.route() != null);
    var decision = switch (output.route()) {
      case ANALYZE -> {
        require(output.selection() != null && output.target() == null
            && output.question() == null && output.reason() == null);
        yield new RoutingDecision.Analyze(output.selection());
      }
      case CLARIFY -> {
        require(output.selection() == null && output.target() != null
            && focusedQuestion(output.question()) && output.reason() == null);
        yield new RoutingDecision.Clarify(output.question().strip());
      }
      case UNSUPPORTED -> {
        require(output.selection() == null && output.target() == null
            && output.question() == null && output.reason() != null);
        yield new RoutingDecision.Unsupported(output.reason());
      }
    };
    // Guard the known misroutes even when the model emits valid JSON.
    if (ACTIVITY.matcher(question).find() && SLEEP.matcher(question).find()
        && RELATIONSHIP.matcher(question).find()) {
      return new RoutingDecision.Unsupported(UnsupportedReason.RELATIONSHIP);
    }
    if (WORK_PRODUCTIVITY.matcher(question).find()) {
      return new RoutingDecision.Clarify("Do you mean work productivity or physical activity?");
    }
    Type statedPeriod = unambiguousNamedPeriod(question);
    if (decision instanceof RoutingDecision.Analyze analyze) {
      var selection = analyze.selection();
      analytics.validateRequest(selection.metrics(), selection.aggregation(), selection.period(),
          referenceDate);
      if (statedPeriod != null && selection.period().type() != statedPeriod) {
        throw periodMismatch(statedPeriod);
      }
    } else if (statedPeriod != null && output.target() == ClarificationTarget.PERIOD) {
      throw periodMismatch(statedPeriod);
    }
    return decision;
  }

  private static Type unambiguousNamedPeriod(String question) {
    var named = NAMED_PERIOD.matcher(question);
    if (OTHER_PERIOD.matcher(question).find() || !named.find()) {
      return null;
    }
    String token = named.group().toUpperCase(Locale.ROOT).replace(' ', '_');
    return named.find() ? null : Type.valueOf(token);
  }

  private static InvalidAnalyticsRequest periodMismatch(Type period) {
    return new InvalidAnalyticsRequest("I couldn't resolve the request for "
        + period.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " reliably.");
  }

  private static boolean focusedQuestion(String text) {
    return text != null && text.length() <= 240 && text.strip().matches("[^.?!؟？！\\r\\n]+[?؟？]");
  }

  private static void require(boolean valid) {
    if (!valid) {
      throw new InvalidAnalyticsRequest("I couldn't interpret the question reliably. "
          + "Please rephrase it with a metric, calculation, and period.");
    }
  }
}
