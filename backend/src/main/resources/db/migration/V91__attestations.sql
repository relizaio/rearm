-- Accountable statements by users and agents: commit acknowledgements and lock releases now,
-- key ownership and review next without a schema change.
--
-- Deliberately NOT folded into rearm.mitigation_attestations, which stays as it is: that table
-- models an assignment with a deadline and a workflow status, this one is an append-only log
-- where a correction is a new row. Merging them is a later migration with its own decisions.
CREATE TABLE rearm.attestations (
    uuid UUID PRIMARY KEY,
    revision INTEGER NOT NULL DEFAULT 0,
    schema_version INTEGER NOT NULL DEFAULT 0,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    record_data JSONB NOT NULL
);

-- The hot read: every attestation of one subject. Recognition asks this per commit, and the
-- activation builder asks it for every commit of a release at once.
CREATE INDEX attestations_org_subject_idx
    ON rearm.attestations
    USING btree ((record_data->>'org'), (record_data->>'subjectType'), (record_data->>'subjectUuid'));

-- The integrity inbox: open items of a type for an org.
CREATE INDEX attestations_org_type_status_idx
    ON rearm.attestations
    USING btree ((record_data->>'org'), (record_data->>'type'), (record_data->>'status'));

-- "What has this principal attested to", for the actor's own history.
CREATE INDEX attestations_org_actor_idx
    ON rearm.attestations
    USING btree ((record_data->>'org'), (record_data->>'actorUuid'));
