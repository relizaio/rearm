
# Reliza BEAR

ReARM supports integration with Reliza's [BEAR](https://github.com/relizaio/bear) project for BOM enrichment.

BEAR is offered as a SaaS service by Reliza for ReARM Pro users, included in the subscription, but can also be self-hosted for ReARM CE users.

## Pre-requisites
You need to have a running instance of BEAR.

## BEAR Part
Configure API token as described in the [BEAR documentation](https://github.com/relizaio/bear/tree/main/backend#3-generate-api-key-hash).

## ReARM Part
1. In ReARM, open Organization Settings from the menu on the left.
2. In the Integrations section, click on "Add BEAR Integration" button.
3. Enter your BEAR URI (if you are a ReARM Pro user, this will be provided to you by Reliza).
4. Enter your BEAR API Key (if you are a ReARM Pro user, this will be provided to you by Reliza).
5. Optionally, configure skip patterns - these are patterns to match on your organization internal components for which you would like to aovid BEAR enrichment (such components will not be sent to BEAR service for enrichment).
6. Click `Submit` - your BEAR integration is now configured.
