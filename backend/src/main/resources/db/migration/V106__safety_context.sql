CREATE TABLE rearm.safety_context_proposals (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY DEFAULT gen_random_uuid(),
    revision integer NOT NULL DEFAULT 0,
    schema_version integer NOT NULL DEFAULT 0,
    created_date timestamptz NOT NULL DEFAULT now(),
    last_updated_date timestamptz NOT NULL DEFAULT now(),
    record_data jsonb NOT NULL
);

CREATE TABLE rearm.safety_context (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY DEFAULT gen_random_uuid(),
    revision integer NOT NULL DEFAULT 0,
    schema_version integer NOT NULL DEFAULT 0,
    created_date timestamptz NOT NULL DEFAULT now(),
    last_updated_date timestamptz NOT NULL DEFAULT now(),
    record_data jsonb NOT NULL
);

-- Reviewer-inbox style queries
CREATE INDEX safety_context_proposals_org_status_idx
    ON rearm.safety_context_proposals
    USING btree (
        (record_data->>'org'),
        (record_data->>'status')
    );

-- Re-import deduplication
CREATE INDEX safety_context_proposals_dedupe_idx
    ON rearm.safety_context_proposals
    USING btree (
        (record_data->>'org'),
        (record_data->>'sourceArtifact'),
        (record_data->>'sourceStatementHash')
    );

-- Artifact lookup
CREATE INDEX safety_context_proposals_artifact_idx
    ON rearm.safety_context_proposals
    USING btree (
        (record_data->>'sourceArtifact')
    );

-- Accepted record lookup
CREATE INDEX safety_context_artifact_idx
    ON rearm.safety_context
    USING btree (
        (record_data->>'sourceArtifact')
    );