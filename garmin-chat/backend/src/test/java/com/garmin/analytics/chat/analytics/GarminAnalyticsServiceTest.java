package com.garmin.analytics.chat.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.garmin.analytics.chat.analytics.exception.InvalidAnalyticsRequest;
import com.garmin.analytics.chat.model.DateRange;
import com.garmin.analytics.chat.model.PeriodSelection.Type;
import com.garmin.analytics.chat.model.PeriodSelection;
import com.garmin.analytics.chat.model.QueryIntent.Aggregation;
import com.garmin.analytics.chat.model.QueryIntent.Metric;
import com.garmin.analytics.chat.repository.DailySummaryRepository;
import com.garmin.analytics.chat.support.ChatTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataRetrievalFailureException;

class GarminAnalyticsServiceTest {

  private final DailySummaryRepository repository = mock(DailySummaryRepository.class);
  private final GarminAnalyticsService analytics = ChatTestSupport.analytics(repository,
      ChatTestSupport.PROPERTIES, ChatTestSupport.CLOCK);
  private final LocalDate referenceDate = analytics.referenceDate();

  @ParameterizedTest
  @CsvSource({
      "YESTERDAY, 0, 2026-11-15, 2026-11-15", "TODAY, 0, 2026-11-16, 2026-11-16",
      "THIS_WEEK, 0, 2026-11-16, 2026-11-16", "LAST_WEEK, 0, 2026-11-09, 2026-11-15",
      "THIS_MONTH, 0, 2026-11-01, 2026-11-16", "LAST_MONTH, 0, 2026-10-01, 2026-10-31",
      "LAST_N_DAYS, 7, 2026-11-09, 2026-11-15", "LAST_N_DAYS, 31, 2026-10-16, 2026-11-15"
  })
  void resolvesRelativePeriods(Type type, int days, LocalDate start, LocalDate end) {
    when(repository.aggregate(any())).thenAnswer(call ->
        List.of(ChatTestSupport.result(call.getArgument(0), "0")));
    var evidence = analytics.aggregate(List.of("totalSteps"), Aggregation.SUM,
        new PeriodSelection(type, "", "", days), referenceDate);

    assertThat(evidence.dateRange()).isEqualTo(new DateRange(start, end));
  }

  @Test
  void getsTodayFromTheConfiguredZoneAroundUtcMidnight() {
    var clock = Clock.fixed(Instant.parse("2026-03-29T22:30:00Z"), ZoneOffset.UTC);
    var oslo = ChatTestSupport.analytics(repository, ChatTestSupport.PROPERTIES, clock);
    when(repository.aggregate(any())).thenAnswer(call ->
        List.of(ChatTestSupport.result(call.getArgument(0), "0")));

    assertThat(oslo.referenceDate()).isEqualTo("2026-03-30");
    assertThat(oslo.aggregate(List.of("totalSteps"), Aggregation.SUM,
        new PeriodSelection(Type.YESTERDAY, "", "", 0), oslo.referenceDate()).dateRange())
        .isEqualTo(new DateRange(LocalDate.parse("2026-03-29"), LocalDate.parse("2026-03-29")));
  }

  @ParameterizedTest
  @CsvSource({"2024-02-29, 2024-02-29", "2024-02-29, 2024-03-02"})
  void acceptsInclusiveExplicitDatesIncludingLeapDay(String start, String end) {
    when(repository.aggregate(any())).thenAnswer(call ->
        List.of(ChatTestSupport.result(call.getArgument(0), "0")));

    var evidence = analytics.aggregate(List.of("totalSteps"), Aggregation.SUM,
        new PeriodSelection(Type.EXPLICIT, start, end, 0), referenceDate);

    assertThat(evidence.dateRange()).isEqualTo(
        new DateRange(LocalDate.parse(start), LocalDate.parse(end)));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 32, Integer.MAX_VALUE})
  void rejectsInvalidOrOversizedRollingPeriods(int days) {
    assertThatThrownBy(() -> analytics.aggregate(List.of("totalSteps"), Aggregation.SUM,
        new PeriodSelection(Type.LAST_N_DAYS, "", "", days), referenceDate))
        .isInstanceOf(InvalidAnalyticsRequest.class);
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @CsvSource({"2026-02-29, 2026-03-01", "2026-11-16, 2026-11-17", "2026-11-15, 2026-11-14",
      "0000-01-01, 0000-01-01", "2026-10-01, 2026-11-01", "11-09, 11-15"})
  void rejectsInvalidOrOversizedExplicitPeriods(String start, String end) {
    assertThatThrownBy(() -> analytics.aggregate(List.of("totalSteps"), Aggregation.SUM,
        new PeriodSelection(Type.EXPLICIT, start, end, 0), referenceDate))
        .isInstanceOf(InvalidAnalyticsRequest.class);
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @EnumSource(Type.class)
  void rejectsConflictingPeriodFields(Type type) {
    assertThatThrownBy(() -> analytics.aggregate(List.of("totalSteps"), Aggregation.SUM,
        new PeriodSelection(type, "2026-11-15", "2026-11-15", 1), referenceDate))
        .isInstanceOf(InvalidAnalyticsRequest.class);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsInconsistentRepositoryResults() {
    when(repository.aggregate(any())).thenReturn(List.of());
    assertThatThrownBy(() -> analytics.aggregate(List.of(Metric.TOTAL_STEPS.field()),
        Aggregation.SUM, new PeriodSelection(Type.LAST_WEEK, "", "", 0), referenceDate))
        .isInstanceOf(DataRetrievalFailureException.class);
  }
}
