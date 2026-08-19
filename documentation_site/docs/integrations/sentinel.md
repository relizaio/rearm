# Microsoft Sentinel

::: warning ReARM Pro only
Microsoft Sentinel is a **security and operational notification channel** (routed
through **Notifications -> Channels**), not a release-notification integration.
It streams the same event feed described in [Notifications](../configure/notifications)
into Microsoft Sentinel (Azure Log Analytics) via the [Logs Ingestion
API](https://learn.microsoft.com/en-us/azure/azure-monitor/logs/logs-ingestion-api-overview).
:::

## Prerequisites

Before adding a Sentinel channel in ReARM, you need, in the Azure portal:

1. **A Data Collection Endpoint (DCE)** and its ingestion URL, e.g.
   `https://<dce-name>.<region>.ingest.monitor.azure.com`.
2. **A Data Collection Rule (DCR)** that targets your Sentinel/Log Analytics table,
   its **immutable ID** (`dcr-...`), and the **stream name** it exposes
   (typically `Custom-<TableName>_CL`).

   You do **not** need a new table if you already have a DCE and DCR -- ReARM
   needs three values, not three new resources. But the stream's declared
   columns decide what survives: Azure silently drops any property the stream
   does not declare, so a DCR built for a different purpose (or a built-in
   table such as `CommonSecurityLog`, whose schema is fixed) will accept the
   batch and quietly discard most of it. `PayloadJson` carries the full event
   regardless, so declaring at least that column means nothing is ever lost.
3. **An Azure AD app registration (service principal)** with the **Monitoring
   Metrics Publisher** role assigned on the DCR, giving you a **tenant ID**,
   **client ID**, and **client secret**.

Microsoft's own [Sentinel data connector
docs](https://learn.microsoft.com/en-us/azure/sentinel/) walk through creating
the DCE/DCR/service-principal trio if you don't have them yet.

## Setting up the Azure side from scratch

If you don't already have a table and DCR, do this first -- it takes about ten
minutes and gets you a correct schema in one pass.

The Azure portal's table wizard infers the table columns **and** the DCR stream
declaration from a sample record you upload. Seeding it with a representative
ReARM record is by far the easiest route, because it creates both halves
together and you never have to keep them in step by hand.

**1. Save this file as `rearm-sample.json`.** It isn't a real notification --
no single event carries every field -- it exists purely so the wizard infers
the full column set and their types.

```json
[
  {
    "TimeGenerated": "2026-08-19T12:04:56.853Z",
    "EventType": "NEW_VULN_AFFECTS_RELEASES",
    "Severity": "CRITICAL",
    "Origin": "REAL",
    "DedupKey": "vuln:CVE-2021-44228:8f14e45f",
    "EventUuid": "38f01f22-ae36-4d72-b5b4-b982ecdf25be",
    "OrgUuid": "c839fa68-1b2c-4ba0-9292-f660e8f47184",
    "ReARMUrl": "https://rearm.example.com/release/show/6869fa46-e4a4-4b9b-87ee-141d19eb3e3a",
    "VulnPrimaryId": "CVE-2021-44228",
    "CvssScore": 9.8,
    "EpssScore": 0.85,
    "OldEpssScore": 0.31,
    "OldSeverity": "MEDIUM",
    "ChangeType": "KEV_ADDED",
    "KevListed": true,
    "FixVersion": "2.17.1",
    "Component": "log4j-core 2.14.1",
    "ComponentPurl": "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1",
    "AffectedReleases": ["payments-api:v9.1 (GENERAL_AVAILABILITY)"],
    "AffectedReleaseDetails": [
      {
        "ReleaseUuid": "6869fa46-e4a4-4b9b-87ee-141d19eb3e3a",
        "Component": "payments-api",
        "ComponentUuid": "0f5c1d2e-9a3b-4c7d-8e6f-1a2b3c4d5e6f",
        "Version": "v9.1",
        "Branch": "main",
        "Lifecycle": "GENERAL_AVAILABILITY",
        "DeployedEnvs": ["prod-eu"]
      }
    ],
    "AffectedComponentNames": ["payments-api"],
    "AffectedComponentUuids": ["0f5c1d2e-9a3b-4c7d-8e6f-1a2b3c4d5e6f"],
    "AffectedReleaseCount": 1,
    "AffectedReleasesTruncated": false,
    "Perspectives": ["2b1c9d8e-7f6a-4b5c-9d8e-7f6a5b4c3d2e"],
    "VexOldState": "affected",
    "VexNewState": "not_affected",
    "ReleaseUuid": "6869fa46-e4a4-4b9b-87ee-141d19eb3e3a",
    "ReleaseVersion": "v9.1",
    "ComponentUuid": "0f5c1d2e-9a3b-4c7d-8e6f-1a2b3c4d5e6f",
    "ComponentName": "payments-api",
    "ComponentType": "COMPONENT",
    "BranchUuid": "3c4d5e6f-1a2b-4c7d-8e6f-9a3b0f5c1d2e",
    "BranchName": "main",
    "Lifecycle": "GENERAL_AVAILABILITY",
    "OldLifecycle": "DRAFT",
    "NewLifecycle": "GENERAL_AVAILABILITY",
    "Scheduled": false,
    "CommitHash": "abc1234",
    "CommitUri": "https://github.com/acme/payments-api/commit/abc1234def5678",
    "CommitMessage": "bump log4j to 2.17.1",
    "UpdatedBy": "Dana Dev",
    "AddedComponents": ["pkg:maven/org.apache.logging.log4j/log4j-core@2.17.1"],
    "RemovedComponents": ["pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1"],
    "AddedCount": 1,
    "RemovedCount": 1,
    "ApprovalRequestUuid": "9d8e7f6a-5b4c-4d2e-3c4d-5e6f1a2b3c4d",
    "RequestedBy": "7f6a5b4c-3d2e-4c7d-8e6f-1a2b3c4d5e6f",
    "RequestedByName": "Dana Dev",
    "ApprovalEntries": ["security-reviewer"],
    "ApprovalEntryCount": 1,
    "TargetUserCount": 2,
    "ApprovalEntryUuid": "5e6f1a2b-3c4d-4b5c-9d8e-7f6a5b4c3d2e",
    "ApprovalEntryName": "security-reviewer",
    "Resolution": "APPROVED",
    "ResolvedBy": "1a2b3c4d-5e6f-4c7d-8e6f-9a3b0f5c1d2e",
    "ResolvedByName": "Sam Security",
    "ResolvedRequestUuids": ["9d8e7f6a-5b4c-4d2e-3c4d-5e6f1a2b3c4d"],
    "PayloadJson": "{\"vulnPrimaryId\":\"CVE-2021-44228\"}",
    "PayloadJsonTruncated": false
  }
]
```

**2. Create the table.** Log Analytics workspace -> **Tables** -> **Create** ->
**New custom log (DCR-based)**. Name it `ReARMNotifications` (Azure appends the
`_CL`). Create a new data collection rule when prompted, pick your DCE, then
**Browse for files** and select `rearm-sample.json`. Leave the transformation
as `source`. Review the inferred schema and create.

**3. Note the three values ReARM needs.** On the new DCR: the **immutable ID**
(`dcr-...`) from its Overview, the **stream name** (`Custom-ReARMNotifications_CL`),
and your DCE's ingestion URL.

**4. Create the service principal.** Microsoft Entra ID -> **App registrations**
-> **New registration**, then **Certificates & secrets** -> **New client secret**.
Copy the secret's **value** immediately -- Azure shows it once, and copying the
secret *ID* instead is the single most common setup mistake.

**5. Grant it access to the DCR.** On the DCR -> **Access control (IAM)** ->
**Add role assignment** -> **Monitoring Metrics Publisher** -> assign to your
app registration. Without this the token is issued but ingestion returns 403.

Then continue with Add the Sentinel channel in ReARM below, and finish with the
paper-plane test.

### The same setup with the Azure CLI

Equivalent to the portal steps above, if you'd rather script it. The DCR
commands need the `monitor-control-service` extension.

```bash
az extension add --name monitor-control-service

RG=my-resource-group
WS=my-workspace
LOC=eastus
DCE=/subscriptions/<sub>/resourceGroups/$RG/providers/Microsoft.Insights/dataCollectionEndpoints/my-dce
WSID=/subscriptions/<sub>/resourceGroups/$RG/providers/Microsoft.OperationalInsights/workspaces/$WS

# 1. Create the table with the full ReARM column set.
az monitor log-analytics workspace table create \
  -g "$RG" --workspace-name "$WS" -n ReARMNotifications_CL --plan Analytics \
  --columns TimeGenerated=datetime EventType=string Severity=string Origin=string \
    DedupKey=string EventUuid=string OrgUuid=string ReARMUrl=string \
    PayloadJson=string PayloadJsonTruncated=boolean \
    VulnPrimaryId=string CvssScore=real EpssScore=real OldEpssScore=real \
    OldSeverity=string ChangeType=string KevListed=boolean FixVersion=string \
    Component=string ComponentPurl=string \
    AffectedReleases=dynamic AffectedReleaseDetails=dynamic \
    AffectedComponentNames=dynamic AffectedComponentUuids=dynamic \
    AffectedReleaseCount=int AffectedReleasesTruncated=boolean Perspectives=dynamic \
    VexOldState=string VexNewState=string \
    ReleaseUuid=string ReleaseVersion=string ComponentUuid=string ComponentName=string \
    ComponentType=string BranchUuid=string BranchName=string Lifecycle=string \
    OldLifecycle=string NewLifecycle=string Scheduled=boolean \
    CommitHash=string CommitUri=string CommitMessage=string UpdatedBy=string \
    AddedComponents=dynamic RemovedComponents=dynamic AddedCount=int RemovedCount=int \
    ApprovalRequestUuid=string RequestedBy=string RequestedByName=string \
    ApprovalEntries=dynamic ApprovalEntryCount=int TargetUserCount=int \
    ApprovalEntryUuid=string ApprovalEntryName=string Resolution=string \
    ResolvedBy=string ResolvedByName=string ResolvedRequestUuids=dynamic
```

Generate the matching DCR stream declaration from that same list rather than
retyping it -- the two must agree exactly:

```bash
# Reuse the --columns arguments above as $COLS, then:
for c in $COLS; do
  printf '{ "name": "%s", "type": "%s" },\n' "${c%%=*}" "${c##*=}"
done
```

Put the result in `dcr.json` and create the rule:

```json
{
  "location": "eastus",
  "kind": "Direct",
  "properties": {
    "streamDeclarations": {
      "Custom-ReARMNotifications_CL": { "columns": [ /* generated list */ ] }
    },
    "destinations": {
      "logAnalytics": [ { "workspaceResourceId": "<WSID>", "name": "rearmDest" } ]
    },
    "dataFlows": [
      {
        "streams": [ "Custom-ReARMNotifications_CL" ],
        "destinations": [ "rearmDest" ],
        "transformKql": "source",
        "outputStream": "Custom-ReARMNotifications_CL"
      }
    ]
  }
}
```

```bash
# 2. Create the DCR.
az monitor data-collection rule create -g "$RG" -n rearm-notifications-dcr --rule-file dcr.json

# 3. Read back the three values ReARM needs.
az monitor data-collection rule show -g "$RG" -n rearm-notifications-dcr \
  --query "{immutableId:immutableId, endpoint:endpoints.logsIngestion}"

# 4. Service principal + role assignment on the DCR.
APP_ID=$(az ad app create --display-name rearm-sentinel --query appId -o tsv)
az ad sp create --id "$APP_ID"
az ad app credential reset --id "$APP_ID" --append --query password -o tsv   # the client secret
DCR_ID=$(az monitor data-collection rule show -g "$RG" -n rearm-notifications-dcr --query id -o tsv)
az role assignment create --assignee "$APP_ID" \
  --role "Monitoring Metrics Publisher" --scope "$DCR_ID"
```

`az account show --query tenantId -o tsv` gives the tenant ID. That plus
`$APP_ID`, the secret from step 4, the ingestion endpoint and immutable ID from
step 3, and the stream name `Custom-ReARMNotifications_CL` are the six values
the ReARM channel form asks for.

### Adding columns to an existing setup

When a ReARM upgrade introduces new fields, add them to **both** halves. Find
your resource names first:

```bash
az monitor log-analytics workspace list \
  --query "[].{workspace:name, resourceGroup:resourceGroup, location:location}" -o table
az monitor data-collection rule list \
  --query "[].{dcr:name, resourceGroup:resourceGroup, immutableId:immutableId}" -o table
```

Match the DCR on the immutable ID configured in the ReARM channel. If several
workspaces look plausible, ask the DCR which one it writes to rather than
guessing from the region:

```bash
az monitor data-collection rule show -g "$RG" -n "$DCR" \
  --query "destinations.logAnalytics[].workspaceResourceId" -o tsv
```

**Pre-flight: confirm the two sides currently agree.** Drift between them is the
failure mode, and nothing in Azure reports it:

```bash
az monitor log-analytics workspace table show -g "$RG" --workspace-name "$WS" \
  -n ReARMNotifications_CL --query "schema.columns[].name" -o tsv | sort > /tmp/table-cols.txt
az monitor data-collection rule show -g "$RG" -n "$DCR" \
  --query 'streamDeclarations."Custom-ReARMNotifications_CL".columns[].name' -o tsv | sort > /tmp/dcr-cols.txt
diff /tmp/table-cols.txt /tmp/dcr-cols.txt && echo "IN SYNC"
```

Lines only in the table are harmless (declared, never sent, always null). Lines
only in the stream are the dangerous direction: the DCR accepts the field and
the table discards it.

::: danger `table update --columns` REPLACES the schema
It does **not** append. You must pass the complete merged set -- every existing
column plus the new ones -- or the omitted ones are **deleted**. Get the current
types from the pre-flight output above.

If you pass only the new columns you'll usually get:

```
(InvalidParameter) User provided schema is invalid ... [Table validation failed
with following 1 errors: MSG 1005: Table missing mandatory 'TimeGenerated'
column or type]
```

That rejection is luck, not a safety net: it fires only because
`TimeGenerated` is mandatory. Omit any *other* column and the call succeeds
and drops it silently.
:::

```bash
az monitor log-analytics workspace table update -g "$RG" --workspace-name "$WS" \
  -n ReARMNotifications_CL --columns \
    TimeGenerated=datetime EventType=string Severity=string Origin=string \
    DedupKey=string EventUuid=string OrgUuid=string ReARMUrl=string \
    PayloadJson=string PayloadJsonTruncated=boolean \
    VulnPrimaryId=string CvssScore=real EpssScore=real OldEpssScore=real \
    OldSeverity=string ChangeType=string KevListed=boolean FixVersion=string \
    Component=string ComponentPurl=string AffectedReleases=dynamic \
    AffectedReleaseDetails=dynamic AffectedReleaseCount=int Perspectives=dynamic \
    VexOldState=string VexNewState=string ReleaseUuid=string \
    ComponentName=string BranchName=string CommitHash=string CommitUri=string \
    CommitMessage=string ReleaseVersion=string Lifecycle=string
```

The DCR is the opposite -- `--add` appends one column at a time, in place, with
no round-trip through a file and no risk of dropping anything:

```bash
for c in ComponentName BranchName CommitHash CommitUri CommitMessage ReleaseVersion Lifecycle; do
  az monitor data-collection rule update -g "$RG" -n "$DCR" \
    --add streamDeclarations.Custom-ReARMNotifications_CL.columns name=$c type=string \
    --output none
done
```

(Non-string columns take the same form, e.g. `... name=AddedCount type=int`.)

Re-run the pre-flight diff to confirm both sides match, then **wait ~15 minutes**
for the propagation window before sending a test notification.

::: tip `-o table` hides a column named `type`
`--query "schema.columns[].{name:name,type:type}" -o table` prints only the
name column -- the table formatter swallows `type`. Use `-o tsv`, or alias it:
`{name:name,coltype:type}`.
:::

## Add the Sentinel channel in ReARM

1. Open **Organization Settings -> Integrations**, then the **Notifications ->
   Channels** tab.
2. Click the **Microsoft Sentinel** card in the catalog to open **Add Microsoft
   Sentinel channel**.
3. Fill in all six fields -- ReARM requires all of them together when you first
   create the channel:
   - **Name** -- a label for this channel, e.g. `SOC workspace`.
   - **Tenant ID** -- your Azure AD tenant ID.
   - **Client ID** -- the app registration's client ID.
   - **Client secret** -- the app registration's client secret.
   - **DCR endpoint** -- the DCE ingestion URL, e.g.
     `https://<dce-name>.<region>.ingest.monitor.azure.com`. Must be HTTPS at an
     `azure.com` host, or the save is rejected.
   - **DCR immutable ID** -- e.g. `dcr-...`.
   - **Stream name** -- e.g. `Custom-<TableName>_CL`.
4. Click **Save**.

::: tip Editing later
On edit, all six fields show blank with an `(unchanged)` placeholder --
credentials and DCR routing are stored and replaced together as one unit.
Leave all six blank to keep everything as-is, or fill in all six together to
replace the whole configuration (e.g. to point at a different DCR, you must
re-enter the tenant ID / client ID / client secret too, even if those
haven't changed).
:::

## Route events to the channel

Adding the channel doesn't send anything by itself -- you also need a
**subscription** with a route pointing at it. See
[Notifications](../configure/notifications) for event types, severity gates,
and how routes work.

To confirm the six values are right before wiring any of that up, use the
**Send test notification** (paper-plane) action on the channel instance in the
catalog. It posts straight to your DCE, so a `SENT` delivery proves the whole
chain -- service principal, DCE, DCR immutable ID and stream name. Worth doing
first: a Sentinel channel saves successfully even when every credential is
wrong, because ReARM cannot verify them against Azure at save time.

`SENT` means Azure accepted the batch, not that the row is queryable yet --
ingestion is asynchronous and the first write to a table can lag a few
minutes. Confirm with a query against your table:

```kusto
ReARMNotifications_CL
| where TimeGenerated > ago(30m)
| project TimeGenerated, EventType, Severity, Origin, PayloadJson
| order by TimeGenerated desc
```

## When the test notification fails

Delivery History records the reason. The most common one is Azure AD refusing
to issue a token, which means nothing was ever POSTed to your DCE:

```
Azure AD rejected the token request with 400 [invalid_client (AADSTS7000215)]
 - likely a bad, rotated, or expired client secret for channel <uuid>;
 re-enter the SP credentials
```

The bracketed `AADSTS` code names the fault:

| Code | Meaning |
| --- | --- |
| `AADSTS7000215` | Wrong client secret. Usually the secret's **ID** was copied instead of its **value** -- Azure only shows the value once, at creation. |
| `AADSTS7000222` | The client secret has expired. Create a new one on the app registration. |
| `AADSTS700016` | The application isn't in the named tenant -- check the client ID against the tenant ID. |
| `AADSTS900023` / `AADSTS90002` | The tenant ID is malformed or the directory doesn't exist. |

Credential faults are not retried -- the channel is broken until you re-enter
the values, so retrying would only delay the signal. Transient Azure AD
conditions (`temporarily_unavailable`, `server_error`) still retry with backoff.

A `403` after a *successful* token acquisition is a different problem: the
service principal authenticated but lacks the **Monitoring Metrics Publisher**
role on the DCR.

## What gets sent

Each delivery is an array of flattened log records, one per matched event,
posted to your DCE's Logs Ingestion API endpoint for the configured stream.
Authentication is a short-lived Azure AD OAuth token acquired with your
service-principal credentials (cached for the token's lifetime and refreshed
automatically) -- ReARM never stores a long-lived Sentinel-side secret beyond
the client secret you provided.

### Columns

Declare the columns you want to keep in your DCR's `streamDeclarations`. Azure
**silently drops any property the stream does not declare**, so a column
missing here costs you the field, not the record. The flip side is that ReARM
can add columns without breaking your DCR -- your stream keeps ingesting
exactly what it declares.

::: warning Columns are never created automatically
The Logs Ingestion API does **not** add columns when the incoming data gains a
property -- unlike the retired HTTP Data Collector API, which auto-created
`_s`/`_d`/`_t` columns. Microsoft's wording: *"The Data Collector API
automatically adjusts a destination legacy table's schema when the source data
object schema changes, but the Logs ingestion API doesn't."*

If you created the table with the portal's **New custom log (DCR-based)**
wizard, it inferred the columns from your sample record once, at creation --
that is why the table already has columns you never added by hand.

Two consequences:

1. **Adding a column is a two-step change.** Add it to the *table* (Log
   Analytics workspace -> Tables -> `...` -> **Edit schema**) **and** to the
   DCR's `streamDeclarations`. Azure Monitor does not update DCRs when you
   change a table schema, and a column present in only one of the two is
   dropped.
2. **When ReARM adds a field, you will not see it until you do that.** Existing
   deliveries keep working and nothing errors -- the new column is simply
   absent, and a KQL query naming it fails to resolve. Check the column table
   below after a ReARM upgrade.

`PayloadJson` softens this for anything carried in the event payload itself
(including per-release detail), since it ships the raw payload as one string.
It does not cover fields ReARM derives at format time, such as `ReARMUrl` --
those exist only as their own columns.
:::

Rows already ingested are immutable: adding a column later does **not**
backfill earlier rows, so send a fresh test notification after a schema change
to see the new field populated.

::: warning Wait ~15 minutes after a schema change before drawing conclusions
The ingestion endpoint caches the DCR's stream declaration. For roughly 10-15
minutes after you add a column, records are still processed under the **old**
declaration and the new fields are dropped -- while the delivery reports `SENT`,
the DCR's JSON view already lists the column, the table already has it, and the
immutable ID matches. Every check passes and the data still disappears, so the
symptom is indistinguishable from a misconfiguration.

If a test notification lands without the new columns, wait a quarter of an hour
and send another one before changing anything. Observed on a live workspace:
a notification sent minutes after the edit arrived with the new columns empty,
and one sent nine minutes later carried them in full.
:::

Every event carries these:

| Column | Type | Notes |
| --- | --- | --- |
| `TimeGenerated` | `datetime` | Required on every Log Analytics table. ISO 8601 UTC. |
| `EventType` | `string` | `NEW_VULN_AFFECTS_RELEASES`, `VULNERABILITY_RECORD_UPDATED`, `VEX_STATE_CHANGED`, `RELEASE_CREATED`, `RELEASE_LIFECYCLE_CHANGED`, `RELEASE_BOM_DIFF`, `APPROVAL_REQUESTED`, `APPROVAL_RESOLVED`. |
| `Severity` | `string` | `CRITICAL`/`HIGH`/`MEDIUM`/`LOW`/`INFO`/`NONE`, or `UNKNOWN` where the event has no severity (VEX, release, approval events). |
| `Origin` | `string` | `REAL` or `SYNTHETIC` -- filter out test notifications with `where Origin == "REAL"`. |
| `DedupKey` | `string` | Stable across notifications about the same finding; join on it to collapse repeats. |
| `EventUuid` | `string` | Unique per event. |
| `OrgUuid` | `string` | The ReARM organization. Separate tenants when one workspace ingests several orgs. |
| `ReARMUrl` | `string` | Deep link back into ReARM. On vulnerability events: the specific release when the event names exactly one, otherwise the org's vulnerability-analysis page. On release and approval events: that release, and omitted rather than falling back to the vulnerability page. Built from the install's configured base URI (`PROJECT_PROTOCOL`/`PROJECT_HOST`), so an install left on the defaults emits `http://localhost:3000/...` -- set those before pointing a SOC at this column. |
| `PayloadJson` | `string` | The full raw event payload. Fallback for anything not promoted to a column; `parse_json()` it in KQL. |
| `PayloadJsonTruncated` | `boolean` | Present and `true` only when `PayloadJson` was cut at 60 KB. |

`NEW_VULN_AFFECTS_RELEASES` and `VULNERABILITY_RECORD_UPDATED` add:

| Column | Type | Notes |
| --- | --- | --- |
| `VulnPrimaryId` | `string` | e.g. `CVE-2021-44228`. |
| `EpssScore` | `real` | |
| `KevListed` | `boolean` | On the CISA Known Exploited Vulnerabilities catalog. |
| `AffectedReleaseDetails` | `dynamic` | **The one to declare.** Array of objects, one per affected release: `ReleaseUuid`, `Component`, `ComponentUuid`, `Version`, `Branch`, `Lifecycle`, `DeployedEnvs`. `mv-expand` it to filter or join on release and component identity. Index-aligned with `AffectedReleases`. `DeployedEnvs` is present in the shape but not yet populated by any event producer -- treat it as reserved for now. |
| `AffectedComponentUuids` / `AffectedComponentNames` | `dynamic` | Distinct component sets. Answers "does this touch a component I own?" with no expansion. Span **all** affected releases, never capped -- a component first appearing past the display cap is still listed. |
| `AffectedReleases` | `dynamic` | Older compact display form, `component:version (LIFECYCLE)` strings. Kept for existing queries; `AffectedReleaseDetails` supersedes it. |
| `AffectedReleaseCount` | `int` | The **true** total, even when the two per-release columns are capped at 50 entries. |
| `AffectedReleasesTruncated` | `boolean` | Present and `true` only when that cap bit, so a capped record never reads as a complete one. |
| `Perspectives` | `dynamic` | Perspective UUIDs across **all** affected releases, uncapped. |
| `CvssScore` | `real` | `NEW_VULN_AFFECTS_RELEASES` only. |
| `FixVersion` | `string` | `NEW_VULN_AFFECTS_RELEASES` only. |
| `Component` / `ComponentPurl` | `string` | `NEW_VULN_AFFECTS_RELEASES` only -- the vulnerable component itself (the dependency), display form and purl. |
| `OldSeverity` / `ChangeType` | `string` | `VULNERABILITY_RECORD_UPDATED` only. `ChangeType` is `SEVERITY_BUMPED`, `KEV_ADDED` or `EPSS_SPIKED`. |
| `OldEpssScore` | `real` | `VULNERABILITY_RECORD_UPDATED` only. |

`VEX_STATE_CHANGED` is deliberately narrow -- it carries no severity, scores or
affected-release set, only:

| Column | Type | Notes |
| --- | --- | --- |
| `VulnPrimaryId` | `string` | |
| `VexOldState` / `VexNewState` | `string` | |
| `ReleaseUuid` | `string` | The release whose VEX state changed. |
| `ComponentPurl` | `string` | |

Release and approval events add the release-identity block: `ReleaseUuid`,
`ReleaseVersion`, `ComponentUuid`, `ComponentName`, `ComponentType`,
`BranchUuid`, `BranchName`, `Lifecycle`, `CommitHash`, `CommitUri`,
`CommitMessage`, `UpdatedBy` (all `string`), plus per-event fields -- `Scheduled` (`boolean`),
`OldLifecycle`/`NewLifecycle`, `AddedComponents`/`RemovedComponents`
(`dynamic`) with `AddedCount`/`RemovedCount` (`int`), and the approval fields
`ApprovalRequestUuid`, `RequestedBy`, `RequestedByName`, `ApprovalEntries`
(`dynamic`), `ApprovalEntryCount`, `TargetUserCount`, `ApprovalEntryUuid`,
`ApprovalEntryName`, `Resolution`, `ResolvedBy`, `ResolvedByName`,
`ResolvedRequestUuids` (`dynamic`).

::: tip Commit provenance
`CommitHash` is the 7-character short form ReARM renders for display; the
**full** SHA is embedded in the `CommitUri` link, so correlating a notification
against your git host doesn't need the short hash to be unique. Both are
present only when the release has a source-code entry and its component has a
VCS repository configured. Vulnerability events carry no commit fields -- their
affected-release set is resolved from artifact metrics, which don't reference a
source-code entry.
:::

::: tip Declaring the types
`guid` is not a valid `streamDeclarations` type -- declare UUID columns as
`string`, which is the form ReARM sends. Column names must start with a letter,
be at most 45 characters, and contain only alphanumerics and underscores;
`_ResourceId`, `id`, `_SubscriptionId`, `TenantId`, `Type`, `UniqueId` and
`Title` are reserved by Azure. ReARM's column names already satisfy all of
this.
:::

A minimal `streamDeclarations` entry covering the vulnerability path:

```json
"streamDeclarations": {
    "Custom-ReARMNotifications_CL": {
        "columns": [
            { "name": "TimeGenerated", "type": "datetime" },
            { "name": "EventType", "type": "string" },
            { "name": "Severity", "type": "string" },
            { "name": "Origin", "type": "string" },
            { "name": "OrgUuid", "type": "string" },
            { "name": "ReARMUrl", "type": "string" },
            { "name": "VulnPrimaryId", "type": "string" },
            { "name": "KevListed", "type": "boolean" },
            { "name": "CvssScore", "type": "real" },
            { "name": "AffectedReleaseCount", "type": "int" },
            { "name": "AffectedReleaseDetails", "type": "dynamic" },
            { "name": "AffectedComponentUuids", "type": "dynamic" },
            { "name": "PayloadJson", "type": "string" }
        ]
    }
}
```

### Querying affected releases

`AffectedReleaseDetails` is what makes the feed joinable. Every KEV-listed
vulnerability currently reaching a production deployment, one row per release:

```kusto
ReARMNotifications_CL
| where TimeGenerated > ago(7d) and Origin == "REAL" and KevListed == true
| mv-expand release = AffectedReleaseDetails
| extend ReleaseComponent = tostring(release.Component),
         ReleaseVersion   = tostring(release.Version),
         Envs             = release.DeployedEnvs
| where Envs has "prod"
| project TimeGenerated, VulnPrimaryId, ReleaseComponent, ReleaseVersion, Envs, ReARMUrl
| order by TimeGenerated desc
```

Run against a live workspace, that returns one row per affected release:

| TimeGenerated | VulnPrimaryId | Component | Ver | Branch | ReleaseUuid | ReARMUrl |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-08-19 12:04:56 | CVE-2025-12345 | myapp | v2.0 | main | 6869fa46-e4a4-... | https://rearm.example.com/release/show/6869fa46-e4a4-... |
| 2026-08-19 11:58:34 | CVE-2025-12345 | myapp | v2.0 | main | a6831fb1-71a4-... | https://rearm.example.com/release/show/a6831fb1-71a4-... |

Component, version and branch are their own filterable fields and the release
UUID joins to anything else you hold, rather than the single
`"myapp:v2.0 (GENERAL_AVAILABILITY)"` display string that `AffectedReleases`
alone would give you. `ReARMUrl` takes an analyst straight to the release.

### Release and approval events

These carry the release-identity block plus commit provenance. A real
`RELEASE_CREATED` record, as ingested:

```json
{
  "TimeGenerated": "2026-08-19T13:05:45Z",
  "EventType": "RELEASE_CREATED",
  "Severity": "UNKNOWN",
  "Origin": "REAL",
  "OrgUuid": "c839fa68-1b2c-4ba0-9292-f660e8f47184",
  "DedupKey": "release:created:f38f1ec1-b79d-4244-829b-1ae38b805f25",
  "ReleaseUuid": "f38f1ec1-b79d-4244-829b-1ae38b805f25",
  "ReleaseVersion": "1.4.2",
  "Lifecycle": "ASSEMBLED",
  "Scheduled": false,
  "ComponentName": "walkthrough-demo-service",
  "ComponentUuid": "5da39e5a-7c9e-4fc3-98f1-edf362e315bf",
  "ComponentType": "COMPONENT",
  "BranchName": "main",
  "BranchUuid": "971204c0-281e-4ca4-81a7-42757d02c806",
  "CommitHash": "9f3c1ab",
  "CommitUri": "https://github.com/acme/walkthrough-demo-service/commit/9f3c1ab77d2e4b6c8a05e1f4d3b2c9a71e0d5f68",
  "CommitMessage": "bump log4j-core to 2.17.1 and pin transitive deps",
  "UpdatedBy": "Kev Probe",
  "ReARMUrl": "https://rearm.example.com/release/show/f38f1ec1-b79d-4244-829b-1ae38b805f25"
}
```

Note `CommitHash` is the 7-character short form while `CommitUri` embeds the
full SHA -- so join on the URI, display the hash.

Every release cut in the last week, with the commit that produced it:

```kusto
ReARMNotifications_CL
| where EventType == "RELEASE_CREATED" and TimeGenerated > ago(7d)
| project TimeGenerated, ComponentName, ReleaseVersion, BranchName,
          Lifecycle, CommitHash, CommitUri, UpdatedBy, ReARMUrl
| order by TimeGenerated desc
```

Or correlate a vulnerability with the commit that introduced or fixed the
component, by joining on the release UUID:

```kusto
let releases =
    ReARMNotifications_CL
    | where EventType == "RELEASE_CREATED"
    | project ReleaseUuid,
              RelCommitHash = CommitHash,
              RelCommitUri = CommitUri,
              RelCommitMessage = CommitMessage;
ReARMNotifications_CL
| where EventType == "NEW_VULN_AFFECTS_RELEASES"
| mv-expand release = AffectedReleaseDetails
| extend ReleaseUuid = tostring(release.ReleaseUuid)
| join kind=leftouter releases on ReleaseUuid
| project TimeGenerated, VulnPrimaryId,
          Component = tostring(release.Component),
          Version = tostring(release.Version),
          RelCommitHash, RelCommitUri, RelCommitMessage
| order by TimeGenerated desc
```

That join is the practical reason `ReleaseUuid` is promoted out of the display
string: it is the key that ties a vulnerability to the change that shipped it.

::: warning Alias the right-hand side of a join
`CommitHash`, `CommitUri` and `CommitMessage` exist on both event families, so
they are columns on *both* sides of that join. Kusto keeps the left side's
values under the original names and suffixes the right side to `CommitHash1`,
`CommitUri1` and so on -- meaning an unaliased `project CommitHash` silently
returns the vulnerability row's (always empty) column instead of the release's.
Rename them in the `let` block, as above.

Likewise, a query naming a column your table does not declare fails outright
with `Failed to resolve scalar expression named '<column>'`. That is the
symptom of a column missing from the table schema, not of missing data.
:::

Or scope an alert to the components one team owns, without expanding at all:

```kusto
let ownedComponents = dynamic(["<component-uuid-1>", "<component-uuid-2>"]);
ReARMNotifications_CL
| where Origin == "REAL" and Severity in ("CRITICAL", "HIGH")
| where AffectedComponentUuids has_any (ownedComponents)
```

::: warning Size limits
The Logs Ingestion API accepts up to **1 MB per call** and truncates any single
field value past **64 KB**. ReARM caps `PayloadJson` at 60 KB (flagged by
`PayloadJsonTruncated`) and the affected-release lists at 50 entries (flagged by
`AffectedReleasesTruncated`, with `AffectedReleaseCount` always carrying the
true total), so a wide-blast-radius CVE degrades visibly instead of being
silently chopped by Azure.
:::
