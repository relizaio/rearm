/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ProgrammaticType;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

@Data
public class RelizaDataParent implements RelizaData {
	
	@JsonProperty(CommonVariables.CREATED_TYPE_FIELD)
	private ProgrammaticType createdType;
	
	@JsonProperty(CommonVariables.LAST_UPDATED_BY_FIELD)
	private UUID lastUpdatedBy;
	
	@JsonProperty(CommonVariables.LAST_UPDATED_IP_ADDRESS_FIELD)
	private String lastUpdatedIp;
	
	@JsonProperty(CommonVariables.CREATED_DATE_FIELD)
	@Setter(AccessLevel.PROTECTED) ZonedDateTime createdDate = null;
	
	@Setter(AccessLevel.PROTECTED) ZonedDateTime updatedDate = null;

}
