/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.model.AnalyticsMetrics;
import io.reliza.model.AnalyticsMetricsData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.repositories.AnalyticsMetricsRepository;
import io.reliza.service.AnalyticsMetricsService;
import io.reliza.service.OrganizationService;

@Service
public class OssAnalyticsMetricsService {

	@Autowired
	private OrganizationService organizationService;
	
	@Autowired
	private AnalyticsMetricsService analyticsMetricsService;
	private final AnalyticsMetricsRepository repository;
	
	OssAnalyticsMetricsService(AnalyticsMetricsRepository repository) {
		this.repository = repository;
	}
	
	public void computeAndRecordAnalyticsMetricsForAllOrgs () {
		// Compute analytics for previous day (yesterday)
		ZonedDateTime yesterday = ZonedDateTime.now().minusDays(1);
		
		var orgs = organizationService.listAllOrganizationData();
		orgs.forEach(org -> {
			if (org.getStatus() != StatusEnum.ARCHIVED && 
					!CommonVariables.EXTERNAL_PROJ_ORG_UUID.equals(org.getUuid())) {
				computeAndRecordAnalyticsMetricsForOrgWithPerspectives(org.getUuid(), yesterday, WhoUpdated.getAutoWhoUpdated());
			}
		});
	}
	
	@Transactional
	public ReleaseMetricsDto computeAndRecordAnalyticsMetricsForOrgAndDate(UUID org, String dateKey, WhoUpdated wu) {
		ZonedDateTime createdDate = java.time.LocalDate.parse(dateKey).atStartOfDay(java.time.ZoneOffset.UTC);
		return computeAndRecordAnalyticsMetricsForOrgWithPerspectives(org, createdDate, wu);
	}
	
	private ReleaseMetricsDto computeAndRecordAnalyticsMetricsForOrgWithPerspectives(UUID org, ZonedDateTime targetDate, WhoUpdated wu) {
		String dateKey = AnalyticsMetricsData.obtainAnalyticsDateKey(targetDate);
		
		// Compute for org-wide
		Optional<AnalyticsMetrics> existingAm = repository.findAnalyticsMetricsByOrgDateKey(org.toString(), dateKey);
		AnalyticsMetrics am = existingAm.orElse(new AnalyticsMetrics());
		AnalyticsMetricsData amd = analyticsMetricsService.computeActualAnalyticsMetricsDataForOrg(org, targetDate);
		save(am, Utils.dataToRecord(amd), wu);
		
		return amd.getMetrics();
	}
	
	public void recomputePerspectiveAnalyticsIfNeeded() {
		// PART OF REARM PRO
	}
	
	@Transactional
	private AnalyticsMetrics save (AnalyticsMetrics am, Map<String,Object> recordData, WhoUpdated wu) {
		if (null == recordData || recordData.isEmpty() || 
				null == recordData.get(CommonVariables.ORGANIZATION_FIELD)) {
			throw new IllegalStateException("Analytics metrics must have organization in record data");
		}
		
		am.setRecordData(recordData);
		am = (AnalyticsMetrics) WhoUpdated.injectWhoUpdatedData(am, wu);
		return repository.save(am);
	}

}
