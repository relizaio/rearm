/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.Utils;
import io.reliza.model.dto.AnalyticsDtos.VulnViolationsChartDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalyticsMetricsData extends RelizaDataParent implements RelizaObject {

	@JsonProperty
	private UUID uuid;
	@JsonProperty
	private UUID org;
	@JsonProperty
	private String dateKey;
	@JsonProperty
	private ReleaseMetricsDto metrics;
	

	public static AnalyticsMetricsData analyticsMetricsDataFactory(UUID org, ReleaseMetricsDto metrics, ZonedDateTime createdDate) {
		AnalyticsMetricsData amd = new AnalyticsMetricsData();
		amd.setOrg(org);
		amd.setMetrics(metrics);
		amd.setCreatedDate(createdDate);
		amd.setDateKey(obtainAnalyticsDateKey(createdDate));
		return amd;
	}
	
	public static String obtainAnalyticsDateKey(ZonedDateTime date) {
		var ldt = date.toLocalDate();
		return ldt.format(DateTimeFormatter.ISO_DATE);
	}
	
	public static AnalyticsMetricsData dataFromRecord (AnalyticsMetrics am) {
		if (am.getSchemaVersion() != 0) {
			throw new IllegalStateException("AnalyticsMetrics schema version is " + am.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = am.getRecordData();
		AnalyticsMetricsData amd = Utils.OM.convertValue(recordData, AnalyticsMetricsData.class);
		amd.setCreatedDate(am.getCreatedDate());
		amd.setUuid(am.getUuid());
		return amd;
	}
	
	public List<VulnViolationsChartDto> convertToChartDto () {
		List<VulnViolationsChartDto> vulnViolDtos = new LinkedList<>();
		vulnViolDtos.add(new VulnViolationsChartDto(this.createdDate, this.metrics.getCritical(), "Critical Vulnerabilities"));
		vulnViolDtos.add(new VulnViolationsChartDto(this.createdDate, this.metrics.getHigh(), "High Vulnerabilities"));
		vulnViolDtos.add(new VulnViolationsChartDto(this.createdDate, this.metrics.getMedium(), "Medium Vulnerabilities"));
		vulnViolDtos.add(new VulnViolationsChartDto(this.createdDate, this.metrics.getLow(), "Low Vulnerabilities"));
		vulnViolDtos.add(new VulnViolationsChartDto(this.createdDate, this.metrics.getUnassigned(), "Unassigned Vulnerabilities"));
		vulnViolDtos.add(new VulnViolationsChartDto(this.createdDate, this.metrics.getPolicyViolationsLicenseTotal(), "License Violations"));
		vulnViolDtos.add(new VulnViolationsChartDto(this.createdDate, this.metrics.getPolicyViolationsOperationalTotal(), "Operational Violations"));
		vulnViolDtos.add(new VulnViolationsChartDto(this.createdDate, this.metrics.getPolicyViolationsSecurityTotal(), "Security Violations"));
		return vulnViolDtos;
	}

	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}
}
