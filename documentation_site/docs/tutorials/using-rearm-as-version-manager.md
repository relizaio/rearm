# Using ReARM as Version Manager

ReARM has built-in capability for automatically assigning release versions.

This tutorial will walk through the most basic example how to set up ReARM as a Version Manager using UI or ReARM CLI. ReARM CLI can then be used in any CI environment.

Time it takes: 5 minutes.

## Pre-requisites
Make sure you have [installed](/installation/) ReARM Community Edition or have access to ReARM Enterprise.

Alternatively, you can follow the steps below on the [ReARM Public Demo instance](https://demo.rearmhq.com). If using Public Demo, click on Profile (Human icon) in the top right of the screen, and then Create New Organization in which you would do subsequent steps. Reload the page (F5) once the organization is created, this will show the dropdown menu on top of the screen where you can switch into your new organization.

## Set up ReARM component
In your ReARM instance, [create a new component](/tutorials/first-bom#create-first-component), which we will call for the purposes of this tutorial `Version Manager Tutorial` - although you are free to choose any name.

You are free to select any proposed Version Schema, or you can create a custom one based on [elements](https://github.com/relizaio/versioning?tab=readme-ov-file#25-known-version-elements) supported by our underlying versioning library. For the purposes of this tutorial we would select **SemVer**.

Similarly, you can choose any Feature Branch Version Schema, we will use default, that is **Branch.Micro** (where **Branch** is a branch name).

For the purposes of this tutorial, we will not assign any VCS Repository to this Component, although you are free to do so.

The component we created for this tutorial is viewable on ReARM public demo [here](https://demo.rearmhq.com/componentsOfOrg/00000000-0000-0000-0000-000000000001/222c1486-c225-415a-ba52-bf98eb66dd2b/fb637fa9-b5c8-4b8c-bd7d-efc68193a024).


## Version Increment on Manually Created Releases

In the Branch section of the screen, click on the `plus-circle` icon (Add Release). You will notice that ReARM will automatically suggest next version in the Add New Branch Release modal.

## Modify Next Version for Future Assignments

In the Branch section of the screen, click on the `right-arrow-circle` icon (Set Next Version). You will be presented with a modal showing current next version value and an option to increment it to the next desired version. I.e., you could increment from 0.0.1 to 0.1.0.

**Important:** once incremented in such way you cannot go back to automated assignment of lower version. However, you can still assign a lower version manually or via CLI. The only restriction on assignment is that each version within the Component must be unique.

## Obtain Next Version Via CLI
1. Download ReARM CLI for your platform from its [GitHub Repository links](https://github.com/relizaio/rearm-cli?tab=readme-ov-file#download-rearm-cli). You can also use its container image **registry.relizahub.com/library/rearm-cli**. 

2. In the ReARM UI, on the component page, click on the `lock icon` (Generate Component API Key) and click `Yes, generate it!` on the next prompt. A screen will appear with API ID and API Key. Note these values, we will need them in the subsequent steps.

3. Use ReARM CLI to obtain next version as following (where *-u* flag needs to point to your ReARM instance and *-b* flag stands for branch):

```bash
rearm getversion \
  -u https://demo.rearmhq.com \
  -i {API_ID} \
  -k {API_KEY} \
  -b main
```

You will receive a JSON response with 2 fields:

```json
{"dockerTagSafeVersion":"0.1.1","version":"0.1.1"}
```

Here `version` is a regular version for the Release and `dockerTagSafeVersion` is a version that can be used as a container image tag in case there are any illegal characters in the regular version.

*Note*, that `getversion` command will automatically create a Release on ReARM with the `Pending` lifecycle. If a new release is not created within the next 2 hours, lifecycle will automatically change to `Rejected`. If this behaviour is undesired and you would not want to create a release on getversion command, pass the `--onlyversion true` flag to the ReARM CLI `getversion` command.

## Support for Conventional Commits
ReARM supports parsing of [Conventional Commits specification](https://www.conventionalcommits.org/en/v1.0.0/) when assigning versions. For example, if commit starts with **fix:**, `micro` element of the version will be updated; if commit starts with **feat:**, `minor` element will be updated.

Use ReARM CLI to pass commit details in `getversion` call, using `--vcsuri`, `--vcstype`, `--commit`, `--commitmessage` flags, refer to ReARM CLI `getversion` [documentation](https://github.com/relizaio/rearm-cli?tab=readme-ov-file#1-use-case-get-version-assignment-from-rearm) for example and more details.

## More Things to Try
From here you can integrate ReARM CLI to any CI solution you may be using. In example, it is being used in our more comprehensive [Container Image Pipeline on GitHub Actions](./github-actions-docker) tutorial.

## Congratulations!
You have successfully configured ReARM to be used as a Version Manager!