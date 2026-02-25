/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/


package io.reliza.model;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ProgrammaticType;
import lombok.Data;

@Data
public final class WhoUpdated {
	
	@JsonProperty(CommonVariables.CREATED_TYPE_FIELD)
	private ProgrammaticType createdType;
	
	@JsonProperty(CommonVariables.LAST_UPDATED_BY_FIELD)
	private UUID lastUpdatedBy;
	
	@JsonProperty(CommonVariables.LAST_UPDATED_IP_ADDRESS_FIELD)
	private String lastUpdatedIp;
	
	private WhoUpdated () {}
	
	// TODO: lock setters
	private static final WhoUpdated whoUpdatedTest;
	private static final WhoUpdated whoUpdatedAuto;
	
	static {
		whoUpdatedTest = new WhoUpdated();
		whoUpdatedTest.setCreatedType(ProgrammaticType.TEST);
		whoUpdatedAuto = new WhoUpdated();
		whoUpdatedAuto.setCreatedType(ProgrammaticType.AUTO);
	}
	
	public static RelizaEntity injectWhoUpdatedData(RelizaEntity re, WhoUpdated wu) {
		Map<String, Object> recordData = re.getRecordData();
		recordData.put(CommonVariables.LAST_UPDATED_BY_FIELD, wu.getLastUpdatedBy());
		recordData.put(CommonVariables.CREATED_TYPE_FIELD, wu.getCreatedType());
		recordData.put(CommonVariables.LAST_UPDATED_IP_ADDRESS_FIELD, wu.getLastUpdatedIp());
		re.setRecordData(recordData);
		return re;
	}
	
	public static WhoUpdated getWhoUpdated(ProgrammaticType pt, UUID userId, String ipAddr) {
		WhoUpdated wu = new WhoUpdated();
		wu.setCreatedType(pt);
		wu.setLastUpdatedBy(userId);
		wu.setLastUpdatedIp(ipAddr);
		return wu;
	}
	
	public static WhoUpdated getWhoUpdated(UserData ud) {
		WhoUpdated wu = new WhoUpdated();
		wu.setCreatedType(ProgrammaticType.MANUAL);
		wu.setLastUpdatedBy(ud.getUuid());
		wu.setLastUpdatedIp(ud.getRemoteIp());
		return wu;
	}
	
	public static WhoUpdated getApiWhoUpdated(UUID apiKeyId, String ipAddr) {
		WhoUpdated wu = new WhoUpdated();
		wu.setCreatedType(ProgrammaticType.API);
		wu.setLastUpdatedBy(apiKeyId);
		wu.setLastUpdatedIp(ipAddr);
		return wu;
	}
	
	public static WhoUpdated getTestWhoUpdated() {
		return whoUpdatedTest;
	}
	
	public static WhoUpdated getAutoWhoUpdated() {
		return whoUpdatedAuto;
	}

}
