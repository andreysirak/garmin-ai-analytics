package com.garmin.analytics.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresDailySummaryRepository implements DailySummaryRepository {

  private final NamedParameterJdbcTemplate jdbc;
  private final String query;

  public PostgresDailySummaryRepository(
      NamedParameterJdbcTemplate jdbc,
      @Value("classpath:sql/daily-summaries.sql") Resource query) throws IOException {
    this.jdbc = jdbc;
    this.query = query.getContentAsString(StandardCharsets.UTF_8);
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
}
