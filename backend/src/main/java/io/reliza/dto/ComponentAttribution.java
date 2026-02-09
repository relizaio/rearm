package io.reliza.dto;

import java.util.UUID;

/**
 * Tracks which component/release contributed a specific change.
 * Uses release-level granularity for precise provenance tracking.
 * 
 * This record is used in changelog attribution to show exactly which release
 * introduced or resolved a finding or SBOM change.
 */
public record ComponentAttribution(
    UUID componentUuid,
    String componentName,
    UUID releaseUuid,           // Specific release where change occurred
    String releaseVersion,      // Version of the release
    UUID branchUuid,            // Branch this release belongs to (nullable)
    String branchName,          // Branch name (nullable if branch deleted)
    String comparedToVersion    // Previous release version for context (nullable)
) {}
