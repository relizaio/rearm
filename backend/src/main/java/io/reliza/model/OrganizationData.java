/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ApprovalRole;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.model.UserPermission.PermissionType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrganizationData extends RelizaDataParent implements RelizaObject {
	
	public static final String DEFAULT_FEATURE_SET_LABEL = "Feature Set";
	public static final int MAX_TERMINOLOGY_LENGTH = 50;
	
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Terminology {
		@JsonProperty
		private String featureSetLabel = DEFAULT_FEATURE_SET_LABEL;
		
		public static Terminology getDefault() {
			return new Terminology();
		}
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Settings {
		@JsonProperty
		private boolean justificationMandatory = false;
		
		public static Settings getDefault() {
			return new Settings();
		}
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class IgnoreViolation {
		@JsonProperty
		private List<String> licenseViolationRegexIgnore = new LinkedList<>();
		@JsonProperty
		private List<String> securityViolationRegexIgnore = new LinkedList<>();
		@JsonProperty
		private List<String> operationalViolationRegexIgnore = new LinkedList<>();
	}
	
	public static class InvitedObject {
		@JsonProperty(CommonVariables.SECRET_FIELD)
		private String secret;
		@JsonProperty(CommonVariables.TYPE_FIELD)
		private PermissionType type; // read only, read write
		@JsonProperty(CommonVariables.EMAIL_FIELD)
		private String email;
		@JsonProperty(CommonVariables.WHO_INVITED_FIELD)
		private UUID whoInvited;
		@JsonProperty
		private ZonedDateTime challengeExpiry;
		
		public PermissionType getType () {
			return type;
		}
		
		public String getEmail () {
			return email;
		}
		
		public UUID getWhoInvited () {
			return whoInvited;
		}
		
		public ZonedDateTime getChallengeExpiry() {
			return challengeExpiry;
		}
	}


	@JsonProperty
	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.INVITEES_FIELD)
	private Set<InvitedObject> invitees = new LinkedHashSet<>();
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private StatusEnum status;
	@JsonProperty
	private List<ApprovalRole> approvalRoles = new LinkedList<>();
	@JsonProperty
	private Terminology terminology;
	@JsonProperty
	private IgnoreViolation ignoreViolation;
	@JsonProperty
	private Settings settings;


	public void removeInvitee(String email, UUID whoInvited){
		boolean found = false;
		Iterator<InvitedObject> invIter = invitees.iterator();
		while (!found && invIter.hasNext()) {
			InvitedObject curInv = invIter.next();
			if (email.equalsIgnoreCase(curInv.email)) {
				invitees.remove(curInv);
				found = true;
			}
		}
	}

	public void addInvitee (String secret, PermissionType type, String email, UUID whoInvited) {
		// check if this email (key) is already registered - if so, simply update secret and type
		boolean found = false;
		Iterator<InvitedObject> invIter = invitees.iterator();
		while (!found && invIter.hasNext()) {
			InvitedObject curInv = invIter.next();
			if (email.equalsIgnoreCase(curInv.email)) {
				curInv.secret = secret;
				curInv.type = type;
				curInv.whoInvited = whoInvited;
				curInv.challengeExpiry = ZonedDateTime.now().plusHours(48);
				found = true;
			}
		}
		// otherwise, create new invitee
		if (!found) {
			InvitedObject invObj = new InvitedObject();
			invObj.secret = secret;
			invObj.type = type;
			invObj.email = email;
			invObj.whoInvited = whoInvited;
			invObj.challengeExpiry = ZonedDateTime.now().plusHours(48);
			invitees.add(invObj);
		}
	}
	
	/**
	 * Scans existing invitees by secret and if present, returns permission type and email.
	 * And also deletes this invitee object from organization data
	 * @param secret
	 */
	public Optional<InvitedObject> resolveInvitee (String secret) {
		// TODO proper locking
		
		Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
		Optional<InvitedObject> resolvedInvObject = Optional.empty();
		// scan invitees by secret
		Iterator<InvitedObject> invIter = invitees.iterator();
		while (resolvedInvObject.isEmpty() && invIter.hasNext()) {
			InvitedObject curInv = invIter.next();
			if (encoder.matches(secret, curInv.secret)) {
				resolvedInvObject = Optional.of(curInv);
				invIter.remove();
			}
		}
		return resolvedInvObject;
	}
	
	@Override
	@JsonIgnore
	public UUID getOrg() {
		return uuid;
	}
	
	public static OrganizationData orgDataFromDbRecord (Organization org) {
		if (org.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("Organization schema version is " + org.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = org.getRecordData();
		OrganizationData od = Utils.OM.convertValue(recordData, OrganizationData.class);
		od.setCreatedDate(org.getCreatedDate());
		od.setUuid(org.getUuid());
		return od;
	}
	
	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}
	
	/**
	 * Returns terminology with defaults if not set
	 */
	@JsonIgnore
	public Terminology getTerminologyWithDefaults() {
		return terminology != null ? terminology : Terminology.getDefault();
	}
	
	/**
	 * Returns settings with defaults if not set
	 */
	@JsonIgnore
	public Settings getSettingsWithDefaults() {
		return settings != null ? settings : Settings.getDefault();
	}
	
	/**
	 * Pattern for allowed characters in terminology: ASCII letters, numbers, spaces, hyphens, underscores
	 */
	private static final java.util.regex.Pattern ALLOWED_CHARS = 
		java.util.regex.Pattern.compile("[^a-zA-Z0-9\\s\\-_]");
	
	/**
	 * Sanitizes and validates terminology input.
	 * Only allows ASCII letters (a-z, A-Z), numbers, spaces, hyphens, and underscores.
	 * @param input the raw input string
	 * @return sanitized string or null if invalid/empty
	 */
	public static String sanitizeTerminologyInput(String input) {
		if (input == null || input.isBlank()) {
			return null;
		}
		String sanitized = ALLOWED_CHARS.matcher(input.trim()).replaceAll("")
			.replaceAll("\\s+", " "); // normalize multiple spaces to single
		if (sanitized.length() > MAX_TERMINOLOGY_LENGTH) {
			sanitized = sanitized.substring(0, MAX_TERMINOLOGY_LENGTH).trim();
		}
		return sanitized.isBlank() ? null : sanitized;
	}
	
}