ALTER TABLE boms ADD COLUMN source_spdx_uuid uuid NULL;
ALTER TABLE boms ADD COLUMN source_format varchar(10) DEFAULT 'cyclonedx';
ALTER TABLE boms ADD CONSTRAINT fk_boms_source_spdx 
    FOREIGN KEY (source_spdx_uuid) REFERENCES spdx_boms(uuid) ON DELETE SET NULL;

CREATE INDEX boms_source_spdx_index ON boms(source_spdx_uuid);
CREATE INDEX boms_source_format_index ON boms(source_format);
