package com.garmin.analytics.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.garmin.analytics.chat.model.DateRange;
import com.garmin.analytics.chat.model.QueryIntent;
import com.garmin.analytics.chat.model.QueryIntent.Aggregation;
import com.garmin.analytics.chat.model.QueryIntent.Metric;
import com.garmin.analytics.chat.support.ChatTestSupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class PostgresMetricAggregationTest {

  private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
  private final DateRange week = new DateRange(
      LocalDate.of(2024, 2, 26), LocalDate.of(2024, 3, 3));
  private final ClassPathResource sql = new ClassPathResource("sql/metric-aggregation.sql");

  @ParameterizedTest
  @MethodSource("supportedQueries")
  void bindsCatalogValuesAndServerProfileAndMapsTypedResults(Metric metric, Aggregation operation)
      throws Exception {
    var row = row(new BigDecimal("2.5"));
    when(jdbc.queryForObject(anyString(), ArgumentMatchers.any(SqlParameterSource.class),
        ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(call -> {
          assertThat((String) call.getArgument(0))
              .isEqualTo(sql.getContentAsString(StandardCharsets.UTF_8));
          MapSqlParameterSource parameters = call.getArgument(1);
          assertThat(parameters.getValues()).containsExactlyInAnyOrderEntriesOf(Map.of(
              "profileId", ChatTestSupport.PROPERTIES.profileId(),
              "startDate", Date.valueOf(week.startDate()), "endDate", Date.valueOf(week.endDate()),
              "metric", metric.field(), "aggregation", operation.name(),
              "divisor", metric.divisor()));
          RowMapper<?> mapper = call.getArgument(2);
          return mapper.mapRow(row, 0);
        });

    var result = repository().aggregate(new QueryIntent(List.of(metric), operation, week))
        .getFirst();

    assertThat(result.metric()).isEqualTo(metric);
    assertThat(result.aggregation()).isEqualTo(operation);
    assertThat(result.value()).isEqualByComparingTo("2.5");
    assertThat(result.unit()).isEqualTo(metric.unit());
    assertThat(result.range()).isEqualTo(week);
    assertThat(result.coverage().expectedDays()).isEqualTo(7);
    assertThat(result.coverage().missingSummaryDays()).isEqualTo(1);
    assertThat(result.coverage().missingMetricDays()).isEqualTo(1);
    assertThat(result.coverage().nullMetricDays()).isEqualTo(1);
    assertThat(result.coverage().invalidTypeDays()).isEqualTo(1);
    assertThat(result.coverage().daysWithData()).isEqualTo(3);
    assertThat(result.coverage().zeroDays()).isEqualTo(1);
    assertThat(result.coverage().missingDays()).isEqualTo(3);
    assertThat(result.coverage().summaryDays()).isEqualTo(6);
    assertThat(result.coverage().complete()).isFalse();
  }

  static Stream<Arguments> supportedQueries() {
    return Arrays.stream(Metric.values()).flatMap(metric -> Arrays.stream(Aggregation.values())
        .filter(metric::supports).map(operation -> Arguments.of(metric, operation)));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "0")
  void preservesSqlNullRatherThanCoercingItToZero(String value) throws Exception {
    var row = row(value == null ? null : new BigDecimal(value));
    when(jdbc.queryForObject(anyString(), ArgumentMatchers.any(SqlParameterSource.class),
        ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(call -> {
          RowMapper<?> mapper = call.getArgument(2);
          return mapper.mapRow(row, 0);
        });
    var result = repository().aggregate(
        new QueryIntent(List.of(Metric.TOTAL_STEPS), Aggregation.SUM, week)).getFirst();
    if (value == null) {
      assertThat(result.value()).isNull();
      assertThat(result.coverage().daysWithData()).isZero();
    } else {
      assertThat(result.value()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(result.coverage().zeroDays()).isEqualTo(1);
    }
  }

  @Test
  void keepsMultipleMetricsSeparateInRequestedOrder() throws Exception {
    var selected = new ArrayList<String>();
    var row = row(BigDecimal.ZERO);
    when(jdbc.queryForObject(anyString(), ArgumentMatchers.any(SqlParameterSource.class),
        ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(call -> {
          SqlParameterSource parameters = call.getArgument(1);
          selected.add((String) parameters.getValue("metric"));
          RowMapper<?> mapper = call.getArgument(2);
          return mapper.mapRow(row, 0);
        });
    var metrics = List.of(Metric.SLEEPING_SECONDS, Metric.ACTIVE_KILOCALORIES);
    var results = repository().aggregate(new QueryIntent(metrics, Aggregation.AVG, week));
    assertThat(selected).containsExactly("sleepingSeconds", "activeKilocalories");
    assertThat(results.stream().map(result -> result.metric()).toList()).isEqualTo(metrics);
  }

  @ParameterizedTest
  @CsvSource({"2024-03-03, 2024-02-26", "2024-01-01, 2024-02-01"})
  void refusesReversedAndOversizedRangesBeforeJdbc(LocalDate start, LocalDate end)
      throws Exception {
    var intent = new QueryIntent(List.of(Metric.TOTAL_STEPS), Aggregation.SUM,
        new DateRange(start, end));
    assertThatThrownBy(() -> repository().aggregate(intent))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("date limit");
    verifyNoInteractions(jdbc);
  }

  private PostgresDailySummaryRepository repository() throws Exception {
    return new PostgresDailySummaryRepository(jdbc,
        new ClassPathResource("sql/daily-summaries.sql"), sql, ChatTestSupport.PROPERTIES);
  }

  private ResultSet row(BigDecimal value) throws Exception {
    var row = mock(ResultSet.class);
    when(row.getBigDecimal("value")).thenReturn(value);
    when(row.getInt("expected_days")).thenReturn(7);
    when(row.getInt("missing_summary_days")).thenReturn(value == null ? 7 : 1);
    when(row.getInt("missing_metric_days")).thenReturn(value == null ? 0 : 1);
    when(row.getInt("null_metric_days")).thenReturn(value == null ? 0 : 1);
    when(row.getInt("invalid_type_days")).thenReturn(value == null ? 0 : 1);
    when(row.getInt("days_with_data")).thenReturn(value == null ? 0 : 3);
    when(row.getInt("zero_days")).thenReturn(value == null ? 0 : 1);
    return row;
  }
}
