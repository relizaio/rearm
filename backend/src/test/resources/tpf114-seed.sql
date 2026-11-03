-- TPF-114 architecture 4.9: the query-plan seed. Not run by any test: load it into a scratch
-- database that Flyway has migrated through V102, for example
--   psql -h localhost -p 5440 -U postgres -d <scratch> -v ON_ERROR_STOP=1 -f tpf114-seed.sql
-- then run the EXPLAIN statements listed in the PR description (and in the TPF-114 notes).
--
-- Shape: org X with 20,000 BOM artifacts, each mapped to itself as its own canonical, plus a
-- second map row (a duplicate upload) for 1,000 of them; 100 INGESTED buckets of 500 bucketed
-- sbom_components (50,000 rows, all dated T0 = now() - 1 day); each canonical holds 40 random
-- components plus 3 "ubiquitous" ones in bucket 0 (about 860k artifact_sbom_components rows);
-- 10,000 non-BOM artifacts without map rows. Org Y: 2,000 canonicals of the same shape over 10
-- buckets. Every map row starts stamped at T0 (the cold-walk case); the statements in the PR
-- unstamp or bump what each measurement needs.

\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE tpf114_org (org uuid PRIMARY KEY, canonicals int, buckets int, non_bom int,
		duplicates int);
INSERT INTO tpf114_org VALUES
	('7f114000-0000-4000-8000-00000000000a', 20000, 100, 10000, 1000),
	('7f114000-0000-4000-8000-00000000000b', 2000, 10, 0, 0);

-- Components: 500 per bucket, bucketed (uuids kept in a temp table for the holdings below).
CREATE TEMP TABLE tpf114_comp AS
SELECT o.org, b, c, gen_random_uuid() AS uuid, 'pkg:npm/tpf114-b' || b || '-c' || c || '@1.0.0' AS purl
FROM tpf114_org o, generate_series(0, o.buckets - 1) b, generate_series(0, 499) c;
CREATE INDEX ON tpf114_comp (org, b, c);
ANALYZE tpf114_comp;
INSERT INTO rearm.sbom_components (uuid, org, canonical_purl, record_data, synthetic_bucket_index, enriched_at)
SELECT uuid, org, purl, jsonb_build_object('name', 'tpf114-b' || b || '-c' || c, 'type', 'library'), b, now()
FROM tpf114_comp;

-- Buckets: INGESTED at T0, ref_map keyed by the members' canonical purls.
INSERT INTO rearm.synthetic_dtrack_bucket (org, bucket_index, ingest_state, ref_map, last_updated_date)
SELECT sc.org, sc.synthetic_bucket_index, 'INGESTED',
		jsonb_object_agg(sc.canonical_purl, sc.canonical_purl), now() - interval '1 day'
FROM rearm.sbom_components sc JOIN tpf114_org o ON o.org = sc.org
GROUP BY sc.org, sc.synthetic_bucket_index;

-- BOM artifacts, scanned, each its own canonical.
CREATE TEMP TABLE tpf114_bom AS
SELECT o.org, gen_random_uuid() AS uuid, n
FROM tpf114_org o, generate_series(1, o.canonicals) n;
INSERT INTO rearm.artifacts (uuid, record_data, metrics, created_date)
SELECT uuid, jsonb_build_object('org', org::text, 'type', 'BOM'),
		jsonb_build_object('lastScanned', extract(epoch FROM now() - interval '1 day'),
				'firstScanned', extract(epoch FROM now() - interval '2 days')),
		now() - interval '3 days' + n * interval '1 second'
FROM tpf114_bom;
INSERT INTO rearm.artifact_canonical_map (org, artifact_uuid, canonical_artifact_uuid, created_date, flow_control)
SELECT b.org, b.uuid, b.uuid, now() - interval '3 days' + b.n * interval '1 second',
		jsonb_build_object('fanOutAt', to_char((now() - interval '1 day') AT TIME ZONE 'UTC',
				'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
FROM tpf114_bom b;

-- Duplicate uploads: a second artifact and map row for the first `duplicates` canonicals.
CREATE TEMP TABLE tpf114_dup AS
SELECT b.org, gen_random_uuid() AS uuid, b.uuid AS canonical, b.n
FROM tpf114_bom b JOIN tpf114_org o ON o.org = b.org WHERE b.n <= o.duplicates;
INSERT INTO rearm.artifacts (uuid, record_data, metrics)
SELECT uuid, jsonb_build_object('org', org::text, 'type', 'BOM'),
		jsonb_build_object('lastScanned', extract(epoch FROM now() - interval '1 day'),
				'firstScanned', extract(epoch FROM now() - interval '2 days'))
FROM tpf114_dup;
INSERT INTO rearm.artifact_canonical_map (org, artifact_uuid, canonical_artifact_uuid, flow_control)
SELECT org, uuid, canonical,
		jsonb_build_object('fanOutAt', to_char((now() - interval '1 day') AT TIME ZONE 'UTC',
				'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
FROM tpf114_dup;

-- Non-BOM artifacts: never scanned, never mapped.
INSERT INTO rearm.artifacts (record_data)
SELECT jsonb_build_object('org', o.org::text, 'type', 'ATTESTATION')
FROM tpf114_org o, generate_series(1, o.non_bom) n;

-- Holdings: 40 random components per canonical (buckets 1..n-1) plus the 3 ubiquitous ones
-- (bucket 0, c0..c2); a repeated random pick is dropped.
CREATE TEMP TABLE tpf114_pick AS
SELECT b.org, b.uuid AS canonical, (1 + floor(random() * (o.buckets - 1)))::int AS pb,
		floor(random() * 500)::int AS pc
FROM tpf114_bom b JOIN tpf114_org o ON o.org = b.org, generate_series(1, 40) g
UNION ALL
SELECT b.org, b.uuid, 0, u FROM tpf114_bom b, generate_series(0, 2) u;
INSERT INTO rearm.artifact_sbom_components (org, canonical_artifact_uuid, sbom_component_uuid, exact_purl)
SELECT DISTINCT ON (p.canonical, c.uuid) p.org, p.canonical, c.uuid, c.purl
FROM tpf114_pick p JOIN tpf114_comp c ON c.org = p.org AND c.b = p.pb AND c.c = p.pc;

COMMIT;

ANALYZE rearm.artifacts;
ANALYZE rearm.artifact_canonical_map;
ANALYZE rearm.artifact_sbom_components;
ANALYZE rearm.sbom_components;
ANALYZE rearm.synthetic_dtrack_bucket;
