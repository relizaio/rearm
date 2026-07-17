-- Board task #38 Stage 4 (v3 era): v1 (finding_change_events) and the v2 fact
-- (finding_change_events_v2) are fully superseded by finding_change_events_v3 on every operated
-- instance (neither ever shipped to customers). finding_dim is SHARED with v3 and is KEPT.
-- Forward-only.
DROP TABLE IF EXISTS rearm.finding_change_events_v2 CASCADE;
DROP TABLE IF EXISTS rearm.finding_change_events CASCADE;
