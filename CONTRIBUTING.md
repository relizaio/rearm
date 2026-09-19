# Welcome to ReARM contributing guide <!-- omit in toc -->

Note: This Contributing guide is based on [GitHub Docs Contributing guide](https://github.com/github/docs).

Thank you for investing your time in contributing to ReARM!

Read our [Code of Conduct](./CODE_OF_CONDUCT.md) to keep our community approachable and respectable.

In this guide you will get an overview of the contribution workflow from opening an issue, creating a PR, reviewing, and merging the PR.

Please read [Licensing of contributions](#licensing-of-contributions) before you open a pull request: submitting a contribution means you agree to those terms.

## New contributor guide

To get an overview of the project, read the [README](../README.md) file. Here are some resources to help you get started with open source contributions:

- [Finding ways to contribute to open source on GitHub](https://docs.github.com/en/get-started/exploring-projects-on-github/finding-ways-to-contribute-to-open-source-on-github)
- [Set up Git](https://docs.github.com/en/get-started/git-basics/set-up-git)
- [Trunk-Based Development]([https://docs.github.com/en/get-started/using-github/github-flow](https://trunkbaseddevelopment.com/))
- [Collaborating with pull requests](https://docs.github.com/en/github/collaborating-with-pull-requests)


### Issues

#### Create a new issue

If you spot a problem with ReARM, first check if a corresponding issue exists in [open issues](https://github.com/relizaio/rearm/issues).
If a related issue doesn't exist, you can open a new issue.

#### Solve an issue

Scan through our [existing issues]([https://github.com/github/docs/issues](https://github.com/relizaio/rearm/issues)) to find one that interests you. 
If you find an issue to work on, you are welcome to open a PR with a fix.

### Make Changes

1. Fork the repository.
- Using the command line:
  - [Fork the repo](https://docs.github.com/en/github/getting-started-with-github/fork-a-repo#fork-an-example-repository) so that you can make your changes without affecting the original project until you're ready to merge them.
2. Create a working branch and start with your changes!

### Commit your update

Commit the changes once you are happy with them.

Make sure you have read and agree to the [Licensing of contributions](#licensing-of-contributions) terms below. Opening a pull request is how you tell us you do.

### Pull Request

When you're finished with the changes, create a pull request, also known as a PR.
- Enable the checkbox to [allow maintainer edits](https://docs.github.com/en/github/collaborating-with-issues-and-pull-requests/allowing-changes-to-a-pull-request-branch-created-from-a-fork) so the branch can be updated for a merge.
Once you submit your PR, a Docs team member will review your proposal. We may ask questions or request additional information.
- We may ask for changes to be made before a PR can be merged, either using [suggested changes](https://docs.github.com/en/github/collaborating-with-issues-and-pull-requests/incorporating-feedback-in-your-pull-request) or pull request comments. You can apply suggested changes directly through the UI. You can make any other changes in your fork, then commit them to your branch.
- As you update your PR and apply changes, mark each conversation as [resolved](https://docs.github.com/en/github/collaborating-with-issues-and-pull-requests/commenting-on-a-pull-request#resolving-conversations).
- If you run into any merge issues, checkout this [git tutorial](https://github.com/skills/resolve-merge-conflicts) to help you resolve merge conflicts and other issues.

### Your PR is merged!

Congratulations :tada::tada: The ReARM team thanks you :sparkles:.

Once your PR is merged, your changes will be added to the next ReARM release based on TBD model.

## Licensing of contributions

ReARM Community Edition is licensed under the [GNU AGPL v3](./LICENSE) (the [`deploy`](./deploy) directory under the [MIT License](./deploy/LICENSE)). Reliza also builds and distributes **ReARM Pro**, a commercial edition that shares code with this repository, and may in future change the license this repository is distributed under. So that we can keep doing both, every contribution to this repository is accepted on the following terms.

By submitting a contribution (a pull request, patch, or any other material you intentionally send to us for inclusion in this repository), you agree that:

1. **You have the right to contribute it.** The contribution is your original work, or you otherwise have the right to submit it under these terms — including, where applicable, permission from your employer.
2. **It is licensed to the project and to everyone under the repository's license.** Your contribution is licensed under the license that applies to the part of the repository it lands in: AGPL-3.0 for the code, MIT for the [`deploy`](./deploy) directory.
3. **You additionally grant Reliza Incorporated the right to relicense it.** In addition to (2), you grant Reliza Incorporated a perpetual, worldwide, non-exclusive, royalty-free, irrevocable license to use, reproduce, modify, distribute, sublicense and otherwise exploit your contribution, with or without modification, **under any license terms, including proprietary ones** — for example as part of ReARM Pro, or under a different open-source license should the project's license change.
4. **You keep your copyright.** Nothing here assigns your copyright to Reliza. You can use your own contribution for anything else you like.
5. **No warranty.** Unless required by law or agreed in writing, you provide your contribution "as is", without warranties of any kind.

If you cannot agree to these terms — for instance because your employer's policy does not allow it — please say so in the pull request before we spend time on review, and we will work out what is possible.

These terms apply to contributions submitted after they were added to this file; they do not change the terms of anything contributed earlier.
