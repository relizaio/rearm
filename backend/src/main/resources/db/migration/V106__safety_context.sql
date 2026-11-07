-- SPDX-License-Identifier: AGPL-3.0-only
-- Staged and accepted safety-context records are kept separately so an imported
-- assertion cannot become part of the accepted store until the trust gate acts.
CREATE TABLE rearm.safety_context_proposals (
    uuid UUID PRIMARY KEY,
    revision INTEGER NOT NULL DEFAULT 0,
    schema_version INTEGER NOT NULL DEFAULT 0,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    record_data JSONB NOT NULL
);
CREATE INDEX safety_context_proposals_org_status_idx
    ON rearm.safety_context_proposals USING btree
    ((record_data->>'org'), (record_data->>'status'));
CREATE INDEX safety_context_proposals_source_dedupe_idx
    ON rearm.safety_context_proposals USING btree
    ((record_data->>'org'), (record_data->>'sourceArtifact'),
     (record_data->>'sourceAssertionHash'));
CREATE INDEX safety_context_proposals_release_component_idx
    ON rearm.safety_context_proposals USING btree
    ((record_data->>'releaseUuid'), (record_data->>'sbomComponentUuid'));

CREATE TABLE rearm.safety_context (
    uuid UUID PRIMARY KEY,
    revision INTEGER NOT NULL DEFAULT 0,
    schema_version INTEGER NOT NULL DEFAULT 0,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    record_data JSONB NOT NULL
);
CREATE INDEX safety_context_org_release_idx
    ON rearm.safety_context USING btree
    ((record_data->>'org'), (record_data->>'releaseUuid'));
CREATE INDEX safety_context_component_idx
    ON rearm.safety_context USING btree
    ((record_data->>'sbomComponentUuid'));
-- acceptedVia is stored in record_data. It records the import path, not approval
-- of the source safety decision.
CREATE INDEX safety_context_accepted_via_idx
    ON rearm.safety_context USING btree ((record_data->>'acceptedVia'));
