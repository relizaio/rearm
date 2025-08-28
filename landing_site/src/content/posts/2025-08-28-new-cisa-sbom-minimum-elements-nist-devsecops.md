---
title: "New CISA SBOM Minimum Elements and NIST DevSecOps Documents Soliciting Comments"
date: "2025-08-28"
---

CISA has proposed a major update over [2021 NTIA SBOM Minimum Elements](https://www.ntia.gov/files/ntia/publications/sbom_minimum_elements_report.pdf), that is [2025 Minimum Elements for a Software Bill of Materials (SBOM)](https://www.cisa.gov/sites/default/files/2025-08/2025_CISA_SBOM_Minimum_Elements.pdf).

Currently, this is a draft publication soliciting comments. Community organizations, including OWASP and OpenSSF are working on the coordinated repsonse.

Major updates inlcude *Coverage* - where previously SBOMs were required to provide top-level dependencies only, now transitive dependencies are also expected. There is also a new category of *Known Unknowns* where a new category is expected to list data that is known to be missing from the SBOM. 

Another major update is inclusion of *Component Hash*. This is currently one of contentious points, as there are cases where it is difficult to pinpoint what this hash should be (i.e. for embedded libraries).

The document is also clarifying definitions of several fields, including *SBOM Author* and *Software Producer*.