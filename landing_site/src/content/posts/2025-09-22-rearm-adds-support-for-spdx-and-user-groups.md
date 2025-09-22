---
title: "New ReARM Release: SPDX and User Groups"
date: "2025-09-22"
---

The ReARM team is pleased to announce the new major release of ReARM CE (v25.09.38) and ReARM Pro (v25.09.31). 

This release contains two key new features:

*1. Parsing Support for SPDX BOM format*

ReARM now supports parsing SPDX JSON 2.2 and 2.3 BOM format files, enabling you to integrate SPDX-based pipelines. You can still benefit from ReARM's Dependency-Track integration and aggregation logic as ReARM will convert SPDX BOMs into CycloneDX behind the scenes.

*2. User Groups*

ReARM now supports user groups for better access control in enterprise environments. User groups allow you to configure permissions for multiple users at once, streamlining the user management.

*3. Transparency Exchange API Updates*

ReARM now supports latest TEA Beta specification, which includes Product-Component data model.

*4. UI improvements*

Opening releases in modal views and tabs in organization settings are now added to the UI routing. This means that browser back and forward navigation will respect accessing these items.

*Upgrade Guidance*

For ReARM Pro users, Reliza will automatically schedule the upgrade to the latest version according to your upgrade preferences.

For ReARM CE users, please upgrade manually to the latest version. Refer to the upgrade instructions in the [ReARM installation documentation](https://docs.rearmhq.com/installation/#installation-via-helm-chart).

For more details about the ReARM CE release, see [its view](https://demo.rearmhq.com/release/show/5be9385a-d043-4638-9cc7-f6fc73c73d9c) on the ReARM Public Demo instance.