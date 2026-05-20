/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

/**
 * Jsonb-side data for {@link AgentIdentity}. The credential pairs live
 * in the flat {@code rearm.agent_identity_credentials} table — the
 * identity row itself just carries the org + an optional human label.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentIdentityData extends RelizaDataParent implements RelizaObject {

	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;

	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;

	/**
	 * Optional human-readable label shown in the admin UI when listing
	 * identities. ReARM auto-fills it on first registration with a
	 * placeholder ("Identity for &lt;keyId&gt;") if the caller doesn't
	 * supply one.
	 */
	@JsonProperty
	private String name;

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		return null;
	}

	public static AgentIdentityData dataFromRecord(AgentIdentity ai) {
		if (ai.getSchemaVersion() != 0) {
			throw new IllegalStateException("AgentIdentity schema version is " + ai.getSchemaVersion()
					+ ", which is not currently supported");
		}
		Map<String, Object> recordData = ai.getRecordData();
		AgentIdentityData aid = Utils.OM.convertValue(recordData, AgentIdentityData.class);
		aid.setUuid(ai.getUuid());
		aid.setCreatedDate(ai.getCreatedDate());
		return aid;
	}
}
