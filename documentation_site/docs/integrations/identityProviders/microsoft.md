# Configure Microsoft as an identity provider for ReARM

## Azure Part
1. You would need to create Azure Service Principal to register Microsoft as an identity provider. For this, in Azure Portal search, search for `App registrations`, click on it and once on the `App registrations` page, click on the `New registration` button.

2. Choose a name for your Service Principal, opt for Single Tenancy - "Accounts in this organizational directory only" (default value) and click `Register` on the bottom.

3. On the registered Service Principal page, note `Application (client) ID` and `Directory (tenant) ID` - you will need these values later.

4. On the same page, under *Client credentials* click on the `Add a certificate or secret`. Then click on the `New client secret`. Enter desired secret description, i.e. `ReARM Identity Provider Credential`, choose desired expiration timeframe and click `Add`. On the next page, note created secret value - you will need it later.

## ReARM Part

1. Login to Keycloak with your administrative account by adding /kauth path to your ReARM URI.

2. In Keycloak, select Reliza realm.

3. Open Identity providers section, and click (if exists) or add Microsoft.

4. Enter your Service Principal's Client ID as noted above.

5. Enter your Service Principal's Client Secret as noted above.

6. Enter your Service Principal's Tenant ID as noted above.

7. Click "Save"

You should now be able to login to ReARM using your Microsoft identities.