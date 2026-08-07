
## Slack

Slack is a **notification channel**: you create one destination and then route
events to it from a subscription. See
[Notifications](../configure/notifications) for what you can route and how.

::: warning Paste the WHOLE URL
Earlier versions of ReARM stored only the path fragment
(`T0XXXXXXXX/XXXXXXXXX/XXXXXXXXXXXX`) and prepended the rest. That is no
longer the case -- the channel form validates what you paste and rejects
anything that is not an `https://` URL on `hooks.slack.com`, so a bare
fragment fails to save with:

```
Slack webhook URL must be an https:// URL on host hooks.slack.com
```

Copy the full URL Slack gives you, starting with `https://`.
:::

### 1. Create the incoming webhook in Slack

1. Go to the Slack API page at https://api.slack.com/apps and click "Create New App"
2. Choose "From scratch", name your app "ReARM Integration" and select your Slack workspace
3. Click "Create App"
4. Click "Incoming Webhooks"
5. Click the toggle "Activate Incoming Webhooks" to change it to "On"
6. Scroll to the bottom of the page
7. Click "Add New Webhook to Workspace"
8. On the next screen, select the Slack channel that should receive notifications
9. Scroll to the bottom of the page
10. Click "Copy" where it says "Webhook URL"
11. You now have a URL like `https://hooks.slack.com/services/T0XXXXXXXX/XXXXXXXXX/XXXXXXXXXXXX`

Treat that URL as a secret: anyone holding it can post to your channel.

### 2. Add the channel in ReARM

1. In ReARM, open **Organization Settings** from the menu on the left
2. Go to the **Integrations** tab, then the **Catalog** sub-tab
3. Find the **Slack** card and click **Add**
4. **Name** it for the destination it posts to, e.g. `#security-alerts` -- this
   is the label you will pick from when building a subscription route
5. Paste the full URL from step 1 into **Incoming-webhook URL**
6. Save

The channel now appears on the Slack card. Use the paper-plane **Send test**
action on it to confirm delivery: a channel test posts directly and does not
need a subscription to exist yet.

### 3. Route something to it

A channel on its own delivers nothing. Add a subscription under
**Integrations -> Subscriptions** with the event types you care about and a
route pointing at this channel. See
[Notifications](../configure/notifications) for event types, filters, and the
other route targets (channel groups, teams, and the affected component's
owner).

::: tip Editing later
Leave **Incoming-webhook URL** blank when editing an existing channel to keep
the stored URL; fill it in only to replace it.
:::
