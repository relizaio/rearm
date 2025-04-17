
# Microsoft Teams

This tutorial requires business version of Microsoft Teams (or `work or school` version).

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