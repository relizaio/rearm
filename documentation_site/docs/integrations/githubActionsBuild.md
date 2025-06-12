# Build and push Container image, Helm Charts, other deliverables on GitHub Actions

## 1. Build and push Docker image on GitHub Actions

Use the [rearm-docker-action](https://github.com/relizaio/rearm-docker-action).

This GitHub Action also supports pushing container images to AWS ECR repositories - simply pass your IAM ID and Key as registry_username and registry_password parameters.

Refer to our [tutorial](/tutorials/github-actions-docker) for detailed walk through.

Also, for sample usage, refer to another sample in our [Demo workflow](https://github.com/Reliza-Demos/rebom-demo-on-rearm/blob/master/.github/workflows/github_actions.yml).

## 2. Build and push Helm chart on GitHub Actions

Use the [rearm-helm-action](https://github.com/relizaio/rearm-helm-action).

For sample usage, refer to our [Demo workflow](https://github.com/Reliza-Demos/rebom-demo-on-rearm/blob/master/.github/workflows/github_actions.yml).

## 3. Other ReARM GitHub Actions
The [rearm-docker-action](https://github.com/relizaio/rearm-docker-action) and the [rearm-helm-action](https://github.com/relizaio/rearm-helm-action) action reuse the following actions, which can be wired to create other custom workflows:

1. [setup-rearm-cli](https://github.com/relizaio/setup-rearm-cli-action): This action, sets up ReARM CLI on GitHub's hosted Actions runners.
2. [rearm-get-version](https://github.com/relizaio/rearm-get-version): This action uses ReARM CLI to get the version for current release.
3. [rearm-add-release](https://github.com/relizaio/rearm-add-release): This action uses ReARM CLI to submit release metadata to ReARM.