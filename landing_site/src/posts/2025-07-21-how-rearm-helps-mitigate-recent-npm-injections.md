---
title: "How ReARM Helps Mitigate Recent npm Package Injections"
date: "2025-07-21"
---

## How ReARM Helps Mitigate Recent npm Package Injections

This post will discuss recent npm supply chain attack and how ReARM can help mitigate it.

#### What Happened

On July 18, 2025 @JounQin [tweeted on X](https://x.com/JounQin/status/1946297662069993690) that several popular npm packages were published with injected malicious code as a result of a phishing attack.

Here is the list of affected packages:

- eslint-config-prettier
  - 8.10.1
  - 9.1.1
  - 10.1.6
  - 10.1.7
- eslint-plugin-prettier:
  - 4.2.2
  - 4.2.3
- snyckit:
  - 0.11.9
- @pkgr/core:
  - 0.2.8
- napi-postinstall:
  - 0.3.1

To make matters worse, at certain instances applications such as Dependabot or Renovate Bot were offering affected versions as automated updates.

The impact of malicious injections is currently unclear, and it looks like the injected code manifests only on Windows.

#### How ReARM Can Help

If you are using ReARM Pro or ReARM CE with Dependency-Track integration, perform the SBOM component search from ReARM dashboard to check whether the affected packages ever entered your supply chain. Follow the instructions in this [video](https://www.youtube.com/watch?v=a1VPDgqG1FA).

Also, if you are using ReARM Pro with configured triggers, you may have received high vulnerability alerts for the affected packages. 

Finally, we are currently releasing a feature that shows and alerts on any change in the SBOM components introduced by a new release. We are publishing a release of ReARM containing this feature by the end of the month.