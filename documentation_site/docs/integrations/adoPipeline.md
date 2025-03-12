# Build and push Container image on Azure DevOps

## 1. Prepare your Azure DevOps project

### 1.1.  Set container registry service connection
In Azure Devops, click on `Project Settings`, click on "Service connections`.

Click `Create service connection`. Choose `Docker Registry`

Fill in details according to specs, i.e. for Reliza Hub Registry:
https&#58;&#47;&#47;registry.relizahub.com/uuid-public-or-private

For *Service connection name* you can enter *rh_registry*.

Once created, click on 3 dots, click `Security`. In `Pipeline permissions`, click `+` and choose desired pipelines.

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

## 2. Use sample pipeline

Sample Azure DevOps YAML workflow will be published here later.
