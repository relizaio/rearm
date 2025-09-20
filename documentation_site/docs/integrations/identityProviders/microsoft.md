# Configure Microsoft Azure as Identity Provider

This will allow you to use Microsoft Entra ID to log in to your ReARM instance.

## Notes
1. It is highly recommended to have your Entra ID users' emails set in Entra ID and to trust those emails to be correct. If you do not have this information set up or do not trust Entra emails, you may set up email verification in ReARM as described below.
2. Choose your grouping strategy. You may group users based on your organization structure, i.e. ADMINISTRATORS, DEVELOPERS, QA, LEGAL or based on permissions, i.e. `READ_ONLY`, `READ_WRITE`, `ADMINISTRATOR`. If you are planning to use ReARM approval workflows, it is recommended to group users based on your organization structure so you can easily map them to approval permissions.


## Azure Part
1. You would need to create Azure Service Principal to register Microsoft as an identity provider. For this, in Azure Portal search, search for `App registrations`, click on it and once on the `App registrations` page, click on the `New registration` button.

2. Choose a name for your Service Principal, opt for Single Tenancy - "Accounts in this organizational directory only" (default value).

3. In the 'Redirect URI' section select Web, and paste `https://<your-rearm-url>/kauth/realms/Reliza/broker/entra/endpoint` as the redirect URI.

4. Click `Register`.

5. On the next page - which is the registered Service Principal page, note `Application (client) ID` and `Directory (tenant) ID` - you will need these values later.

6. Click on the `Authentication` in the menu on the left, set `Front-channel logout URL` to `https://<your-rearm-url>/kauth/realms/Reliza/broker/entra/endpoint/logout_response`, then check ID tokens checkbox and save.

7. Click on the `Certificates & secrets` in the menu on the left, under `Client secrets` tab click on the `New client secret`. Enter desired secret description, i.e. `ReARM Identity Provider Credential`, choose desired expiration timeframe and click `Add`. On the next page, note created secret value - you will need it later.

8. Click on the `Manifest` in the menu on the left, and define desired roles inside the `"appRoles":[]` section via manifest like this (amend roles as desired based on your grouping strategy and generate new uuids for roles instead of samples provided below):

```json
"appRoles": [
  {
    "allowedMemberTypes": [ "User" ],
    "displayName": "ReARM Administrators",
    "description": "ReARM and Dependency-Track Administrators",
    "id": "3aef8d86-cf12-4f3b-9a72-dbcbe0596854",
    "isEnabled": true,
    "value": "REARM_ADMINISTRATORS",
    "origin": "Application"
  },
  {
    "allowedMemberTypes": [ "User" ],
    "displayName": "ReARM Developers",
    "description": "Gives organization Read-Write access to ReARM with DEV Approval permissions and Read access to Dependency-Track with permissions to participate in vulnerability and violation triage and audit",
    "id": "9da53c67-d18a-48a9-9ae3-6df633e96f43",
    "isEnabled": true,
    "value": "REARM_DEVELOPERS",
    "origin": "Application"
  },
  {
    "allowedMemberTypes": [ "User" ],
    "displayName": "ReARM and Dependency-Track QA",
    "description": "Gives organization Read-Only access to ReARM with QA Approval permissions and Read-Only access to Dependency-Track",
    "id": "aa2c66d0-1fd4-4532-95ba-3695d7c5183d",
    "isEnabled": true,
    "value": "REARM_QA",
    "origin": "Application"
  }
]
```

and save the manifest.

9. In the Entra admin center or in the Azure portal, navigate to the `Enterprise applications` section, and find the application you just created. Click on it. Open `Users and groups` from the menu on the left and assign users to roles as desired via clicking `Add user/group`.

## ReARM Part - Configure Keycloak Groups
1. Login to Keycloak with your administrative account by adding /kauth path to your ReARM URI.
2. In Keycloak, select Reliza realm.
3. Open `Groups` section and create desired groups for your users based on your grouping strategy.

## ReARM Part - Registering Microsoft as an Identity Provider
1. Continue in Keycloak Reliza realm.
2. Open Identity providers section, and add OpenID Connect v1.0.
3. Change Alias to `entra` and Display Name to `Entra ID`.
4. Use `https://login.microsoftonline.com/<tenant-id>/v2.0/.well-known/openid-configuration` for `Discovery endpoint` (where tenant id is your Azure application tenant id).
5. Enter your Client ID as noted above in the `Client ID` field.
6. Enter your Client Secret as noted above in the `Client Secret` field.
7. Click "Save"

## ReARM Part - Configuring Microsoft as an Identity Provider
1. Continue in Keycloak Reliza realm.
2. Once identity provider is configured, set `Sync mode` to `Force` and set `Trust Email` to `On` if your users' emails are set in Entra ID and you trust those emails to be correct. Otherwise, set `Trust Email` to `Off`.
3. Make sure your Realm Settings -> Login has `Verify email` enabled and your SMTP email settings are configured. The later setting will ensure that Keycloak verifies the email addresses of your users.
4. Configure Mappers - for each of your roles, on the `Mappers` tab click on `Add mapper`, then: 
   1. Enter Name based on your role name, in the `Mapper type` select `Advanced Claim to Group`. 
   2. Click `Add Claims`
   3. In the claim's `Key` enter `roles` and in the `Value` enter your role name based on the manifest you set up.
   4. In the `Group` field enter desired `Keycloak` groups withch should be mapped to this role.
   5. Click `Save`.

## ReARM Part - Configure Client Group Mapper
1. Continue in Keycloak Reliza realm.
2. Open `Clients` section, and select `login-app` client.
3. Click `Client scopes` tab.
4. Click on `login-app-dedicated`.
5. In the `Mappers` tab, click `Configure a new mapper`.
6. Choose `Group Membership`.
7. Use `groups` as Name and as `Token claim name`.
8. Toggle all selectors to On except `Full group path`.
9. Click `Save`.

## ReARM Part - Configure Keycloak Group Mapping to ReARM application
1. Log in to ReARM as administrative user
2. Open the `Organization Settings` view from the left menu.
3. Open the `User Groups` tab.
4. Create desired User Groups, then set permissions for each group as desired.
5. For each groups in SSO groups list, add desired mapped Keycloak groups.


## Dependency-Track Part (if using Keycloak for Dependency-Track auth)
1. Log in to Dependency-Track as administrative user.
2. Open the `Administration` view from the menu on the left.
3. Click `Access Management` and select `OpenID Connect Groups`.
4. Create groups based on desired Keycloak groups and map them to corresponding Dependency-Track teams.

You should now be able to login to ReARM using your Microsoft identities.