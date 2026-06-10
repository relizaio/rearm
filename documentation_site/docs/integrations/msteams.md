
# Microsoft Teams

This tutorial requires business version of Microsoft Teams (or `work or school` version).

::: info Which Teams destination do you want?
ReARM can post to Microsoft Teams from **two separate features** — configure the right one:

- **Release notifications** — set up on **this page**, under `Organization Settings → Integrations` (the "Add MS Teams integration" steps below).
- **Security & vulnerability alerts** (CVE / VEX / EPSS events, with filtering and routing) — set up under **Notifications → Channels** by adding an **MS Teams** channel and a subscription. *Not covered on this page.*

Both use the **same kind** of **Power Automate Workflows** webhook URL — Microsoft's supported replacement for the now-retired Office 365 Connectors. Produce one URL per target channel using the *Microsoft Teams Part* steps below (if you want release notifications and security alerts to land in different channels, repeat the steps to create a Workflows webhook per channel).
:::

::: warning Migrating from the legacy "Incoming Webhook" connector?
Microsoft is **retiring Office 365 Connectors** in Teams — including the old **Incoming Webhook** connector. Those legacy webhook URLs will stop delivering once Microsoft completes the retirement, so any ReARM Teams destination still pointed at one will silently stop posting.

**How to tell which one you have** — look at the stored webhook URL:

- **Legacy connector (action needed):** the URL contains `webhook.office.com` (e.g. `https://<tenant>.webhook.office.com/webhookb2/...`).
- **Modern Workflows (you're fine):** the URL contains `logic.azure.com/workflows/...` or a Power Platform host (`*.powerplatform.com/...`).

In practice only the **Integrations → MS Teams** path (release notifications) can still hold a legacy URL — the newer **Notifications → Channels** MS Teams channels reject anything that isn't a Workflows URL, so there's nothing to migrate there.

**If your MS Teams *integration* is on a legacy connector, migrate:**

1. Create a new Workflows webhook by following the **Microsoft Teams Part** steps below.
2. In ReARM, open `Organization Settings → Integrations`, edit your existing **MS Teams** integration, and paste the new Workflows URL into the secret field (or remove the old integration and add a new one with the new URL).
3. Send a test, confirm delivery, then remove the old **Incoming Webhook** connector from your Teams channel.

New setups need no migration — just follow the steps below.
:::

## Microsoft Teams Part
1. Create or choose an existing Channel in MS Teams where you would get notifications from ReARM.
2. Click on ... on the right of the channel and select `Workflows`.
3. In the Workflows search box, type `webhook`, then choose **`Send webhook alerts to a channel`** — the plain "to a channel" template. Do **not** pick the "from specific people to a channel" or "from people in an org to a channel" variants: those add sender allowlists and will silently reject ReARM's POSTs.

   ::: tip Microsoft renames these templates periodically
   If the exact name above isn't visible in your tenant's Workflows gallery, search for `webhook` and pick the closest **unfiltered** "to a channel" template (i.e. one without a "from …" qualifier). The previous name for this same template was `Post to a channel when a webhook request is received`.
   :::
4. Rename your workflow to something meaningful, i.e. `ReARM Webhook Connector`, and click `Next`.
5. Click `Add workflow`.
6. You will get the URI to post Webhooks on the next screen, copy it and note for the ReARM steps below.

## ReARM Part
1. Open `Organization Settings` from the menu on the left.
2. In the `Integrations` tab, click on the `Add MS Teams Integration` button.
3. In the secret field paste the webhook URI you obtained above.
4. Click `Submit`.

Congratulations! Your Microsoft Teams Integration is now set up and you will be receiving ReARM notifications in your chosen channel!