package com.garmin.analytics.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.garmin.analytics.chat.config.GarminChatProperties;
import com.garmin.analytics.chat.model.DataCoverage;
import com.garmin.analytics.chat.model.DateRange;
import com.garmin.analytics.chat.model.QueryIntent.Aggregation;
import com.garmin.analytics.chat.model.QueryIntent.Metric;
import com.garmin.analytics.chat.model.QueryIntent;
import com.garmin.analytics.chat.repository.impl.DailySummaryRepositoryPostgresImpl;
import com.garmin.analytics.chat.support.ChatTestSupport;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Opt-in, SELECT-only tests: synthetic JSON rows need no DDL or test database. */
@EnabledIfEnvironmentVariable(named = "GARMIN_CHAT_TEST_DB_URL", matches = ".+")
class PostgresMetricAggregationDatabaseTest {

  private static final ClassPathResource SQL = new ClassPathResource("sql/metric-aggregation.sql");
  private static final DateRange WEEK = new DateRange(
      LocalDate.of(2024, 2, 26), LocalDate.of(2024, 3, 3));
  private DriverManagerDataSource dataSource;
  private NamedParameterJdbcTemplate jdbc;

  @BeforeEach
  void connectWithExplicitReaderConfiguration() {
    dataSource = new DriverManagerDataSource(System.getenv("GARMIN_CHAT_TEST_DB_URL"),
        System.getenv("GARMIN_CHAT_TEST_DB_USERNAME"),
        System.getenv("GARMIN_CHAT_TEST_DB_PASSWORD"));
    jdbc = new NamedParameterJdbcTemplate(dataSource);
  }

  @ParameterizedTest
  @CsvSource({
      "TOTAL_STEPS, SUM, 300", "TOTAL_STEPS, AVG, 100", "TOTAL_STEPS, MIN, 0",
      "TOTAL_STEPS, MAX, 200", "RESTING_HEART_RATE, AVG, 36",
      "RESTING_HEART_RATE, MIN, 0", "RESTING_HEART_RATE, MAX, 60",
      "ACTIVE_KILOCALORIES, SUM, 300.75", "ACTIVE_KILOCALORIES, AVG, 100.25",
      "ACTIVE_KILOCALORIES, MIN, 0", "ACTIVE_KILOCALORIES, MAX, 200.25",
      "SLEEPING_SECONDS, SUM, 4.5", "SLEEPING_SECONDS, AVG, 1.5",
      "SLEEPING_SECONDS, MIN, 0", "SLEEPING_SECONDS, MAX, 3"
  })
  void calculatesAllApprovedOperationsAndDisjointCoverage(
      Metric metric, Aggregation operation, BigDecimal expected) throws Exception {
    var result = mixedFixture().aggregate(query(metric, operation, WEEK)).getFirst();

    assertThat(result.value()).isEqualByComparingTo(expected);
    assertThat(result.coverage()).isEqualTo(new DataCoverage(7, 1, 1, 1, 1, 3, 1));
    assertThat(result.coverage().missingDays()).isEqualTo(3);
    assertThat(result.coverage().complete()).isFalse();
    assertThat(result.unit()).isEqualTo(metric.unit());
  }

  @ParameterizedTest
  @EnumSource(Aggregation.class)
  void emptyPeriodReturnsNullEvenForSum(Aggregation operation) throws Exception {
    var result = synthetic().aggregate(query(Metric.TOTAL_STEPS, operation, WEEK)).getFirst();
    assertThat(result.value()).isNull();
    assertThat(result.coverage()).isEqualTo(new DataCoverage(7, 7, 0, 0, 0, 0, 0));
  }

  @ParameterizedTest
  @EnumSource(Aggregation.class)
  void absentAndNullReadingsAreNotZero(Aggregation operation) throws Exception {
    var repository = synthetic(row(123, "2024-02-26", "{}"),
        row(123, "2024-02-27", measurements("null")));
    var result = repository.aggregate(query(Metric.TOTAL_STEPS, operation, WEEK)).getFirst();
    assertThat(result.value()).isNull();
    assertThat(result.coverage()).isEqualTo(new DataCoverage(7, 5, 1, 1, 0, 0, 0));
  }

