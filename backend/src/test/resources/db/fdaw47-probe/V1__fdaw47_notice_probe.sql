-- FDAW-47: the notice probe for FlywayMigrationNoticeLogTest. Not under db/migration: the
-- application never scans this location. The CREATE TABLE raises nothing; the DO block raises one
-- NOTICE (SQL state 00000) and one WARNING (SQL state 01000).
-- The counts are RAISE NOTICE lines; the deploy log carries each as `migration V<n> notice: <text>`
-- (logger io.reliza.common.MigrationNoticeLogger) and as Flyway's `DB: <text>` (logger
-- org.flywaydb.core.internal.sqlscript.DefaultSqlScriptExecutor), both at INFO. RAISE WARNING is for
-- a condition the operator should act on and is logged on both loggers at WARN. A notice carries
-- counts and row uuids only, never a credential, a person's name or e-mail, or user-written text.
-- The session admits notices because spring.flyway.init-sqls runs SET client_min_messages TO NOTICE.
CREATE TABLE fdaw47_probe.marker (n int);
DO $$ BEGIN
    RAISE NOTICE 'fdaw47 probe: % rows migrated', 3;
    RAISE WARNING 'fdaw47 probe warning';
END $$;
