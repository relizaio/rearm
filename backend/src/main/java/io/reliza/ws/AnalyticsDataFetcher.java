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
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.WhoUpdated;
import io.reliza.exceptions.RelizaException;
import io.reliza.service.AnalyticsMetricsService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.ReleaseService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class AnalyticsDataFetcher {
	
	@Autowired
	private AnalyticsMetricsService analyticsMetricsService;
	
	@Autowired
	private ReleaseService releaseService;
	
	@Autowired
	private UserService userService;
	
	@Autowired
	private AuthorizationService authorizationService;
	
	@Autowired
	private BranchService branchService;
	
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
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "vulnerabilitiesViolationsOverTimeByBranch")
	public List<VulnViolationsChartDto> vulnerabilitiesViolationsOverTimeByBranch (
			@InputArgument("branchUuid") UUID branchUuid,
			@InputArgument("dateFrom") ZonedDateTime dateFrom,
			@InputArgument("dateTo") ZonedDateTime dateTo) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = branchService.getBranchData(branchUuid)
				.orElseThrow(() -> new RelizaException("Branch not found"))
				.getOrg();
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		return analyticsMetricsService.getVulnViolationByBranchChartData(branchUuid, dateFrom, dateTo);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "findingsPerDay")
	public ReleaseMetricsDto findingsPerDay(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("date") String date) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		return analyticsMetricsService.getFindingsPerDay(orgUuid, date).orElse(null);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "computeAnalyticsMetricsForDate")
	public ReleaseMetricsDto computeAnalyticsMetricsForDate(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("date") String date) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		return analyticsMetricsService.computeAndRecordAnalyticsMetricsForOrgAndDate(orgUuid, date, wu);
	}
}
