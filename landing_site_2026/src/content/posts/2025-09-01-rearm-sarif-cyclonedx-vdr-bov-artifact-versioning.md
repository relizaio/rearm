---
title: "New ReARM Release: SARIF, CycloneDX VDR/BOV, and Artifact Versioning"
date: "2025-09-01"
---

The ReARM team is pleased to announce the new major release of ReARM CE (v25.08.115) and ReARM Pro (v25.08.108). 

This release contains two key new features:

*1. Support for SARIF, CycloneDX VDR and BOV formats*

Whenever you upload a SARIF, CycloneDX VDR or BOV files, ReARM will automatically parse them and aggregate included vulnerabilities and weaknesses on the component release and product release levels. This process can and should be fully integrated into your CI/CD pipeline so you can receive continuously updated view of your product security posture.

See live demonstration of this feature by Pavel Shukhman, CEO of Reliza, in the [YouTube video](https://www.youtube.com/watch?v=d_VLFthTn3o).

*2. Support for artifact versioning*

ReARM now supports artifact versioning, enabling you to track and download older versions of each artifact. This ensures that ReARM’s artifact storage remains fully auditable and compliant with applicable regulations.

*Upgrade Guidance*

For ReARM Pro users, Reliza will automatically schedule the upgrade to the latest version according to your upgrade preferences.

For ReARM CE users, please upgrade manually to the latest version. Refer to the upgrade instructions in the [ReARM installation documentation](https://docs.rearmhq.com/installation/#installation-via-helm-chart).

For more details about the ReARM CE release, see [its view](https://demo.rearmhq.com/release/show/69fc5c14-c93d-436d-a14b-95b88fb64466) on the ReARM Public Demo instance.