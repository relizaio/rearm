CREATE TABLE rearm.vex_statement_proposals (
    uuid UUID PRIMARY KEY,
    revision INTEGER NOT NULL DEFAULT 0,
    schema_version INTEGER NOT NULL DEFAULT 0,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    record_data JSONB NOT NULL
);

-- Index for the most common reviewer-inbox query: pending proposals for an org.
CREATE INDEX vex_statement_proposals_org_status_idx
    ON rearm.vex_statement_proposals
    USING btree ((record_data->>'org'), (record_data->>'status'));

-- Index for re-import dedupe: lookup by (org, sourceArtifact, sourceStatementHash).
CREATE INDEX vex_statement_proposals_dedupe_idx
    ON rearm.vex_statement_proposals
    USING btree ((record_data->>'org'),
                 (record_data->>'sourceArtifact'),
                 (record_data->>'sourceStatementHash'));

-- Index for the per-source-artifact audit query.
CREATE INDEX vex_statement_proposals_artifact_idx
    ON rearm.vex_statement_proposals
    USING btree ((record_data->>'sourceArtifact'));
