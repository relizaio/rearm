/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
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
import io.reliza.common.CommonVariables.PerspectiveType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalyticsMetricsData extends RelizaDataParent implements RelizaObject {

	public static record PerspectiveWithHash(UUID perspectiveUuid, String perspectiveHash, PerspectiveType perspectiveType) {}

	@JsonProperty
	private UUID uuid;
	@JsonProperty
	private UUID org;
	@JsonProperty
	private UUID perspective;
	@JsonProperty
	private String perspectiveHash;
	@JsonProperty
	private PerspectiveType perspectiveType;
	@JsonProperty
	private String dateKey;
	@JsonProperty
	private ReleaseMetricsDto metrics;
	

	public static AnalyticsMetricsData analyticsMetricsDataFactory(UUID org, ReleaseMetricsDto metrics, ZonedDateTime createdDate) {
		return analyticsMetricsDataFactory(org, null, null, null, metrics, createdDate);
	}
	
	public static AnalyticsMetricsData analyticsMetricsDataFactory(UUID org, UUID perspective, ReleaseMetricsDto metrics, ZonedDateTime createdDate) {
		return analyticsMetricsDataFactory(org, perspective, null, null, metrics, createdDate);
	}
	
	public static AnalyticsMetricsData analyticsMetricsDataFactory(UUID org, UUID perspective, String perspectiveHash, PerspectiveType perspectiveType, ReleaseMetricsDto metrics, ZonedDateTime createdDate) {
		AnalyticsMetricsData amd = new AnalyticsMetricsData();
		amd.setOrg(org);
		amd.setPerspective(perspective);
		amd.setPerspectiveHash(perspectiveHash);
		amd.setPerspectiveType(perspectiveType);
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
		// Parse dateKey (format: YYYY-MM-DD) and convert to ZonedDateTime
		java.time.LocalDate localDate = java.time.LocalDate.parse(this.dateKey, DateTimeFormatter.ISO_DATE);
		ZonedDateTime dateFromKey = localDate.atStartOfDay(this.createdDate.getZone());
		return this.metrics.convertToChartDto(dateFromKey);
	}

	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}
}
