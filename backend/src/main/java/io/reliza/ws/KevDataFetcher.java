/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.dto.KevRecordDetails;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.KevAssertionService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

/**
 * GraphQL surface for KEV data.
 *
 * <p>Child resolvers serve {@code knownExploited} on the three vulnerability
 * read types ({@code Vulnerability}, {@code ReleaseVulnerabilityInfo},
 * {@code VulnerabilityWithAttribution}) from the value stamped on the
 * parent record by {@code ReleaseMetricsComputeService}. In V54 KEV moved
 * to per-org assertions and the parent assembly paths don't carry an
 * orgUuid into the field resolver, so a per-request fresh-probe is no
 * longer feasible at this layer; metrics compute runs immediately after
 * a per-org KEV sync via {@code KevCatalogSyncService.triggerKevReGate},
 * so the stamped value stays close to fresh.
 *
 * <p>{@code kevRecordDetails(orgUuid, cveId)} (org-authorized, READ)
 * returns the per-source assertion detail for the UI's CVE-details
 * surface, scoped to one org; null when no source in that org has ever
 * listed the CVE.
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

	@DgsData(parentType = "Vulnerability", field = "knownExploited")
	public Boolean knownExploitedOfVulnerability(DgsDataFetchingEnvironment dfe) {
		VulnerabilityDto vuln = dfe.getSource();
		return Boolean.TRUE.equals(vuln.knownExploited());
	}

	// V54 NOTE: ReleaseVulnerabilityInfo (changelog) and
	// VulnerabilityWithAttribution (attribution) don't carry a stamped
	// knownExploited; a fresh per-org probe at this layer would need
	// orgUuid threaded through their assembly path. Followup ticket:
	// thread orgUuid into the changelog + attribution assemblers and
	// stamp knownExploited at construction (same pattern as
	// VulnerabilityDto in ReleaseMetricsComputeService.recomputeKevFlags).
	// For now, those surfaces show knownExploited = false; the primary
	// release-findings + vulnerability-analysis surfaces (which DO go
	// through metrics compute) remain correct.
	@DgsData(parentType = "ReleaseVulnerabilityInfo", field = "knownExploited")
	public Boolean knownExploitedOfReleaseVulnerabilityInfo(DgsDataFetchingEnvironment dfe) {
		return Boolean.FALSE;
	}

	@DgsData(parentType = "VulnerabilityWithAttribution", field = "knownExploited")
	public Boolean knownExploitedOfVulnerabilityWithAttribution(DgsDataFetchingEnvironment dfe) {
		return Boolean.FALSE;
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
		return kevAssertionService.getKevDetails(orgUuid, cveId).orElse(null);
	}
}
