
# Microsoft Teams

This tutorial requires business version of Microsoft Teams (or `work or school` version).

ReARM delivers Teams notifications through a **Power Automate Workflows** webhook (the steps below). This is Microsoft's supported replacement for the now‑retired Office 365 Connectors.

::: warning Migrating from the legacy "Incoming Webhook" connector?
Microsoft is **retiring Office 365 Connectors** in Teams — including the old **Incoming Webhook** connector. Those legacy webhook URLs will stop delivering once Microsoft completes the retirement, so any ReARM MS Teams integration still pointed at one will silently stop posting.

**How to tell which one you have** — look at the webhook URL stored in your ReARM MS Teams integration:

- **Legacy connector (action needed):** the URL contains `webhook.office.com` (e.g. `https://<tenant>.webhook.office.com/webhookb2/...`).
- **Modern Workflows (you're fine):** the URL contains `logic.azure.com/workflows/...` or a Power Platform host (`*.powerplatform.com/...`). ReARM only accepts these.

**If you're on the legacy connector, migrate:**

1. Create a new Workflows webhook by following the **Microsoft Teams Part** steps below.
2. In ReARM, open `Organization Settings` → `Integrations`, edit your existing **MS Teams** integration, and paste the new Workflows URL into the webhook secret field (or remove the old integration and add a new one with the new URL).
3. Send a test notification to confirm delivery, then remove the old **Incoming Webhook** connector from your Teams channel.

New setups need no migration — just follow the steps below.
:::

## Microsoft Teams Part
1. Create or choose an existing Channel in MS Teams where you would get notifications from ReARM.
2. Click on ... on the right of the channel and select `Workflows`.
3. Choose `Post to a channel when a webhook request is received` option.
4. Rename you workflow to something meaningful, i.e. `ReARM Webhook Connector`, and click `Next`.
5. Click `Add workflow`.
6. You will get the URI to post Webhooks on the next screen, copy it and note for the ReARM steps below.

## ReARM Part
1. Open `Organization Settings` from the menu on the left.
2. In the `Integrations` tab, click on the `Add MS Teams Integration` button.
3. In the secret field paste the webhook URI you obtained above.
4. Click `Submit`.

Congratulations! Your Microsoft Teams Integration is now set up and you will be receiving ReARM notifications in your chosen channel!