/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.model.dto.IntegrationWebDto;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Release is essentially a collection of release entries
 * all concrete details about items in the release are found inside release entries
 * Release itself contains mainly meta data: what is project or product for which release is built,
 * what's overall version, who is responsible
 * @author pavel
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class IntegrationData extends RelizaDataParent implements RelizaObject {
	
	public enum IntegrationType {
		DEPENDENCYTRACK,
		GITHUB,
		GITLAB,
		JENKINS,
		ADO,
		SLACK,
		MSTEAMS;
		
		private IntegrationType () {}
	}
	
	@JsonProperty
	private UUID uuid;
	@JsonProperty(CommonVariables.IDENTIFIER_FIELD) // essentially allows to have several integrations of same type per org, i.e. this could be slack channel
	private String identifier;
	@JsonProperty
	private Boolean isEnabled = true;
	@JsonProperty
	private UUID component;
	@JsonProperty
	private UUID org;
	@JsonProperty
	private String schedule; // schedule id in github actions
	@JsonProperty(CommonVariables.TYPE_FIELD)
	private IntegrationType type;
	@JsonProperty(CommonVariables.URI_FIELD)
	private URI uri;
	@JsonProperty
	private URI frontendUri;
	@JsonProperty(CommonVariables.SECRET_FIELD)
	private String secret;
	@JsonProperty
	private UUID vcs;
	@JsonProperty
	private Map<String,Object> parameters = new LinkedHashMap<>(); // custom parameters for providers
	@JsonProperty
	private String note;

	public IntegrationWebDto toWebDto() {
		return IntegrationWebDto.builder()
				.uuid(this.uuid)
				.identifier(this.identifier)
				.isEnabled(this.isEnabled)
				.org(this.org)
				.type(this.type)
				.note(this.note)
				.build();
	}
	
	public static IntegrationData integrationDataFactory(String identifier, UUID org, IntegrationType type, URI uri, String secret, URI frontendUri) {
		IntegrationData id = new IntegrationData();
		id.setIdentifier(identifier);
		id.setOrg(org);
		id.setType(type);
		id.setUri(uri);
		id.setFrontendUri(frontendUri);
		id.setSecret(secret);
		return id;
	}	
	
	public static IntegrationData dataFromRecord (Integration i) {
		if (i.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("Integration schema version is " + i.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = i.getRecordData();
		IntegrationData id = Utils.OM.convertValue(recordData, IntegrationData.class);
		id.setUuid(i.getUuid());
		return id;
	}
	
	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}
}
