# Trigger Jenkins Pipeline from ReARM

N.B. This functinality is not part of ReARM Community Edition and is only available on Enterprise Edition.

## Jenkins Part

1. Install *Build Authorization Token Root* plugin - https://plugins.jenkins.io/build-token-root/

2. In the *Build Triggers* section of your job check *Trigger builds remotely (e.g. from scripts)* and paste random alphanumeric sequence into the Authentication Token field. 

![Build Triggers in Jenkins](https://d7ge14utcyki8.cloudfront.net/documentation/jenkins_remote_build.png)

Note: To generate the token you may use the following CLI command:

```
openssl rand 32 | base64
```

Remember this token for now for future input in the ReARM configuration form.

3. Note that ReARM using *buildByToken* endpoint as provided by the *Build Authorization Token Root* plugin. Without this plugin the basic API path is actually not operational on Jenkins.

## ReARM Part

Note that for integration triggers firing on approval policy events, you would need an Approval Policy configured; for firing on vulnerabilities or policy violations, you would need [Dependency Track integration configured](./dtrack).

### Organization-Wide CI Integration Part (requires Organization Admin permissions)

1. In ReARM, open **Organization Settings** menu. Under **Integrations** tab, in the `CI Integrations` sub-section, click on `Add CI Integration`.

2. Enter description (try to make this descriptive as this will be used to identify integration).

3. Choose `Jenkins` as CI Type. 

4. Enter your Jenkins Home URI in the Jenkins URI field, i.e. `https://jenkins.localhost`.

5. Enter your token established above in the Jenkins Token field.

6. Click `Save`. Your CI Integration is now created.

### Component Part (requires User with Write permissions)

1. You need to set up a ReARM component that will have corresponding triggers configured. Once your component is created, open it and click on the tool icon to toggle component settings:
![Toggle Component Settings in ReARM UI](images/component-settings-icon.png)

2. If you are setting triggers based on approvals, make sure you have Approval Policy selected under **Core Settings** tab.

4. Open **Output Triggers** tab and click on `Add Output Trigger`.

5. Enter name for your trigger, i.e. `Trigger Jenkins`.

6. Select `External Integration` as *Type*.

7. Choose your previously created Jenkins Integration in the `Choose CI Integration` field.

8. Enter the name of your Jenkins job in the `Jenkins Job Name` field.

9. Click on 'Save', your trigger is now created.

10. Now create a Trigger Event linked to this trigger to make it fire on desired events (TODO - to be documented soon).