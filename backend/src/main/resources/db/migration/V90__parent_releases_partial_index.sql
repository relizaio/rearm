-- Replace the parentReleases index from V15.
--
-- V15 created a btree over the TEXT of record_data->>'parentReleases'. A btree
-- entry cannot exceed 2704 bytes (one third of a page), and every parent entry
-- costs ~40-70 bytes of JSON, so inserting or updating a release with more than
-- ~65 direct parent releases fails with:
--   ERROR: index row size NNNN exceeds btree version 4 maximum 2704
-- Any product with more components than that cannot create releases at all.
--
-- Nothing ever looks a release up by the full text of its parent list. The only
-- consumers are the two queries that filter on
--   record_data->>'parentReleases' != '[]'
-- (FIND_ALL_PRODUCT_RELEASES_OF_ORG, FIND_RELEASES_FOR_METRICS_COMPUTE_BY_PARENT),
-- which the partial-index predicate alone serves. The indexed column is the one
-- those queries order by, so the index remains useful for them; the oversized
-- text expression is simply gone.
CREATE INDEX IF NOT EXISTS idx_releases_has_parents
ON rearm.releases (created_date DESC)
WHERE record_data->>'parentReleases' != '[]';

DROP INDEX IF EXISTS rearm.idx_releases_parent_releases;
