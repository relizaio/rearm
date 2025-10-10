---
title: "ReARM 25.10.40: Major Release with Breaking Changes"
date: "2025-10-10"
---

We're excited to announce a major release of ReARM CE (v25.10.40) and ReARM Pro (v25.10.30).

This release includes significant improvements and important breaking changes that users should be aware of before upgrading.

## Breaking Changes

**Helm Chart Resource Naming Alignment**
- The naming of resources created by the ReARM OSS Helm chart has been aligned with ReARM Pro.
- All prefixes are now simply `rearm-` for consistency across both editions.
- **Important for OSS users**: If you're not using an external database, we strongly recommend:
  1. Backing up your databases prior to upgrade
  2. Clearing PVCs after backup
  3. Restoring databases after the upgrade completes

## New Features

**Artifact Storage Optimization**
- Artifacts are now stored using zstd compression, providing better storage efficiency.
- This is a transparent upgrade - downloading old uncompressed artifacts continues to work seamlessly.
- No action required from users; the system handles both compressed and uncompressed artifacts automatically.

## Bug Fixes and Improvements

**Security Enhancements**
- Dependencies have been updated across the platform
- Security posture has been improved (visit the [release on ReARM Public Demo](https://demo.rearmhq.com/release/show/526920e8-0292-4441-9b87-d3c069c81a2d) for detailed security improvements)

**SBOM Search Improvements**
- SBOM-level search now includes group-level searches
- Multiple fixes related to SBOM-level search functionality

**CycloneDX BOM Parsing**
- Fixed parsing of CycloneDX BOMs with empty `dependsOn` arrays
- This fix was implemented on the ReARM CLI side (ReARM CLI bumped to v25.09.2)

**Performance Optimization**
- Optimized SQL query to compute metrics by artifacts directly
- Improved overall system performance

## Upgrade Guidance

**For ReARM Pro Users**
- Reliza will automatically schedule the upgrade to the latest version according to your configured upgrade preferences.

**For ReARM CE Users**
- Please upgrade manually to the latest version.
- **Critical**: Follow the database backup and PVC clearing steps mentioned above if you're not using an external database.
- Refer to the upgrade instructions in the [ReARM installation documentation](https://docs.rearmhq.com/installation/#installation-via-helm-chart).

For more details about the ReARM CE release, see [the release entry](https://demo.rearmhq.com/release/show/526920e8-0292-4441-9b87-d3c069c81a2d) on the ReARM Public Demo instance.
