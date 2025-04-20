# Build and push Container image on Azure DevOps

## 1. Prepare your Azure DevOps project

### 1.1.  Set container registry service connection
In Azure Devops, click on `Project Settings`, click on "Service connections`.

Click `Create service connection`. Choose `Docker Registry`

Fill in details according to specs, i.e. for Reliza Hub Registry:
https&#58;&#47;&#47;registry.relizahub.com/uuid-public-or-private

For *Service connection name* you can enter *rh_registry*.

Once created, click on 3 dots, click `Security`. In `Pipeline permissions`, click `+` and choose desired pipelines (note, you may have to wait before your pipeline is created in step 2 below for this step).

### 1.2 Set ReARM auth variables

Click on `Library` menu from your Azure DevOps project screen.
Click `+ Variable group`.

Use name: *rearm-variable-group*.

Add following variables (or use similar names from key vault secrets):

1. rearmApiKey

2. rearmApiKeyId

Change both variable types to secret by clicking lock button on the right.

Click `Save`.

Once Saved, click `Pipeline permissions`, click `+`, add desired pipelines.

### 1.3 Create shared script repository

Under your Azure DevOps project, create a shared repository `rearm-scripts`, with the content:

```
rearm-scripts/
├── scripts/
│   └── azure-build-submit-template.yml
```

Where you use a copy `azure-build-submit-template.yml` from ReARM repository [here](https://github.com/relizaio/rearm/blob/main/integrations/azureDevOps/azure-build-submit-template.yml).

## 2. Create Pipeline YAML in your repository

In your repository, create `azure-pipelines.yml` file like below:

```
resources:
  repositories:
    - repository: self
    - repository: rearm-scripts
      type: git
      name: 'Project Name/rearm-scripts'

variables:
  - group: rearm-variable-group
  - name: dockerfilePath
    value: '$(Build.SourcesDirectory)/Dockerfile'
  - name: tag
    value: '$(Build.BuildId)'
  - name: branch
    value: '$(Build.SourceBranch)'
  - name: commit
    value: '$(Build.SourceVersion)'
  - name: buildUri
    value: '$(Build.BuildUri)'

stages:
- stage: Setup
  jobs:
  - template: scripts/azure-build-submit-template.yml@rearm-scripts
    parameters:
      name: 'ReARM Container Build'
      containerRegistry: 'registry.relizahub.com'
      imageRepository: '2c96830f-0c34-4bcc-bd79-94cbf78cf6a5/myimage'
      componentID: 7a10a1f8-4fab-40a3-8a79-542bba2ada3e
      vmImageName: 'ubuntu-latest'
      rearmCliVersion: '25.03.2'
      dockerRegistryServiceConnection: rh_registry
      rearmUrl: https://demo.rearmhq.com
      buildPath: .
      pushLatestTag: true
      enableSbom: true
      sbomType: dotnet
      dockerfilePath: $(dockerfilePath)
      tag: $(tag)
      branch: $(branch)
      commit: $(commit)
      buildUri: $(buildUri)
      rearmApiKey: $(rearmApiKey)
      rearmApiKeyId: $(rearmApiKeyId)
```

Make sure to change the following according to your setup:

1. `Project Name` -> change to your actual Azure DevOps project name
2. `containerRegistry` parameter -> URI of your OCI registry
3. `imageRepository` parameter - full path to your image within registry
4. `componentID` - UUID of your ReARM component
5. `rearmUrl` - must point to your ReARM instance
6. `sbomType` - currently accepted values are `npm`, `dotnet` or `other` (`other` would use `cdxgen` to resolve)

Congratulations! You now have working Azure DevOps Container image publish pipeline!