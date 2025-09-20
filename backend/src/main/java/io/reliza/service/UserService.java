/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.InstallationType;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.CommonVariables.UserStatus;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.InvitedObject;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserData.OrgUserData;
import io.reliza.model.UserGroupData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.UserWebDto;
import io.reliza.repositories.UserRepository;
import io.reliza.ws.RelizaConfigProps;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class UserService {
	
	@Autowired
    private AuditService auditService;
	
	@Autowired
    private EmailService emailService;
	
	@Autowired
	private EncryptionService encryptionService;
	
	@Autowired
	private ComponentService componentService;
	
	@Autowired
	private SystemInfoService systemInfoService;
	
	@Autowired
	private GetOrganizationService getOrganizationService;
	
	@Autowired
	private UserGroupService userGroupService;

	private static final Logger log = LoggerFactory.getLogger(UserService.class);
			
	private final UserRepository repository;
	
	private RelizaConfigProps relizaConfigProps;
	
	public static final UUID USER_ORG = new UUID(0,1);
		
	@Autowired
    public void setProps(RelizaConfigProps relizaConfigProps) {
        this.relizaConfigProps = relizaConfigProps;
    }
	
	UserService(UserRepository repository) {
	    this.repository = repository;
	}
	
	private Optional<User> getUser (UUID uuid) {
		return repository.findById(uuid);
	}

	private Optional<User> getUserByIdWithOrg (UUID uuid, UUID org) {
		return repository.findUserByIdWithOrganization(uuid, org.toString());
	}
	
	public Optional<UserData> getUserData (UUID uuid) {
		Optional<UserData> userData = Optional.empty();
		Optional<User> user = getUser(uuid);
		if (user.isPresent()) {
			userData = Optional
							.of(
								UserData
									.dataFromRecord(user
										.get()
								));
		}
		return userData;
	}
	
	public Optional<UserData> getUserDataWithOrg (UUID uuid, UUID org) {
		Optional<UserData> userData = Optional.empty();
		Optional<User> user = getUserByIdWithOrg(uuid, org);
		if (user.isPresent()) {
			userData = Optional
							.of(
								UserData
									.dataFromRecord(user
										.get()
								));
		}
		return userData;
	}
	
	private Optional<User> getUserByEmail (String email) {
		return repository.findUserByEmail(email);
	}
	
	public Optional<UserData> getUserDataByEmail (String email) {
		Optional<UserData> userData = Optional.empty();
		Optional<User> user = getUserByEmail(email);
		if (user.isPresent()) {
			userData = Optional
							.of(
								UserData
									.dataFromRecord(user
										.get()
								));
		}
		return userData;
	}
	
	private Optional<User> getUserByOauthIdAndType(OauthType oauthType, String oauthId) {
		return repository.findUserByOauthIdAndType(oauthType.toString(), oauthId);
	}

	public List<User> listUsersByOrg(UUID org) {
		return repository.findUsersByOrg(org.toString());
	}
	
	public List<UserData> listUserDataByOrg(UUID org) {
		List<User> udList = listUsersByOrg(org);
		return transformUserToUserData(udList);
	}

	public List<OrgUserData> listOrgAdminUsersDataByOrg(UUID org){
		List<UserData> udList = listUserDataByOrg(org);
		return udList.stream().filter(ud -> UserData.isOrgAdmin(ud, org)).map(ud -> UserData.convertUserDataToOrgUserData(ud, org)).collect(Collectors.toList());
	}
	
	public List<OrgUserData> listOrgUserDataByOrg (UUID org) {
		List<UserData> udList = listUserDataByOrg(org);
		return udList.stream().map(ud -> UserData.convertUserDataToOrgUserData(ud, org)).collect(Collectors.toList());
	}
	
	private List<UserData> transformUserToUserData (Collection<User> users) {
		return users.stream()
				.map(UserData::dataFromRecord)
				.collect(Collectors.toList());
	}
	
	//TODO: disallow duplicate email registrations
	public User createUser (String name, String email, boolean isVerifiedEmail, Collection<UUID> orgs, 
			String oauthId, OauthType oauthType, WhoUpdated wu) throws RelizaException {
		// make sure this email does not belong to another user already
		checkIfEmailAlreadyRegistered(email, null);
		User u = new User();
		UserData ud = UserData.userDataFactory(name, email, orgs, oauthId, oauthType, isVerifiedEmail);
		Map<String,Object> recordData = Utils.dataToRecord(ud);
		return saveUser(u, recordData, wu);
	}
	
	public User updateUser (UUID uuid, String name, String email, boolean isTrustedEmail, Collection<UUID> orgs, String oauthId, 
			OauthType oauthType, WhoUpdated wu) {
		User u = null;
		// locate user service in db
		Optional<User> uOpt = getUser(uuid);
		if (uOpt.isPresent()) {
			u = uOpt.get();
			// get user data
			UserData uData = UserData.dataFromRecord(u);
			// use factory to update as data
			if (StringUtils.isEmpty(uData.getEmail())) {
				uData = UserData.updateUserData(uData, orgs, name, email, isTrustedEmail, false, null, oauthId, oauthType);
			} else {
				log.error("Unimplemented email updated for user id = " + uuid);
			}
			Map<String,Object> recordData = Utils.dataToRecord(uData);
			u = saveUser(u, recordData, wu);
		}
		return u;
	}
	
	@Transactional
	public User addUserToOrg(UUID userUuid, UUID orgUuid, WhoUpdated wu) {
		User u = null;
		// locate user in db
		Optional<User> uOpt = getUser(userUuid);
		if (uOpt.isPresent()) {
			u = uOpt.get();
			// get user data
			UserData uData = UserData.dataFromRecord(u);
			// list orgs
			Set<UUID> orgs = uData.getOrganizations();
			if (!orgs.contains(orgUuid)) {
				orgs.add(orgUuid);
				uData = UserData.updateUserData(uData, orgs, null, null, null, null);
				Map<String,Object> recordData = Utils.dataToRecord(uData);
				u = saveUser(u, recordData, wu);
			}
		}
		return u;
	}
	
	@Transactional
	public User setUserPermission(UUID userUuid, UUID orgUuid, PermissionScope scope, UUID permissionObject, 
			PermissionType type, Collection<String> approvals, WhoUpdated wu) {
		User u = null;
		// locate user in db
		Optional<User> uOpt = getUser(userUuid);
		if (uOpt.isPresent()) {
			u = uOpt.get();
			// get user data
			UserData uData = UserData.dataFromRecord(u);
			// if the user is already an admin, skip
			boolean skip = false;
			var existingOrgPermissionOpt = uData.getPermission(orgUuid, PermissionScope.ORGANIZATION, orgUuid);
			if (scope != PermissionScope.ORGANIZATION && existingOrgPermissionOpt.isPresent()) {
				if (existingOrgPermissionOpt.get().getType() == PermissionType.ADMIN) {
					skip = true;
				} else if (existingOrgPermissionOpt.get().getType() == PermissionType.READ_WRITE &&
							type != PermissionType.ADMIN) {
					skip = true;
				}
			}
			if (!skip) {
				uData.setPermission(orgUuid, scope, permissionObject, type, approvals);
				// save user
				Map<String,Object> recordData = Utils.dataToRecord(uData);
				u = saveUser(u, recordData, wu);
			}
		}
		return u;
	}
	
	/**
	 * Updates org wide permission with supplied set of approvals
	 * @param userUuid
	 * @param orgUuid
	 * @param approvals
	 * @param wu
	 * @return updated user data if user is present, otherwise null
	 * @throws RelizaException 
	 */
	@Transactional
	public UserData setUserPermissions (UUID userUuid, UUID orgUuid, Collection<String> approvals, 
			Optional<PermissionType> permissionType,
			List<PermissionDto> permissions, WhoUpdated wu) throws RelizaException {
		User u = null;
		UserData ud = null;
		// locate user in db
		Optional<User> uOpt = getUser(userUuid);
		if (uOpt.isPresent()) {
			u = uOpt.get();
			// get user data
			boolean update = false;
			UserData uData = UserData.dataFromRecord(u);
			// retrieve org wide permission
			Optional<UserPermission> orgWidePermission = uData.getPermission(orgUuid, PermissionScope.ORGANIZATION, orgUuid);
			if (orgWidePermission.isPresent()) {
				PermissionType pt = permissionType.isPresent() ? permissionType.get() : orgWidePermission.get().getType();
				uData.setPermission(orgUuid, PermissionScope.ORGANIZATION, orgUuid, pt, approvals);
				update = true;
			} else {
				throw new RelizaException("Cannot set approvals as User is not added to organization.");
			}
			// resolve individual permissions
			if (null != permissions && !permissions.isEmpty()) {
				permissions.forEach(p -> {
					uData.setPermission(orgUuid, p.scope(), p.object(), p.type(), null);
				});
			}
			
			if (update) {
				// save user
				Map<String,Object> recordData = Utils.dataToRecord(uData);
				u = saveUser(u, recordData, wu);
				ud = UserData.dataFromRecord(u);
			}
			
		}
		return ud;
	}

	private User saveUser (User u, Map<String,Object> recordData, WhoUpdated wu) {
		// let's add some validation here
		if (null == recordData || recordData.isEmpty()) {
			throw new IllegalStateException("User must have record data");
		}
		// TODO: add better validation
		Optional<User> ou = getUser(u.getUuid());
		if (ou.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.USERS, u);
			u.setRevision(u.getRevision() + 1);
			u.setLastUpdatedDate(ZonedDateTime.now());
		}
		u.setRecordData(recordData);
		u = (User) WhoUpdated.injectWhoUpdatedData(u, wu);
		return repository.save(u);
	}
	
