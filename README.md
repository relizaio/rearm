 [![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
 ![Build Status](https://github.com/relizaio/rearm/actions/workflows/github_actions.yml/badge.svg?branch=main)

# ReARM - SBOM / xBOM Repository and Release Manager - Community Edition

https://github.com/user-attachments/assets/a315c5b2-7116-4b4a-bb4b-28e77d3ae9b3

## About
ReARM is an abbreviation for "Reliza's Awesome Release Manager". It is a DevSecOps and Supply Chain Security tool to organize product releases with their metadata, including various Bills of Materials (SBOMs / xBOMs).

ReARM stores xBOMs on OCI-compatible storage via Reliza's [Rebom](https://github.com/relizaio/rebom) project.

ReARM is developed by [Reliza](https://reliza.io).

## Motivation
ReARM is a xBOM management system that allows organizations to maintain compliance within the framework of [European CRA regulations](https://eur-lex.europa.eu/eli/reg/2024/2847/oj) as well as US Executive Orders [14028](https://www.federalregister.gov/documents/2021/05/17/2021-10460/improving-the-nations-cybersecurity) and [14144](https://www.federalregister.gov/documents/2025/01/17/2025-01470/strengthening-and-promoting-innovation-in-the-nations-cybersecurity).

While highlighting regulatory pressure, we strive to make sure that ReARM bears minimum or no overhead on developers and more so provides real value in terms of managing technology releases and their metadata. In other words, our goal is creating a product that would be useful for developers and managers, while also solving the compliance problem.

## Transparency Exchange API
Creators of ReARM are part of active maintainers of [Transparency Exchange API](https://github.com/CycloneDX/transparency-exchange-api/) (TEA) that aims to build standard API for exchanging supply chain artifacts and intelligence.

ReARM will be supporting TEA is an overlay when the standard is ready. Preliminary work to have support for TEA Beta 1 has already started.

## Project links
- Documentation: https://docs.rearmhq.com
- ReARM CLI: https://github.com/relizaio/rearm-cli
- Project ReARM web-site: https://rearmhq.com
- Public Demo: https://demo.rearmhq.com
- Reliza Website: https://reliza.io

## Related Projects
- ReARM CLI: https://github.com/relizaio/rearm-cli - CLI tool to interact with ReARM for humans and automation bots
- Rebom: https://github.com/relizaio/rebom - ReARM is using Rebom as a layer to perform actual storage of certain metadata artifacts
- BEAR (BOM Enrichment and Augmentation by Reliza): https://github.com/relizaio/bear - BEAR may be used for BOM enrichment before uploading to ReARM

## Public Demo
Public Demo is available at https://demo.rearmhq.com. When you register for the demo, you get read-only account for the Demo organization and can browse several existing demo Components, Products, Releases. You may then also create your own organization and try organizing storage for your own release metadata (Documentation for this coming soon). Note, that while your data on Public Demo is private, it is subject to deletion at any time and without notice.

## Installation, Tutorials, Documentation
Refer to the project documentation: https://docs.rearmhq.com

This documentation is built using vitepress and checked in to this repository under `documentation_site`. If you spot any issues or would like to propose additions, please open issues or Pull Requests accordingly.

## Developing ReARM

1. Create a docker container for database:
```
docker run --name rearm-postgres -d -p 5440:5432 -e POSTGRES_PASSWORD=relizaPass postgres:16
```

This part will be continued (TODO).

## Contact Reliza
Easiest way to contact us is through our [Discord Community](https://devopscommunity.org/) - find #rearm channel there and either post in this channel or send a direct message to maintainers.

You can also send us an email to [info@reliza.io](mailto:info@reliza.io).
