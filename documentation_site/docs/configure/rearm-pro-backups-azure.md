# ReARM Pro Backups - Azure

This section will describe how to set up automated backups for ReARM Pro using Azure Blob Storage. This document assumes you have a ReARM Pro license and are running ReARM Pro configured by Reliza.

## Create storage account in Azure

Create storage account in Azure and create 2 containers in the storage account (names can be chosen arbitrarily, defaults provided below):
- dbbackups (for database backups)
- ocibackups (for artifact backups)

## Create service principal in Azure with permissions to access the storage account

1. For this, in Azure Portal search, search for App registrations, click on it and once on the App registrations page, click on the New registration button.

2. Choose a name for your Service Principal, i.e. "ReARM Backups" opt for Single Tenancy - "Accounts in this organizational directory only" (default value).

3. Click "Register" button.

4. On the next page - which is the registered Service Principal page, note Application (client) ID and Directory (tenant) ID - you will need these values later.

5. Click on the "Add a certificate or secret" near "Client credentials", then click on the "New client secret". Enter desired secret description, i.e. ReARM Backups Credential, choose desired expiration timeframe and click Add. On the next page, note created secret value - you will need it later.

6. Open your storage account in Azure Portal, go to Access control (IAM) and click on Add -> Add role assignment. Select "Storage Blob Data Contributor" role and assign it to the Service Principal you created (note that this will give the Service Principal access to the entire storage account, not just the containers you created).

Create 2 secrets in your `rearm` namespace and 1 secret in your `dtrac` namespace as following:

**Note**: Replace `<your_storage_account_name>`, `<your_tenant_id>`, `<your_client_id>`, `<your_client_secret>`, and `<encryption_password>` with your actual values.
For encryption password, you may use `openssl rand -base64 32` to generate a random password. Make sure to store it securely. You would need this password to decrypt and restore the backups later. If you used non-default names for containers, update them accordingly in the secrets below.

```bash
kubectl create secret generic rearm-backup \
  --from-literal=azure-storage-account=<your_storage_account_name> \
  --from-literal=azure-tenant-id=<your_tenant_id> \
  --from-literal=azure-client-id=<your_client_id> \
  --from-literal=azure-client-secret=<your_client_secret> \
  --from-literal=azure-container=dbbackups \
  --from-literal=encryption-password="<encryption_password>" \
  --namespace=rearm
```

```bash
kubectl create secret generic rearm-backup-oci \
  --from-literal=azure-storage-account=<your_storage_account_name> \
  --from-literal=azure-tenant-id=<your_tenant_id> \
  --from-literal=azure-client-id=<your_client_id> \
  --from-literal=azure-client-secret=<your_client_secret> \
  --from-literal=azure-container=ocibackups \
  --from-literal=encryption-password="<encryption_password>" \
  --namespace=rearm
```

```bash
kubectl create secret generic rearm-backup \
  --from-literal=azure-storage-account=<your_storage_account_name> \
  --from-literal=azure-tenant-id=<your_tenant_id> \
  --from-literal=azure-client-id=<your_client_id> \
  --from-literal=azure-client-secret=<your_client_secret> \
  --from-literal=azure-container=dbbackups \
  --from-literal=encryption-password="<encryption_password>" \
  --namespace=dtrack
```

## Arrange Backup Schedule with Reliza
Once the secrets are provisioned, you can arrange backup schedules with Reliza, by default weekly backups are scheduled.