SELECT user_profile_id, calendar_date, payload
FROM staging.stg_garmin_daily_summary
WHERE user_profile_id = :profileId
  AND calendar_date BETWEEN CAST(:startDate AS date) AND CAST(:endDate AS date)
ORDER BY user_profile_id, calendar_date;
