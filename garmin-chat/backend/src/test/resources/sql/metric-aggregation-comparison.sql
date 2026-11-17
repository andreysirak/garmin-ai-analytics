-- Independent direct-table calculation for opt-in read-only comparison; no generated date rows.
WITH source AS (
    SELECT payload -> CAST(:metric AS text) AS reading
    FROM staging.stg_garmin_daily_summary
    WHERE user_profile_id = :profileId
      AND calendar_date BETWEEN CAST(:startDate AS date) AND CAST(:endDate AS date)
), typed AS (
    SELECT reading,
           CASE WHEN jsonb_typeof(reading) = 'number'
                THEN CAST(reading #>> '{}' AS numeric)
           END AS numeric_value
    FROM source
)
SELECT sum(numeric_value) / CAST(:divisor AS numeric) AS sum,
       avg(numeric_value) / CAST(:divisor AS numeric) AS avg,
       min(numeric_value) / CAST(:divisor AS numeric) AS min,
       max(numeric_value) / CAST(:divisor AS numeric) AS max,
       count(*) AS summary_days,
       count(*) - count(reading) AS missing_metric_days,
       count(*) FILTER (WHERE reading = 'null'::jsonb) AS null_metric_days,
       count(*) FILTER (WHERE jsonb_typeof(reading) NOT IN ('number', 'null')) AS invalid_type_days,
       count(numeric_value) AS days_with_data,
       count(*) FILTER (WHERE numeric_value = 0) AS zero_days
FROM typed;
