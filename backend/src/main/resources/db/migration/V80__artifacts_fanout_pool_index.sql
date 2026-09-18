-- Ordered index for findCanonicalArtifactsNeedingFanOut's pool slice, matching
-- the coalesce() rewrite of findFanOutPoolSlice that ships with it.
--
-- V74 added artifacts_org_last_scanned_idx to stop this query timing out and
-- fixed only half of it. The predicate was an OR of two ranges
-- (lastScanned IS NULL OR lastScanned < cutoff), which cannot become an index
-- condition, so lastScanned stayed a Filter and the ORDER BY stayed a separate
-- Sort. A Sort consumes its whole input before emitting a row, so the LIMIT
-- never got a stop key and each tick cost O(org) rather than O(limit). That is
-- the plan production reached: a QueryTimeoutException on this exact statement,
-- 2026-08-14 overnight. It is self-reinforcing -- fan-out is what stamps
-- lastScanned, so a tick that cannot finish leaves its candidates unstamped and
-- the pool the next tick must sort is no smaller.
--
-- The query now compares and orders by coalesce(lastScanned, -1), one range
-- over one key; this index stores that expression so the scan streams rows
-- already sorted and stops at the LIMIT. The spellings must stay identical or
-- the planner loses the match. Ordering is unchanged: -1 sorts below every real
-- epoch, reproducing NULLS FIRST. See findFanOutPoolSlice's javadoc for why the
-- sentinel is -1 and not 0.
--
-- Measured on 216k artifacts across a backlog org (96k never-scanned) and a
-- steady-state org (120k all recently scanned), sandbox 2026-08-15, LIMIT 2000:
--
--   plan                              steady state   backlog
--   V74, OR predicate                 1.9 ms         31.5 ms (prod: timeout)
--   ordered index, OR predicate       57.0 ms        0.9 ms
--   this index + coalesce rewrite     0.06 ms        0.7 ms
--
-- The middle row is why both shapes have to be measured together: an ordered
-- index alone looks like a fix on the backlog org, because a backlog fills the
-- LIMIT and exits early even without a stop key. The steady-state org is the
-- one that exposes the missing stop key, and it is the permanent shape.
--
-- Plain (blocking) build, not CONCURRENTLY, for the reason V73 documents at
-- length: community Flyway holds its schema-history lock connection
-- idle-in-transaction while a non-transactional script runs on a second
-- connection, and CREATE INDEX CONCURRENTLY then waits on that virtualxid
-- forever. Unlike V73's, this index is not partial, so on a large instance the
-- build is a full scan of artifacts and holds SHARE on the table for its
-- duration: writes block, reads do not. Nothing here takes ACCESS EXCLUSIVE --
-- no index is dropped -- so an operator who cannot afford even the write pause
-- can build this index by hand with CONCURRENTLY before upgrading and the
-- migration will accept it.
--
-- That escape hatch is why the build is guarded rather than a bare
-- IF NOT EXISTS, which matches on NAME alone: a cancelled or failed CONCURRENTLY
-- build leaves an indisvalid = false index of this name behind, IF NOT EXISTS
-- would adopt it silently, and the query would stay on the Filter+Sort plan this
-- migration exists to retire, with V80 recorded as applied and no way to
-- self-heal. So an invalid index of this name is dropped and rebuilt.
--
-- The guard deliberately stops at indisvalid and TRUSTS a valid index of this
-- name to be the right one. Comparing pg_get_indexdef against an expected
-- string was tried and rejected: Postgres renders this definition with
-- normalizations that are not reproducible by hand and are not contractual
-- across versions -- the sentinel comes back as ('-1'::integer)::double
-- precision, and the COALESCE key loses the parentheses it was written with --
-- so the comparison never matched, and the "guard" silently rebuilt the index
-- on every run, which is precisely the outage the escape hatch exists to
-- avoid. A wrong-shaped-but-valid index of this exact name is not a state any
-- released artifact can produce, since the only definition an operator has to
-- copy is the one below.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_class c
               JOIN pg_namespace n ON n.oid = c.relnamespace
               JOIN pg_index i ON i.indexrelid = c.oid
               WHERE n.nspname = 'rearm'
                 AND c.relname = 'artifacts_org_fanout_pool_idx'
                 AND NOT i.indisvalid) THEN
        DROP INDEX rearm.artifacts_org_fanout_pool_idx;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS artifacts_org_fanout_pool_idx
    ON rearm.artifacts (
        (record_data->>'org'),
        (coalesce(cast(metrics->>'lastScanned' as float), -1)),
        created_date DESC);

-- V74's artifacts_org_last_scanned_idx is deliberately KEPT. It looks redundant
-- -- same table, same leading org key, same underlying field -- but it is not:
-- it keys the bare cast(... as float), and three live consumers need exactly
-- that spelling, none of which coalesce() can serve.
--   - VariableQueries.FIND_MAX_LAST_SCANNED_EPOCH_FOR_ORG takes
--     max(cast(... as float)) per org; its comment states it is shaped around
--     V74's index. It backs NotificationFanOutService's affected-release guard,
--     which must not silently degrade to an estate-sized scan.
--   - ArtifactCanonicalMapRepository's two fan-out stall diagnostics filter
--     cast(... as float) IS NULL, which btree answers as an is-null scan.
-- coalesce(..., -1) is never NULL and is a different expression from the bare
-- cast, so the index below cannot serve any of the three whatever the planner
-- would otherwise prefer. Which index those consumers actually get is
-- distribution-dependent -- on the sandbox estate they chose the older
-- idx_artifacts_metrics_last_scanned over V74's, so this is not the claim that
-- V74's is always their plan, only that removing it here would be an unforced
-- bet on that never mattering at production scale. The two indexes coexist by
-- design, at the cost of a second index write per artifact scan stamp;
-- re-spelling those consumers in coalesce() form would let a later migration
-- revisit it.
--
-- Statistics for an expression index live with the index, so the new one has
-- none until it is analyzed, and autovacuum will not revisit a large artifacts
-- table until roughly 10% of it changes. The design depends on that estimate.
ANALYZE rearm.artifacts;
