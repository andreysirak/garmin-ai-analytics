-- Manual, one-time setup as the analytics bootstrap administrator after staging exists.
-- This is deliberately separate from application startup and pipeline role setup.
\set ON_ERROR_STOP on
BEGIN;
CREATE ROLE garmin_chat_reader LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOREPLICATION NOBYPASSRLS;
GRANT CONNECT ON DATABASE garmin_analytics TO garmin_chat_reader;
GRANT USAGE ON SCHEMA staging TO garmin_chat_reader;
GRANT SELECT ON TABLE staging.stg_garmin_daily_summary TO garmin_chat_reader;
ALTER ROLE garmin_chat_reader SET default_transaction_read_only = on;
COMMIT;
-- Set the password using psql's interactive \password, never in this file.
\password garmin_chat_reader
