-- One ReARM user is linked to at most one ACTIVE committer per org.
-- Archived rows can keep the link for historical attribution, so the
-- partial index narrows on status='ACTIVE'.
CREATE UNIQUE INDEX committers_org_user_active
    ON rearm.committers (
        (record_data->>'org'),
        (record_data->>'user')
    )
    WHERE record_data->>'user' IS NOT NULL
      AND record_data->>'status' = 'ACTIVE';
