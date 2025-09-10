# Add a User to a Reliza-managed ReARM Pro instance

This section will describe how to manually add a user to a Reliza-managed ReARM Pro instance. Note that this approach is not needed if you are using SSO integration, such as with [Microsoft Entra ID](/integrations/microsoft-entra-id.md).

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

The user will then need to log in with their new password to the ReARM instance. Once they log in and accept terms and conditions, they will automatically join your main organization in ReARM. However, they will have no allocated permissions in ReARM. To set permissions, in ReARM, in the left menu, click on the `Organization Settings` section, then click on the `Users` tab on top. Click on the pencil icon next to the user and assign desired permissions.


### User Deletion
Note, that if you delete a user from the Admin console, they will not be able to log in to ReARM even if they still have permissions in the `Organization Settings` view of ReARM.