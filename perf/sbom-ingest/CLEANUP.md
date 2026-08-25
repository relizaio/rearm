# Cleanup after a run

**Do not run these immediately after a test.** The data is the analysis material;
wait for an explicit go-ahead. Recorded here so cleanup is reproducible when the
call is made.

A run leaves four kinds of residue. Only the first is large.

## 1. Releases and SBOM components in the test org

The bulk. A 20,000-release run adds roughly 20,000 rows to `rearm.releases`,
plus `rearm.artifacts`, `rearm.artifact_canonical_map`, `rearm.sbom_components`
and the rebom-backend's own content-addressed store.

Archiving the organization is **not** sufficient: `archiveOrganization` does not
delete rows, and on this codebase it has historically not even persisted the
ARCHIVED state. Deletion has to be explicit.

Identify the orgs first -- the load test creates one per run, named
`sbom-*`:

```sql
SELECT uuid, record_data->>'name' AS name, created_date
FROM rearm.organizations
WHERE record_data->>'name' LIKE 'sbom-%'
ORDER BY created_date;
```

Then, per org uuid, delete children before parents (there are no FK cascades in
this schema by design -- see coding_principles.md):

```sql
-- Order matters. Run inside a transaction and check counts before COMMIT.
BEGIN;
DELETE FROM rearm.sbom_components          WHERE org = '<org>';
DELETE FROM rearm.synthetic_dtrack_bucket  WHERE org = '<org>';
DELETE FROM rearm.artifact_canonical_map   WHERE artifact IN (SELECT uuid FROM rearm.artifacts WHERE org = '<org>');
DELETE FROM rearm.artifacts                WHERE org = '<org>';
DELETE FROM rearm.releases                 WHERE record_data->>'org' = '<org>';
DELETE FROM rearm.branches                 WHERE record_data->>'org' = '<org>';
DELETE FROM rearm.components               WHERE record_data->>'org' = '<org>';
DELETE FROM rearm.api_keys                 WHERE org = '<org>';
DELETE FROM rearm.organizations            WHERE uuid = '<org>';
COMMIT;
```

Verify the reconcile queue is empty for the org *before* deleting, or the
scheduler will keep selecting rows that are disappearing underneath it:

```sql
SELECT count(*) FROM rearm.releases
WHERE record_data->>'org' = '<org>'
  AND flow_control->>'sbomReconcileRequestedAt' IS NOT NULL;
```

Follow with a targeted vacuum, since these are large deletes:

```sql
VACUUM ANALYZE rearm.releases;
VACUUM ANALYZE rearm.sbom_components;
VACUUM ANALYZE rearm.artifacts;
```

## 2. Keycloak users

One throwaway user per staging run, all matching the test prefixes:

```bash
# list
curl -s "$URL/kauth/admin/realms/Reliza/users?search=sbomrun" -H "Authorization: Bearer $ADMIN_TOKEN"
curl -s "$URL/kauth/admin/realms/Reliza/users?search=loadtest" -H "Authorization: Bearer $ADMIN_TOKEN"
# delete by id
curl -s -X DELETE "$URL/kauth/admin/realms/Reliza/users/<id>" -H "Authorization: Bearer $ADMIN_TOKEN"
```

## 3. Host-side files

```bash
rm -rf /tmp/sbom-ingest-boms          # synthesized BOMs; the plan deletes each
                                      # after upload, so this should already be
                                      # near-empty. Non-empty means the run died
                                      # mid-flight.
rm -f /home/reliza/.loadtest-keys.json /home/reliza/.loadtest_run_org \
      /home/reliza/.loadtest_key /home/reliza/.loadtest_org
```

The API keys in that file are live `ORGANIZATION_RW` credentials for the test
org. Delete them even if the org is being kept.

## 4. rebom-backend content store

rebom stores BOMs content-addressed in its own Postgres
(`rearm-rebom-postgres-0`). Deleting the ReARM-side rows does not reclaim it.
Check size before deciding whether it is worth touching:

```bash
kubectl exec -n rearm rearm-rebom-postgres-0 -- \
  psql -U postgres -d rebom -c "SELECT pg_size_pretty(pg_database_size('rebom'));"
```

## What NOT to clean

- The component pool (`lib/pool.json`) is an input, not residue.
- `results/` is the record of the run and belongs in the repo.
