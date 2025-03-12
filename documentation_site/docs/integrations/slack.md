
## Slack

### 1. Add App To Slack

1. Go to Slack api page at https://api.slack.com/apps and click on "Create New App"
2. Name your app "Reliza Hub" and select your Slack workspace in the "Development Slack Workspace"
3. Click on "Create App"
4. Click on "Incoming Webhooks"
5. Click on the toggle "Activate Incoming Webhooks" to change it to "On"
6. Scroll down to the bottom of the page
7. Click on "Add New Webhook to Workspace"
8. On the next screen, select desired Slack channel for integration
9. Scroll down to the bottom of the page
10. Click "Copy" where it says "Webhook URL"
11. You would get a URL like https://hooks.slack.com/services/T0XXXXXXXX/XXXXXXXXX/XXXXXXXXXXXX
12. Copy everything after https://hooks.slack.com/services/, in the case above this would be T0XXXXXXXX/XXXXXXXXX/XXXXXXXXXXXX (note that this is your Slack webhook secret so treat it accordingly)

### 2. Register Slack App With ReARM

1. In ReARM, open Organization Settings from the menu on the left
2. In the Integrations section, click on "Add Slack Integration" button
3. In the secret field paste the webhook secret you obtained when adding App to slack above - in this tutorial T0XXXXXXXX/XXXXXXXXX/XXXXXXXXXXXX
4. Click "Submit"
5. Your Slack Integration is now set up!