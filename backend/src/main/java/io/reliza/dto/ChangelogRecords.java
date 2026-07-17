/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.dto;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.reliza.model.AnalysisState;
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
	 * Wire/string sentinel used when a finding's severity or violation type enum is
	 * absent. Shared by {@code ChangelogRecords} projections and
	 * {@code FindingComparisonService} so the fallback value stays identical across
	 * producers/consumers. This is the {@code String} sentinel, distinct from any
	 * enum constant named {@code UNKNOWN}.
	 */
	public static final String UNKNOWN_SEVERITY = "UNKNOWN";

	public enum ChangeType { ADDED, CHANGED, REMOVED }

	/**
	 * Sealed interface for component changelogs.
	 * Permits NONE, AGGREGATED, and per-product-release (NONE product) implementations.
	 */
	public sealed interface ComponentChangelog permits NoneChangelog, AggregatedChangelog, NoneProductChangelog {
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
		List<NoneBranchChanges> branches,
		// ADDITIVE: re-scan-driven finding changes over time (board task #37).
		// Nullable/empty-default keeps existing callers backward compatible.
		List<MetricsRevisionFindingChange> overTimeFindingChanges,
		// ADDITIVE (board task #38, phase 3): the effective lower bound the over-time read started at
		// when the requested window was clamped to the retention horizon; null when not clamped.
		ZonedDateTime overTimeFindingChangesSince
	) implements ComponentChangelog {
		public NoneChangelog(UUID componentUuid, String componentName, UUID orgUuid,
				ReleaseInfo firstRelease, ReleaseInfo lastRelease, List<NoneBranchChanges> branches) {
			this(componentUuid, componentName, orgUuid, firstRelease, lastRelease, branches, List.of(), null);
		}
	}

	/**
	 * Changelog for NONE mode scoped to a PRODUCT.
	 * Groups child component changes per product release rather than as a flat
	 * branch list, so each product release in the range is a self-contained unit.
	 */
	public record NoneProductChangelog(
		UUID componentUuid,
		String componentName,
		UUID orgUuid,
		ReleaseInfo firstRelease,
		ReleaseInfo lastRelease,
		List<ProductReleaseChanges> productReleases
	) implements ComponentChangelog {}

	/**
	 * Child component changes introduced by a single product release (NONE mode).
	 * The branches list reuses {@link NoneBranchChanges} and carries the child
	 * component releases that this product release newly pinned.
	 */
	public record ProductReleaseChanges(
		UUID releaseUuid,
		String version,
		ReleaseLifecycle lifecycle,
		ZonedDateTime createdDate,
		List<NoneBranchChanges> branches
	) {}
	
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
		FindingChangesWithAttribution findingChanges,
		// ADDITIVE: re-scan-driven finding changes over time (board task #37).
		List<MetricsRevisionFindingChange> overTimeFindingChanges,
		// ADDITIVE (board task #38, phase 3): retention-horizon clamp lower bound; null when not clamped.
		ZonedDateTime overTimeFindingChangesSince,
		// ADDITIVE (board task #38, phase 3): COMPONENT-scope window posture-diff; null unless the
		// changelogPostureDiffEnabled flag is on and the component posture-diff path ran.
		FindingChangesWithAttribution postureFindingChanges
	) implements ComponentChangelog {
		public AggregatedChangelog(UUID componentUuid, String componentName, UUID orgUuid,
				ReleaseInfo firstRelease, ReleaseInfo lastRelease, List<AggregatedBranchChanges> branches,
				SbomChangesWithAttribution sbomChanges, FindingChangesWithAttribution findingChanges) {
			this(componentUuid, componentName, orgUuid, firstRelease, lastRelease, branches,
				sbomChanges, findingChanges, List.of(), null, null);
		}
		public AggregatedChangelog(UUID componentUuid, String componentName, UUID orgUuid,
				ReleaseInfo firstRelease, ReleaseInfo lastRelease, List<AggregatedBranchChanges> branches,
				SbomChangesWithAttribution sbomChanges, FindingChangesWithAttribution findingChanges,
				List<MetricsRevisionFindingChange> overTimeFindingChanges,
				ZonedDateTime overTimeFindingChangesSince) {
			this(componentUuid, componentName, orgUuid, firstRelease, lastRelease, branches,
				sbomChanges, findingChanges, overTimeFindingChanges, overTimeFindingChangesSince, null);
		}
	}
	
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
		List<ComponentChangelog> components,
		// ADDITIVE: org-wide re-scan-driven finding changes over time (board task #37).
		List<MetricsRevisionFindingChange> overTimeFindingChanges,
		// ADDITIVE (board task #38, phase 3): retention-horizon clamp lower bound; null when not clamped.
		ZonedDateTime overTimeFindingChangesSince
	) implements OrganizationChangelog {
		public NoneOrganizationChangelog(UUID orgUuid, ZonedDateTime dateFrom, ZonedDateTime dateTo,
				List<ComponentChangelog> components) {
			this(orgUuid, dateFrom, dateTo, components, List.of(), null);
		}
	}
	
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
		FindingChangesWithAttribution findingChanges,
		// ADDITIVE: org-wide re-scan-driven finding changes over time (board task #37).
		List<MetricsRevisionFindingChange> overTimeFindingChanges,
		// ADDITIVE (board task #38, phase 3): retention-horizon clamp lower bound; null when not clamped.
		ZonedDateTime overTimeFindingChangesSince
	) implements OrganizationChangelog {
		public AggregatedOrganizationChangelog(UUID orgUuid, ZonedDateTime dateFrom, ZonedDateTime dateTo,
				List<ComponentChangelog> components, SbomChangesWithAttribution sbomChanges,
				FindingChangesWithAttribution findingChanges) {
			this(orgUuid, dateFrom, dateTo, components, sbomChanges, findingChanges, List.of(), null);
		}
	}
	
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
		List<NoneReleaseChanges> releases,
		ChangeType changeType
	) {}
	
	/**
	 * Branch changes for AGGREGATED mode (commits grouped by type).
	 */
	public record AggregatedBranchChanges(
		UUID branchUuid,
		String branchName,
		UUID componentUuid,
		String componentName,
		UUID firstReleaseUuid,
		String firstVersion,
		UUID lastReleaseUuid,
		String lastVersion,
		List<CommitsByType> commitsByType,
		ChangeType changeType
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
		ReleaseFindingChanges findingChanges,
		ZonedDateTime createdDate
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
		Set<ReleaseMetricsDto.VulnerabilityAliasDto> aliases,
		AnalysisState analysisState
	) {
		/**
		 * Single source of truth for projecting a {@link ReleaseMetricsDto.VulnerabilityDto}
		 * onto the lightweight display record. Shared by {@code ChangeLogService} (release-pair
		 * diffs) and {@code FindingComparisonService} (re-scan over-time changes) so the two
		 * call sites cannot drift on the severity null-guard.
		 */
		public static ReleaseVulnerabilityInfo from(ReleaseMetricsDto.VulnerabilityDto v) {
			return new ReleaseVulnerabilityInfo(v.vulnId(), v.purl(),
				v.severity() != null ? v.severity().name() : null, v.aliases(),
				v.analysisState());
		}
	}

	/**
	 * Lightweight violation info for per-release NONE mode display.
	 */
	public record ReleaseViolationInfo(
		String type,
		String purl,
		AnalysisState analysisState
	) {
		/**
		 * Single source of truth for projecting a {@link ReleaseMetricsDto.ViolationDto}
		 * onto the lightweight display record. Shared by {@code ChangeLogService} and
		 * {@code FindingComparisonService}; preserves the {@link #UNKNOWN_SEVERITY} type fallback.
		 */
		public static ReleaseViolationInfo from(ReleaseMetricsDto.ViolationDto v) {
			return new ReleaseViolationInfo(
				v.type() != null ? v.type().name() : UNKNOWN_SEVERITY, v.purl(),
				v.analysisState());
		}
	}

	/**
	 * Lightweight weakness info for per-release NONE mode display.
	 */
	public record ReleaseWeaknessInfo(
		String cweId,
		String severity,
		String ruleId,
		String location,
		AnalysisState analysisState
	) {
		/**
		 * Single source of truth for projecting a {@link ReleaseMetricsDto.WeaknessDto}
		 * onto the lightweight display record. Shared by {@code ChangeLogService} and
		 * {@code FindingComparisonService} so the severity null-guard cannot drift.
		 */
		public static ReleaseWeaknessInfo from(ReleaseMetricsDto.WeaknessDto w) {
			return new ReleaseWeaknessInfo(w.cweId(),
				w.severity() != null ? w.severity().name() : null,
				w.ruleId(), w.location(),
				w.analysisState());
		}
	}
	
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
	 * Kind of finding change detected between two consecutive metrics snapshots of the
	 * same release (re-scan driven), independent of any new release.
	 *
	 * <p>APPEARED / RESOLVED: finding key present in only the newer / only the older snapshot.
	 * SEVERITY_INCREASED / SEVERITY_DECREASED: same key in both snapshots, newer severity is more /
	 * less severe. KEV_ADDED / KEV_REMOVED: same key in both snapshots, KEV flag went false/null -&gt;
	 * true / true -&gt; false/null. The DECREASED / REMOVED counterparts make the event log a COMPLETE
	 * bidirectional change record, so an endpoint's posture can be reverse-reconstructed from the
	 * release's current metrics + the in-window events without reading {@code metrics_audit}.
	 */
	public enum FindingChangeKind {
		APPEARED, RESOLVED, SEVERITY_INCREASED, SEVERITY_DECREASED, KEV_ADDED, KEV_REMOVED
	}

	/**
	 * Version of the {@link FindingChangeKind} vocabulary the emit/backfill diff produces. BUMP THIS
	 * whenever a new kind is added that the posture-diff reverse-replay depends on, so already-seeded orgs
	 * are NOT certified for the new vocabulary until their history is re-diffed with it (the max-revision
	 * coverage skip is blind to newly-emittable kinds at covered revisions). The per-org watermark stores
	 * the version it was seeded at; {@code ChangeLogService.posturePathEnabled} requires the CURRENT
	 * version, and the boot backfill AUTOMATICALLY reseeds any org below it -- so a bump self-heals on the
	 * next deploy with no manual per-org action. v1 = APPEARED/RESOLVED/SEVERITY_INCREASED/KEV_ADDED;
	 * v2 adds the bidirectional SEVERITY_DECREASED/KEV_REMOVED counterparts that reverse-replay inverts.
	 */
	public static final int FINDING_CHANGE_EVENT_VOCAB_VERSION = 2;

	/**
	 * A single re-scan-driven finding change for one release. {@code changeDate} is the moment the
	 * change took effect -- the metrics_audit revision date at which the older snapshot was
	 * overwritten by the newer one -- NOT the release creation date. Exactly one of
	 * {@code vulnerability} / {@code violation} / {@code weakness} is non-null.
	 *
	 * <p>{@code previousSeverity} is populated for SEVERITY_INCREASED and SEVERITY_DECREASED (the older
	 * snapshot's severity, i.e. the pre-change value); null otherwise.
	 */
	public record MetricsRevisionFindingChange(
		ZonedDateTime changeDate,
		FindingChangeKind changeKind,
		UUID releaseUuid,
		String version,
		UUID componentUuid,
		String componentName,
		// ADDITIVE (board task F7b): the producing branch, so the drill-down timeline drawer can disambiguate
		// the many rows that share one component@version (a finding fanned out across a component's branches).
		UUID branchUuid,
		String branchName,
		ReleaseVulnerabilityInfo vulnerability,
		ReleaseViolationInfo violation,
		ReleaseWeaknessInfo weakness,
		String previousSeverity
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
