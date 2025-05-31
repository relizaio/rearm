 [![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
 [![Website](https://img.shields.io/badge/https://-rearmhq.com-blue.svg)](https://rearmhq.com)
 [![Documentation](https://img.shields.io/badge/read-documentation-blue.svg)](https://docs.rearmhq.com)
 ![Build Status](https://github.com/relizaio/rearm/actions/workflows/github_actions.yml/badge.svg?branch=main)
 [![OpenSSF Best Practices](https://www.bestpractices.dev/projects/10664/badge)](https://www.bestpractices.dev/projects/10664)

# ReARM - SBOM / xBOM Repository and Release Manager - Community Edition

https://github.com/user-attachments/assets/a315c5b2-7116-4b4a-bb4b-28e77d3ae9b3

## About
ReARM is an abbreviation for "Reliza's Artifact and Release Management". It is a DevSecOps and Supply Chain Security tool to organize product releases with their metadata, including various Bills of Materials (SBOMs / xBOMs).

ReARM stores xBOMs on OCI-compatible storage via Reliza's [Rebom](https://github.com/relizaio/rebom) project.

ReARM is developed by [Reliza](https://reliza.io). Read about project history on [our blog](https://rearmhq.com/blog/rearm-history).

## ReARM Product Status Information
- ReARM itself - Public Beta
- Transparency Exchange API (TEA) implementation - in the process of implementation of TEA Beta 1 - Public Alpha available on ReARM Demo Instance (see below for details)

## Motivation
ReARM is a xBOM management system that allows organizations to maintain compliance within the framework of [European CRA regulations](https://eur-lex.europa.eu/eli/reg/2024/2847/oj) as well as US Executive Orders [14028](https://www.federalregister.gov/documents/2021/05/17/2021-10460/improving-the-nations-cybersecurity) and [14144](https://www.federalregister.gov/documents/2025/01/17/2025-01470/strengthening-and-promoting-innovation-in-the-nations-cybersecurity).

While highlighting regulatory pressure, we strive to make sure that ReARM bears minimum or no overhead on developers and more so provides real value in terms of managing technology releases and their metadata. In other words, our goal is creating a product that would be useful for developers and managers, while also solving the compliance problem.

## Capabilities
1. Storage and retrieval of SBOMs / xBOMs
2. Maintaining representation of organization's products and components with branches and releases
3. Automated creation of release versions and changelogs between releases
4. Close integration with [Dependency-Track](https://dependencytrack.org/) for analysis of vulnerabilities and policies, including license policy
5. Integration with various CI systems (including GitHub Actions, Azure DevOps, Jenkins, GitLab CI and others) to produce BOMs and upload them with other release metadata to ReARM
6. Release approval logic (Commercial Edition only)
7. Marketing release workflow (Commercial Edition only)

## Releases
ReARM follows [Trunk Based Development (TBD)](https://trunkbaseddevelopment.com/) methodology. This means that maintainers commit directly to the main branch where possible. Consumers should use releases marked as SHIPPED (or GENERAL_AVAILABILITY).

Refer to [ReARM Community Edition Product](https://demo.rearmhq.com/productsOfOrg/00000000-0000-0000-0000-000000000001/eab985e6-f4f1-42db-95ba-5d9d1d695348) on [ReARM Demo Instance](https://demo.rearmhq.com) for the constantly updated list of ReARM releases. SHIPPED releases are also published under [GitHub Releases](https://github.com/relizaio/rearm/releases) in this repository.

Helm chart and docker compose files tagged to a release contain a list of specific images that correspond to particular release.

## Transparency Exchange API
Creators of ReARM are part of [active contributors](https://github.com/CycloneDX/transparency-exchange-api/blob/main/contributors.md) of [Transparency Exchange API](https://github.com/CycloneDX/transparency-exchange-api/) (TEA) that aims to build standard API for exchanging supply chain artifacts and intelligence.

A lot of core ReARM ideas are shared as a part of the TEA workgroup with permissive Open Source licensing.

ReARM currently supports most of TEA Beta 1 functionality and we are working to expand and improve support. Refer to usage and status documentation on ReARM Documentation Website [here](https://docs.rearmhq.com/tea/index.html).

## Project links
- Documentation: https://docs.rearmhq.com
- ReARM CLI: https://github.com/relizaio/rearm-cli
- Project ReARM web-site: https://rearmhq.com
- Public Demo: https://demo.rearmhq.com
- Reliza Website: https://reliza.io
- Reliza Versioning: https://github.com/relizaio/versioning

## Related Projects
- ReARM CLI: https://github.com/relizaio/rearm-cli - CLI tool to interact with ReARM for humans and automation bots
- BEAR (BOM Enrichment and Augmentation by Reliza): https://github.com/relizaio/bear - BEAR may be used for BOM enrichment before uploading to ReARM
- Reliza Versioning library: https://github.com/relizaio/versioning - Versioning library is used for automated versioning increments, comparisons and change logs handled by ReARM

## Public Demo
Public Demo is available at https://demo.rearmhq.com. When you register for the demo, you get read-only account for the Demo organization and can browse several existing demo Components, Products, Releases. You may then also create your own organization and try organizing storage for your own release metadata (Documentation for this coming soon). Note, that while your data on Public Demo is private, it is subject to deletion at any time and without notice.

## Installation, Tutorials, Documentation
Refer to the project documentation: https://docs.rearmhq.com

This documentation is built using vitepress and checked in to this repository under `documentation_site`. If you spot any issues or would like to propose additions, please open issues or Pull Requests accordingly.

## Developing ReARM

### Generate TEA-overlay from TEA OpenAPI spec
OpenAPI Spec can be found here - https://github.com/CycloneDX/transparency-exchange-api/blob/main/spec/openapi.yaml

And then copied into tea-spec/ directory in this repository as well.

To generate initial tea-server spring service, run

```
npx @openapitools/openapi-generator-cli generate -i tea-spec/openapi.yaml -g spring -o tea-server/ --additional-properties=useSpringBoot3=true
```

Then rename model files to Tea prefix (from ReARM repo root directory):

```bash
./scripts/rename_with_tea.sh ./tea-server/src/main/java/org/openapitools/model
```



### Local Development

1. Create a docker container for database:
```
docker run --name rearm-postgres -d -p 5440:5432 -e POSTGRES_PASSWORD=relizaPass postgres:16
```

This part will be continued (TODO).

## Contact Reliza
Easiest way to contact us is through our [Discord Community](https://devopscommunity.org/) - find #rearm channel there and either post in this channel or send a direct message to maintainers.

You can also send us an email to [info@reliza.io](mailto:info@reliza.io).
