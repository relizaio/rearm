/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.dto;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.ReleaseMetricsDto;

/**
 * Data records for the changelog feature.
 * These are pure data carriers used across the changelog data flow:
 * backend services, GraphQL schema, and frontend types.
 */
public final class ChangelogRecords {

	private ChangelogRecords() {} // prevent instantiation

	/**
	 * Sealed interface for component changelogs.
	 * Permits only NONE and AGGREGATED mode implementations.
	 */
	public sealed interface ComponentChangelog permits NoneChangelog, AggregatedChangelog {
		UUID componentUuid();
		String componentName();
		UUID orgUuid();
		ReleaseInfo firstRelease();
		ReleaseInfo lastRelease();
	}
	
	/**
	 * Changelog for NONE mode (per-release breakdown).
	 */
	public record NoneChangelog(
		UUID componentUuid,
		String componentName,
		UUID orgUuid,
		ReleaseInfo firstRelease,
		ReleaseInfo lastRelease,
		List<NoneBranchChanges> branches
	) implements ComponentChangelog {}
	
	/**
	 * Changelog for AGGREGATED mode (component-level summary).
	 */
	public record AggregatedChangelog(
		UUID componentUuid,
		String componentName,
		UUID orgUuid,
		ReleaseInfo firstRelease,
		ReleaseInfo lastRelease,
		List<AggregatedBranchChanges> branches,
		SbomChangesWithAttribution sbomChanges,
		FindingChangesWithAttribution findingChanges
	) implements ComponentChangelog {}
	
	/**
	 * Sealed interface for organization changelogs.
	 * Permits only NONE and AGGREGATED mode implementations.
	 */
	public sealed interface OrganizationChangelog permits NoneOrganizationChangelog, AggregatedOrganizationChangelog {
		UUID orgUuid();
		ZonedDateTime dateFrom();
		ZonedDateTime dateTo();
		List<ComponentChangelog> components();
	}
	
	/**
	 * Organization changelog for NONE mode (per-component, per-release breakdown).
	 * Each component contains its own NoneChangelog.
	 */
	public record NoneOrganizationChangelog(
		UUID orgUuid,
		ZonedDateTime dateFrom,
		ZonedDateTime dateTo,
		List<ComponentChangelog> components
	) implements OrganizationChangelog {}
	
	/**
	 * Organization changelog for AGGREGATED mode (organization-wide summary).
	 * Each component contains its own AggregatedChangelog, plus org-wide SBOM/Finding aggregation.
	 */
	public record AggregatedOrganizationChangelog(
		UUID orgUuid,
		ZonedDateTime dateFrom,
		ZonedDateTime dateTo,
		List<ComponentChangelog> components,
		SbomChangesWithAttribution sbomChanges,
		FindingChangesWithAttribution findingChanges
	) implements OrganizationChangelog {}
	
	/**
	 * Release metadata.
	 */
	public record ReleaseInfo(
		UUID uuid,
		String version,
		ReleaseLifecycle lifecycle
	) {}
	
	/**
	 * Branch changes for NONE mode (per-release breakdown).
	 */
	public record NoneBranchChanges(
		UUID branchUuid,
		String branchName,
		UUID componentUuid,
		String componentName,
		List<NoneReleaseChanges> releases
	) {}
	
	/**
	 * Branch changes for AGGREGATED mode (commits grouped by type).
	 */
	public record AggregatedBranchChanges(
		UUID branchUuid,
		String branchName,
		UUID componentUuid,
		String componentName,
		List<CommitsByType> commitsByType
	) {}
	
	/**
	 * Commits grouped by change type.
	 */
	public record CommitsByType(
		String changeType,
		List<CodeCommit> commits
	) {}
	
	/**
	 * All changes for a single release (NONE mode).
	 * Embeds code, SBOM, and finding changes in one self-contained record.
	 */
	public record NoneReleaseChanges(
		UUID releaseUuid,
		String version,
		ReleaseLifecycle lifecycle,
		List<CodeCommit> commits,
		ReleaseSbomChanges sbomChanges,
		ReleaseFindingChanges findingChanges
	) {}
	
	/**
	 * Individual code commit.
	 */
	public record CodeCommit(
		String commitId,
		String commitUri,
		String message,
		String author,
		String email,
		String changeType
	) {}
	
	/**
	 * Structured SBOM artifact info for NONE mode display.
	 */
	public record ReleaseSbomArtifact(
		String purl,
		String name,
		String version
	) {}
	
	/**
	 * SBOM changes for a single release (NONE mode).
	 */
	public record ReleaseSbomChanges(
		List<ReleaseSbomArtifact> addedArtifacts,
		List<ReleaseSbomArtifact> removedArtifacts
	) {}
	
	/**
	 * Lightweight vulnerability info for per-release NONE mode display.
	 */
	public record ReleaseVulnerabilityInfo(
		String vulnId,
		String purl,
		String severity,
		Set<ReleaseMetricsDto.VulnerabilityAliasDto> aliases
	) {}
	
	/**
	 * Lightweight violation info for per-release NONE mode display.
	 */
	public record ReleaseViolationInfo(
		String type,
		String purl
	) {}
	
	/**
	 * Lightweight weakness info for per-release NONE mode display.
	 */
	public record ReleaseWeaknessInfo(
		String cweId,
		String severity,
		String ruleId,
		String location
	) {}
	
	/**
	 * Finding changes for a single release (NONE mode).
	 */
	public record ReleaseFindingChanges(
		int appearedCount,
		int resolvedCount,
		List<ReleaseVulnerabilityInfo> appearedVulnerabilities,
		List<ReleaseVulnerabilityInfo> resolvedVulnerabilities,
		List<ReleaseViolationInfo> appearedViolations,
		List<ReleaseViolationInfo> resolvedViolations,
		List<ReleaseWeaknessInfo> appearedWeaknesses,
		List<ReleaseWeaknessInfo> resolvedWeaknesses
	) {}
	
	/**
	 * Commit record for mapping source code entry data.
	 */
	public record CommitRecord(
		String commitUri,
		String commitId,
		String commitMessage,
		String commitAuthor,
		String commitEmail
	) {}
}
