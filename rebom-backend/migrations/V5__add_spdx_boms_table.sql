CREATE TABLE spdx_boms (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    spdx_metadata jsonb NOT NULL, -- Extracted key fields from SPDX (SPDXID, name, version, etc.)
    oci_response jsonb NULL, -- Store OASResponse from OCI upload
    converted_bom_uuid uuid NULL, -- Reference to converted CycloneDX BOM
    organization uuid NULL,
    file_sha256 varchar(64) NULL, -- SHA256 of original SPDX file
    conversion_status varchar(20) DEFAULT 'pending', -- pending, success, failed
    conversion_error text NULL,
    tags jsonb NULL,
    public boolean NOT NULL default false,
    FOREIGN KEY (converted_bom_uuid) REFERENCES boms(uuid) ON DELETE SET NULL
);

CREATE INDEX spdx_bom_org_index ON spdx_boms(organization);
CREATE INDEX spdx_bom_conversion_status_index ON spdx_boms(conversion_status);
CREATE UNIQUE INDEX spdx_bom_spdxid_unique_index ON spdx_boms((spdx_metadata->>'SPDXID'));
CREATE INDEX spdx_bom_metadata_gin_index ON spdx_boms USING gin(spdx_metadata);
