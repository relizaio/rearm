-- Backfill: mark pkg:generic canonical components enrichment-terminal.
--
-- pkg:generic purls have no upstream registry by definition, so no
-- vulnerability source (OSS Index / OSV / GHSA ecosystems, NVD-by-CPE)
-- indexes them and BEAR has nothing to resolve them against. Filesystem-
-- cataloguing scanners emit thousands of such per-file rows
-- (pkg:generic/<file>?path=...): left matchable, they clog the oldest-first
-- enrichment candidate window (starving every other BOM's pull), gate
-- fan-out coverage on components that can never yield a finding, and ship
-- inert rows to synthetic Dependency-Track buckets.
--
-- Terminal semantics (V75) already exclude such rows everywhere at once:
-- candidate window, bucket membership (a previously shipped row drops out,
-- its bucket's content hash changes and resubmits once without it - sticky
-- indexes mean no other bucket moves), coverage gate, and stall counters.
-- Mint-time stamping of NEW rows lives in
-- SbomComponentService.stampTerminalIfUnmatchablePurlType.
--
-- A CPE identity rescues a row: NVD matching works regardless of purl type,
-- so CPE-bearing generic components stay matchable. Deliberately no
-- enriched_at guard, unlike markEnrichmentTerminal: a generic row that DID
-- get enriched still cannot match any advisory, and must leave the
-- Dependency-Track population too.
UPDATE rearm.sbom_components
SET flow_control = coalesce(flow_control, '{}'::jsonb)
                   || jsonb_build_object(
                        'enrichmentTerminalAt', to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SSOF'),
                        'enrichmentTerminalReason', 'UNMATCHABLE_PURL_TYPE')
WHERE canonical_purl LIKE 'pkg:generic/%'
  AND flow_control->>'enrichmentTerminalAt' IS NULL
  AND NOT coalesce(identities, '[]'::jsonb) @> '[{"scheme":"cpe"}]';