//	public UserData isUserAuthorized(HttpServletRequest request, final JwtAuthenticationToken auth, RelizaObject ro, 
//			HttpServletResponse response, CallType ct) throws IOException {
//		try {
//			UserData ud = resolveUser(auth);
//			isUserAuthorized(ud, ro, response, ct);
//			return ud;
//		} catch (Exception e) {
//			throw new IOException("not authorized", e);
//		}
//	}
//	
//	public UserData isUserAuthorized(HttpServletRequest request, final JwtAuthenticationToken auth, RelizaObject ro, 
//													HttpServletResponse response) throws IOException {
//		try {
//			UserData ud = resolveUser(auth);
//			isUserAuthorized(ud, ro, response, CallType.WRITE);
//			return ud;
//		} catch (Exception e) {
//			throw new IOException("not authorized", e);
//		}
//
//	}
	
	
	private void verifyThatEmailIsUnclaimedOrBelongsToThisUser(Optional<User> emailUser, Optional<User> cookieUser, Optional<User> oauth2User) {
		if (emailUser.isPresent()) {
			UUID emailUserUuid = emailUser.get().getUuid();
			UUID cookieUserUuid = null;
			if (cookieUser.isPresent()) {
				cookieUserUuid = cookieUser.get().getUuid();
			}
			if (null != cookieUserUuid && !emailUserUuid.equals(cookieUserUuid)) {
				log.warn("Email user = " + emailUserUuid + " tried to use cookie user = " + cookieUserUuid);
				throw new RuntimeException("User auth error");
			} else {
				UUID oauth2UserUuid = null;
				if (oauth2User.isPresent()) {
					oauth2UserUuid = oauth2User.get().getUuid();
				}
				if (null != oauth2UserUuid && !emailUserUuid.equals(oauth2UserUuid)) {
					log.warn("Email user = " + emailUserUuid + " tried to use oauth2 user = " + cookieUserUuid);
					throw new RuntimeException("User auth error");
				}
			}
		}
		
		
	}
	
	/**
	 * Attempts to locate user by jwt
	 * @param auth, can be null in which case optional empty is returned
	 * @return
	 */
	protected Optional<User> getUserByAuth (final JwtAuthenticationToken auth) {
		Optional<User> ou = Optional.empty();
		if (null != auth) {
			OauthType oauthType = OauthType.RELIZA_KEYCLOAK_OWN;
			switch (oauthType) {
			case RELIZA_KEYCLOAK_OWN:
				Jwt creds = (Jwt) auth.getCredentials();
				String sub = creds.getClaimAsString("sub");
				ou = getUserByOauthIdAndType(oauthType, sub);
				break;
			default:
				break;
			}
		}
		return ou;
	}
	
	public Optional<UserData> getUserDataByAuth (final JwtAuthenticationToken auth) {
		Optional<UserData> oud = Optional.empty();
		var ou = getUserByAuth(auth);
		if (ou.isPresent()) {
			oud = Optional.of(UserData.dataFromRecord(ou.get()));
			Jwt creds = (Jwt) auth.getCredentials();
			List<String> groups = null != creds.getClaimAsStringList("groups") ? creds.getClaimAsStringList("groups") : new LinkedList<>();
			synchronizeUserWithGroups(oud.get(), new LinkedHashSet<>(groups));
		}
		return oud;
	}
	
	private void synchronizeUserWithGroups (UserData ud, Set<String> presentSsoGroups) {
		for (var uOrg : ud.getOrganizations()) {
			synchronizeUserWithGroupsPerOrg(ud, presentSsoGroups, uOrg);
		}
		
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	private void synchronizeUserWithGroupsPerOrg (UserData ud, Set<String> presentSsoGroups, UUID uOrg) {
		Set<UserGroupData> orgGroups = userGroupService.getUserGroupsByOrganization(uOrg)
		.stream().filter(x -> {
			return null != x.getConnectedSsoGroups() && !x.getConnectedSsoGroups().isEmpty() 
					&& !Collections.disjoint(x.getConnectedSsoGroups(), presentSsoGroups);
			})
		.collect(Collectors.toSet());

		List<UserGroupData> userGroups = userGroupService.getUserGroupsByUserAndOrg(ud.getUuid(), uOrg);

		// check equality - extract UUID sets from each and compare
		Set<UUID> expectedGroupUuids = orgGroups.stream()
			.map(UserGroupData::getUuid)
			.collect(Collectors.toSet());
		
		Set<UUID> currentGroupUuids = userGroups.stream()
			.map(UserGroupData::getUuid)
			.collect(Collectors.toSet());
		
		boolean areEqual = expectedGroupUuids.equals(currentGroupUuids);
		if (!areEqual) {
			// write lock user
			Optional<User> ou = repository.findByIdWriteLocked(ud.getUuid());
			if (ou.isPresent()) {
				// double check equality
				Set<UserGroupData> orgGroups2 = userGroupService.getUserGroupsByOrganization(uOrg)
				.stream().filter(x -> null != x.getConnectedSsoGroups() && !x.getConnectedSsoGroups().isEmpty() 
					&& !Collections.disjoint(x.getConnectedSsoGroups(), presentSsoGroups))
				.collect(Collectors.toSet());
		
				List<UserGroupData> userGroups2 = userGroupService.getUserGroupsByUserAndOrg(ud.getUuid(), uOrg);
		
				// check equality - extract UUID sets from each and compare
				Set<UUID> expectedGroupUuids2 = orgGroups2.stream()
					.map(UserGroupData::getUuid)
					.collect(Collectors.toSet());
				
				Set<UUID> currentGroupUuids2 = userGroups2.stream()
					.map(UserGroupData::getUuid)
					.collect(Collectors.toSet());
				
				boolean areEqual2 = expectedGroupUuids2.equals(currentGroupUuids2);
				if (!areEqual2) {
					// update user groups
					for (var orgGroup : expectedGroupUuids2) {
						if (!currentGroupUuids2.contains(orgGroup)) {
							userGroupService.addUserToGroup(orgGroup, ud.getUuid(), WhoUpdated.getAutoWhoUpdated());
						}
					}
					for (var orgGroup : currentGroupUuids2) {
						if (!expectedGroupUuids2.contains(orgGroup)) {
							userGroupService.removeUserFromGroup(orgGroup, ud.getUuid(), WhoUpdated.getAutoWhoUpdated());
						}
					}
				}
			}			
		}
	}
	
	public User parsePostSignupData(UUID userUuid, boolean acceptPolicies, boolean acceptMarketing,
		String email) throws UnsupportedEncodingException, RelizaException {
		// only proceed if policies accepted
		boolean proceed = acceptPolicies;
		User u = null;
		UserData ud = null;
		if (proceed) {
			// locate user
			Optional<User> ou = getUser(userUuid);
			if (ou.isPresent()) {
				u = ou.get();
				ud = UserData.dataFromRecord(u);
			} else {
				proceed = false;
			}
		}
		if (proceed) {
			// check if ud has email or email supplied
			if (StringUtils.isEmpty(ud.getEmail()) &&
					StringUtils.isEmpty(email)) {
				proceed = false;
			}
		}
		// if email is supplied and email is present, make sure they match
		if (proceed && !StringUtils.isEmpty(ud.getEmail()) && StringUtils.isNotEmpty(email) && !ud.getEmail().equals(email)) {
			log.error("Signed up user supplied email = " + email + " , but had email = " + ud.getEmail());
			proceed = false;
		}
		if (proceed && StringUtils.isNotEmpty(email)) {
			// check to make sure it's not registered already
			checkIfEmailAlreadyRegistered(email, ud);
			
			// validate email if supplied
			// get and validate email
			EmailValidator ev = EmailValidator.getInstance();
			if (!ev.isValid(email)) {
				proceed = false;
			}
		}
		// all checks passed, do update
		if (proceed) {
			boolean isEmailVerified = false;
			ud.setPoliciesAccepted(acceptPolicies);
			if (StringUtils.isNotEmpty(email)) {
				if (StringUtils.isNotEmpty(ud.getEmail())) { // Note, we already checked for email equality
					// we may have pass-through from Reliza own keycloak here
					isEmailVerified = ud.isPrimaryEmailVerified();
				}
				ud = UserData.updateUserData(ud, null, null, email, isEmailVerified, acceptMarketing, null, null, null);
			} else {
				ud.setAcceptMarketingOnEmail(ud.getEmail(), acceptMarketing);
			}
			

			u = saveUser(u, Utils.dataToRecord(ud), WhoUpdated.getWhoUpdated(ud));
			if (!isEmailVerified) u = requestEmailVerification(u, email, null, null);
		}
		return u;
	}
	
	/**
	 * This method should be used when a new user joins organization following invite link
	 * @param ud
	 * @return
	 */
	@Transactional
	public UserData injectNewOrgDetailsToUser(UserData ud, InvitedObject io, UUID orgUuid, WhoUpdated wu) {
		ud.addOrganization(orgUuid);
		ud.setPermission(orgUuid, PermissionScope.ORGANIZATION, orgUuid, io.getType(), null);
		User u = saveUser(getUser(ud.getUuid()).get(), Utils.dataToRecord(ud), wu);
		return UserData.dataFromRecord(u);
	}

	public void redirectToReliza (HttpServletResponse response) {
		try {
			response.sendRedirect(relizaConfigProps.getBaseuri());
		} catch (IOException e) {
			log.error("IO exception when sending redirect for user joining org to github", e);
		}
	}
	
	public void redirectToEmailVerified (HttpServletResponse response) {
		try {
			response.sendRedirect(relizaConfigProps.getBaseuri() + "/profile?emailVerified=true");
		} catch (IOException e) {
			log.error("IO exception when sending redirect to email verified", e);
		}
	}

	@Transactional
	public boolean removeUserFromOrg(UUID orgUuid, UUID userUuid, WhoUpdated wu) {
		boolean removed = false;
		Optional<User> ou = getUser(userUuid);
		if (ou.isPresent()) {
			UserData ud = UserData.dataFromRecord(ou.get());
			removed = ud.removeOrganization(orgUuid);
			if (removed) {
				ud.revokeAllOrgPermissions(orgUuid);
			}
			componentService.handleRemoveUserFromTriggers(orgUuid, userUuid, wu);
			saveUser(ou.get(), Utils.dataToRecord(ud), wu);
		}
		return removed;
	}

	public void softDeleteUser(UUID userUuid, WhoUpdated wu){
		// locate user
		Optional<User> ou = getUser(userUuid);
		if (ou.isPresent()) {
			UserData ud = UserData.dataFromRecord(ou.get());
			ud.setStatus(UserStatus.INACTIVE);
			
			// save user
			saveUser(ou.get(), Utils.dataToRecord(ud), wu);
		}
		return ;
	}

	public boolean updateUserEmail(UserData ud, String oldEmail, String newEmail, Boolean makePrimary,
			Boolean acceptMarketing, WhoUpdated whoUpdated) {
		boolean updated = false;
		try {
			// check if old email record actually belongs to this user
			if (ud.hasEmail(oldEmail)) {
				// send email to old address if it's valid that a new one is provided
				EmailValidator ev = EmailValidator.getInstance(false);
				if (ev.isValid(oldEmail)) {
					String emailSubject = "This email address was requested to be replaced with a new one on ReARM";
					String contentStr = "Our records indicate that you requested to replace this email with a new one in ReARM."
							+ "If you didn't do it, please contact us at info@reliza.io as soon as possible.";
					emailService.sendEmail(List.of(oldEmail), emailSubject, "text/html", contentStr);
				}
				// if email address changed, require verification
				User u = getUser(ud.getUuid()).get();
				if (!oldEmail.equalsIgnoreCase(newEmail)) { 
				    u = requestEmailVerification(u, oldEmail, makePrimary, acceptMarketing);
				} else {
				    // only update primary and marketing props
				    ud.updateEmail(oldEmail, null, makePrimary, acceptMarketing);
				    u = saveUser(u, Utils.dataToRecord(ud), whoUpdated);
				}
				updated = true;
			}
		} catch (Exception e) {
			log.error("Failed to updated user email", e);
		}
		return updated;
		
	}
	
	public boolean resendEmailVerification (UUID userId) throws UnsupportedEncodingException, RelizaException {
	    boolean resent = false;
	    var uOpt = getUser(userId);
	    if (uOpt.isPresent()) {
			User u = uOpt.get();
			UserData ud = UserData.dataFromRecord(u);
			requestEmailVerification(u, ud.getEmail(), null, null);
			resent = true;
	    }
	    return resent;
	}
	
	/**
	 * This method checks whether the email is already registered, and if yes, will not allow re-register
	 * @throws RelizaException 
	 */
	private void checkIfEmailAlreadyRegistered (String email, UserData ud) throws RelizaException {
		if (!StringUtils.isEmpty(email)) {
			var optUser = repository.findAnyUserByEmail(email);
			boolean sameUser = false;
			if (null != ud && null != optUser && !optUser.isEmpty() && optUser.get(0).getUuid().equals(ud.getUuid())) sameUser = true;
			if (!sameUser && null != optUser && !optUser.isEmpty()) {
				throw new RelizaException("Email already registered");
			}
		}
	}
	
	private User requestEmailVerification (User u, String email, Boolean makePrimary,
			Boolean acceptMarketing) throws UnsupportedEncodingException, RelizaException {
	    UserData ud = UserData.dataFromRecord(u);
	    if (StringUtils.isEmpty(email)) email = ud.getEmail();
	    EmailValidator ev = EmailValidator.getInstance();
		if (!ev.isValid(email)) {
			throw new RelizaException("Valid email must be set");
	    }
		
		// check rate limit
		if (!ud.isVerificationRateLimitAllowed(email)) {
			throw new RelizaException("Verification rate limit exceeded. Please try again later.");
		}
		
		// generate key
		StringBuilder keyBuilder = new StringBuilder();
		for (int i=0; i<2; i++) {
			keyBuilder.append(KeyGenerators.string().generateKey());
		}
		String key = keyBuilder.toString();
		key = encryptionService.encrypt(email) + CommonVariables.JOIN_SECRET_SEPARATOR + key;
		String urlSafeKey = URLEncoder.encode(key, StandardCharsets.UTF_8.toString());
		Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
		String secret = encoder.encode(urlSafeKey);
		// set verification requirement into user data
		var setChallenge = ud.updateEmail(email, secret, makePrimary, acceptMarketing);
		if (!setChallenge) {
		    throw new RelizaException("Could not locate user email");
		}
		if (StringUtils.isEmpty(email)) email = ud.getEmail();
		// send email to user
		String emailSubject = "Verify your email on ReARM";
		String contentStr = "Please click <a clicktracking=off href=\"" + relizaConfigProps.getBaseuri()
			+ "/verifyEmail/" 
			+ urlSafeKey + "\">this link</a> to verify your email on ReARM. "
			+ "Note that the link will be valid only within the next 48 hours.";
		boolean sentEmail = emailService.sendEmail(List.of(email), emailSubject, "text/html", contentStr);
		if (!sentEmail) {
			throw new RelizaException("Internal error sending email"); 
		} else {
			var recordData = Utils.dataToRecord(ud);
			return saveUser(u, recordData, WhoUpdated.getWhoUpdated(ud));
		}
	}

	public Optional<UserData> verifyEmail(String secret, UserData ud) {
		Optional<UserData> oud = Optional.empty();
		try {
			// resolve new email from secret
			String[] splitSecret = secret.split(CommonVariables.JOIN_SECRET_SEPARATOR);
			// 1st part would be new email
			String newEmail = encryptionService.decrypt(splitSecret[0]);
			// resolve new email
			boolean resolved = ud.resolveVerifiedEmail(secret, newEmail);
			if (resolved) {
				// save
				User u = getUser(ud.getUuid()).get();
				u = saveUser(u, Utils.dataToRecord(ud), WhoUpdated.getWhoUpdated(ud));
				oud = Optional.of(UserData.dataFromRecord(u));
				log.info("New User Verified with email : ", newEmail);
			} else {
				oud = Optional.empty();
			}
		} catch (Exception e) {
			log.error("Error when verifying email", e);
			oud = Optional.empty();
		}
		return oud;
	}
	
	/**
	 * Aggregates number of users per time unit per defined time frame - for global admins
	 * @return
	 */
	public List<Map<String,Object>> getUserCreateOverTimeAnalytics() {
		LocalDateTime cutOffDate = LocalDateTime.now();
		cutOffDate = cutOffDate.minusDays(60);
		List<User> users = listUsersCreatedAfterDate(cutOffDate);
		List<Map<String,Object>> retAnalytics = new LinkedList<>();
		users.forEach(r -> {
			Map<String,Object> rObj = new HashMap<>(); 
			rObj.put("num", 1);
			rObj.put("date", r.getCreatedDate().toString());
			retAnalytics.add(rObj);
		});
		return retAnalytics;
	}

	private List<User> listUsersCreatedAfterDate(LocalDateTime cutOffDate) {
		return repository.findUsersCreatedAfterDate(cutOffDate.toString());
	}

	private UserData revokeUserPermission (UUID userUuid, UUID orgUuid, PermissionScope scope, UUID objectUuid, WhoUpdated wu) throws RelizaException {
		User u = null;
		UserData ud = null;
		// locate user in db
		Optional<User> uOpt = getUser(userUuid);
		if (uOpt.isPresent()) {
			u = uOpt.get();
			// get user data
			boolean revoked = false;
			UserData uData = UserData.dataFromRecord(u);
			revoked = uData.revokePermission(orgUuid, scope, objectUuid);			
			if (revoked) {
				// save user
				Map<String,Object> recordData = Utils.dataToRecord(uData);
				u = saveUser(u, recordData, wu);
				ud = UserData.dataFromRecord(u);
			}
			
		}
		return ud;
	}
	
	@Transactional
	public UserWebDto updateUserName(UserData ud, String name, WhoUpdated whoUpdated) {
		UserWebDto uwd = null;
		var ou = getUser(ud.getUuid());
		if (ou.isPresent()) {
			ud = UserData.dataFromRecord(ou.get());
			ud = UserData.updateUserData(ud, null, name, null, null, null);
			var u = saveUser(ou.get(), Utils.dataToRecord(ud), whoUpdated);
			ud = UserData.dataFromRecord(u);
			uwd = UserData.toWebDto(ud);
		}
		return uwd;
	}

	public void markUserLogout(UUID userId) {
		Optional<User> ou = getUser(userId);
		if (ou.isEmpty()) {
			log.error("Could not find user on logout with id = " + userId);
		} else {
			User u = ou.get();
			u.setLastLogoutDate();
			// not creating new revision for this case
			repository.save(u);
		}
	}
	
	@Transactional
	public UserData resolveUser (final JwtAuthenticationToken auth) throws RelizaException {
		Jwt creds = (Jwt) auth.getCredentials();
		Optional<User> ou = getUserByAuth(auth);
		if (ou.isEmpty()) {
			OauthType oauthType = OauthType.RELIZA_KEYCLOAK_OWN;
			switch (oauthType) {
			case RELIZA_KEYCLOAK_OWN:
				String sub = creds.getClaimAsString("sub");
				String name = creds.getClaimAsString("name");
				String email = creds.getClaimAsString("email");
				User u;
				if (InstallationType.OSS == getInstallationType()) {
					boolean isFirstUser = !repository.findAll().iterator().hasNext();
					PermissionType pt = isFirstUser ? PermissionType.ADMIN : PermissionType.NONE;
					u = createUser(name, email, true, List.of(USER_ORG), sub, oauthType, WhoUpdated.getAutoWhoUpdated());
					u = setUserPermission(u.getUuid(), USER_ORG, PermissionScope.ORGANIZATION, USER_ORG, pt, null, WhoUpdated.getWhoUpdated(UserData.dataFromRecord(u)));
					OrganizationData od = getOrganizationService.getOrganizationData(USER_ORG).get();
					sendEmailToOrgAdminsOnUserJoined(od, email, pt);
				} else if (InstallationType.DEMO == getInstallationType()) {
					boolean isFirstUser = !repository.findAll().iterator().hasNext();
					PermissionType pt = isFirstUser ? PermissionType.ADMIN : PermissionType.READ_ONLY;
					u = createUser(name, email, true, List.of(USER_ORG), sub, oauthType, WhoUpdated.getAutoWhoUpdated());
					u = setUserPermission(u.getUuid(), USER_ORG, PermissionScope.ORGANIZATION, USER_ORG, pt, null, WhoUpdated.getWhoUpdated(UserData.dataFromRecord(u)));
					OrganizationData od = getOrganizationService.getOrganizationData(USER_ORG).get();
					sendEmailToOrgAdminsOnUserJoined(od, email, pt);
				} else if (InstallationType.MANAGED_SERVICE == getInstallationType()) {
					UUID defaultOrg = systemInfoService.getDefaultOrg();
					if ( null == defaultOrg && !repository.findAll().iterator().hasNext() ) {
						// first user is registered should be global admin
						u = createUser(name, email, true, List.of(), sub, oauthType, WhoUpdated.getAutoWhoUpdated());
					} else if (null == defaultOrg) {
						throw new RelizaException("Your organization is not set, ask your administrator to set it");
					} else {
						u = createUser(name, email, true, List.of(defaultOrg), sub, oauthType, WhoUpdated.getAutoWhoUpdated());
						u = setUserPermission(u.getUuid(), defaultOrg, PermissionScope.ORGANIZATION, defaultOrg, PermissionType.NONE, null, WhoUpdated.getWhoUpdated(UserData.dataFromRecord(u)));
						OrganizationData od = getOrganizationService.getOrganizationData(defaultOrg).get();
						sendEmailToOrgAdminsOnUserJoined(od, email, PermissionType.NONE);
					}
				} else {
					Boolean isEmailVerified = Boolean.parseBoolean(creds.getClaimAsString("email_verified"));
					u = createUser(name, email, isEmailVerified, List.of(), sub, oauthType, WhoUpdated.getAutoWhoUpdated());
				} 
				ou = Optional.of(u);
				break;
			default:
				break;
			}
		}
		return UserData.dataFromRecord(ou.get());
	}
	
	public InstallationType getInstallationType() {
		InstallationType it = InstallationType.SAAS;
		if ("OSS".equals(relizaConfigProps.getInstallationType())) {
			it = InstallationType.OSS;
		} else if ("MANAGED_SERVICE".equals(relizaConfigProps.getInstallationType())) {
			it = InstallationType.MANAGED_SERVICE;
		} else if ("DEMO".equals(relizaConfigProps.getInstallationType())) {
			it = InstallationType.DEMO;
		}
		return it;
	}
	
	public boolean sendEmailToUsers(UUID org, Collection<UUID> users,
			String subject, String contentType, String contentStr) {
		Set<UUID> distinctUserUuids = new HashSet<>(users);
		Set<String> emails = distinctUserUuids
			.stream()
			.map(du -> getUserDataWithOrg(du, org).get().getEmail(org))
			.collect(Collectors.toSet());
		return emailService.sendEmail(emails, subject, contentType, contentStr);
	}

	public void sendEmailToOrgAdminsOnUserJoined(OrganizationData od, String userEmail, PermissionType permissionType) {
		String adminEmailSub = "New user joined the organizaiton " + od.getName() + " on ReARM at " + relizaConfigProps.getBaseuri();
		String adminEmailContent = "A new user with the email " + userEmail + 
		" joined the organization "+ od.getName() 
		+ " on ReARM with permission: " + permissionType.toString() + ".";
		if (PermissionType.NONE == permissionType) {
			adminEmailContent += "\n\nPlease open Organization Settings view in ReARM and assign permissions for this user.";
		}
		sendEmailToOrgAdmins(adminEmailSub, adminEmailContent, od.getUuid());
	}
	
	private void sendEmailToOrgAdmins(String subject, String content, UUID orgUuid) {
		List<String> adminEmails = listOrgAdminUsersDataByOrg(orgUuid)
					.stream().map(admin -> admin.getEmail()).toList();
		emailService.sendEmail(adminEmails, subject, "text/html", content);
	}

}
