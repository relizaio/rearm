/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthPrincipalType;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.UserStatus;
import io.reliza.common.Utils;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.UserPermission.Permissions;
import io.reliza.model.dto.EmailWebDto;
import io.reliza.model.dto.UserWebDto;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UserData extends RelizaDataParent implements AuthPrincipal {
	
	public static class EmailObject {
		@JsonProperty(CommonVariables.EMAIL_FIELD)
		private String email;
		@JsonProperty(CommonVariables.IS_PRIMARY_FIELD)
		private Boolean isPrimary = Boolean.FALSE;
		@JsonProperty(CommonVariables.IS_VERIFIED_FIELD)
		private Boolean isVerified = Boolean.FALSE;
		@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
		private Set<UUID> organizations = new LinkedHashSet<>(); // optional, needed if this is a specific organization email - to show for admin instead of primary
		@JsonProperty
		private String verificationChallenge;
		@JsonProperty
		private ZonedDateTime challengeExpiry;
		@JsonProperty
		private ZonedDateTime verificationTriggered;
		@JsonProperty(CommonVariables.IS_ACCEPT_MARKETING)
		private Boolean isAcceptMarketing;
				
		@JsonIgnore
		public String getEmail () {
			return email;
		}
		
		@JsonIgnore
		public Boolean isPrimary() {
			return isPrimary;
		}
		
		public Set<UUID> getOrganizations () {
			return new LinkedHashSet<>(organizations);
		}
		
		public static EmailWebDto toWebDto (EmailObject eo) {
		    return EmailWebDto.builder()
			    		.email(eo.email)
			    		.isPrimary(eo.isPrimary)
			    		.isVerified(eo.isVerified)
			    		.organizations(eo.organizations)
			    		.isAcceptMarketing(eo.isAcceptMarketing)
			    		.build();
		}
	}
	
	/**
	 * This sub-class is needed to send user details to organization admins
	 * It avoids privacy details about users that may be shared with other orgs
	 * @author pavel
	 *
	 */
	public static class OrgUserData {
		@JsonProperty(CommonVariables.UUID_FIELD)
		private UUID uuid;
		@JsonProperty(CommonVariables.NAME_FIELD)
		private String name;
		@JsonProperty(CommonVariables.EMAIL_FIELD)
		private String email;
		@JsonProperty(CommonVariables.GITHUB_ID_FIELD)
		private String githubId;
		/**
		 * In this context we will only show permissions for this specific org
		 */
		@JsonProperty(CommonVariables.PERMISSIONS_FIELD)
		private Permissions permissions = new Permissions();

		public String getEmail(){
			return email;
		}
	}
	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.EMAIL_FIELD)
	private String email; // primary email
	@JsonProperty(CommonVariables.ALL_EMAILS_FIELD)
	private List<EmailObject> allEmails = new LinkedList<>();
	@JsonProperty(CommonVariables.GITHUB_ID_FIELD)
	private String githubId;
	@JsonProperty(CommonVariables.OAUTH_ID_FIELD)
	private String oauthId;
	@JsonProperty(CommonVariables.OAUTH_TYPE_FIELD)
	private OauthType oauthType;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private Set<UUID> organizations = new HashSet<>(); // user may belong to zero, one or several organizations
	@JsonProperty(CommonVariables.GLOBAL_ADMIN_FIELD)
	private boolean globalAdmin = false;
	@JsonProperty(CommonVariables.POLICIES_ACCEPTED_FIELD)
	private Boolean policiesAccepted = false;
	@JsonProperty(CommonVariables.PERMISSIONS_FIELD)
	private Permissions permissions = new Permissions();
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private UserStatus status = UserStatus.ACTIVE;
	@JsonProperty(CommonVariables.REGISTRY_USER_ID_FIELD)
	private Integer registryUserId;

	@JsonIgnore
	private String remoteIp;

	private UserData () {}
	
	public UUID getUuid() {
		return uuid;
	}
	private void setUuid(UUID uuid) {
		this.uuid = uuid;
	}
	
	public String getName() {
		return name;
	}
	private void setName(String name) {
		this.name = name;
	}
	
	public String getEmail() {
		return email;
	}

	public Integer getRegistryUserId() {
		return this.registryUserId;
	}

	public void setRegistryUser(Integer registryUserId)
	{
		this.registryUserId = registryUserId;
	}
	
	@JsonIgnore
	private void setEmail(String email) {
		this.email = email;
	}
	
	@JsonIgnore
	private void setAllEmails(Collection<EmailObject> updAllEmails) {
		this.allEmails = new LinkedList<>(updAllEmails);
	}
	
	@JsonIgnore
	private List<EmailObject> getAllEmails() {
		return new LinkedList<>(this.allEmails);
	}
	
	/**
	 * Returns email that needs to be returned per specific organization
	 * @param orgUuid - UUID of organization for which we request email
	 * @return
	 */
	public String getEmail (UUID orgUuid) {
		String email;
		var oeo = getEmailObject(orgUuid);
		if (oeo.isPresent()) {
			email = oeo.get().getEmail();
		} else {
			email = this.email;
		}
		return email;
	}
	
	public Optional<EmailObject> getEmailObject (UUID orgUuid) {
		Optional<EmailObject> orgEo = allEmails.stream().filter(eo -> eo.getOrganizations().contains(orgUuid)).findAny();
		if (!orgEo.isEmpty()) {
			orgEo = allEmails.stream().filter(eo -> eo.isPrimary() == true).findAny();
		}
		return orgEo;
	}
	
	private void setEmail(String email, boolean primary, boolean verified, UUID emailOrgUuid, boolean acceptMarketing) {
		this.email = email;
		Optional<EmailObject> existingEo = this.allEmails.stream().filter(eo -> eo.getEmail().equalsIgnoreCase(email)).findAny();
		if (existingEo.isEmpty()) {
			EmailObject eo = new EmailObject();
			eo.email = email;
			eo.isPrimary = Boolean.TRUE;
			eo.isVerified = verified;
			eo.isAcceptMarketing = acceptMarketing;
			if (null != emailOrgUuid) {
				eo.organizations.add(emailOrgUuid);
			}
			this.allEmails.add(eo);
		} else {
			EmailObject eo = existingEo.get();
			eo.isVerified = verified;
			eo.isPrimary = primary;
			eo.isAcceptMarketing = acceptMarketing;
			if (null != emailOrgUuid) {
				eo.organizations.add(emailOrgUuid);
			}
		}
	}
	
	public String getGithubId() {
		return githubId;
	}
	private void setGithubId(String githubId) {
		this.githubId = githubId;
	}
	
	public String getOauthId() {
		return oauthId;
	}
	private void setOauthId(String oauthId) {
		this.oauthId = oauthId;
	}
	
	public OauthType getOauthType() {
		return oauthType;
	}
	private void setOauthType(OauthType oauthType) {
		this.oauthType = oauthType;
	}
	
	public Set<UUID> getOrganizations() {
		return new HashSet<>(organizations);
	}
	
	public boolean isInOrganization(UUID org) {
		return organizations.contains(org);
	}
	
	private void setOrganizations(Collection<UUID> orgs) {
		this.organizations = new HashSet<>(orgs);
	}
	
	public void addOrganization(UUID org) {
		this.organizations.add(org);
	}
	
	public boolean removeOrganization(UUID org) {
		return this.organizations.remove(org);
	}
	
	public boolean isGlobalAdmin() {
		return globalAdmin;
	}

	private void setGlobalAdmin(boolean globalAdmin) {
		this.globalAdmin = globalAdmin;
	}

	public boolean isPoliciesAccepted() {
		return policiesAccepted;
	}

	public void setPoliciesAccepted(boolean policiesAccepted) {
		this.policiesAccepted = policiesAccepted;
	}
	
	public void setPermission (UUID orgUuid, PermissionScope scope, UUID objectUuid, PermissionType type, Collection<String> approvals) {
		permissions.setPermission(orgUuid, scope, objectUuid, type, approvals);
	}

	public void setPermission (UUID orgUuid, PermissionScope scope, UUID objectUuid, PermissionType type,
			Collection<PermissionFunction> functions, Collection<String> approvals) {
		permissions.setPermission(orgUuid, scope, objectUuid, type, functions, approvals);
	}
	
	public boolean revokePermission (UUID orgUuid, PermissionScope scope, UUID objectUuid) {
		return permissions.revokePermission(orgUuid, scope, objectUuid);
	}
	
	public boolean revokeAllOrgPermissions (UUID orgUuid) {
		return permissions.revokeAllOrgPermissions(orgUuid);
	}
	
	public Optional<UserPermission> getPermission (UUID orgUuid, PermissionScope scope, UUID objectUuid) {
		return permissions.getPermission(orgUuid, scope, objectUuid);
	}
	
	public Set<UserPermission> getOrgPermissions (UUID orgUuid) {
		return permissions.getOrgPermissionsAsSet(orgUuid);
	}
	
	private Permissions getPermissions() {
		return permissions;
	}
	
	private void setPermissions(Permissions permissions) {
		this.permissions = permissions;
	}

	public UserStatus getStatus(){
		return status;
	}

	public void setStatus(UserStatus status){
		this.status = status;
	}
	
	public void addToAllEmail(EmailObject eo) {
		this.allEmails.add(eo);
	}
	
	public void setAcceptMarketingOnEmail (String email, boolean acceptMarketing) {
		Optional<EmailObject> oeo = this.allEmails.stream().filter(eo -> eo.getEmail().equalsIgnoreCase(email)).findAny();
		if (oeo.isPresent()) {
			EmailObject eo = oeo.get();
			eo.isAcceptMarketing = acceptMarketing;
		}
	}
	
	public boolean hasEmail (String email) {
		Optional<EmailObject> oeo = this.allEmails.stream().filter(eo -> eo.getEmail().equalsIgnoreCase(email)).findAny();
		return oeo.isPresent();
	}
	
	/**
	 * Sets email verification secret, make primary or accept marketing props
	 * @param email - email to verify, if not set primary email is assumed
	 * @param verificationSecret
	 * @param makePrimary
	 * @param acceptMarketing
	 * @return true - if were able to set verification challenge, false - otherwise
	 */
	public boolean updateEmail (String oldEmail, String verificationSecret, Boolean makePrimary, Boolean acceptMarketing) {
    	if (StringUtils.isEmpty(oldEmail)) {
    	    oldEmail = this.email;
    	}
    	final String email = oldEmail;
    	boolean foundEmail = false;
    	Optional<EmailObject> oeo = this.allEmails.stream().filter(eo -> eo.getEmail().equalsIgnoreCase(email)).findAny();
    	if (oeo.isPresent()) {
    		foundEmail = true;
			if (StringUtils.isNotEmpty(verificationSecret)) {
				oeo.get().verificationChallenge = verificationSecret;
				oeo.get().challengeExpiry = ZonedDateTime.now().plusHours(48);
				oeo.get().verificationTriggered = ZonedDateTime.now();
			}
			if (null != makePrimary && makePrimary) {
				oeo.get().isPrimary = makePrimary;
			}
			if (null != acceptMarketing) {
				oeo.get().isAcceptMarketing = acceptMarketing;
			}
    	}
		return foundEmail;
	}
	
	@JsonIgnore
	public boolean isPrimaryEmailVerified () {
	    boolean isVerified = false;
	    Optional<EmailObject> oeo = this.allEmails.stream().filter(eo -> eo.getEmail().equalsIgnoreCase(email)).findAny();
	    if (oeo.isPresent()) {
	    	isVerified = oeo.get().isVerified;
	    }
	    return isVerified;
	}
	
	@JsonIgnore
	public boolean isVerificationRateLimitAllowed (String email) {
	    boolean isAllowed = false;
	    
		if (StringUtils.isEmpty(email)) email = this.email;
		final String emailToFind = email;
		Optional<EmailObject> oeo = this.allEmails.stream().filter(eo -> eo.getEmail().equalsIgnoreCase(emailToFind)).findAny();
		
		if (StringUtils.isEmpty(oeo.get().verificationChallenge) || (StringUtils.isNotEmpty(oeo.get().verificationChallenge) 
				&& oeo.get().verificationTriggered.isBefore(ZonedDateTime.now().minusMinutes(3)))) {
			isAllowed = true;
		}
		return isAllowed;
	}
	
	public boolean resolveVerifiedEmail (String secret, String newEmail) {
		boolean resolved = false;
		Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
		// scan emails by secret
		Optional<EmailObject> oeo = this.allEmails.stream()
				.filter(eo -> StringUtils.isNotEmpty(eo.verificationChallenge) 
					&& encoder.matches(secret, eo.verificationChallenge) 
					&& ZonedDateTime.now().isBefore(eo.challengeExpiry))
				.findAny();
		if (oeo.isPresent()) {
			oeo.get().email = newEmail;
			oeo.get().isVerified = Boolean.TRUE;
			// if email is primary, modify main email to that
			if (oeo.get().isPrimary) {
				this.email = newEmail;
			}
			// reset challenge
			oeo.get().verificationChallenge = "";
			// replicate
//			eoToInsert = emailEntry.getValue();
//			emailIter.remove();
			resolved = true;
		}

//		if (null != eoToInsert) {
//			this.allEmails.put(newEmail, eoToInsert);
//		}
		return resolved;
	}

	public static UserData updateUserData (UserData ud, Collection<UUID> orgs, String name, String email, 
			String oauthId, OauthType oauthType) {
		return updateUserData(ud, orgs, name, email, false, false, null, oauthId, oauthType);
	}
	
	public static UserData updateUserData (UserData ud, Collection<UUID> orgs, String name, String email, boolean isEmailVerified, 
			boolean acceptEmailMarketing, UUID emailOrg, String oauthId, OauthType oauthType) {
		UserData updatedUserData = new UserData();
		if (null != orgs) {
			updatedUserData.setOrganizations(orgs);
		} else {
			updatedUserData.setOrganizations(ud.getOrganizations());
		}
		
		if (null != name) {
			updatedUserData.setName(name);
		} else {
			updatedUserData.setName(ud.getName());
		}
		
		if (null != email) {
			updatedUserData.setAllEmails(ud.getAllEmails());
			updatedUserData.setEmail(email, true, isEmailVerified, emailOrg, acceptEmailMarketing);
		} else {
			updatedUserData.setEmail(ud.getEmail());
			updatedUserData.setAllEmails(ud.getAllEmails());
		}
		
		if (null != oauthType && null != oauthId) {
			// normalize oauth types
			if (oauthType == OauthType.RELIZA_KEYCLOAK_GITHUB) oauthType = OauthType.GITHUB;
			if (oauthType == OauthType.RELIZA_KEYCLOAK_GOOGLE) oauthType = OauthType.GOOGLE;
			if (oauthType == OauthType.RELIZA_KEYCLOAK_MICROSOFT) oauthType = OauthType.MICROSOFT;
			if (oauthType == OauthType.GITHUB || oauthType == OauthType.RELIZA_KEYCLOAK_GITHUB) {
				updatedUserData.setGithubId(oauthId);
				updatedUserData.setOauthId(ud.getOauthId());
				updatedUserData.setOauthType(ud.getOauthType());
			} else {
				updatedUserData.setGithubId(ud.getGithubId());
				updatedUserData.setOauthId(oauthId);
				updatedUserData.setOauthType(oauthType);
			}
		} else {
			updatedUserData.setGithubId(ud.getGithubId());
			updatedUserData.setOauthId(ud.getOauthId());
			updatedUserData.setOauthType(ud.getOauthType());
		}
		updatedUserData.setPermissions(ud.getPermissions());
		updatedUserData.setUuid(ud.getUuid());
		updatedUserData.setPoliciesAccepted(ud.isPoliciesAccepted());
		updatedUserData.setGlobalAdmin(ud.isGlobalAdmin());
		updatedUserData.setRegistryUser(ud.getRegistryUserId());
		return updatedUserData;
	}
	
	public static UserData userDataFactory(String name, String email, Collection<UUID> orgs, 
			String oauthId, OauthType oauthType, boolean isEmailVerified) {
		UserData ud = new UserData();
		ud.setName(name);
		ud.setEmail(email, true, isEmailVerified, null, false);
		ud.setOrganizations(orgs);
		ud.setOauthId(oauthId);
		ud.setOauthType(oauthType);
		return ud;
	}
	
	public static UserData dataFromRecord (User u) {
		if (u.getSchemaVersion() != 0) { // if schema version is not supported, throw exception
			throw new IllegalStateException("Service schema version is " + u.getSchemaVersion() 
			+ ", which is not currently supported");
		}
		Map<String,Object> recordData = u.getRecordData();
		UserData ud = Utils.OM.convertValue(recordData, UserData.class);
		ud.setUuid(u.getUuid());
		return ud;
	}
	
	/**
	 * This method converts plain UserData into OrgUserData, which is shareable with org admin and does not contain private data from other organizations
	 * It assumes that input UserData contains input OrgUuid, otherwise null result is produced
	 * @param ud, not null must contain a permission for orgUuid
	 * @param orgUuid, not null
	 * @return OrgUserData
	 */
	public static OrgUserData convertUserDataToOrgUserData (UserData ud, UUID orgUuid) {
		OrgUserData oud = null;
		// resolve permissions - only return permissions per org
		Permissions orgPermissions = ud.getPermissions().getOrgPermissions(orgUuid);
		if (orgPermissions.getSize() > 0) {
			oud = new OrgUserData();
			oud.uuid = ud.uuid;
			oud.name = ud.name;
			oud.githubId = ud.githubId;
			oud.permissions = orgPermissions;
			oud.email = ud.getEmail(orgUuid);
		}
		return oud;
	}

	public static boolean isOrgAdmin(UserData ud, UUID orgUuid){
		Permissions orgPermissions = ud.getPermissions().getOrgPermissions(orgUuid);
		return orgPermissions.hasType(PermissionType.ADMIN);
	}
	
	public static UserWebDto toWebDto (UserData ud) {
	    List<EmailWebDto> webDtoAllEmails = ud.allEmails.stream().map(EmailObject::toWebDto).toList();
	    return UserWebDto.builder()
		    		.uuid(ud.getUuid())
		    		.name(ud.getName())
		    		.email(ud.getEmail())
		    		.allEmails(webDtoAllEmails)
		    		.githubId(ud.getGithubId())
		    		.oauthId(ud.getOauthId())
		    		.oauthType(ud.getOauthType())
		    		.organizations(ud.getOrganizations())
		    		.permissions(ud.getPermissions())
		    		.policiesAccepted(ud.isPoliciesAccepted())
		    		.isGlobalAdmin(ud.isGlobalAdmin())
		    		.build();
	}

	@JsonIgnore
	@Override
	public AuthPrincipalType getAuthPrincipalType() {
		return AuthPrincipalType.MANUAL;
	}
	
	@JsonIgnore
	@Override
	public String getRemoteIp() {
		return this.remoteIp;
	}
	
	@JsonIgnore
	public void setRemoteIp(String ip) {
		this.remoteIp = ip;
	}
}
