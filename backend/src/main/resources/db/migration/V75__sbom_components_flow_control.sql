-- Per-component flow control, mirroring releases.flow_control and
-- artifact_canonical_map.flow_control (jsonb so later per-component queue
-- state can share it without further DDL).
--
-- First key: enrichmentTerminalAt (+ enrichmentTerminalReason). Set when a
-- component's own representative BOM has been fully pulled and stamped and the
-- component STILL could not be matched (era-drifted coordinates, re-parse no
-- longer contains it, internal self-references) -- i.e. no mechanism can ever
-- enrich it. Terminal rows keep enriched_at NULL (they genuinely were never
-- enriched) and instead leave the matchable universe entirely: excluded from
-- the enrichment candidate window, the synthetic bucket membership (nothing
-- useless ships to DTrack), the fan-out coverage gate (mirroring the isRoot
-- exclusion -- otherwise artifacts containing them would scan-block forever),
-- and the stall counters. Without a terminal state such rows sat at the head
-- of the oldest-first candidate window permanently, decaying the enrichment
-- drain toward zero (observed live 2026-07-26 as {COMPLETED=N} residue).
ALTER TABLE rearm.sbom_components
    ADD COLUMN flow_control jsonb;
