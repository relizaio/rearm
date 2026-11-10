# Add a User to a Reliza-managed ReARM Pro instance

This section will describe how to manually add a user to a Reliza-managed ReARM Pro instance. Note that this approach is not needed if you are using SSO integration, such as with [Microsoft Entra ID](/integrations/identityProviders/microsoft.md).

As your instance is provisioned, you will have one or more administrative account. With this account, log in to your ReARM Pro managed instance adding `/kauth/admin/Reliza/console/` to the end of your ReARM Pro URL.

Click on the `Users` section in the left navigation bar and click on the `Add User` button in the right pane.

Fill in the form with the following information:
- `Required user actions` - If you require 2FA for your user, add 'Configure OTP' (Google Authenticator or FreeOTP application may be used)
- `Email` - The email address of the user
- `First Name` - The first name of the user
- `Last Name` - The last name of the user
- `Groups` - For Dependency-Track Administrator, click on the `Join Groups` button and select `DTRACK_ADMINS` group. For Dependency-Track Users, click on the `Join Groups` button and select `DTRACK_USERS` group. If no selection is made, the user will have no access to Dependency-Track.

Click `Create` to create the user.

Once the user is created, open the `Credentials` tab on top. Click `Credential Reset`. Select `Update Password` as the `Reset action` and click `Send Email`. The user will receive an email with a link to set their password.

The user will then need to log in with their new password to the ReARM instance. What happens at their first login depends on the **SSO users without a role** setting of your main organization (`Organization Settings` > `Admin Settings` > `General`), and on whether the user is in a Keycloak group connected to a ReARM user group:

- If one of the user's Keycloak groups is connected to a ReARM user group of your main organization (the `Connected SSO groups` of the user group, read from the `groups` claim set up in [Configure Client Group Mapper](/integrations/identityProviders/microsoft.md#rearm-part-configure-client-group-mapper)), the user joins your main organization with that group's permissions.
- Otherwise, with the default setting, **Refuse sign-in**, the login is refused with a message that no ReARM role is assigned (reference `NO-ROLE`), unless the user holds a pending invitation to an organization. Add the user to a connected Keycloak group in the `Groups` step above, or switch the setting to **Create account without a role**.
- With **Create account without a role**, once they log in and accept terms and conditions, they join your main organization with no allocated permissions. To set permissions, in ReARM, in the left menu, click on the `Organization Settings` section, then click on the `Users` tab on top. Click on the pencil icon next to the user and assign desired permissions.

A user with a permission above `None` takes a license seat. See [License Seats](/configure/license-seats.md).


### User Deletion
Note, that if you delete a user from the Admin console, they will not be able to log in to ReARM even if they still have permissions in the `Organization Settings` view of ReARM.

Deleting or disabling a user in the Admin console does not free their license seat: their ReARM account stays active and keeps it. To free the seat, also inactivate the user in ReARM (`Organization Settings` > `Users`, pencil icon, `Inactivate User`). See [License Seats](/configure/license-seats.md#disabling-a-user-in-the-identity-provider-does-not-free-a-seat).
