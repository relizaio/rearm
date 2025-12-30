# GitHub Actions for ReARM

## 1. ReARM GitHub Actions
We have bundled GitHub Actions for ReARM in the [rearm-actions](https://github.com/relizaio/rearm-actions) repository.

Currently the bundle includes following actions:

- relizaio/rearm-actions/setup-cli (installs ReARM CLI - https://github.com/relizaio/rearm-cli)
- relizaio/rearm-actions/initialize (initializes ReARM with last commit, commit diff, versions, synchronizes branches and creates pending release on ReARM)
- relizaio/rearm-actions/sbom-sign-scan (generates SBOMs and signs them and deliverables, alternatively can be replaced with your own workflow for SBOMs and other artifacts)
- relizaio/rearm-actions/finalize (pushes metadata to ReARM and finalizes ReARM release)

See some opinionated usage below.

## 2. Opinionated action for building and pushing container images on GitHub Actions

Use the [rearm-docker-action](https://github.com/relizaio/rearm-docker-action).

This GitHub Action also supports pushing container images to AWS ECR repositories - simply pass your IAM ID and Key as registry_username and registry_password parameters.

Refer to our [tutorial](/tutorials/github-actions-docker) for detailed walk through.

Also, for sample usage, refer to another sample in our [Demo workflow](https://github.com/Reliza-Demos/rebom-demo-on-rearm/blob/master/.github/workflows/github_actions.yml).

Further, you can inspect this action to see a sample how individual ReARM actions are used - https://github.com/relizaio/rearm-docker-action/blob/main/action.yaml

## 3. Opinionated action for building and pushing Helm charts on GitHub Actions

Use the [rearm-helm-action](https://github.com/relizaio/rearm-helm-action).

For sample usage, refer to our [Demo workflow](https://github.com/Reliza-Demos/rebom-demo-on-rearm/blob/master/.github/workflows/github_actions.yml).

## 4. Legacy Actions
There are 2 legacy actions available for ReARM:

- relizaio/rearm-add-release
- relizaio/setup-rearm-cli-action

These actions are not recommended for use in new workflows. New ReARM actions are available as mentioned in point 1.