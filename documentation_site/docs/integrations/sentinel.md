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

## What gets sent

Each delivery is an array of flattened log records, one per matched event,
posted to your DCE's Logs Ingestion API endpoint for the configured stream.
Authentication is a short-lived Azure AD OAuth token acquired with your
service-principal credentials (cached for the token's lifetime and refreshed
automatically) -- ReARM never stores a long-lived Sentinel-side secret beyond
the client secret you provided.
