/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.dataloader.BatchLoader;
import org.dataloader.DataLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.DgsDataLoader;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.dto.ChangelogRecords.ReleaseVulnerabilityInfo;
import io.reliza.dto.KevRecordDetails;
import io.reliza.dto.VulnerabilityWithAttribution;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityAliasDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.KevAssertionService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

/**
 * GraphQL surface for KEV data.
 *
 * <p>Child resolvers add {@code knownExploited} to the three vulnerability
 * read types ({@code Vulnerability}, {@code ReleaseVulnerabilityInfo},
 * {@code VulnerabilityWithAttribution}). They ride the parent query's
 * authorization — the parent fetchers already org-scope their results, and
 * KEV listing itself is public data, so no per-field auth check is needed.
 *
 * <p>A release page renders hundreds of vulnerability rows; the
 * {@code kevListedLoader} batch loader collapses them into one
 * {@code IN}-probe against {@code kev_assertions} per request instead of a
 * query per row. Rows with no CVE-shaped id (pure GHSA/OSV findings)
 * short-circuit to {@code false} without touching the loader.
 *
 * <p>{@code kevRecordDetails} (org-authorized, READ) returns the per-source
 * assertion detail for the UI's CVE-details surface; null when no source
 * has ever listed the CVE.
 */
@Slf4j
@DgsComponent
public class KevDataFetcher {

	@Autowired
	private UserService userService;

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	@Autowired
	private KevAssertionService kevAssertionService;

	/**
	 * Batch key: the candidate CVE ids (primary + aliases, already
	 * normalized) of one vulnerability row. A record so identical rows across
	 * the page dedupe in the loader cache.
	 */
	public record KevProbeKey(Set<String> cveIds) {}

	@DgsDataLoader(name = "kevListedLoader")
	public class KevListedBatchLoader implements BatchLoader<KevProbeKey, Boolean> {

		@Autowired
		private KevAssertionService dataLoaderKevAssertionService;

		@Override
		public CompletionStage<List<Boolean>> load(List<KevProbeKey> keys) {
			Set<String> union = new HashSet<>();
			for (KevProbeKey key : keys) union.addAll(key.cveIds());
			Set<String> listed;
			try {
				listed = dataLoaderKevAssertionService.filterKnownExploited(union);
			} catch (Exception e) {
				log.error("Error probing KEV assertions for {} candidate ids", union.size(), e);
				listed = Set.of();
			}
			List<Boolean> results = new ArrayList<>(keys.size());
			for (KevProbeKey key : keys) {
				boolean hit = false;
				for (String id : key.cveIds()) {
					if (listed.contains(id)) {
						hit = true;
						break;
					}
				}
				results.add(hit);
			}
			return CompletableFuture.completedFuture(results);
		}
	}

	@DgsData(parentType = "Vulnerability", field = "knownExploited")
	public CompletableFuture<Boolean> knownExploitedOfVulnerability(DgsDataFetchingEnvironment dfe) {
		VulnerabilityDto vuln = dfe.getSource();
		return resolveKnownExploited(dfe, vuln.vulnId(), vuln.aliases());
	}

	@DgsData(parentType = "ReleaseVulnerabilityInfo", field = "knownExploited")
	public CompletableFuture<Boolean> knownExploitedOfReleaseVulnerabilityInfo(DgsDataFetchingEnvironment dfe) {
		ReleaseVulnerabilityInfo vuln = dfe.getSource();
		return resolveKnownExploited(dfe, vuln.vulnId(), vuln.aliases());
	}

	@DgsData(parentType = "VulnerabilityWithAttribution", field = "knownExploited")
	public CompletableFuture<Boolean> knownExploitedOfVulnerabilityWithAttribution(DgsDataFetchingEnvironment dfe) {
		VulnerabilityWithAttribution vuln = dfe.getSource();
		return resolveKnownExploited(dfe, vuln.vulnId(), vuln.aliases());
	}

	private CompletableFuture<Boolean> resolveKnownExploited(DgsDataFetchingEnvironment dfe,
			String vulnId, Set<VulnerabilityAliasDto> aliases) {
		Set<String> candidates = new LinkedHashSet<>();
		String primary = KevAssertionService.normalizeCveId(vulnId);
		if (primary != null) candidates.add(primary);
		if (aliases != null) {
			for (VulnerabilityAliasDto alias : aliases) {
				String normalized = alias != null ? KevAssertionService.normalizeCveId(alias.aliasId()) : null;
				if (normalized != null) candidates.add(normalized);
			}
		}
		if (candidates.isEmpty()) return CompletableFuture.completedFuture(Boolean.FALSE);
		DataLoader<KevProbeKey, Boolean> dataLoader = dfe.getDataLoader("kevListedLoader");
		return dataLoader.load(new KevProbeKey(candidates));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "kevRecordDetails")
	public KevRecordDetails kevRecordDetails(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("cveId") String cveId) throws RelizaException {
		if (orgUuid == null) throw new RelizaException("orgUuid is required");
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		// singletonList, not List.of: a miss leaves ro null and List.of(null)
		// NPEs into an opaque SERVICE_ERROR instead of a clean denial
		authorizationService.isUserAuthorizedForObjectGraphQL(
				oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION,
				orgUuid, Collections.singletonList(ro), CallType.READ);
		return kevAssertionService.getKevDetails(cveId).orElse(null);
	}
}
