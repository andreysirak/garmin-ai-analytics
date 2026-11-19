package com.garmin.analytics.chat.ai.tool;

import com.garmin.analytics.chat.analytics.GarminAnalyticsService;
import com.garmin.analytics.chat.analytics.exception.InvalidAnalyticsRequest;
import com.garmin.analytics.chat.execution.ChatDeadline;
import com.garmin.analytics.chat.model.AnalyticsSelection;
import com.garmin.analytics.chat.model.PeriodSelection;
import com.garmin.analytics.chat.model.QueryEvidence;
import com.garmin.analytics.chat.model.QueryIntent.Aggregation;
import java.time.LocalDate;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** One instance per conversation, retaining the evidence used to populate response dates. */
public class GarminAnalyticsTool {

  static final String NAME = "aggregate_garmin_metrics";

  private final GarminAnalyticsService analytics;
  private final LocalDate referenceDate;
  private final ChatDeadline deadline;
  private final AnalyticsSelection selection;
  private QueryEvidence evidence;

  GarminAnalyticsTool(GarminAnalyticsService analytics, LocalDate referenceDate,
      ChatDeadline deadline, AnalyticsSelection selection) {
    this.analytics = analytics;
    this.referenceDate = referenceDate;
    this.deadline = deadline;
    this.selection = selection;
  }

  @Tool(name = NAME, description = "Calculate one allowed scalar aggregation independently "
      + "for distinct Garmin daily-summary metrics over one period. Returns values and coverage.")
  public QueryEvidence aggregate(
      @ToolParam(description = "Distinct canonical field names from the Garmin metric catalog")
      List<String> metrics,
      @ToolParam(description = "Calculation to apply independently to each metric")
      Aggregation aggregation,
      @ToolParam(description = "One inclusive period following the period rules")
      PeriodSelection period) {
    deadline.check();
    if (!selection.equals(new AnalyticsSelection(metrics, aggregation, period))) {
      throw new InvalidAnalyticsRequest(
          "I couldn't complete the analysis because the tool request did not match the question.");
    }
    evidence = analytics.aggregate(metrics, aggregation, period, referenceDate);
    return evidence;
  }

  public QueryEvidence evidence() {
    return evidence;
  }
}
