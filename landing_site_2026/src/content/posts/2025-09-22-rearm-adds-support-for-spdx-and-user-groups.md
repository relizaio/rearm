---
title: "New ReARM Release: SPDX and User Groups"
date: "2025-09-22"
---

We’re pleased to announce a new major release of ReARM CE (v25.09.38) and ReARM Pro (v25.09.31).

This release includes the following highlights:

1. SPDX BOM Parsing
   - ReARM now supports parsing SPDX JSON 2.2 and 2.3 BOM files, enabling integration with SPDX-based pipelines.
   - ReARM converts SPDX BOMs to CycloneDX behind the scenes, so you can continue benefiting from our Dependency-Track integration and aggregation logic.

2. User Groups
   - ReARM now supports user groups for better access control in enterprise environments.
   - User groups let you configure permissions for multiple users at once, streamlining user management.

3. Transparency Exchange API Updates
   - ReARM now supports the latest TEA Beta specification, including the Product–Component data model.

4. UI Improvements
   - Release details opened in modal dialogs and tabs in Organization Settings are URL-addressable, so browser back/forward navigation works as expected.

5. Switch from Bitnami PostgreSQL to Reliza PostgreSQL
   - For ReARM CE users, as OSS Bitnami PostgreSQL is no longer supported, we are switching to compatible Reliza PostgreSQL image that is maintained by Reliza. This is a drop-in replacement.

Upgrade Guidance

- For ReARM Pro users, Reliza will automatically schedule the upgrade to the latest version according to your upgrade preferences.
- For ReARM CE users, please upgrade manually to the latest version. Refer to the upgrade instructions in the [ReARM installation documentation](https://docs.rearmhq.com/installation/#installation-via-helm-chart).

For more details about the ReARM CE release, see [the release entry](https://demo.rearmhq.com/release/show/5be9385a-d043-4638-9cc7-f6fc73c73d9c) on the ReARM Public Demo instance.