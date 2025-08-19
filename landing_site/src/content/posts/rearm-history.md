---
title: "ReARM History"
date: "2025-05-23"
---

In the fast-paced world of software delivery, one of the most persistent challenges has been managing releases, deliverables and their metadata including artifacts. Project ReARM — short for Reliza's Artifact and Release Management — was born out of this very challenge. Developed by [Reliza](https://reliza.io), ReARM rethinks how modern DevSecOps pipelines handle Supply Chain Security.

This post takes a look at the origins of Project ReARM, its evolution, and how it's transforming the DevSecOps and CI/CD landscape.

### It's All About Release Metadata

Reliza was established in 2019 where the work has started on its first product - [Reliza Hub](https://relizahub.com). The aim of the project was to answer **5 Why's of DevOps** - Who, When, Where and Why did What.

During this work we started experimenting with separation between software objects and their metadata. We got involved in [CycloneDX ecosystem](https://cyclonedx.org) and realized that describing software is a crucial piece of any system that aims to correctly manage software. The key reason for that is that the Check step of the classic PDCA (Plan-Do-Check-Act) Deming Cycle requires prior knowledge of software metadata to be able to check actual value against these metadata.

### Evolution of ReARM within Reliza Hub

Development of Reliza Hub was going in 2 directions. First - directly related to DevOps, such as management of ephemeral instances and being able to deploy software on various instances with a click of a button. Second - management of Releases, their deliverables and metadata, including artifacts, such as SBOMs.

Eventually, we realized that each of those directions would be better suited as its own Product. That is how an idea of separate Project ReARM was born in 2023.

The big differentiator in our approach to Software and Release metadata was that we pioneers in using Product - Component hierarchy, rich branching structure and clear isolation of each release in the DevOps and DevSecOps pipeline. Such design choices were difficult from technical prospective, but they were driven by the actual problems we were facing in the way how software was developed and deployed in fast-paced organizations.

### Regulatory Pressure

In parallel, in different jurisdictions worldwide regulatory pressure in terms of Supply Chain Security and particularly SBOMs / xBOMs was evolving. Particularly, we can mention here CRA act in EU, Executive Orders 14028 and 14144 and Section 524B of the FD&C in the US.

Such regulatory initiatives were driving certain architectural choices that we did, for example an ability to store SBOM / xBOM artifacts for many years and bound to a specific release or a set of releases as required by CRA (the actual regulated term depends on circumstances, but in practice it may be anywhere between 5 and 15 years.)

### ReARM Public Beta Released

We have released ReARM Closed Beta in 2024 and ReARM Public Beta in 2025. ReARM comes in both Community Edition as seen on [GitHub](https://github.com/relizaio/rearm) and Commercial Managed Service version.

We made a decision to include all functionality necessary to fulfill regulatory requirements in the Community Edition to make sure that a wide range of organizations are able to be compliant under emerging regulations. At the same time we offer managed service, premium support and additional release management capabilities in ReARM Commercial version.

ReARM also provides tight integration with [OWASP Dependency-Track](https://dependencytrack.org/), which is a popular tool in Supply Chain Security based on SBOMs that enables constant monitoring of vulnerabilities and policy violations for existing SBOMs.

### Project Rebom Merged to ReARM

In May 2025 we have merged our earlier SBOM repository project Rebom into ReARM as its component. Rebom is leveraging [ORAS](https://github.com/oras-project/oras) capabilities to store SBOMs and xBOMs on OCI-compatible storage platforms.

Rebom also enables seamless xBOM augmentation and merging that is needed for ReARM operations.

### Transparency Exchange API Implementation

Creators of ReARM are involved in the creation of [OWASP Transparency Exchange API (TEA) standard](https://github.com/CycloneDX/transparency-exchange-api/).

Significant part of ReARM development in 2025 is aimed to implement TEA and improve ReARM functionality that is related to TEA.

### Future Work

As we are preparing ReARM GA releases, we are mostly focusing on improved TEA implementation and support for various advanced workflows and use-cases related to insights in ReARM.
