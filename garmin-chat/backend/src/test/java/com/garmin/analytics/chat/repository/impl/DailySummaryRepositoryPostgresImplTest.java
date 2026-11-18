package com.garmin.analytics.chat.repository.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.garmin.analytics.chat.model.DailySummary;
import com.garmin.analytics.chat.support.ChatTestSupport;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class DailySummaryRepositoryPostgresImplTest {

  @Test
  void bindsProfileAndDatesAndPreservesEveryJsonField() throws Exception {
    var start = LocalDate.of(2026, 10, 1);
    var end = start.plusDays(6);
    var jdbc = mock(NamedParameterJdbcTemplate.class);
    var zeroRow = mock(ResultSet.class);
    var unknownRow = mock(ResultSet.class);
    String completePayload = """
        {"totalSteps":0,"restingHeartRate":55,"bodyBattery":{"events":[0,null,75]},
        "newGarminField":{"recorded":true}}
        """;
    String missingStepsPayload = "{\"totalSteps\":null,\"restingHeartRate\":58}";
    when(zeroRow.getLong("user_profile_id")).thenReturn(123L);
    when(unknownRow.getLong("user_profile_id")).thenReturn(123L);
    when(zeroRow.getObject("calendar_date", LocalDate.class)).thenReturn(start);
    when(zeroRow.getString("payload")).thenReturn(completePayload);
    when(unknownRow.getObject("calendar_date", LocalDate.class)).thenReturn(start.plusDays(1));
    when(unknownRow.getString("payload")).thenReturn(missingStepsPayload);
    when(jdbc.query(anyString(), ArgumentMatchers.any(SqlParameterSource.class),
        ArgumentMatchers.<RowMapper<DailySummary>>any())).thenAnswer(invocation -> {
          String query = invocation.getArgument(0);
          assertThat(query.toUpperCase()).doesNotContain("LIMIT", "JOIN", "->");
          assertThat(query).contains("SELECT user_profile_id, calendar_date, payload");
          assertThat(query).contains("user_profile_id = :profileId", ":startDate", ":endDate");
          SqlParameterSource parameters = invocation.getArgument(1);
          assertThat(parameters.getValue("profileId")).isEqualTo(123L);
          assertThat(parameters.getValue("startDate")).isEqualTo(Date.valueOf(start));
          assertThat(parameters.getValue("endDate")).isEqualTo(Date.valueOf(end));
          RowMapper<DailySummary> mapper = invocation.getArgument(2);
          return List.of(mapper.mapRow(zeroRow, 0), mapper.mapRow(unknownRow, 1));
        });
    var repository = new DailySummaryRepositoryPostgresImpl(jdbc,
        new ClassPathResource("sql/daily-summaries.sql"),
        new ClassPathResource("sql/metric-aggregation.sql"), ChatTestSupport.PROPERTIES);

    assertThat(repository.findSummaries(123L, start, end)).containsExactly(
        new DailySummary(123L, start, completePayload),
        new DailySummary(123L, start.plusDays(1), missingStepsPayload));
  }
}
