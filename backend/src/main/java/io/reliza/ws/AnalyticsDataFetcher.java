/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
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
import io.reliza.common.Utils;
import io.reliza.model.dto.AnalyticsDtos.ActiveComponentsInput;
import io.reliza.model.dto.AnalyticsDtos.ReleasesPerBranch;
import io.reliza.model.dto.AnalyticsDtos.ReleasesPerComponent;
import io.reliza.model.dto.AnalyticsDtos.VegaDateValue;
import io.reliza.model.dto.AnalyticsDtos.VulnViolationsChartDto;
import io.reliza.service.AnalyticsMetricsService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.ReleaseService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class AnalyticsDataFetcher {
	
	@Autowired
	AnalyticsMetricsService analyticsMetricsService;
	
	@Autowired
	ReleaseService releaseService;
	
	@Autowired
	UserService userService;
	
	@Autowired
	AuthorizationService authorizationService;
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "mostActiveComponentsOverTime")
	public List<ReleasesPerComponent> getMostActiveComponentsOverTime(DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Map<String, Object> aciInput = dfe.getArgument("activeComponentsInput");
		ActiveComponentsInput aci = Utils.OM.convertValue(aciInput, ActiveComponentsInput.class);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), aci.organization(), CallType.READ);
		return analyticsMetricsService.analyticsComponentsWithMostRecentReleases(aci.cutOffDate(),
				aci.componentType(), aci.maxComponents(), aci.organization());
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "mostActiveBranchesOverTime")
	public List<ReleasesPerBranch> getMostActiveBranchessOverTime(DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Map<String, Object> aciInput = dfe.getArgument("activeComponentsInput");
		ActiveComponentsInput aci = Utils.OM.convertValue(aciInput, ActiveComponentsInput.class);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), aci.organization(), CallType.READ);
		return analyticsMetricsService.analyticsBranchesWithMostRecentReleases(aci.cutOffDate(),
				aci.componentType(), aci.maxComponents(), aci.organization());
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releaseAnalytics")
	public List<VegaDateValue> getReleaseAnalytics(@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("cutOffDate") ZonedDateTime cutOffDate) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		return releaseService.getReleaseCreateOverTimeAnalytics(orgUuid, cutOffDate);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "vulnerabilitiesViolationsOverTime")
	public List<VulnViolationsChartDto> vulnerabilitiesViolationsOverTime (
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("dateFrom") ZonedDateTime dateFrom,
			@InputArgument("dateTo") ZonedDateTime dateTo) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		return analyticsMetricsService.getVulnViolationByOrgChartData(orgUuid, dateFrom, dateTo);
	}
}
