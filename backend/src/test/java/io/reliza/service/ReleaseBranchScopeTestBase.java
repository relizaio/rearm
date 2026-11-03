/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.Branch;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.ParentRelease;
import io.reliza.model.ReleaseArtifactIndex;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.dto.CveSearchResultDto.BranchWithReleases;
import io.reliza.model.dto.CveSearchResultDto.ComponentWithBranches;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.MetricsVulnerabilityTestBase;
import io.reliza.repositories.ReleaseArtifactIndexRepository;

/**
 * TPF-135 fixtures over the TPF-131 metrics fixtures: second branches of a component (archived or
 * with no status key), CVE-carrying releases with a set creation time, product releases bundling a
 * release, and the artifact-index rows the SBOM-component lookup reads.
 */
public abstract class ReleaseBranchScopeTestBase extends MetricsVulnerabilityTestBase {

	@Autowired protected ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Autowired protected ReleaseArtifactIndexRepository releaseArtifactIndexRepository;
	@Autowired protected SbomComponentService sbomComponentService;

	protected static String cveId() {
		return "CVE-TPF135-" + suffix();
	}

	/** A new FEATURE branch of {@code cb}'s component. */
	protected ComponentAndBranch featureBranch(ComponentAndBranch cb) throws RelizaException {
		Branch b = branchService.createBranch("tpf135-" + suffix(), cb.component().getUuid(), BranchType.FEATURE, WU);
		return new ComponentAndBranch(cb.component(), b);
	}

	/** A new FEATURE branch of {@code cb}'s component, archived through BranchService. */
	protected ComponentAndBranch archivedBranch(ComponentAndBranch cb) throws RelizaException {
		ComponentAndBranch f = featureBranch(cb);
		branchService.archiveBranch(f.branch().getUuid(), WU);
		return f;
	}

	/** A new FEATURE branch of {@code cb}'s component whose record has no status key (a legacy row). */
	protected ComponentAndBranch statuslessBranch(ComponentAndBranch cb) throws RelizaException {
		ComponentAndBranch f = featureBranch(cb);
		jdbc.update("UPDATE rearm.branches SET record_data = record_data - 'status' WHERE uuid = ?", f.branch().getUuid());
		return f;
	}

	/** A release of {@code cb} carrying {@code cve}, created {@code secondsAgo} seconds before now. */
	protected UUID cveRelease(UUID org, ComponentAndBranch cb, String cve, int secondsAgo) throws RelizaException {
		UUID r = release(org, cb, List.of(vuln("pkg:npm/tpf135@1.0.0", cve)), List.of());
		jdbc.update("UPDATE rearm.releases SET created_date = now() - make_interval(secs => ?) WHERE uuid = ?",
				secondsAgo, r);
		return r;
	}

	/** A release of {@code cb} with no findings. */
	protected UUID plainRelease(UUID org, ComponentAndBranch cb) throws RelizaException {
		return release(org, cb, List.of(), List.of());
	}

	/** A release of the product {@code cb} bundling {@code children}. */
	protected UUID productRelease(UUID org, ComponentAndBranch cb, UUID... children) throws RelizaException {
		return ossReleaseService.createRelease(ReleaseDto.builder()
				.component(cb.component().getUuid()).branch(cb.branch().getUuid()).org(org)
				.version("2.0." + Math.abs(UUID.randomUUID().hashCode() % 1_000_000) + "-" + suffix())
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.parentReleases(List.of(children).stream().map(ParentRelease::minimalParentReleaseFactory).toList())
				.build(), WU).getUuid();
	}

	/** Indexes {@code release} as referencing the sbom component {@code sbomComponent} through a canonical BOM of its own. */
	protected void indexRelease(UUID org, UUID release, UUID sbomComponent) {
		UUID canonical = UUID.randomUUID();
		ArtifactSbomComponent asc = new ArtifactSbomComponent();
		asc.setOrg(org);
		asc.setCanonicalArtifactUuid(canonical);
		asc.setSbomComponentUuid(sbomComponent);
		asc.setExactPurl("pkg:npm/tpf135-sbom@1.0.0");
		asc.setParents(new ArrayList<>());
		artifactSbomComponentRepository.save(asc);
		ReleaseArtifactIndex rai = new ReleaseArtifactIndex();
		rai.setOrg(org);
		rai.setReleaseUuid(release);
		rai.setCanonicalArtifactUuid(canonical);
		releaseArtifactIndexRepository.save(rai);
	}

	/** Component uuid to the set of its listed branch uuids. */
	protected static Map<UUID, Set<UUID>> branchesByComponent(List<ComponentWithBranches> components) {
		Map<UUID, Set<UUID>> out = new TreeMap<>();
		components.forEach(c -> out.put(c.uuid(), c.branches().stream().map(BranchWithReleases::uuid)
				.collect(Collectors.toCollection(TreeSet::new))));
		return out;
	}

	/** Every listed release uuid. */
	protected static Set<UUID> releasesOf(List<ComponentWithBranches> components) {
		return components.stream().flatMap(c -> c.branches().stream())
				.flatMap(b -> b.releases().stream()).map(rd -> rd.getUuid())
				.collect(Collectors.toCollection(TreeSet::new));
	}

	protected static BranchWithReleases branchOf(List<ComponentWithBranches> components, UUID branch) {
		return components.stream().flatMap(c -> c.branches().stream()).filter(b -> branch.equals(b.uuid()))
				.findFirst().orElseThrow(() -> new AssertionError("branch " + branch + " not listed: " + components));
	}
}
