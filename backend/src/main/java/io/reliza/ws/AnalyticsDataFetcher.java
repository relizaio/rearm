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
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
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
import io.reliza.service.GetComponentService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.ReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.oss.OssAnalyticsMetricsService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class AnalyticsDataFetcher {
	
	@Autowired
	private AnalyticsMetricsService analyticsMetricsService;
	
	@Autowired
	private OssAnalyticsMetricsService ossAnalyticsMetricsService;
	
	@Autowired
	private ReleaseService releaseService;
	
	@Autowired
	private UserService userService;
	
	@Autowired
	private AuthorizationService authorizationService;
	
	@Autowired
	private BranchService branchService;
	
	@Autowired
	private GetComponentService getComponentService;

	@Autowired
	private GetOrganizationService getOrganizationService;
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "mostActiveComponentsOverTime")
	public List<ReleasesPerComponent> getMostActiveComponentsOverTime(DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Map<String, Object> aciInput = dfe.getArgument("activeComponentsInput");
		ActiveComponentsInput aci = Utils.OM.convertValue(aciInput, ActiveComponentsInput.class);
		var od = getOrganizationService.getOrganizationData(aci.organization());
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, aci.organization(), List.of(ro), CallType.READ);
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
		var od = getOrganizationService.getOrganizationData(aci.organization());
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, aci.organization(), List.of(ro), CallType.READ);
		return analyticsMetricsService.analyticsBranchesWithMostRecentReleases(aci.cutOffDate(),
				aci.componentType(), aci.maxComponents(), aci.organization());
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releaseAnalytics")
	public List<VegaDateValue> getReleaseAnalytics(@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("cutOffDate") ZonedDateTime cutOffDate) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		var od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.READ);
		return releaseService.getReleaseCreateOverTimeAnalytics(orgUuid, cutOffDate);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releaseAnalyticsByComponent")
	public List<VegaDateValue> getReleaseAnalyticsByComponent(@InputArgument("componentUuid") UUID componentUuid,
			@InputArgument("cutOffDate") ZonedDateTime cutOffDate) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		ComponentData cd = getComponentService.getComponentData(componentUuid)
				.orElseThrow(() -> new RelizaException("Component not found"));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.COMPONENT, componentUuid, List.of(cd), CallType.READ);
		return releaseService.getReleaseCreateOverTimeAnalyticsByComponent(componentUuid, cutOffDate);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releaseAnalyticsByBranch")
	public List<VegaDateValue> getReleaseAnalyticsByBranch(@InputArgument("branchUuid") UUID branchUuid,
			@InputArgument("cutOffDate") ZonedDateTime cutOffDate) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		BranchData bd = branchService.getBranchData(branchUuid)
				.orElseThrow(() -> new RelizaException("Branch not found"));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.BRANCH, branchUuid, List.of(bd), CallType.READ);
		return releaseService.getReleaseCreateOverTimeAnalyticsByBranch(branchUuid, cutOffDate);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "vulnerabilitiesViolationsOverTime")
	public List<VulnViolationsChartDto> vulnerabilitiesViolationsOverTime (
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("dateFrom") ZonedDateTime dateFrom,
			@InputArgument("dateTo") ZonedDateTime dateTo) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		var od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.READ);
		return analyticsMetricsService.getVulnViolationByOrgChartData(orgUuid, dateFrom, dateTo);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "vulnerabilitiesViolationsOverTimeByComponent")
	public List<VulnViolationsChartDto> vulnerabilitiesViolationsOverTimeByComponent (
			@InputArgument("componentUuid") UUID componentUuid,
			@InputArgument("dateFrom") ZonedDateTime dateFrom,
			@InputArgument("dateTo") ZonedDateTime dateTo) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		ComponentData cd = getComponentService.getComponentData(componentUuid)
				.orElseThrow(() -> new RelizaException("Component not found"));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.COMPONENT, componentUuid, List.of(cd), CallType.READ);
		return analyticsMetricsService.getVulnViolationByComponentChartData(componentUuid, dateFrom, dateTo);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "vulnerabilitiesViolationsOverTimeByBranch")
	public List<VulnViolationsChartDto> vulnerabilitiesViolationsOverTimeByBranch (
			@InputArgument("branchUuid") UUID branchUuid,
			@InputArgument("dateFrom") ZonedDateTime dateFrom,
			@InputArgument("dateTo") ZonedDateTime dateTo) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		BranchData bd = branchService.getBranchData(branchUuid)
				.orElseThrow(() -> new RelizaException("Branch not found"));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.BRANCH, branchUuid, List.of(bd), CallType.READ);
		return analyticsMetricsService.getVulnViolationByBranchChartData(branchUuid, dateFrom, dateTo);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "findingsPerDay")
	public ReleaseMetricsDto findingsPerDay(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("date") String date) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		var od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.READ);
		return analyticsMetricsService.getFindingsPerDay(orgUuid, date).orElse(null);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "findingsPerDayForComponent")
	public ReleaseMetricsDto findingsPerDayForComponent(
			@InputArgument("componentUuid") UUID componentUuid,
			@InputArgument("date") String date) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		ComponentData cd = getComponentService.getComponentData(componentUuid)
				.orElseThrow(() -> new RelizaException("Component not found"));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.COMPONENT, componentUuid, List.of(cd), CallType.READ);
		return analyticsMetricsService.getFindingsPerDayForComponent(componentUuid, date).orElse(null);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "findingsPerDayForBranch")
	public ReleaseMetricsDto findingsPerDayForBranch(
			@InputArgument("branchUuid") UUID branchUuid,
			@InputArgument("date") String date) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		BranchData bd = branchService.getBranchData(branchUuid)
				.orElseThrow(() -> new RelizaException("Branch not found"));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.BRANCH, branchUuid, List.of(bd), CallType.READ);
		return analyticsMetricsService.getFindingsPerDayForBranch(branchUuid, date).orElse(null);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "computeAnalyticsMetricsForDate")
	public ReleaseMetricsDto computeAnalyticsMetricsForDate(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("date") String date) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		var od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		return ossAnalyticsMetricsService.computeAndRecordAnalyticsMetricsForOrgAndDate(orgUuid, date, wu);
	}
}
