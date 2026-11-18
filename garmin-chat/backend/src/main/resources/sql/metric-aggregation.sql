-- Fixed scalar aggregation. All selections are bound values from Java's metric catalog.
WITH expected_dates AS (
    SELECT CAST(:startDate AS date) + day_offset AS calendar_date
    FROM generate_series(0, CAST(:endDate AS date) - CAST(:startDate AS date)) AS day_offset
), readings AS (
    SELECT summary.calendar_date IS NOT NULL AS has_summary,
           summary.payload -> CAST(:metric AS text) AS reading
    FROM expected_dates AS expected
    LEFT JOIN staging.stg_garmin_daily_summary AS summary
      ON summary.user_profile_id = :profileId
     AND summary.calendar_date = expected.calendar_date
), numeric_readings AS (
    SELECT has_summary,
           reading,
           CASE WHEN jsonb_typeof(reading) = 'number'
                THEN CAST(reading #>> '{}' AS numeric)
           END AS numeric_value
    FROM readings
)
SELECT CASE CAST(:aggregation AS text)
           WHEN 'SUM' THEN sum(numeric_value)
           WHEN 'AVG' THEN avg(numeric_value)
           WHEN 'MIN' THEN min(numeric_value)
           WHEN 'MAX' THEN max(numeric_value)
       END / CAST(:divisor AS numeric) AS value,
       count(*) AS expected_days,
       count(*) FILTER (WHERE NOT has_summary) AS missing_summary_days,
       count(*) FILTER (WHERE has_summary AND reading IS NULL) AS missing_metric_days,
       count(*) FILTER (WHERE has_summary AND reading = 'null'::jsonb) AS null_metric_days,
       count(*) FILTER (WHERE has_summary AND jsonb_typeof(reading) NOT IN ('number', 'null'))
           AS invalid_type_days,
       count(numeric_value) AS days_with_data,
       count(*) FILTER (WHERE numeric_value = 0) AS zero_days
FROM numeric_readings;
