package com.garmin.analytics.chat.repository.impl;

import com.garmin.analytics.chat.config.GarminChatProperties;
import com.garmin.analytics.chat.model.DailySummary;
import com.garmin.analytics.chat.model.DataCoverage;
import com.garmin.analytics.chat.model.MetricAggregation;
import com.garmin.analytics.chat.model.QueryIntent;
import com.garmin.analytics.chat.repository.DailySummaryRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DailySummaryRepositoryPostgresImpl implements DailySummaryRepository {

  private final NamedParameterJdbcTemplate jdbc;
  private final String query;
  private final String aggregationQuery;
  private final GarminChatProperties properties;

  public DailySummaryRepositoryPostgresImpl(
      NamedParameterJdbcTemplate jdbc,
      @Value("classpath:sql/daily-summaries.sql") Resource query,
      @Value("classpath:sql/metric-aggregation.sql") Resource aggregationQuery,
      GarminChatProperties properties) throws IOException {
    this.jdbc = jdbc;
    this.query = query.getContentAsString(StandardCharsets.UTF_8);
    this.aggregationQuery = aggregationQuery.getContentAsString(StandardCharsets.UTF_8);
    this.properties = properties;
  }

  @Override
  public List<DailySummary> findSummaries(long profileId, LocalDate startDate, LocalDate endDate) {
    var parameters = new MapSqlParameterSource()
        .addValue("profileId", profileId)
        .addValue("startDate", Date.valueOf(startDate))
        .addValue("endDate", Date.valueOf(endDate));
    return jdbc.query(query, parameters, (row, index) -> new DailySummary(
        row.getLong("user_profile_id"),
        row.getObject("calendar_date", LocalDate.class), row.getString("payload")));
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<MetricAggregation> aggregate(QueryIntent intent) {
    var range = intent.range();
    if (range.startDate() == null || range.endDate() == null) {
      throw new IllegalArgumentException("Aggregation dates are required");
    }
    long expectedDays = ChronoUnit.DAYS.between(range.startDate(), range.endDate()) + 1;
    if (expectedDays < 1 || expectedDays > properties.maxQueryDays()) {
      throw new IllegalArgumentException("Aggregation range exceeds the configured date limit");
    }
    return intent.metrics().stream().map(metric -> {
      var parameters = new MapSqlParameterSource()
          .addValue("profileId", properties.profileId())
          .addValue("startDate", Date.valueOf(range.startDate()))
          .addValue("endDate", Date.valueOf(range.endDate()))
          .addValue("metric", metric.field())
          .addValue("aggregation", intent.aggregation().name())
          .addValue("divisor", metric.divisor());
      return jdbc.queryForObject(aggregationQuery, parameters, (row, index) ->
          new MetricAggregation(metric, intent.aggregation(), row.getBigDecimal("value"),
              metric.unit(), range,
              new DataCoverage(row.getInt("expected_days"), row.getInt("missing_summary_days"),
                  row.getInt("missing_metric_days"), row.getInt("null_metric_days"),
                  row.getInt("invalid_type_days"), row.getInt("days_with_data"),
                  row.getInt("zero_days"))));
    }).toList();
  }
}
