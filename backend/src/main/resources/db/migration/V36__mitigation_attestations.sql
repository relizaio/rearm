CREATE TABLE rearm.mitigation_attestations (
    uuid UUID PRIMARY KEY,
    revision INTEGER NOT NULL DEFAULT 0,
    schema_version INTEGER NOT NULL DEFAULT 0,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    record_data JSONB NOT NULL
);

-- Inbox query: pending attestations for an org.
CREATE INDEX mitigation_attestations_org_status_idx
    ON rearm.mitigation_attestations
    USING btree ((record_data->>'org'), (record_data->>'status'));

-- Per-proposal lookup (one attestation per proposal).
CREATE INDEX mitigation_attestations_proposal_idx
    ON rearm.mitigation_attestations
    USING btree ((record_data->>'proposal'));

-- Per-assignee inbox.
CREATE INDEX mitigation_attestations_assignee_idx
    ON rearm.mitigation_attestations
    USING btree ((record_data->>'assignedTo'));
