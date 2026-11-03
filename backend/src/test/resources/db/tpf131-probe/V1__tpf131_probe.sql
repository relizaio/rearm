-- TPF-131: a failing non-transactional probe script for FlywayFailedNonTransactionalMigrationHistoryTest.
-- The first statement commits on its own (the .conf beside it says executeInTransaction=false),
-- the second fails. Not under db/migration: the application never scans this location.
CREATE TABLE IF NOT EXISTS tpf131_probe.marker (n int);
SELECT 1/0;
