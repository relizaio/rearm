# Trigger GitHub Actions Workflow from ReARM

N.B. This functinality is not part of ReARM Community Edition.

## GitHub Part
1. You need to register a GitHub application that would trigger events in your repositories. To do so, refer to instructions [here](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app#registering-a-github-app).

Leave most values at their defaults, uncheck `Active` on Webhook, and set the following permissions:
Repository Permissions -> Contents -> Access: Read and write.

Select to install for only this account or other accounts as well based on your organization needs. Note, that currently ReARM allows integration with only one GitHub App per ReARM organization.

2. Once the GitHub App is created, note its App ID.

3. Generate App Private Key as suggested by GitHub (on the home page of your app scroll down to the `Private keys` section and click on `Generate a private key`). A .pem file would be downloaded onto your machine.

4. From your bash terminal, perform the following commands on this .pem file (I would assume the file to be named `key.pem` for the commands below):

```
openssl pkcs8 -topk8 -inform PEM -outform DER -in key.pem -out key.der -nocrypt
base64 -w 0 key.der
```

Note the output, you would need it to paste into ReARM integration form.

5. In your browser, from the home page of your GitHub App, click on the `Install App` and install it for desired Account(s) and repository or repositories.

Once installed, the App would display installation ID in the browser address bar as shown on the image below.

![Extract App Installation ID from GitHub](https://worklifenotes.com/wp-content/uploads/2020/05/image-3-1024x453.png)

Note this installation ID for adding into ReARM integration form later.

6. Create a desired GitHub Actions script in your repository which would fire on repository dispatch event, i.e.

```
on:
  repository_dispatch:
    types: [reliza-build-event]
```

See sample script [here](https://github.com/Reliza-Demos/action-dispatch/blob/main/.github/workflows/workflow.yml).

This script must be present on the main branch of your repository as GitHub Actions does not support branch selection for triggers.

Note that the event type is optional, and you can choose any event and configure it on ReARM.

In your script, you may also make use of client payload as described in the GitHub Documentation [here](https://docs.github.com/en/actions/writing-workflows/choosing-when-your-workflow-runs/events-that-trigger-workflows#repository_dispatch).

## ReARM Part

1. In ReARM, make sure you register your VCS repository that contains desired GitHub Actions script either via Component creation or via **VCS** menu item and the plus-circle icon.

2. In ReARM, open **Organization Settings** menu. Under **Integrations** tab, click on `Add GitHub Integration`. In the corresponding fields, paste your Base64 encoded key noted above and GitHub Application ID, also noted above. Then click `Submit`.

3. Integration triggers on approval policy events, for which you would need an Approval Policy configured or on vulnerabilities or policy violations, for which you need [Dependency Track integration configured](./dtrack).

4. You need to set up a ReARM component that will have corresponding triggers configured. Once your component is created, open it and click on the tool icon to toggle component settings:
![Toggle Component Settings in Reliza Hub UI](images/component-settings-icon.png)

5. If you are setting triggers based on approvals, make sure you have Approval Policy selected under **Core Settings** tab.

6. Open **Output Triggers** tab and click on `Add Output Trigger`.

7. Enter name for your trigger, i.e. `Trigger GitHub Actions Approval Workflow`.

8. Select `External Integration` as *Type* and `GitHub` as *Sub-Type*.

9. Enter your GitHub App's Installation ID as noted above.

10. Enter name of your GitHub Actions event as referenced in your GitHub Actions script (the event name used in these instructions was `reliza-build-event`).

11. If you require any additional client payload, enter it in the JSON format in the *Optional Client Payload JSON* field.

12. Under *CI Repository* click on the Edit icon and select your GitHub repository containing desired GitHub Actions workflow set up above.

13. Click on 'Save', your trigger is now created.

14. Now create a Trigger Event linked to this trigger to make it fire on desired events (TODO - to be documented soon).