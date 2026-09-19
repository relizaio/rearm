-- Single use of federated identity tokens across every backend node: the (issuer, jti) of an
-- exchanged assertion is reserved here for the assertion's lifetime. Rows are purged once expired.
CREATE TABLE rearm.federated_assertion_ids (
    issuer text NOT NULL,
    jti text NOT NULL,
    expires_date timestamptz NOT NULL,
    PRIMARY KEY (issuer, jti)
);

CREATE INDEX federated_assertion_ids_expiry_idx ON rearm.federated_assertion_ids (expires_date);
