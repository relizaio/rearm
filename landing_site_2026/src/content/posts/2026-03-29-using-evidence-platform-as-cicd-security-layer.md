---
title: "Using Evidence Platform as CI/CD Security Layer"
date: "2026-03-29"
ogImage: "2026-03-29-evidence-store-github-actions-security.png"
---

Following the recent Trivy and LiteLLM compromises, it has become clear that we still lack a good way to protect our CI/CD layer, specifically GitHub Actions, from supply chain attacks.

![2 locks protecting each other - representing Evidence Platform such as ReARM and CI/CD system such as GitHub Actions Protecting each other](/blog_images/2026-03-29-evidence-store-github-actions-security.png)

It takes a single compromised dependency downloaded on a developer machine to run malicious scripts at installation time. Such scripts are known to steal and exfiltrate available credentials, including tokens with access to source code repositories, Kubernetes clusters, cloud providers, crypto wallets, and others.

Open source ecosystems, including npm and PyPI, are frequently used in such attacks because they are popular and because packages are often allowed to run unchecked scripts during the dependency installation phase. So all it takes is a single compromised dependency somewhere in the supply chain and a developer running `npm install` or a similar command. Pavel Shukhman, CEO of [Reliza](https://reliza.io), previously discussed npm specifically in relation to the Shai-Hulud 2.0 attack in his blog post [npm Has Become a Russian Roulette](https://worklifenotes.com/2025/09/24/npm-has-become-a-russian-roulette/). While he listed a set of possible defences there, he admitted that no foolproof solution currently exists.

## GitHub Actions Cannot Protect Itself
In 2026, GitHub Actions is arguably the most popular CI/CD platform out there. However, if its release workflow becomes compromised, it is almost impossible to think of a control within GitHub Actions itself that could mitigate that compromise.

Let's consider 2 attack scenarios:
1. An attacker steals a GitHub write token from a developer machine. If that token had elevated permissions, the attacker would immediately gain full control over the GitHub repository and all associated resources. Such an attacker would then be able to publish releases and propagate the attack down the supply chain. Even if the token were only a write token, the attacker would still be able to overwrite GitHub Actions workflow scripts and use that to construct malicious releases.
2. A GitHub Actions workflow inadvertently executes malicious code that gains access to CI tokens. This can happen through a compromised dependency, or through a `pull_request_target` workflow misconfiguration - where a pull request from a fork runs with write permissions and access to repository secrets. This is what happened in the Trivy breach: a malicious PR exploited a vulnerable `pull_request_target` workflow to exfiltrate secrets. The stolen credentials were then used to publish backdoored versions of the Trivy GitHub Actions, which in turn compromised downstream users - possibly including LiteLLM, whose PyPI publishing credentials may have been stolen by the backdoored Trivy action running in LiteLLM's own CI pipeline (this is not confirmed, but it is a plausible scenario).

Note that in the case of stolen tokens, the attacker's window of permissions would likely be limited by the lifespan of those tokens, but this could still be enough time to publish a malicious release and spread the compromise.

In both such scenarios, GitHub Actions workflow itself becomes compromised and any control within it would be ineffective.

## Existing Protections and Their Limitations
We should note here that existing mitigations such as pinning GitHub Actions to specific commit SHAs (rather than mutable tags) and using short-lived OIDC tokens for authentication where possible help reduce the attack surface. However, they do not fully solve the problem:

1. SHA pinning does not protect against a compromised action at the pinned commit (or such action may still download additional unpinned dependencies at runtime, including other GitHub Actions).
2. If an attacker obtained a write token to the repository, as in our first scenario above, they would be able to overwrite any existing SHA pinning and completely replace the workflow scripts if they wished.
3. OIDC tokens - while short-lived - are still accessible to any code running within the compromised workflow and can be used during their lifespan, which may be sufficient to carry out an attack.

## Evidence Platform as CI/CD Security Layer
An evidence platform such as [ReARM](https://rearmhq.com) can serve as a security layer for CI/CD workflows. By definition, an evidence platform creates a verifiable record of supply chain events and artifacts that is stored outside of the actual CI/CD system. Of course, an evidence platform may itself be attacked, but to compromise the overall system an attacker would now need to breach both the CI/CD system and the evidence platform. That makes such an attack significantly more difficult to carry out. In other words, an evidence platform can be used in a manner similar to 2FA - it adds an additional layer of security to the CI/CD workflow.

## Practical Implementation of Evidence Platform as CI/CD Security Layer
To properly protect CI/CD workflows with an evidence platform, it is important to build workflows with the principle of separation of duties. This means that different stages of the workflow should be performed by different entities, and each entity should have a different set of permissions. For example, a workflow that builds and releases a container image should have a separate stage for building the image and a separate stage for releasing the image. The building stage should have permissions to build the image, but not to release it. The releasing stage should have permissions to release the image, but not to build it. This way, if an attacker compromises one stage, they would not be able to compromise the other stage. For more details on this and other CI/CD best practices, refer to another Pavel Shukhman's post [7 Best Practices of Modern CI/CD](https://worklifenotes.com/2020/06/04/7-best-practices-modern-cicd/).

That blog post was written six years ago, but in 2026 we have to admit that true separation of duties requires stages to live in different repositories. That is because different workflows in the same repository may still have access to the same GitHub Actions secrets. In certain cases, it is possible to limit access to sensitive tokens on a per-workflow basis, but this is more an exception than a rule.

So, to practically implement secure workflows, we need to introduce structure similar to the following:
1. CI stage builds initial (non-release) artifacts - this stage may still belong to the main repository
2. Evidence platform verifies the artifacts and stores them
3. Approval and/or release lifecycle rules within the evidence platform gate the release
4. Separate CI/CD workflow in a different repository with no direct developer write access - only the evidence platform's service account has permission to trigger it (so if a developer token is compromised, the release workflow remains intact)
5. Evidence platform triggers the release workflow when release is approved
6. Release workflow itself should contain additional security checks to ensure that the release is legitimate - for example, verifying Sigstore/cosign signatures and attestations that were stored in the evidence platform during the build stage

Note that for ReARM specifically, approvals and triggering workflows are only available in ReARM Pro, i.e. as [described here for GitHub Actions](https://docs.rearmhq.com/integrations/githubActionsTriggers.html). However, even ReARM CE users can leverage the GitHub Actions scheduler and use ReARM CLI to [request the latest release with a desired lifecycle](https://github.com/relizaio/rearm-cli?tab=readme-ov-file#4-use-case-request-latest-release-per-component-or-product), thus achieving a similar security outcome.

## Summary
The described implementation mitigates the attack scenarios outlined above by introducing an additional layer of security between the CI/CD system and the evidence platform. Even if an attacker compromises the initial CI/CD workflow, they would still need to compromise the evidence platform to publish a malicious release. While possible, this would require more time, and the evidence platform itself should be configured to include additional controls to flag suspicious activities - such as signature and identity verifications, [SBOM diffing](https://worklifenotes.com/2025/11/12/sbom-diffing-next-frontier-for-supply-chain-security/), and other security checks.

With the system outlined above, CI/CD system and Evidence Platform work together to create a more secure supply chain. They create a set of controls that essentially "police" each other, so that to mount a successful attack, an attacker would need to compromise both systems at the same time.