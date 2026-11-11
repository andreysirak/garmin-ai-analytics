-- One complete parsed response per Garmin profile and date; raw bytes stay archived.
-- Execute as one transaction through garmin_analytics_schema. Never alter rows.
DO $$
BEGIN
    IF current_database() <> 'garmin_analytics' THEN
        RAISE EXCEPTION 'Daily-summary staging requires database garmin_analytics; connected to %',
            current_database();
    END IF;
END
$$;
SET LOCAL ROLE garmin_owner;

-- One definition drives creation and exact column/constraint verification.
CREATE TEMPORARY TABLE expected_daily_summary_staging (
    user_profile_id bigint NOT NULL,
    calendar_date date NOT NULL,
    raw_file_path text NOT NULL,
    payload jsonb NOT NULL,
    PRIMARY KEY (user_profile_id, calendar_date),
    CONSTRAINT stg_garmin_daily_summary_payload_object
        CHECK (jsonb_typeof(payload) = 'object')
) ON COMMIT DROP;

CREATE TABLE IF NOT EXISTS staging.stg_garmin_daily_summary
    (LIKE pg_temp.expected_daily_summary_staging INCLUDING ALL);

DO $$
DECLARE
    expected_columns jsonb;
    actual_columns jsonb;
    expected_constraints text[];
    actual_constraints text[];
BEGIN
    IF (SELECT relkind FROM pg_class
        WHERE oid = 'staging.stg_garmin_daily_summary'::regclass) <> 'r' THEN
        RAISE EXCEPTION 'Schema migration required for staging.stg_garmin_daily_summary: expected an ordinary table';
    END IF;

    -- Block ACCESS EXCLUSIVE operations until transaction end; other DDL may run.
    LOCK TABLE staging.stg_garmin_daily_summary IN ACCESS SHARE MODE;
    WITH definitions AS (
        SELECT a.attrelid, jsonb_object_agg(a.attname, jsonb_build_object(
            'type', format_type(a.atttypid, a.atttypmod),
            'not_null', a.attnotnull,
            'default', pg_get_expr(d.adbin, d.adrelid),
            'identity', a.attidentity,
            'generated', a.attgenerated
        )) AS columns
        FROM pg_attribute a
        LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
        WHERE a.attrelid IN ('pg_temp.expected_daily_summary_staging'::regclass,
                            'staging.stg_garmin_daily_summary'::regclass)
          AND a.attnum > 0 AND NOT a.attisdropped
        GROUP BY a.attrelid
    )
    SELECT
        (SELECT columns FROM definitions
         WHERE attrelid = 'pg_temp.expected_daily_summary_staging'::regclass),
        (SELECT columns FROM definitions
         WHERE attrelid = 'staging.stg_garmin_daily_summary'::regclass)
    INTO expected_columns, actual_columns;

    IF expected_columns IS DISTINCT FROM actual_columns THEN
        RAISE EXCEPTION 'Schema migration required for staging.stg_garmin_daily_summary columns: expected four-column JSONB contract %, found %',
            expected_columns, actual_columns
            USING HINT = 'Existing wide tables require a reviewed migration or replay from the raw archive. This task leaves existing schema and rows unchanged.';
    END IF;

    -- Constraint names may differ; definitions, including validation state, must match.
    SELECT array_agg(pg_get_constraintdef(oid) ORDER BY pg_get_constraintdef(oid))
        INTO expected_constraints
        FROM pg_constraint WHERE conrelid = 'pg_temp.expected_daily_summary_staging'::regclass;
    SELECT array_agg(pg_get_constraintdef(oid) ORDER BY pg_get_constraintdef(oid))
        INTO actual_constraints
        FROM pg_constraint WHERE conrelid = 'staging.stg_garmin_daily_summary'::regclass;
    IF expected_constraints IS DISTINCT FROM actual_constraints THEN
        RAISE EXCEPTION 'Schema migration required for staging.stg_garmin_daily_summary constraints: expected %, found %',
            expected_constraints, coalesce(actual_constraints, ARRAY['<none>'])
            USING HINT = 'Require a nondeferrable PRIMARY KEY (user_profile_id, calendar_date) and a validated CHECK (jsonb_typeof(payload) = ''object''). Apply a reviewed migration; existing schema and rows are unchanged.';
    END IF;
    IF (SELECT count(*) FROM pg_index
        WHERE indrelid = 'staging.stg_garmin_daily_summary'::regclass AND indisunique) <> 1
       OR NOT EXISTS (
           SELECT FROM pg_index
           WHERE indrelid = 'staging.stg_garmin_daily_summary'::regclass
             AND indisprimary AND indisvalid AND indisready AND indislive AND indimmediate
       ) THEN
        RAISE EXCEPTION 'Schema migration required for staging.stg_garmin_daily_summary indexes: expected only a valid, immediate primary unique index on (user_profile_id, calendar_date)'
            USING HINT = 'Apply a reviewed migration; existing schema and rows are unchanged.';
    END IF;
END
$$;