  @ParameterizedTest
  @EnumSource(Aggregation.class)
  void aRecordedZeroIsACompleteNumericReading(Aggregation operation) throws Exception {
    var repository = synthetic(row(123, "2024-02-29", measurements("0")));
    var day = new DateRange(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29));
    var result = repository.aggregate(query(Metric.SLEEPING_SECONDS, operation, day)).getFirst();
    assertThat(result.value()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(result.coverage()).isEqualTo(new DataCoverage(1, 0, 0, 0, 0, 1, 1));
    assertThat(result.coverage().complete()).isTrue();
  }

  @Test
  void stringsBooleansArraysAndObjectsStayInvalidWithoutAttemptingNumericCasts() throws Exception {
    var values = List.of("\"42\"", "true", "false", "[]", "{}", "\"NaN\"", "\"\"");
    var rows = new String[7];
    for (int index = 0; index < values.size(); index++) {
      rows[index] = row(123, WEEK.startDate().plusDays(index).toString(),
          measurements(values.get(index)));
    }
    for (var result : synthetic(rows).aggregate(
        new QueryIntent(List.of(Metric.values()), Aggregation.AVG, WEEK))) {
      assertThat(result.value()).isNull();
      assertThat(result.coverage()).isEqualTo(new DataCoverage(7, 0, 0, 0, 7, 0, 0));
    }
  }

