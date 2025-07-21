# Concepts

## ReARM
ReARM is an abbreviation for "Reliza's Artifact and Release Management". It is a DevSecOps and Supply Chain Security tool to organize product releases with their metadata, including various Bills of Materials (xBOMs). 

ReARM allows organizations to maintain compliance within the various regulatory frameworks, among them:
- European [CRA regulations](https://eur-lex.europa.eu/eli/reg/2024/2847/oj), [BSI TR-03183](https://www.bsi.bund.de/EN/Themen/Unternehmen-und-Organisationen/Standards-und-Zertifizierung/Technische-Richtlinien/TR-nach-Thema-sortiert/tr03183/tr-03183.html), implied use of xBOMs in [NIS2 Directive](https://eur-lex.europa.eu/eli/dir/2022/2555/oj/eng) and [DORA](https://eur-lex.europa.eu/eli/reg/2022/2554/oj/eng)
- US executive order [14028](https://www.federalregister.gov/documents/2021/05/17/2021-10460/improving-the-nations-cybersecurity)
- US Army [Memorandum](https://federalnewsnetwork.com/wp-content/uploads/2024/09/081624_Army_SBOM_Memo.pdf) on SBOMs
- US executive order [14144](https://www.federalregister.gov/documents/2025/01/17/2025-01470/strengthening-and-promoting-innovation-in-the-nations-cybersecurity) (later [amended](https://rearmhq.com/blog/sbom-remains-attestations-out-amending-executive-order-14144))
- Section 524B of the US [FD&C Act](https://www.govinfo.gov/content/pkg/COMPS-973/pdf/COMPS-973.pdf)
- Reserve Bank of India (RBI) and the Securities and Exchange Board of India (SEBI) Cybersecurity and Cyber Resilience Framework SBOM [requirements](https://www.sebi.gov.in/legal/circulars/aug-2024/cybersecurity-and-cyber-resilience-framework-cscrf-for-sebi-regulated-entities-res-_85964.html) with [faq](https://www.sebi.gov.in/sebi_data/faqfiles/jun-2025/1749647139924.pdf)

## Component
Component refers to software or hardware that is being developed or used by an organization and in case of software usually maps to a version control system (VCS) repository (i.e., this could be a Git repository). In other words, if you are working on a software which has its own Version Control repository or is a component in a Monorepo - that would be a Component.

Developers and Engineers usually think in terms of Components when discussing what they are currently working on.

## Branch
Branch is a part of the *Component* and, for software, usually has same meaning as a branch in a version control system branch (i.e., Git).

Usually branches are divided into *Base* branch (main), *Feature* branches (short-term) and *Release* branches (long-term). In practical sense, *Feature* branches, usually have different naming and versioning conventions, i.e. they can be named after tasks like *feature/ticket-2*. Some systems also include *Develop* and *Hotfix* branches.

There are 3 well-known work organization systems that deal with branching:

- [Trunk-Based Development or TBD](https://trunkbaseddevelopment.com/)
- [GitHub Flow](https://githubflow.github.io/)
- [GitFlow](https://nvie.com/posts/a-successful-git-branching-model/)

While all of the above are supported by ReARM, we at Reliza recommend using Trunk-Based Development where possible.

## Product
Product is a customer-facing, operational piece of software or hardware. This is what customer buys. Products are not mapped directly to a version control repository, but rather through their Components. Products may themselves be components of other Products.

Customers, Project Managers and Product Marketing teams usually think in terms of Products when describing software or hardware produced by organization.

Using Product-Component relationships helps communication between Development, Ops and Product Marketing teams.

## Feature Set
Feature Set is a part of a *Product*. It is understood as specific set of functionality as to be delivered to the customer. A *Feature Set* for a *Product* is similar to what a *Branch* is for a *Component*.

## Deliverable
Deliverable may be a binary resource, container image, a collection of directories and/or files or a piece of hardware.
ReARM supports Deliverable types as per CycloneDX specification of *Component Type* resource - currently using [CycloneDX schema v1.6](https://github.com/CycloneDX/specification/blob/master/schema/bom-1.6.schema.json).

We assume that 2 software Deliverables are equal if they are of the same type and their known digests (such as sha256 or sha512) are equal.

## Artifact
Artifact is a Metadata document that describes specific release. Here are the types currently supported by ReARM:

- BOM
- Attestation
- VDR
- VEX
- User Documentation
- Development Documentation
- Project Documentation
- Marketing Documentation
- Test Report
- SARIF file
- Build Metadata Document
- Certification
- Formulation
- License
- Release Notes
- Security Text File
- Thread Model
- Signature
- Signed Payload
- Public Key
- X509 Certificate
- PGP Certificate
- Other

## Source Code Entry
Source Code Entry is a pointer to a specific commit or revision in a version control system. Unlike commit in a distributed VCS, Source Code Entry also points to a specific VCS repository to ensure auditability.

## Release
Release may belong to a Component or a Product.

In the case of a Component Release, Release points to a specific Source Code Entry and may have several Deliverables.

In the case of a Product Release, Release would only have components (other Releases). It may have its own Deliverables, in example this could be a tarball containing Deliverables of parent releases.

## Instance
Instance is a medium which runs software built by organization (Components or Products). This could be a VM, a set of VMs, a Kubernetes cluster, a PaaS solution - literally anything that is configured to run software. Instance is defined by its URI.

## Cluster
Cluster is a set of instances. In example, Kubernetes may be considered a cluster with Instances running inside its namespaces.