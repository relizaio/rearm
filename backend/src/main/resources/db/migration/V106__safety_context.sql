-- SPDX-License-Identifier: AGPL-3.0-only
-- CycloneDX 2.0 SAFETY_CONTEXT import (FDA-5). Imported safety integrity levels are staged
-- as proposals; accepted ones become records. Records are never deleted: a change supersedes
-- the ACTIVE record. Accepted means ingested with provenance, not verified by ReARM.
-- No foreign keys (repo convention); every query filters by org first.

CREATE TABLE rearm.safety_context_proposals (
    uuid UUID PRIMARY KEY,
    revision INTEGER NOT NULL DEFAULT 0,
    schema_version INTEGER NOT NULL DEFAULT 0,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    record_data JSONB NOT NULL,
    CONSTRAINT safety_context_proposals_lineage_nn CHECK (record_data->>'lineageKey' IS NOT NULL)
);

-- Review inbox: org + status, newest first.
CREATE INDEX safety_context_proposals_org_status_idx
    ON rearm.safety_context_proposals USING btree
    ((record_data->>'org'), (record_data->>'status'), created_date DESC);
CREATE INDEX safety_context_proposals_org_artifact_idx
    ON rearm.safety_context_proposals USING btree
    ((record_data->>'org'), (record_data->>'sourceArtifact'));
CREATE INDEX safety_context_proposals_org_release_idx
    ON rearm.safety_context_proposals USING btree
    ((record_data->>'org'), (record_data->>'releaseUuid'), (record_data->>'status'));
-- At most one PENDING proposal per identity and lineage; a re-upload of the same lineage
-- supersedes it, another lineage stages alongside.
CREATE UNIQUE INDEX safety_context_proposals_pending_uq
    ON rearm.safety_context_proposals USING btree
    ((record_data->>'org'), (record_data->>'scope'), COALESCE(record_data->>'scopeUuid', ''),
     (record_data->>'componentPurl'), (record_data->>'schemeKey'),
     COALESCE(record_data->>'blueprintRefId', ''), COALESCE(record_data->>'assetRefId', ''),
     (record_data->>'lineageKey'))
    WHERE record_data->>'status' = 'PENDING';

CREATE TABLE rearm.safety_context_records (
    uuid UUID PRIMARY KEY,
    revision INTEGER NOT NULL DEFAULT 0,
    schema_version INTEGER NOT NULL DEFAULT 0,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    record_data JSONB NOT NULL
);

-- One ACTIVE record per identity; this is the upsert key and closes the concurrent-upload race.
CREATE UNIQUE INDEX safety_context_records_active_uq
    ON rearm.safety_context_records USING btree
    ((record_data->>'org'), (record_data->>'scope'), COALESCE(record_data->>'scopeUuid', ''),
     (record_data->>'componentPurl'), (record_data->>'schemeKey'),
     COALESCE(record_data->>'blueprintRefId', ''), COALESCE(record_data->>'assetRefId', ''))
    WHERE record_data->>'status' = 'ACTIVE';
-- Component-wide reads (most conservative level across assets) and the coarse lock key.
CREATE INDEX safety_context_records_target_idx
    ON rearm.safety_context_records USING btree
    ((record_data->>'org'), (record_data->>'scope'), (record_data->>'scopeUuid'),
     (record_data->>'componentPurl'), (record_data->>'schemeKey'));
CREATE INDEX safety_context_records_org_release_idx
    ON rearm.safety_context_records USING btree
    ((record_data->>'org'), (record_data->>'releaseUuid'), (record_data->>'status'));
CREATE INDEX safety_context_records_org_artifact_idx
    ON rearm.safety_context_records USING btree
    ((record_data->>'org'), (record_data->>'sourceArtifact'));