  @Test
  void returnsIndependentCoverageForEachRequestedMetric() throws Exception {
    var repository = synthetic(row(123, "2024-02-29",
        "{\"totalSteps\":0,\"restingHeartRate\":null,\"activeKilocalories\":\"4\"}"));
    var day = new DateRange(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29));
    var results = repository.aggregate(new QueryIntent(List.of(Metric.values()), Aggregation.AVG,
        day));
    assertThat(results.get(0).coverage()).isEqualTo(new DataCoverage(1, 0, 0, 0, 0, 1, 1));
    assertThat(results.get(1).coverage()).isEqualTo(new DataCoverage(1, 0, 0, 1, 0, 0, 0));
    assertThat(results.get(2).coverage()).isEqualTo(new DataCoverage(1, 0, 0, 0, 1, 0, 0));
    assertThat(results.get(3).coverage()).isEqualTo(new DataCoverage(1, 0, 1, 0, 0, 0, 0));
  }

  @Test
  void retainsNumericPrecisionAndConvertsFractionalSleepHoursInPostgres() throws Exception {
    var repository = synthetic(row(123, "2024-02-29", """
        {"totalSteps":9223372036854775808,"activeKilocalories":1.25,"sleepingSeconds":5400}
        """));
    var day = new DateRange(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29));
    var results = repository.aggregate(new QueryIntent(List.of(Metric.TOTAL_STEPS,
        Metric.ACTIVE_KILOCALORIES, Metric.SLEEPING_SECONDS), Aggregation.SUM, day));
    assertThat(results.get(0).value()).isEqualByComparingTo("9223372036854775808");
    assertThat(results.get(1).value()).isEqualByComparingTo("1.25");
    assertThat(results.get(2).value()).isEqualByComparingTo("1.5");
  }

  @Test
  void recognizesDecimalNegativeAndExponentFormsOfNumericZero() throws Exception {
    var repository = synthetic(row(123, "2024-02-26", measurements("0.0")),
        row(123, "2024-02-27", measurements("-0.00")),
        row(123, "2024-02-28", measurements("0e2")));
    var result = repository.aggregate(query(Metric.TOTAL_STEPS, Aggregation.SUM, WEEK)).getFirst();
    assertThat(result.value()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(result.coverage()).isEqualTo(new DataCoverage(7, 4, 0, 0, 0, 3, 3));
  }

  @Test
  void readerLoginIsReadOnlyAndHasNoStagingWritePrivileges() {
    var privileges = jdbc.queryForMap("""
        SELECT current_setting('default_transaction_read_only') = 'on' AS read_only,
               NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolbypassrls
                   AS restricted_role,
               has_table_privilege(current_user, 'staging.stg_garmin_daily_summary', 'SELECT')
                   AS can_read,
               NOT has_table_privilege(current_user, 'staging.stg_garmin_daily_summary',
                                       'INSERT,UPDATE,DELETE,TRUNCATE') AS cannot_write
        FROM pg_roles WHERE rolname = current_user
        """, Map.of());
    assertThat(privileges.values()).containsOnly(true);
  }

  @Test
  void serviceExplainsOnlyCalculatedEvidenceFromPostgres() throws Exception {
    String selection = ChatTestSupport.arguments("[\"totalSteps\",\"sleepingSeconds\"]", "AVG", """
        {"type":"EXPLICIT","days":0,"startDate":"2024-02-26","endDate":"2024-03-03"}
        """);
    var repository = mixedFixture();
    var service = ChatTestSupport.service(repository,
        ChatTestSupport.model(selection, prompt -> {
          try {
            var evidence = new ObjectMapper()
                .readTree(ChatTestSupport.toolEvidence(prompt));
            assertThat(evidence.at("/metrics/0/metric").asText()).isEqualTo("totalSteps");
            assertThat(evidence.at("/metrics/0/value").decimalValue()).isEqualByComparingTo("100");
            assertThat(evidence.at("/metrics/1/value").decimalValue()).isEqualByComparingTo("1.5");
            assertThat(evidence.at("/metrics/1/unit").asText()).isEqualTo("hours");
            assertThat(evidence.at("/metrics/0/coverage/daysWithData").asInt()).isEqualTo(3);
            assertThat(evidence.at("/metrics/0/coverage/invalidTypeDays").asInt()).isEqualTo(1);
            assertThat(evidence.at("/metrics/0/coverage/complete").asBoolean()).isFalse();
            assertThat(evidence.toString()).doesNotContain("profileId", "payload");
          } catch (IOException failure) {
            throw new AssertionError(failure);
          }
          return ChatTestSupport.response("Steps averaged 100; sleep averaged 1.5 hours. "
              + "Coverage is incomplete: 3 of 7 days have numeric data.");
        }));
    var answer = service.answer("Average steps and sleep duration from 2024-02-26 to 2024-03-03?");
    assertThat(answer.startDate()).isEqualTo(WEEK.startDate());
    assertThat(answer.endDate()).isEqualTo(WEEK.endDate());
    assertThat(answer.answer()).contains("100", "1.5 hours", "incomplete");
  }

  @Test
  void servicePassesMissingSummariesAsToolEvidence() throws Exception {
    var repository = synthetic();
    var service = ChatTestSupport.service(repository,
        ChatTestSupport.model(ChatTestSupport.WEEK, prompt -> {
          try {
            var evidence = new ObjectMapper().readTree(ChatTestSupport.toolEvidence(prompt));
            assertThat(evidence.at("/metrics/0/value").isNull()).isTrue();
            assertThat(evidence.at("/metrics/0/coverage/missingSummaryDays").asInt()).isEqualTo(7);
          } catch (IOException failure) {
            throw new AssertionError(failure);
          }
          return ChatTestSupport.response("No Garmin daily summaries are available.");
        }));
    assertThat(service.answer("Total steps last week?").answer()).contains("No Garmin daily");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GARMIN_CHAT_TEST_PROFILE_ID", matches = "[1-9][0-9]*")
  void comparesTheLatestStoredWeekWithIndependentDirectReadOnlyQueries() throws Exception {
    long profile = Long.parseLong(System.getenv("GARMIN_CHAT_TEST_PROFILE_ID"));
    var properties = new GarminChatProperties(profile, ChatTestSupport.PROPERTIES.timezone(),
        8192, 1024, 31);
    var end = jdbc.queryForObject("""
        SELECT max(calendar_date) FROM staging.stg_garmin_daily_summary
        WHERE user_profile_id = :profileId
        """, Map.of("profileId", profile), LocalDate.class);
    assertThat(end != null).as("Configured profile must have stored summaries").isTrue();
    var range = new DateRange(end.minusDays(6), end);
    var repository = repository(SQL, properties);
    var comparison = new ClassPathResource("sql/metric-aggregation-comparison.sql")
        .getContentAsString(StandardCharsets.UTF_8);
    for (var metric : Metric.values()) {
      var parameters = new MapSqlParameterSource().addValue("profileId", profile)
          .addValue("startDate", range.startDate()).addValue("endDate", range.endDate())
          .addValue("metric", metric.field()).addValue("divisor", metric.divisor());
      var direct = jdbc.queryForMap(comparison, parameters);
      var expectedCoverage = new DataCoverage(7, 7 - number(direct, "summary_days"),
          number(direct, "missing_metric_days"), number(direct, "null_metric_days"),
          number(direct, "invalid_type_days"), number(direct, "days_with_data"),
          number(direct, "zero_days"));
      for (var operation : Aggregation.values()) {
        if (!metric.supports(operation)) {
          continue;
        }
        var actual = repository.aggregate(query(metric, operation, range)).getFirst();
        var expected = (BigDecimal) direct.get(operation.name().toLowerCase(Locale.ROOT));
        boolean sameValue = expected == null ? actual.value() == null
            : actual.value() != null && expected.compareTo(actual.value()) == 0;
        // Failure output must not print personal measurements or profile identifiers.
        assertThat(sameValue).as("Direct aggregate comparison: %s %s", metric, operation).isTrue();
        assertThat(actual.coverage().equals(expectedCoverage))
            .as("Direct coverage comparison: %s", metric).isTrue();
      }
    }
  }

  private int number(Map<String, Object> row, String column) {
    return ((Number) row.get(column)).intValue();
  }

  private DailySummaryRepository mixedFixture() throws Exception {
    return synthetic(row(123, "2024-02-26", measurements("0")),
        row(123, "2024-02-27", """
            {"totalSteps":100,"restingHeartRate":48,
             "activeKilocalories":100.5,"sleepingSeconds":5400}
            """), row(123, "2024-02-28", """
            {"totalSteps":200,"restingHeartRate":60,
             "activeKilocalories":200.25,"sleepingSeconds":10800}
            """), row(123, "2024-02-29", "{}"), row(123, "2024-03-01", measurements("null")),
        row(123, "2024-03-02", """
            {"totalSteps":"42","restingHeartRate":true,"activeKilocalories":[],"sleepingSeconds":{}}
            """), row(124, "2024-02-26", measurements("99999")),
        row(123, "2024-02-25", measurements("99999")),
        row(123, "2024-03-04", measurements("99999")));
  }

  private String row(long profile, String date, String payload) {
    return "{\"user_profile_id\":" + profile + ",\"calendar_date\":\"" + date
        + "\",\"payload\":" + payload + "}";
  }

  private String measurements(String value) {
    return ("{\"totalSteps\":%s,\"restingHeartRate\":%s,"
        + "\"activeKilocalories\":%s,\"sleepingSeconds\":%s}")
        .formatted(value, value, value, value);
  }

  private QueryIntent query(Metric metric, Aggregation operation, DateRange range) {
    return new QueryIntent(List.of(metric), operation, range);
  }

  private DailySummaryRepository synthetic(String... rows) throws Exception {
    // Replace only the source relation with synthetic JSON records; execute production SQL logic.
    // This SELECT-only fixture works with the existing reader role, without creating any tables.
    String fixture = "(SELECT * FROM jsonb_to_recordset('[" + String.join(",", rows)
        .replace("'", "''") + "]'::jsonb) AS fixture(user_profile_id bigint, "
        + "calendar_date date, payload jsonb))";
    String statement = SQL.getContentAsString(StandardCharsets.UTF_8)
        .replace("staging.stg_garmin_daily_summary", fixture);
    return repository(new ByteArrayResource(statement.getBytes(StandardCharsets.UTF_8)),
        ChatTestSupport.PROPERTIES);
  }

  private DailySummaryRepository repository(Resource statement, GarminChatProperties properties)
      throws Exception {
    var target = new DailySummaryRepositoryPostgresImpl(jdbc,
        new ClassPathResource("sql/daily-summaries.sql"), statement, properties);
    var transaction = new TransactionInterceptor();
    transaction.setTransactionManager(new DataSourceTransactionManager(dataSource));
    transaction.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    var proxy = new ProxyFactory(target);
    proxy.addAdvice(transaction);
    return (DailySummaryRepository) proxy.getProxy();
  }
}
