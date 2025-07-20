/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthorizationStatus;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.InstallationType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.common.Utils;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.InvitedObject;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.ws.RelizaConfigProps;

@Service
public class OrganizationService {

	@Autowired
    private AuditService auditService;
	
	@Autowired
    private EmailService emailService;
	
	@Autowired
	@Lazy
    private UserService userService;
	
	@Autowired
    private EncryptionService encryptionService;
	
	private final OrganizationRepository repository;
	
	private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);
	
	private RelizaConfigProps relizaConfigProps;
	
	@Autowired
    public void setProps(RelizaConfigProps relizaConfigProps) {
        this.relizaConfigProps = relizaConfigProps;
    }
	
	OrganizationService(OrganizationRepository repository) {
	    this.repository = repository;
	}
	
	public Optional<Organization> getOrganization (@NonNull UUID uuid) {
		return repository.findById(uuid);
	}
	
	public Optional<OrganizationData> getOrganizationData (@NonNull UUID uuid) {
		Optional<OrganizationData> orgData = Optional.empty();
		Optional<Organization> org = getOrganization(uuid);
		if (org.isPresent()) {
			orgData = Optional
							.of(
								OrganizationData
									.orgDataFromDbRecord(org
										.get()
								));
		}
		return orgData;
	}
	
	@Transactional
	private Organization saveOrganization (Organization org, Map<String,Object> recordData, WhoUpdated wu) {
		if (null == recordData || recordData.isEmpty() ||  StringUtils.isEmpty((String) recordData.get(CommonVariables.NAME_FIELD))) {
			throw new IllegalStateException("Organization must have name in record data");
		}
		Optional<Organization> oo = getOrganization(org.getUuid());
		if (oo.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.ORGANIZATIONS, org);
			org.setRevision(org.getRevision() + 1);
			org.setLastUpdatedDate(ZonedDateTime.now());
		}
		org.setRecordData(recordData);
		org = (Organization) WhoUpdated.injectWhoUpdatedData(org, wu);
		return repository.save(org);
	}

	public void saveOrg(Organization org){
		repository.save(org);
	}
	
	private List<Organization> listAllOrganizations() {
		return repository.findAll();
	}
	
	public List<OrganizationData> listAllOrganizationData() {
		List<Organization> orgList = listAllOrganizations();
		return new ArrayList<>(transformOrgToOrgData(orgList));
	}
	
	private List<Organization> listOrganizationsById(Iterable<UUID> ids) {
		return (List<Organization>) repository.findAllById(ids);
	}
	
	public Collection<OrganizationData> listMyOrganizationData(UserData ud) {
		var orgList = ud.getOrganizations();
		if (ud.isGlobalAdmin() && InstallationType.OSS != userService.getInstallationType()) {
			orgList.add(CommonVariables.EXTERNAL_PROJ_ORG_UUID);
		}
		return transformOrgToOrgData(listOrganizationsById(orgList));
	}
	
	private Collection<OrganizationData> transformOrgToOrgData (Collection<Organization> orgs) {
		return orgs.stream()
				.map(OrganizationData::orgDataFromDbRecord)
				.filter(od -> od.getStatus()== null || !od.getStatus().equals(StatusEnum.ARCHIVED))
				.collect(Collectors.toList());
	}

	public Optional<OrganizationData> getOrganizationDataFromEncryptedUUID(String encryptedUuidStr){
		String uuidStr = encryptionService.decrypt(encryptedUuidStr);
		UUID orgUUID = UUID.fromString(uuidStr);
		return getOrganizationData(orgUUID);
	}

	@Transactional
	public void removeNonAdminUsersFromOrg(UUID orgUuid, WhoUpdated wu) {
		List<UserData> users = userService.listUserDataByOrg(orgUuid);
		users.stream()
			.filter(ud -> !UserData.isOrgAdmin(ud, orgUuid))
			.forEach(u -> {
				Boolean thisUserRemoved	= userService.removeUserFromOrg(orgUuid, u.getUuid(), wu);
				if(!thisUserRemoved){
					log.error("Could not remove user " + u.getUuid().toString() + " from organization " + orgUuid.toString());
				}
			});
	}
	
	public Map<String,BigInteger> getNumericAnalytics(UUID orgUuid) {
		return repository.getNumericAnalytics(orgUuid.toString());
	}
	
	protected AuthorizationStatus isUserAuthorizedOrgWide(UserData ud, UUID org, CallType ct) {
		AuthorizationStatus as = AuthorizationStatus.AUTHORIZED;
		boolean authorized = false;
		try {
			Optional<OrganizationData> od = Optional.empty();
			if (null != org) od = getOrganizationData(org);
			
			authorized = (od.isPresent() && null != ud && ud.isGlobalAdmin());
			boolean acceptedAndVerified = (null != ud && ud.isPoliciesAccepted() && ud.isPrimaryEmailVerified() &&
					(StringUtils.isNotEmpty(ud.getGithubId()) || StringUtils.isNotEmpty(ud.getOauthId())));
			// special case for init call
			if (!authorized && od.isPresent() && ct == CallType.INIT && acceptedAndVerified) {
					authorized = true;
			}
			if (!authorized && od.isPresent() && acceptedAndVerified) {
				// for now, all permissions are only resolved on org level - TODO - allow by component
				Optional<UserPermission> oup = ud.getPermission(org, PermissionScope.ORGANIZATION, org);
				switch (ct) {
				case ADMIN:
					if (oup.isPresent() && oup.get().getType() == PermissionType.ADMIN) {
						authorized = true;
					}
					break;
				case WRITE:
					if (oup.isPresent() && oup.get().getType().ordinal() >= PermissionType.READ_WRITE.ordinal()) {
						authorized = true;
					}
					break;
				case READ:
					if (CommonVariables.EXTERNAL_PROJ_ORG_UUID.equals(org) ||
							(oup.isPresent() && oup.get().getType().ordinal() >= PermissionType.READ_ONLY.ordinal())) {
						authorized = true;
					}
					break;
				case GLOBAL_ADMIN:
				case INIT:
					authorized = true;
					break;
				}
			}
		} catch (Exception e) {
			log.warn("Exception when trying to authorize user, deem as not authorized", e);
			authorized = false;
		}
		if (!authorized) {
			as = AuthorizationStatus.FORBIDDEN;
		}
		return as;
	}
	
	public boolean isUserAuthorizedOrgWide(UserData ud, UUID org, HttpServletResponse response, CallType ct) {
		AuthorizationStatus as = isUserAuthorizedOrgWide(ud, org, ct);
		boolean authorized = (as == AuthorizationStatus.AUTHORIZED);
		if (!authorized) {
			try {
				if (!response.isCommitted()) response.sendError(HttpStatus.FORBIDDEN.value(), "You do not have permissions to this resource");
			} catch (IOException e) {
				log.error("IO error when sending response", e);
				// re-throw
				throw new IllegalStateException("IO error when sending error response");
			}
		}
		return authorized;
	}
	
	/**
	 * Adds email to the list of invitees for organization
	 * Email must be pre-validated before passing to this method
	 * Then sends actual email to invitee with full secret
	 * @param od - OrganizationData, not null
	 * @param userEmail
	 * @param type - PermissionType
	 * @param wu - WhoUpdated
	 * @return modified OrganizationData with new invitee or updated previous invitee record
	 */
	@Transactional
	public OrganizationData inviteUserToOrganization (UUID org, String userEmail, PermissionType type, WhoUpdated wu) {
		// we generate secret first, then store hash in the database
		OrganizationData od = null;
		try {
			od = getOrganizationData(org).get();

			StringBuilder keyBuilder = new StringBuilder();
			for (int i=0; i<2; i++) {
				keyBuilder.append(KeyGenerators.string().generateKey());
			}
			String key = keyBuilder.toString();
			// encrypt org uuid and attach to the key
			String orgUuidStr = od.getUuid().toString();
			key = encryptionService.encrypt(orgUuidStr) + CommonVariables.JOIN_SECRET_SEPARATOR + key;
			String urlSafeKey = URLEncoder.encode(key, StandardCharsets.UTF_8.toString());
			Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
			String secret = encoder.encode(urlSafeKey);
			od.addInvitee(secret, type, userEmail, wu.getLastUpdatedBy());
			Organization o = saveOrganization(getOrganization(od.getUuid()).get(), Utils.dataToRecord(od), wu);
			String orgName = od.getName();
			String emailSubject = "You are invited to join organization " + orgName + " on ReARM";
			String contentStr = "Please click <a clicktracking=off href=\"" + relizaConfigProps.getBaseuri() + "/joinOrganization/" 
					+ urlSafeKey + "\">the link </a> to join organization. The link is valid for 48 hours. Note that by clicking the link you are also accepting ReARM cookie and privacy policies.";
			emailService.sendEmail(List.of(userEmail), emailSubject, "text/html", contentStr);

			var wud = userService.getUserData(wu.getLastUpdatedBy()).get();

			String admingEmailSub = "New user invited to join the organizaiton " + orgName + " on ReARM";
			String adminEmailContent = "<a clicktracking=off href=\"mailto:" + wud.getEmail() + "\">" + wud.getName() 
			+ "</a> invited a new user with the email: " + userEmail + " to join the organization "+ orgName 
			+ " on ReARM with permission: " + type.toString() + "."; 
			var adminEmails = userService.listOrgAdminUsersDataByOrg(od.getUuid()).stream().map(admin -> admin.getEmail()).toList();
			emailService.sendEmail(adminEmails, admingEmailSub, "text/html", adminEmailContent);
			od = OrganizationData.orgDataFromDbRecord(o);
		} catch (Exception e) {
			log.error("Exception when inviting user to organization", e);
			throw new RuntimeException("could not invite user to organization");
		}
		return od;
	}
	
	@Transactional
	public Optional<UserData> joinUserToOrganization (String secret, UserData ud) throws RelizaException {
		Optional<UserData> oud = Optional.empty();
		// resolve org uuid from secret
		String[] splitSecret = secret.split(CommonVariables.JOIN_SECRET_SEPARATOR);
		String orgUuidStr = encryptionService.decrypt(splitSecret[0]);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		Optional<Organization> oorg = getOrganization(orgUuid);
		if (oorg.isPresent()) {
			Organization org = oorg.get();
			OrganizationData od = OrganizationData.orgDataFromDbRecord(org);
			Optional<InvitedObject> oio = od.resolveInvitee(secret);
			if (oio.isPresent()) {
				if (ZonedDateTime.now().isAfter(oio.get().getChallengeExpiry())) {
					throw new RelizaException("Invitation expired");
				}
				if (!(oio.get().getEmail().equalsIgnoreCase(ud.getEmail()))) {
					throw new RelizaException("Please log in with invited account or ask to resend invitation to your primary account email");
				}
				UserData whoInvitedUserData = userService.getUserData(oio.get().getWhoInvited()).get();
				ud = userService.injectNewOrgDetailsToUser(ud, oio.get(), org.getUuid(), WhoUpdated.getWhoUpdated(whoInvitedUserData));
				// now need to save organization data to persist that invitee object was consumed
				// set modifier to the invited user this time, not to the one who invited
				saveOrganization(org, Utils.dataToRecord(od), WhoUpdated.getWhoUpdated(ud));
				oud = Optional.of(ud);

				//send email to admins about user joining the organization
				String adminEmailSub = "New user joined the organizaiton " + od.getName() + " on ReARM";
				String adminEmailContent = "A new user with the email " + oio.get().getEmail() + 
				" joined the organization "+ od.getName() 
				+ " on ReARM with permission: " + oio.get().getType().toString() + ".";
				List<String> adminEmails = userService.listOrgAdminUsersDataByOrg(od.getUuid())
						.stream().map(admin -> admin.getEmail()).toList();
				emailService.sendEmail(adminEmails, adminEmailSub, "text/html", adminEmailContent);
			}
		}
		return oud;
	}
	
	/**
	 * Removes email from the list of invitees for organization
	 * Email must be pre-validated before passing to this method
	 * @param org - organization UUID, not null
	 * @param userEmail
	 * @param wu - WhoUpdated
	 * @return modified OrganizationData updated invitee record
	 */
	@Transactional
	public OrganizationData removeInvitedUserFromOrganization (@NonNull UUID org, @NonNull String userEmail, @NonNull WhoUpdated wu) {
		try {
			OrganizationData od = getOrganizationData(org).get();
			od.removeInvitee(userEmail, wu.getLastUpdatedBy());
			Organization o = saveOrganization(getOrganization(od.getUuid()).get(), Utils.dataToRecord(od), wu);
			od = OrganizationData.orgDataFromDbRecord(o);
			return od;
		} catch (Exception e) {
			log.error("Exception when removing invited user from organization", e);
			throw new RuntimeException("could not remove user from organization");
		}
	}


	public Boolean isBomDiffAlertEnabled(UUID org) {
		return true;
	}
}