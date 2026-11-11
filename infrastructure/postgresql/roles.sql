-- Run as the bootstrap superuser in garmin_analytics, including after a restore.
-- Passwords are deliberately absent: set the loader password with psql \password.
\set ON_ERROR_STOP on
BEGIN;
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'garmin_owner') THEN
    CREATE ROLE garmin_owner NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'garmin_loader') THEN
    CREATE ROLE garmin_loader LOGIN;
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'garmin_schema_manager') THEN
    CREATE ROLE garmin_schema_manager LOGIN;
  END IF;
END
$$;
ALTER ROLE garmin_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE garmin_loader LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE garmin_schema_manager LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
REVOKE garmin_owner FROM garmin_loader;
GRANT garmin_owner TO garmin_schema_manager WITH INHERIT FALSE, SET TRUE;

ALTER DATABASE garmin_analytics OWNER TO garmin_owner;
REVOKE ALL ON DATABASE garmin_analytics FROM PUBLIC;
GRANT CONNECT ON DATABASE garmin_analytics TO garmin_loader;
GRANT CONNECT ON DATABASE garmin_analytics TO garmin_schema_manager;
ALTER SCHEMA public OWNER TO garmin_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA IF NOT EXISTS staging AUTHORIZATION garmin_owner;
ALTER SCHEMA staging OWNER TO garmin_owner;
REVOKE ALL ON SCHEMA staging FROM PUBLIC;
GRANT USAGE ON SCHEMA staging TO garmin_loader;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA staging TO garmin_loader;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA staging TO garmin_loader;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA staging FROM PUBLIC;

-- Migrations must SET ROLE garmin_owner before creating objects. Defaults belong
-- to the role that creates an object, not to the bootstrap admin running setup.
ALTER DEFAULT PRIVILEGES FOR ROLE garmin_owner IN SCHEMA staging
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO garmin_loader;
ALTER DEFAULT PRIVILEGES FOR ROLE garmin_owner IN SCHEMA staging
  GRANT USAGE, SELECT ON SEQUENCES TO garmin_loader;
ALTER DEFAULT PRIVILEGES FOR ROLE garmin_owner
  REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
COMMIT;
