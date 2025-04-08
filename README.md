![Build Status](https://github.com/relizaio/rearm/actions/workflows/github_actions.yml/badge.svg?branch=main)

# ReARM - SBOM / xBOM and Release Manager - Community Edition

https://github.com/user-attachments/assets/a315c5b2-7116-4b4a-bb4b-28e77d3ae9b3

## About
ReARM is an abbreviation for "Reliza's Awesome Release Manager". It is a DevSecOps and Supply Chain Security tool to organize product releases with their metadata, including various Bills of Materials (SBOMs / xBOMs).

ReARM stores xBOMs on OCI-compatible storage via Reliza's [Rebom](https://github.com/relizaio/rebom) project.

ReARM is developed by [Reliza](https://reliza.io). 

## Project links
ReARM CLI: https://github.com/relizaio/rearm-cli

Project ReARM web-site: https://rearmhq.com

Documentation: https://docs.rearmhq.com

Public Demo: https://demo.rearmhq.com

## Public Demo
Public Demo is available at https://demo.rearmhq.com. When you register for the demo, you get read-only account for the Demo organization and can browse several existing demo Components, Products, Releases. You may then also create your own organization and try organizing storage for your own release metadata (Documentation for this coming soon). Note, that while your data on Public Demo is private, it is subject to deletion at any time and without notice.

## Developing ReARM

1. Create a docker container for database:
```
docker run --name rearm-postgres -d -p 5440:5432 -e POSTGRES_PASSWORD=relizaPass postgres:16
```

This part will be continued (TODO).

## Contact Reliza
Easiest way to contact us is through our [Discord Community](https://devopscommunity.org/) - find #rearm channel there and either post in this channel or send a direct message to maintainers.

You can also send us an email to [info@reliza.io](mailto:info@reliza.io).
