/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
		MSTEAMS,
		// Notification-channel destination types folded in from the retired
		// NotificationChannelType (Phase 2b-1). SLACK and MSTEAMS above
		// double as notification destinations; these three are
		// notification-only. The notification dispatch worker rejects
		// non-destination types (DEPENDENCYTRACK/GITHUB/…) at its gate.
		EMAIL,
		WEBHOOK,
		SENTINEL,
		// KEV catalog sources (V54 per-org refactor). CISA_KEV has no
		// credentials (public feed) and is auto-created enabled per org;
		// VULNCHECK_KEV stores the per-org VulnCheck API token in
		// {@link IntegrationData#secret} (encrypted via EncryptionService,
		// same path as DEPENDENCYTRACK). Sync orchestration in
		// KevCatalogSyncService iterates enabled integrations of these
		// two types per org.
		CISA_KEV,
		VULNCHECK_KEV;

		private IntegrationType () {}
	}

	/**
	 * Capabilities a GITHUB integration is wired up to perform. Each is
	 * an assertion of intent the user picks; the outbound dispatch paths
	 * (EXTERNAL_VALIDATION, PR_COMMENT) and inbound webhook intake gate
	 * on the relevant capability rather than on a separate IntegrationType.
	 */
	public enum IntegrationCapability {
		/** Triggers a repository_dispatch / workflow_dispatch in the linked repo. */
		WORKFLOW_DISPATCH,
		/**
		 * Posts check-run conclusions and PR comments back to GitHub via the
		 * App's installation token. Required for the EXTERNAL_VALIDATION /
		 * PR_COMMENT output event types and the PR-level validation dispatch.
		 */
		PR_VALIDATE,
		/**
		 * Receives inbound webhook deliveries from GitHub. Required for a
		 * GITHUB integration to show up in the "create webhook" picker.
		 */
		WEBHOOK;
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
	@JsonProperty
	private List<IntegrationCapability> capabilities; // null/empty = no capabilities asserted
	@JsonProperty
	private String name; // user-facing display name (notification-channel integrations; null for legacy CI types)
	@JsonProperty
	private UUID resourceGroup; // perspective/resource-group scoping (notification-channel integrations)

	@JsonIgnore
	public boolean hasCapability(IntegrationCapability cap) {
		return capabilities != null && capabilities.contains(cap);
	}

	@JsonIgnore
	public Set<IntegrationCapability> getCapabilitySet() {
		return capabilities == null ? Set.of() : Set.copyOf(capabilities);
	}

	public IntegrationWebDto toWebDto() {
		return IntegrationWebDto.builder()
				.uuid(this.uuid)
				.identifier(this.identifier)
				.isEnabled(this.isEnabled)
				.org(this.org)
				.type(this.type)
				.note(this.note)
				.capabilities(this.capabilities)
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
		return resourceGroup;
	}

	/**
	 * The {@link IntegrationType} values that are valid notification-channel
	 * destinations (folded in from the retired {@code NotificationChannelType}
	 * in Phase 2b-1). A notification-channel integration row has a non-null
	 * {@link #name} AND a type in this set; legacy CI / base rows have a null
	 * name. The dispatch worker rejects any type outside this set at its gate.
	 */
	@JsonIgnore
	public static final Set<IntegrationType> NOTIFICATION_DESTINATION_TYPES = Set.of(
			IntegrationType.SLACK,
			IntegrationType.MSTEAMS,
			IntegrationType.EMAIL,
			IntegrationType.WEBHOOK,
			IntegrationType.SENTINEL);

	/**
	 * Map a GraphQL channel-type enum value name onto the {@link IntegrationType}.
	 * The GraphQL layer still speaks the retired {@code NotificationChannelType}
	 * value names — only {@code MS_TEAMS} differs ({@code MS_TEAMS} ↔
	 * {@code MSTEAMS}); the rest are identical both ways.
	 *
	 * @throws IllegalArgumentException if the name maps to no IntegrationType.
	 */
	public static IntegrationType fromChannelTypeName(String channelTypeName) {
		if (channelTypeName == null) {
			throw new IllegalArgumentException("channel type name is null");
		}
		if ("MS_TEAMS".equals(channelTypeName)) {
			return IntegrationType.MSTEAMS;
		}
		return IntegrationType.valueOf(channelTypeName);
	}

	/**
	 * Map an {@link IntegrationType} back to the GraphQL channel-type enum
	 * value name. Inverse of {@link #fromChannelTypeName(String)}:
	 * {@code MSTEAMS} ↔ {@code MS_TEAMS}; the rest are identical.
	 */
	public static String toChannelTypeName(IntegrationType type) {
		if (type == null) return null;
		if (type == IntegrationType.MSTEAMS) {
			return "MS_TEAMS";
		}
		return type.name();
	}
}
