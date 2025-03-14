DROP INDEX IF EXISTS rebom.bom_version_component_unique_index;
CREATE UNIQUE INDEX bom_version_component_unique_index on rebom.boms ( (bom->>'version'), (bom->'metadata'->'component'->>'group'), (bom->'metadata'->'component'->>'name'), (bom->'metadata'->'component'->>'version'), (bom->'metadata'->'component'->>'purl') );
