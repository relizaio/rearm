# Trigger Azure DevOps Pipeline from ReARM

N.B. This functinality is not part of ReARM Community Edition.

## Azure Part
1. You would need to create Azure Service Principal to perform Azure DevOps trigger operations. For this, in Azure Portal search, search for `App registrations`, click on it and once on the `App registrations` page, click on the `New registration` button.

2. Choose a name for your Service Principal, opt for Single Tenancy - "Accounts in this organizational directory only" (default value) and click `Register` on the bottom.

3. On the registered Service Principal page, note `Application (client) ID` and `Directory (tenant) ID` - you will need these values later in the ReARM configuration part.

4. On the same page, under *Client credentials* click on the `Add a certificate or secret`. Then click on the `New client secret`. Enter desired secret description, i.e. `ReARM Integration`, choose desired expiration timeframe and click `Add`. On the next page, note created secret value - you will need it later.

5. In Azure DevOps, in your git repository, create your desired pipeline by creating `azure-pipelines.yml` file. The most basic *hello-world* pipeline may look as following:

```
stages:
- stage: Build
  jobs:
  - job: Build
    pool:
      vmImage: 'ubuntu-latest'
    steps:
    - script: echo "hello world"
```

6. Commiting an `azure-pipelines.yml` would create a pipeline, open the `Pipelines` menu in Azure DevOps to view it. 

7. From the `Pipelines` menu, hover with mouse over pipeline name and note definition id as shown on the image below, alternatively, right click pipeline name and select `Copy link address`, then paste the link somewhere and inspect definition id. You would need this definition id (or pipeline id) later in the ReARM configuration part.

![Find Definition (Pipeline) ID in Azure DevOps UI](images/ado-pipeline-definition-id.png)

In the image above, the definition (pipeline) ID would be `2`.

8. Also, in Azure DevOps, note your organization name and your project name. You would need both of these in the ReARM configuration steps.

9. In Azure DevOps, click on your organization name, click on the `Organization Settings` in the bottom left corner, click on `Permissions` under *Security*, then click on the `Project Collection Build Service Accounts`. Click on `Members`, click `Add` and search for the Service Principal you created above, click on it, then click on `Save`.

Note, that you may want to refine these permissions based on your organization policies. Setting Azure permissions may be quite complex, if you need to tune permissions, start with the base documentation [here](https://learn.microsoft.com/en-us/azure/devops/integrate/get-started/authentication/service-principal-managed-identity?view=azure-devops).


## ReARM Part

1. Integration triggers on approval policy events, for which you would need an Approval Policy configured or on vulnerabilities or policy violations, for which you need [Dependency Track integration configured](./dtrack).

2. You need to set up a ReARM component that will have corresponding triggers configured. Once your component is created, open it and click on the tool icon to toggle component settings:
![Toggle Component Settings in Reliza Hub UI](images/component-settings-icon.png)

3. If you are setting triggers based on approvals, make sure you have Approval Policy selected under **Core Settings** tab.

4. Open **Output Triggers** tab and click on `Add Output Trigger`.

5. Enter name for your trigger, i.e. `Trigger Azure DevOps Pipeline`.

6. Select `External Integration` as *Type* and `Azure DevOps` as *Sub-Type*.

7. Enter your Service Principal's Client ID as noted above.

8. Enter your Service Principal's Client Secret as noted above.

9. Enter your Service Principal's Tenant ID as noted above.

10. Enter your Azure DevOps Organization Name as noted above.

11. Enter your Azure DevOps Project Name as noted above.

12. Enter your Azure DevOps Pipeline ID as noted above.

13. If needed, enter Optional Parameters json values, that contain Azure variables and may be set as JSON and be distributed as `parameters` in Azure call as described in Azure API documentation [here](https://learn.microsoft.com/en-us/rest/api/azure/devops/build/builds/queue?view=azure-devops-rest-7.1).

In example, this may be set to

```
{"param1":  "value1", "param2":  "value2"}
```

14. Click on 'Save', your trigger is now created.

15. Now create a Trigger Event linked to this trigger to make it fire on desired events (TODO - to be documented soon).