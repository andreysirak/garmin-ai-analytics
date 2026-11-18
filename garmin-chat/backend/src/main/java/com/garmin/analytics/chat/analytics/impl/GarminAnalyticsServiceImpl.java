package com.garmin.analytics.chat.analytics.impl;

import com.garmin.analytics.chat.analytics.GarminAnalyticsService;
import com.garmin.analytics.chat.analytics.exception.InvalidAnalyticsRequest;
import com.garmin.analytics.chat.config.GarminChatProperties;
import com.garmin.analytics.chat.diagnostics.ChatDiagnostics;
import com.garmin.analytics.chat.model.DateRange;
import com.garmin.analytics.chat.model.MetricAggregation;
import com.garmin.analytics.chat.model.PeriodSelection;
import com.garmin.analytics.chat.model.QueryEvidence;
import com.garmin.analytics.chat.model.QueryIntent.Aggregation;
import com.garmin.analytics.chat.model.QueryIntent.Metric;
import com.garmin.analytics.chat.model.QueryIntent;
import com.garmin.analytics.chat.repository.DailySummaryRepository;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class GarminAnalyticsServiceImpl implements GarminAnalyticsService {

  private static final Logger log = LoggerFactory.getLogger(GarminAnalyticsServiceImpl.class);

  private final DailySummaryRepository repository;
  private final GarminChatProperties properties;
  private final Clock clock;
  private final ChatDiagnostics diagnostics;

  public GarminAnalyticsServiceImpl(DailySummaryRepository repository, GarminChatProperties properties,
      Clock clock, ChatDiagnostics diagnostics) {
    this.repository = repository;
    this.properties = properties;
    this.clock = clock;
    this.diagnostics = diagnostics;
  }

  @Override
  public LocalDate referenceDate() {
    return LocalDate.now(clock.withZone(properties.timezone()));
  }

  @Override
  public QueryEvidence aggregate(List<String> fields, Aggregation aggregation,
      PeriodSelection period, LocalDate referenceDate) {
    var intent = validateRequest(fields, aggregation, period, referenceDate);
    diagnostics.record("sql_request", intent);
    long started = System.nanoTime();
    try {
      var results = repository.aggregate(intent);
      validateResults(intent, results);
      var evidence = new QueryEvidence(intent.aggregation(), intent.range(),
          results.stream().map(result -> new QueryEvidence.MetricValue(result.metric().field(),
              result.value(), result.unit(), result.coverage())).toList());
      log.info("sql_completed query=metric-aggregation metrics={} aggregation={} days={} "
              + "elapsed_ms={}", intent.metrics(), intent.aggregation(),
          ChronoUnit.DAYS.between(intent.range().startDate(), intent.range().endDate()) + 1,
          (System.nanoTime() - started) / 1_000_000);
      diagnostics.record("sql_evidence", evidence);
      return evidence;
    } catch (RuntimeException failure) {
      log.warn("sql_failed query=metric-aggregation elapsed_ms={} error_type={}",
          (System.nanoTime() - started) / 1_000_000, failure.getClass().getSimpleName());
      if (failure instanceof TransactionException transactionFailure) {
        throw new DataRetrievalFailureException("Garmin aggregation transaction failed",
            transactionFailure);
      }
      throw failure;
    }
  }

  private QueryIntent validateRequest(List<String> fields, Aggregation aggregation,
      PeriodSelection period, LocalDate referenceDate) {
    if (fields == null || fields.isEmpty() || fields.size() > Metric.values().length
        || aggregation == null) {
      throw new InvalidAnalyticsRequest("Choose one or more supported metrics and a calculation.");
    }
    var metrics = new ArrayList<Metric>();
    for (String field : fields) {
      var metric = Metric.fromField(field).orElseThrow(() ->
          new InvalidAnalyticsRequest("Choose a supported Garmin metric."));
      if (metrics.contains(metric)) {
        throw new InvalidAnalyticsRequest("Choose each Garmin metric only once.");
      }
      if (!metric.supports(aggregation)) {
        throw new InvalidAnalyticsRequest(
            "That calculation is unavailable for " + metric.meaning() + ". Choose "
                + metric.supportedAggregations() + ".");
      }
      metrics.add(metric);
    }
    var range = resolvePeriod(period, referenceDate).orElseThrow(() -> new InvalidAnalyticsRequest(
        "Please provide valid, ordered dates no later than today, or a clear relative period."));
    if (ChronoUnit.DAYS.between(range.startDate(), range.endDate()) + 1
        > properties.maxQueryDays()) {
      throw new InvalidAnalyticsRequest("Please choose a period of at most "
          + properties.maxQueryDays() + " calendar days (inclusive).");
    }
    return new QueryIntent(metrics, aggregation, range);
  }

  private Optional<DateRange> resolvePeriod(PeriodSelection selection, LocalDate referenceDate) {
    try {
      if (selection == null || selection.type() == null || selection.days() == null
          || selection.startDate() == null || selection.endDate() == null) {
        return Optional.empty();
      }
      var today = referenceDate;
      DateRange range = switch (selection.type()) {
        case EXPLICIT -> {
          if (selection.days() != 0 || !selection.startDate().matches("\\d{4}-\\d{2}-\\d{2}")
              || !selection.endDate().matches("\\d{4}-\\d{2}-\\d{2}")) {
            yield null;
          }
          yield new DateRange(LocalDate.parse(selection.startDate()),
              LocalDate.parse(selection.endDate()));
        }
        case LAST_N_DAYS -> {
          if (selection.days() <= 0
              || !selection.startDate().isEmpty() || !selection.endDate().isEmpty()) {
            yield null;
          }
          yield new DateRange(today.minusDays(selection.days()), today.minusDays(1));
        }
        case LAST_WEEK -> {
          var monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
          yield relativeRange(selection, monday.minusWeeks(1), monday.minusDays(1));
        }
        case THIS_WEEK -> relativeRange(selection,
            today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today);
        case LAST_MONTH -> relativeRange(selection,
            today.withDayOfMonth(1).minusMonths(1), today.withDayOfMonth(1).minusDays(1));
        case THIS_MONTH -> relativeRange(selection, today.withDayOfMonth(1), today);
        case YESTERDAY -> relativeRange(selection, today.minusDays(1), today.minusDays(1));
        case TODAY -> relativeRange(selection, today, today);
      };
      if (range == null || range.startDate().getYear() < 1
          || range.endDate().isBefore(range.startDate()) || range.endDate().isAfter(today)) {
        return Optional.empty();
      }
      return Optional.of(range);
    } catch (DateTimeException | ArithmeticException invalidSelection) {
      // Model-generated dates are untrusted. Never query with malformed/ambiguous values.
      return Optional.empty();
    }
  }

  private DateRange relativeRange(PeriodSelection selection, LocalDate start, LocalDate end) {
    return selection.startDate().isEmpty() && selection.endDate().isEmpty() && selection.days() == 0
        ? new DateRange(start, end) : null;
  }

  private void validateResults(QueryIntent intent, List<MetricAggregation> results) {
    long expectedDays = ChronoUnit.DAYS.between(
        intent.range().startDate(), intent.range().endDate()) + 1;
    if (results == null || results.size() != intent.metrics().size()) {
      throw new DataRetrievalFailureException("Garmin aggregation results are incomplete");
    }
    for (int index = 0; index < results.size(); index++) {
      var result = results.get(index);
      var metric = intent.metrics().get(index);
      if (result == null || result.metric() != metric
          || result.aggregation() != intent.aggregation()
          || !intent.range().equals(result.range()) || !metric.unit().equals(result.unit())
          || result.coverage() == null || result.coverage().expectedDays() != expectedDays
          || (result.value() == null) != (result.coverage().daysWithData() == 0)) {
        throw new DataRetrievalFailureException(
            "Garmin aggregation results do not match the query");
      }
    }
  }
}
