## GitHub Actions

### 1. Build and push Docker image on GitHub Actions

Use the [rearm-docker-action](https://github.com/relizaio/rearm-docker-action).

This GitHub Action also supports AWS ECR repositories - simply pass your IAM ID and Key as registry_username and registry_password parameters.

### 2. Other Reliza Github Actions
The [reliza-docker-action](https://github.com/marketplace/actions/relizahub-build-and-submit-release-metadata-action) and the [reliza-helm-action](https://github.com/marketplace/actions/relizahub-version-and-publish-helm-chart-action) action reuses the following actions (also available on github marketplace), which can be reused to create other custom workflows:

1. [setup-rearm-cli](https://github.com/relizaio/setup-rearm-cli-action): This action, sets up ReARM CLI on GitHub's hosted Actions runners.
2. [rearm-get-version](https://github.com/relizaio/rearm-get-version): This action uses ReARM CLI to get the version for current release.
3. [rearm-add-release](https://github.com/relizaio/rearm-add-release): This action uses ReARM CLI to submit release metadata to ReARM.