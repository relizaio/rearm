CREATE TABLE rebom.bom_lineage (
    organization uuid NOT NULL,
    serial_number text NOT NULL,
    owner_component uuid NULL,
    claimed_at timestamptz NULL,
    claimed_via text NULL,
    claimed_by text NULL,
    created_date timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization, serial_number)
);
