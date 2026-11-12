INSERT INTO {schema}.{table}
    (user_profile_id, calendar_date, payload)
VALUES (%s, %s, %s::jsonb)
ON CONFLICT (user_profile_id, calendar_date)
DO UPDATE SET payload = EXCLUDED.payload
