-- Synthetic privilege checks. Everything, including fixture DDL, is rolled back.
\set ON_ERROR_STOP on
BEGIN;
DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_roles WHERE rolname IN ('garmin_owner', 'garmin_loader')
             AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) THEN
    RAISE EXCEPTION 'Application roles must not have administrative privileges';
  END IF;
  IF pg_has_role('garmin_loader', 'garmin_owner', 'MEMBER') THEN
    RAISE EXCEPTION 'The loader must not be a member of the owner role';
  END IF;
END
$$;
SET ROLE garmin_owner;
CREATE TABLE staging._role_privilege_check (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  marker text NOT NULL
);
RESET ROLE;
-- A real loader session must be tested: SET ROLE alone retains the superuser
-- session identity and would still allow switching to any role.
SET SESSION AUTHORIZATION garmin_loader;
INSERT INTO staging._role_privilege_check (marker) VALUES ('synthetic');
SELECT id FROM staging._role_privilege_check WHERE marker = 'synthetic';
UPDATE staging._role_privilege_check SET marker = 'updated';
DELETE FROM staging._role_privilege_check WHERE marker = 'updated';
DO $$
BEGIN
  BEGIN
    CREATE TABLE staging._loader_must_not_create (id integer);
    RAISE EXCEPTION 'Loader unexpectedly created a staging table';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  BEGIN
    DROP TABLE staging._role_privilege_check;
    RAISE EXCEPTION 'Loader unexpectedly dropped an owner table';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  BEGIN
    CREATE SCHEMA _loader_must_not_create;
    RAISE EXCEPTION 'Loader unexpectedly created a schema';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  BEGIN
    SET ROLE garmin_owner;
    RAISE EXCEPTION 'Loader unexpectedly assumed the owner role';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END
$$;
RESET SESSION AUTHORIZATION;
ROLLBACK;
\echo 'PASS: owner-created tables/sequences allow loader DML and reject loader DDL/ownership'
