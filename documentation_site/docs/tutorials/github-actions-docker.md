# Container Image Pipeline on GitHub Actions

This tutorial will walk you through building ReARM integrated pipeline on GitHub Actions. We will achieve the following:

1. Have a Component that produces container image deliverables
2. Versioning, including branching model versioning will be managed by ReARM
3. On every build, a Deliverable level SBOMs will be produced and uploaded to ReARM

Time it takes: 5 minutes.

## Pre-requisites
1. Make sure you have [installed](/installation/) ReARM Community Edition or have access to ReARM Pro. Alternatively, you can follow this steps on [ReARM Public Demo instance](https://demo.rearmhq.com). If using Public Demo, click on Profile (Human icon) in the top right of the screen, and then Create New Organization in which you would do subsequent steps. Reload the page (F5) once the organization is created, this will show the dropdown menu on top of the screen where you can switch into your new organization.

2. Optionally, but highly recommended is to set up [Dependency-Track](https://dependencytrack.org/) integration. ReARM relies on Dependency-Track for SBOM analysis, including vulnerability scans and policy violations. If you are using ReARM Pro, Dependency Track integration will be set up for you by Reliza. If you are using Community Edition or your organization on Public Demo, follow [these instructions on setting up Dependency-Track integration](/integrations/dtrack).

3. You need to have OCI compatible storage to store produced container deliverables. Examples include Docker Hub, ACR, ECR, GCR. One of the quickest options is to use Reliza Hub product which gives you 1GB of free storage - see instructions how to set this up [here](https://docs.relizahub.com/registry/). Note that it is possible to reuse same registry that was used for ReARM installation. You would need an account with push permissions, so you would need to know OCI repository login, password and uri. You also need to know image namespace - that is full path to your image and image name which may be set arbitrarily or has to be pre-configured, depending on your container registry.

## Initialize GitHub repository
1. Under your GitHub account, set up a new GitHub repository. We will use [https://github.com/Reliza-Demos/rearm-container-image-tutorial](https://github.com/Reliza-Demos/rearm-container-image-tutorial) for the purposes of this tutorial.
2. In this repository, create `Dockerfile` with the content:
```
FROM hello-world
```
This would essentially replicate `docker.io/library/hello-world` image. Note, that this is one of the simplest examples possible. You are free to use any Dockerfile though, however complex.

## Set up ReARM component
1. In your ReARM instance, [create a new component](/tutorials/first-bom#create-first-component), which we will call for the purposes of this tutorial `hello-world` - although you are free to choose any name. The component we created is viewable on ReARM public demo [here](https://demo.rearmhq.com/componentsOfOrg/00000000-0000-0000-0000-000000000001/91b89d22-b82d-461b-a668-ca560ce003a2/ea14da55-7537-49a1-9a19-82a511ca09a2).
2. Once your component is created, on the component page, click on the `lock icon` (Generate Component API Key) and click `Yes, generate it!` on the next prompt. A screen will appear with API ID and API Key. Note these values, we will need them in the subsequent steps.

## Configure GitHub repository
In the GitHub repository, click on `Settings` in the top menu, then expand `Secrets and variables` menu on the left and click on `Actions`.
From here you would need to create 4 secrets:
- `DOCKER_LOGIN` (login to your container registry)
- `DOCKER_TOKEN` (password for your container registry)
- `REARM_API_ID` (ReARM API ID noted above)
- `REARM_API_KEY` (ReARM API Key noted above)
For each secret perform the following 4-step procedure:
1. Click on the `New repository secret` button
2. In the `Name` field, add your secret name, i.e. `DOCKER_LOGIN`
3. In the `Secret` field, enter the actual secret
4. Click the `Add secret` button

## Set Up GitHub Action
In the GitHub repository, create new file `.github/workflows/github_actions.yml`. 

For the content, use the sample below, but make sure to modify `registry_host`, `image_namespace`, `image_name` and `rearm_api_url` parameters based on your actual settings:

```yaml
on: [push]

name: Build Docker Image And Submit Metadata To ReARM

jobs:
  build:
    name: Build And Push Docker Image With Metadata
    runs-on: ubuntu-latest
    steps:
      - name: ReARM Build And Submit Backend Release metadata action
        uses: relizaio/rearm-docker-action@514a2c018d53238945e860af9749df6805143543 # v1.2.0
        with:
          registry_username: ${{ secrets.DOCKER_LOGIN }}
          registry_password: ${{ secrets.DOCKER_TOKEN }}
          registry_host: registry.relizahub.com
          image_namespace: registry.relizahub.com/d50bca61-d588-44ee-9dae-c0fbcd376270-public
          image_name: hello-world
          rearm_api_id: ${{ secrets.REARM_API_ID }}
          rearm_api_key: ${{ secrets.REARM_API_KEY }}
          rearm_api_url: https://demo.rearmhq.com
          path: .
          enable_sbom: 'true'
          source_code_sbom_type: 'none'
```
Note that if you have actual source code that builds into docker, you may configure `source_code_sbom_type`. Currently values of `npm` for node projects or `other` for all other projects are supported. In case of `other`, we are using [cdxgen](https://github.com/CycloneDX/cdxgen) that will try to automatically resolve the project type. If this setting is set, both Deliverable level and Source Code level SBOMs will be produced.

Once this file file is pushed in the repository, that will automatically trigger GitHub Action and produce container image with its metadata uploaded to ReARM.

You can find one of the releases we produced with the workflow built in this demo [here](https://demo.rearmhq.com/release/show/d4ea598d-6788-4b05-9ae0-59715f646ca8).

## More Things to Try
Feel free to modify something in your code and produce additional deliverables. Also feel free to create branches in git and see how those would manifest in ReARM.

## Congratulations!
You have set up your first automated GitHub Actions pipeline for you ReARM instance!