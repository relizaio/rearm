
# Microsoft Sentinel

::: info ReARM Pro only
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
On edit, the tenant ID / client ID / client secret fields show as
`(unchanged)` -- leave them blank to keep the existing credentials, or fill in
all three together to rotate them. The DCR endpoint / immutable ID / stream
name always show their current values and can be changed independently.
:::

## Route events to the channel

Adding the channel doesn't send anything by itself -- you also need a
**subscription** with a route pointing at it. See
[Notifications](../configure/notifications) for event types, severity gates,
and how routes work. ReARM doesn't currently offer a UI button to send a test
event to a Sentinel channel; the only way to confirm delivery today is to
route a real (or synthetic, via the API) event to it and check your Log
Analytics table for the resulting rows.

## What gets sent

Each delivery is an array of flattened log records, one per matched event,
posted to your DCE's Logs Ingestion API endpoint for the configured stream.
Authentication is a short-lived Azure AD OAuth token acquired with your
service-principal credentials (cached for the token's lifetime and refreshed
automatically) -- ReARM never stores a long-lived Sentinel-side secret beyond
the client secret you provided.
