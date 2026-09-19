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
	/** the human behind a key-authenticated write (CLI browser login); null otherwise */
	@JsonProperty(CommonVariables.LAST_UPDATED_ACTOR_FIELD)
	private UUID actor;
	
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
		injectWhoUpdatedIntoMap(recordData, wu);
		re.setRecordData(recordData);
		return re;
	}

	public static void injectWhoUpdatedIntoMap(Map<String, Object> recordData, WhoUpdated wu) {
		recordData.put(CommonVariables.LAST_UPDATED_BY_FIELD, wu.getLastUpdatedBy());
		recordData.put(CommonVariables.CREATED_TYPE_FIELD, wu.getCreatedType());
		recordData.put(CommonVariables.LAST_UPDATED_IP_ADDRESS_FIELD, wu.getLastUpdatedIp());
		if (wu.getActor() != null) recordData.put(CommonVariables.LAST_UPDATED_ACTOR_FIELD, wu.getActor());
		else recordData.remove(CommonVariables.LAST_UPDATED_ACTOR_FIELD);
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
	/** Same, with the acting user recorded (CLI browser login through a key). */
	public WhoUpdated withActor(UUID actorUser) {
		this.actor = actorUser;
		return this;
	}
	
	public static WhoUpdated getTestWhoUpdated() {
		return whoUpdatedTest;
	}
	
	public static WhoUpdated getAutoWhoUpdated() {
		return whoUpdatedAuto;
	}

}
