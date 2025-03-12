---
sidebarDepth: 2
---

# Configure ReARM

## Configure Personal Programmatic Access Key

- In ReARM go to `Organization Settings` page. 
- There, locate "Users" section and click on the lock icon in the "Manage" column corresponding to your user. 
![Icon to set personal programmatic access key](https://d7ge14utcyki8.cloudfront.net/documentation/personal_programmtic_key.png)
- Click *Yes, generate it!* at the *Are you sure?* prompt
- You will see your API ID and API Key shown on the screen
- If you need to reset your API Key, click again on the same lock icon and confirm the prompt

## Login to ReARM CLI on Local

- Obtain Personal Programmatic Access Key as described in the [Configure Personal Programmatic Access Key](./index.md#configure-personal-programmatic-access-key) section.
- Download latest ReARM CLI for your platform as described on the [ReARM CLI page](https://github.com/relizaio/rearm-cli?tab=readme-ov-file#download-rearm-cli).
- Unpack executable from downloaded Reliza CLI archive and add to your system path.
- Login with your programmatic access key and id using following command:
```
rearm login -i YOUR_PERSONAL_API_ID -k YOUR_PERSONAL_API_KEY
```