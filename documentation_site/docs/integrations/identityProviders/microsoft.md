# Configure Microsoft Azure as Identity Provider

This will allow you to use Microsoft Entra ID to log in to your ReARM instance.

## Azure Part
1. You would need to create Azure Service Principal to register Microsoft as an identity provider. For this, in Azure Portal search, search for `App registrations`, click on it and once on the `App registrations` page, click on the `New registration` button.

2. Choose a name for your Service Principal, opt for Single Tenancy - "Accounts in this organizational directory only" (default value).

3. In the 'Redirect URI' section select Web, and paste `https://<your-rearm-url>/kauth/realms/Reliza/broker/entra/endpoint` as the redirect URI.

4. Click `Register`.

5. On the next page - which is the registered Service Principal page, note `Application (client) ID` and `Directory (tenant) ID` - you will need these values later.

6. Click on the `Authentication` in the menu on the left, set `Front-channel logout URL` to `https://<your-rearm-url>/kauth/realms/Reliza/broker/entra/endpoint/logout_response`, then check ID tokens checkbox and save.

7. Click on the `Certificates & secrets` in the menu on the left, under `Client secrets` tab click on the `New client secret`. Enter desired secret description, i.e. `ReARM Identity Provider Credential`, choose desired expiration timeframe and click `Add`. On the next page, note created secret value - you will need it later.

8. Click on the `Token configuration` in the menu on the left. Click `Add optional claim`. Select `ID` token type and check `email` and click 'Add'.

8. Click on the `Manifest` in the menu on the left, and define desired roles inside the `"appRoles":[]` section via manifest like this (generate new uuids for roles instead of samples, also roles may be amended as desired):

```json
"appRoles": [
  {
    "allowedMemberTypes": [ "User" ],
    "displayName": "ReARM Administrator",
    "description": "ReARM and Dependency-Track Administrator",
    "id": "3aef8d86-cf12-4f3b-9a72-dbcbe0596854",
    "isEnabled": true,
    "value": "rearm_admin",
    "origin": "Application"
  },
  {
    "allowedMemberTypes": [ "User" ],
    "displayName": "ReARM Write User and Dependency-Track Collaborator",
    "description": "Gives organization Read-Write access to ReARM and Read access to Dependency-Track with permissions to participate in vulnerability and violation triage and audit",
    "id": "9da53c67-d18a-48a9-9ae3-6df633e96f43",
    "isEnabled": true,
    "value": "rearm_write_user",
    "origin": "Application"
  },
  {
    "allowedMemberTypes": [ "User" ],
    "displayName": "ReARM and Dependency-Track Read User",
    "description": "Gives organization Read-Only access to ReARM and Read-Only access to Dependency-Track",
    "id": "aa2c66d0-1fd4-4532-95ba-3695d7c5183d",
    "isEnabled": true,
    "value": "rearm_read_user",
    "origin": "Application"
  }
]

and save the manifest.

9. In the Entra admin center or in the Azure portal, navigate to the `Enterprise applications` section, and find the application you just created. Click on it. Open `Users and groups` from the menu on the left and assign users to roles as desired via clicking `Add user/group`.
```
## ReARM Part - Registering Microsoft as an Identity Provider

1. Login to Keycloak with your administrative account by adding /kauth path to your ReARM URI.

2. In Keycloak, select Reliza realm.

3. Open Identity providers section, and add OpenID Connect v1.0.

4. Change Alias to `entra` and Display Name to `Entra ID`.

5. Use `https://login.microsoftonline.com/<tenant-id>/v2.0/.well-known/openid-configuration` for `Discovery endpoint` (where tenant id is your Azure application tenant id).

4. Enter your Client ID as noted above in the `Client ID` field.

5. Enter your Client Secret as noted above in the `Client Secret` field.

6. Click "Save"

## ReARM Part - Configuring Microsoft as an Identity Provider

1. Once identity provider is configured, set `Sync mode` to `Force` and set `Trust Email` to `On`.

2. Set  "Scopes" to "openid email"
2. Configure Mappers - for each of your roles, on the `Mappers` tab click on `Add mapper`, then: 
2.1. Enter Name based on your role name, in the `Mapper type` select `Advanced Claim to Group`. 
2.2. Click `Add Claims`
2.3. In the claim's `Key` enter `roles` and in the `Value` enter your role name based on the manifest you set up.
2.4. In the `Group` field enter desired `Keycloak` groups.
2.5. Click `Save`.

## ReARM Part - Configure Client Mapper
1. Open `Clients` section, and select `login-app` client`.
2. Click `Client scopes` tab.
3. Click on `login-app-dedicated`.
4. In the `Mappers` tab, click `Configure a new mapper`.
5. Choose `Group Membership`.
6. Use `groups` as Name and as `Token claim name`.
7. Toggle all selectors to On except `Full group path`.
8. Click `Save`.

You should now be able to login to ReARM using your Microsoft identities.