/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.InstallationType;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.CommonVariables.UserStatus;
import io.reliza.common.Utils;
import io.reliza.exceptions.AccountRefusedException;
import io.reliza.exceptions.ActionRefusedException;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.InvitedObject;
import io.reliza.model.OrganizationData.SsoUsersWithoutRole;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserData.OrgUserData;
import io.reliza.model.UserGroupData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.UserWebDto;
import io.reliza.service.BrandedEmailText.EmailText;
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
	private BrandingService brandingService;
	
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

	@Autowired
	@Lazy
	private ApiKeyService apiKeyService;

	@Autowired
	@Lazy
	private OrganizationService organizationService;

	@Autowired
	@Lazy
	private SeatGate seatGate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	/** A last-login stamp older than this is renewed at the next sign-in (D11). */
	static final Duration LAST_LOGIN_STAMP_EVERY = Duration.ofHours(1);

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
		return listUsersByOrg(org, false);
	}

	public List<User> listUsersByOrg(UUID org, boolean includeInactive) {
		if (includeInactive) {
			return repository.findUsersByOrgIncludingInactive(org.toString());
		}
		return repository.findUsersByOrg(org.toString());
	}
	
	public List<UserData> listUserDataByOrg(UUID org) {
		return listUserDataByOrg(org, false);
	}

	public List<UserData> listUserDataByOrg(UUID org, boolean includeInactive) {
		List<User> udList = listUsersByOrg(org, includeInactive);
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

	public List<OrgUserData> listOrgUserDataByOrg (UUID org, boolean includeInactive) {
		List<UserData> udList = listUserDataByOrg(org, includeInactive);
		return udList.stream().map(ud -> UserData.convertUserDataToOrgUserData(ud, org)).collect(Collectors.toList());
	}
	
	private List<UserData> transformUserToUserData (Collection<User> users) {
		return users.stream()
				.map(UserData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/**
	 * Returns every user holding any permission whose {@code object} equals the
	 * given UUID (a native JSONB scan over each user's permission array). The
	 * caller is responsible for narrowing by scope / type / approvals — this only
	 * does the inverse object→users lookup that the {@code Permissions} model has
	 * no forward index for. Used by {@link ComponentTeamService} to derive a
	 * component's team and approvers.
	 */
	public List<UserData> getUsersByPermissionObject (UUID objectUuid) {
		return transformUserToUserData(repository.findUsersByPermissionObject(objectUuid.toString()));
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
			PermissionType type, Collection<PermissionFunction> functions, Collection<String> approvals, WhoUpdated wu) {
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
				// license seats (TPF-167, P6): the lock and the before count first, the judge after the save
				SeatGate.Change seats = seatGate.beginSeatChange();
				uData.setPermission(orgUuid, scope, permissionObject, type, functions, approvals);
				// save user
				Map<String,Object> recordData = Utils.dataToRecord(uData);
				u = saveUser(u, recordData, wu);
				seatGate.assertFitsAfterWrite(seats);
			}
		}
		return u;
	}
	
	/**
	 * Updates user permissions for an organization
	 * @param userUuid
	 * @param orgUuid
	 * @param permissionType
	 * @param permissions
	 * @param wu
	 * @return updated user data if user is present, otherwise null
	 * @throws RelizaException 
	 */
	@Transactional
	public UserData setUserPermissions (UUID userUuid, UUID orgUuid,
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
				uData.setPermission(orgUuid, PermissionScope.ORGANIZATION, orgUuid, pt, orgWidePermission.get().getApprovals());
				update = true;
			} else {
				throw new RelizaException("Cannot set permissions as User is not added to organization.");
			}
			// resolve individual permissions
			if (null != permissions) {
				// remove existing individual permissions for this org
				Set<UserPermission> existingPermissions = uData.getOrgPermissions(orgUuid);
				for (UserPermission p : existingPermissions) {
					if (p.getScope() != PermissionScope.ORGANIZATION) {
						uData.revokePermission(orgUuid, p.getScope(), p.getObject());
					}
				}
				
				if (!permissions.isEmpty()) {
					permissions.forEach(p -> {
						uData.setPermission(orgUuid, p.scope(), p.object(), p.type(), p.functions(), p.approvals());
					});
				}
				update = true;
			}
			
			if (update) {
				// license seats (TPF-167, P6): every scope counts, an object-scope READ_WRITE is a write seat
				SeatGate.Change seats = seatGate.beginSeatChange();
				// save user
				Map<String,Object> recordData = Utils.dataToRecord(uData);
				u = saveUser(u, recordData, wu);
				seatGate.assertFitsAfterWrite(seats);
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
			// license seats (TPF-167, D7 point 1): an account outside the licensed number of users is refused on
			// every request, before its sync runs
			seatGate.assertAdmitted(oud.get());
			Jwt creds = (Jwt) auth.getCredentials();
			List<String> groups = null != creds.getClaimAsStringList("groups") ? creds.getClaimAsStringList("groups") : new LinkedList<>();
			synchronizeUserWithGroups(oud.get(), new LinkedHashSet<>(groups));
		}
		return oud;
	}
	
	/**
	 * The SSO group sync never throws (TPF-167, D5, F-12): each organization's sync runs in a transaction of
	 * its own, and a failure there, a lock or persistence error included, surfaces at its commit, here, where
	 * it is logged and the request goes on with the user's memberships unchanged.
	 */
	private void synchronizeUserWithGroups (UserData ud, Set<String> presentSsoGroups) {
		for (var uOrg : ud.getOrganizations()) {
			try {
				synchronizeUserWithGroupsPerOrg(ud, presentSsoGroups, uOrg);
			} catch (Exception e) {
				log.error("SSO group sync of user = " + ud.getUuid() + " in org = " + uOrg + " failed; memberships unchanged", e);
			}
		}
	}

	/** One org's SSO-connected groups, the user's pending adds and removals among them (D5). */
	private record SsoDiff(Map<UUID, UserGroupData> connected, Set<UUID> pendingAdds, Set<UUID> pendingRemovals) {
		boolean isEmpty() {
			return pendingAdds.isEmpty() && pendingRemovals.isEmpty();
		}
	}

	/**
	 * The comparison (reads only): expected = connected groups the claim names, current = the user's
	 * memberships among connected groups; adds = expected - current, removals = current - expected. The
	 * pod's skipped-lift memory narrows the ADD set only (F-13): a remembered group the user already belongs
	 * to is forgotten and compared normally, any other is left out of the adds. Removals never read it.
	 */
	private SsoDiff ssoDiff (UUID userUuid, Set<String> presentSsoGroups, UUID uOrg) {
		Map<UUID, UserGroupData> connected = new HashMap<>();
		for (UserGroupData g : userGroupService.getUserGroupsByOrganization(uOrg)) {
			if (null != g.getConnectedSsoGroups() && !g.getConnectedSsoGroups().isEmpty()) connected.put(g.getUuid(), g);
		}
		Set<UUID> expected = connected.values().stream()
			.filter(g -> !Collections.disjoint(g.getConnectedSsoGroups(), presentSsoGroups))
			.map(UserGroupData::getUuid)
			.collect(Collectors.toSet());
		// Only consider SSO-connected groups; manual group memberships are not managed by SSO sync
		Set<UUID> current = userGroupService.getUserGroupsByUserAndOrg(userUuid, uOrg).stream()
			.map(UserGroupData::getUuid)
			.filter(connected::containsKey)
			.collect(Collectors.toSet());
		Set<UUID> adds = new LinkedHashSet<>(expected);
		adds.removeAll(current);
		Set<UUID> removals = new LinkedHashSet<>(current);
		removals.removeAll(expected);
		for (UUID remembered : seatGate.skippedLiftsOf(userUuid)) {
			if (current.contains(remembered)) {
				seatGate.forgetSkippedLift(userUuid, remembered);
			} else {
				adds.remove(remembered);
			}
		}
		return new SsoDiff(connected, adds, removals);
	}

	/**
	 * One org's SSO group sync (D5): the unlocked comparison first, the common case returning there; only
	 * when an add or a removal is pending does the locked part run, in a REQUIRES_NEW transaction of its
	 * own. Never call it from a transaction that holds LICENSE_SEATS: it would wait for itself.
	 */
	public void synchronizeUserWithGroupsPerOrg (UserData ud, Set<String> presentSsoGroups, UUID uOrg) {
		SsoDiff diff = ssoDiff(ud.getUuid(), presentSsoGroups, uOrg);
		if (diff.isEmpty()) return;
		boolean addPending = !diff.pendingAdds().isEmpty();
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		tx.executeWithoutResult(status -> lockedSync(ud, presentSsoGroups, uOrg, addPending));
	}

	private void lockedSync (UserData ud, Set<String> presentSsoGroups, UUID uOrg, boolean addPending) {
		// LICENSE_SEATS before the user row lock whenever any add is pending, lift or not: every add goes
		// through addUserToGroup, a seat funnel that locks (D4 lock order, F-3, F-11)
		SeatGate.Change seats = addPending ? seatGate.beginSeatChange() : null;
		// write lock user
		Optional<User> ou = repository.findByIdWriteLocked(ud.getUuid());
		if (ou.isEmpty()) return;
		// double check after acquiring the lock
		SsoDiff d = ssoDiff(ud.getUuid(), presentSsoGroups, uOrg);
		if (null != seats) {
			applySsoAdds(ud, uOrg, d, seats);
		} else if (!d.pendingAdds().isEmpty()) {
			// an add appeared after the unlocked comparison; this transaction holds no seat lock and may not
			// take it under the row lock, so the next request adds it, lock first (F-16)
			log.debug("SSO sync of user = {} in org = {} leaves {} new add(s) to the next request", ud.getUuid(), uOrg,
					d.pendingAdds().size());
		}
		for (var orgGroup : d.pendingRemovals()) {
			userGroupService.removeSsoUserFromGroup(orgGroup, ud.getUuid(), WhoUpdated.getAutoWhoUpdated());
		}
	}

	/**
	 * The seat part of the sync (D5): each add is asked of the seat gate first, in order; a refused one
	 * is skipped and the user keeps their level.
	 */
	private void applySsoAdds (UserData ud, UUID uOrg, SsoDiff d, SeatGate.Change seats) {
		SeatGate.SsoAdds judge = seatGate.ssoAdds(seats, ud, uOrg);
		for (UUID orgGroup : d.pendingAdds()) {
			if (!judge.admit(d.connected().get(orgGroup))) continue;
			userGroupService.addUserToGroup(orgGroup, ud.getUuid(), WhoUpdated.getAutoWhoUpdated());
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
			log.error("Signed up user uuid = " + ud.getUuid() + " had email mismatch on signup");
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
		// license seats (TPF-167, P9): the join took the lock before its org row lock, so this returns its handle
		SeatGate.Change seats = seatGate.beginSeatChange();
		ud.addOrganization(orgUuid);
		ud.setPermission(orgUuid, PermissionScope.ORGANIZATION, orgUuid, io.getType(), null);
		User u = saveUser(getUser(ud.getUuid()).get(), Utils.dataToRecord(ud), wu);
		seatGate.assertFitsAfterWrite(seats);
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
		Optional<User> ou = repository.findByIdWriteLocked(userUuid);
		if (ou.isPresent()) {
			UserData ud = UserData.dataFromRecord(ou.get());
			removed = ud.removeOrganization(orgUuid);
			if (removed) {
				ud.revokeAllOrgPermissions(orgUuid);
				userGroupService.removeUserFromAllGroupsInOrg(userUuid, orgUuid, wu);
				apiKeyService.releaseHeldKeysInOrg(userUuid, orgUuid, wu);
				apiKeyService.revokeUserKeysInOrg(userUuid, orgUuid, wu);
			}
			componentService.handleRemoveUserFromTriggers(orgUuid, userUuid, wu);
			saveUser(ou.get(), Utils.dataToRecord(ud), wu);
		}
		return removed;
	}

	@Transactional
	public UserData softDeleteUser(UUID userUuid, WhoUpdated wu){
		// locate user
		Optional<User> ou = getUser(userUuid);
		if (ou.isPresent()) {
			UserData ud = UserData.dataFromRecord(ou.get());
			ud.setStatus(UserStatus.INACTIVE);
			
			// save user
			User saved = saveUser(ou.get(), Utils.dataToRecord(ud), wu);
			// An inactive user is no longer an org member for the actions
			// users-in-org check, so scrub them from component actions the same
			// way removeUserFromOrg does, in every org they belong to. Best effort,
			// after the deactivation is saved: a failed component save must never
			// undo or fail the deactivation, so each org's scrub runs and commits in
			// a transaction of its own (the caller, inactivateUser, holds one; a
			// failure inside it would mark it rollback-only). If the caller's
			// transaction rolls back after this, the user stays ACTIVE but scrubbed:
			// they get no more action emails until re-added. reactivateUser does not
			// restore it.
			for (UUID orgUuid : ud.getOrganizations()) {
				try {
					componentService.handleRemoveUserFromTriggersInNewTransaction(orgUuid, userUuid, wu);
				} catch (Exception e) {
					log.error("Error removing deactivated user = " + userUuid + " from component actions in org = " + orgUuid, e);
				}
			}
			return UserData.dataFromRecord(saved);
		}
		return null;
	}

	@Transactional
	public UserData reactivateUser(UUID userUuid, WhoUpdated wu){
		// license seats (TPF-167, P7): an inactive user holds no seat, so a reactivation may grant one
		SeatGate.Change seats = seatGate.beginSeatChange();
		Optional<User> ou = getUser(userUuid);
		if (ou.isPresent()) {
			UserData ud = UserData.dataFromRecord(ou.get());
			ud.setStatus(UserStatus.ACTIVE);
			User saved = saveUser(ou.get(), Utils.dataToRecord(ud), wu);
			seatGate.assertFitsAfterWrite(seats);
			return UserData.dataFromRecord(saved);
		}
		return null;
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
					EmailText notice = BrandedEmailText.emailChangeNotice(brandingService.current().values());
					emailService.sendEmail(List.of(oldEmail), notice.subject(), "text/html", notice.body());
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
		EmailText verify = BrandedEmailText.verifyEmail(brandingService.current().values(),
				relizaConfigProps.getBaseuri(), urlSafeKey);
		boolean sentEmail = emailService.sendEmail(List.of(email), verify.subject(), "text/html", verify.body());
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
				log.info("New User Verified, uuid = {}", ud.getUuid());
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
		if (ou.isPresent()) {
			UserData existing = UserData.dataFromRecord(ou.get());
			// license seats (TPF-167, D7 point 2): admission by creation date at sign-in
			seatGate.assertAdmitted(existing);
			stampLastLoginIfStale(existing);
			return existing;
		}
		OauthType oauthType = OauthType.RELIZA_KEYCLOAK_OWN;
		String sub = creds.getClaimAsString("sub");
		String name = creds.getClaimAsString("name");
		String email = creds.getClaimAsString("email");
		boolean isFirstUser = !repository.findAll().iterator().hasNext();
		User u;
		try {
			u = createResolvedUser(creds, oauthType, sub, name, email, isFirstUser);
		} catch (ActionRefusedException are) {
			// TPF-167 (F-9): a seat refusal at a first sign-in reaches the person as the seat gate's typed,
			// number-free refusal; the admin text goes to the admins' event
			UUID eventOrg = null != systemInfoService.getDefaultOrg() ? systemInfoService.getDefaultOrg() : USER_ORG;
			throw seatGate.newAccountRefusal(eventOrg, email, are);
		}
		return UserData.dataFromRecord(u);
	}

	/** The census key a new account would be counted under (its email lower-cased). */
	private static String newUserKey(String email, String sub) {
		return StringUtils.isBlank(email) ? "new-user:" + sub : email.toLowerCase(Locale.ROOT);
	}

	private User createResolvedUser (Jwt creds, OauthType oauthType, String sub, String name, String email,
			boolean isFirstUser) throws RelizaException {
		User u;
		if (InstallationType.OSS == getInstallationType()) {
			PermissionType pt = isFirstUser ? PermissionType.ADMIN : PermissionType.NONE;
			u = createUser(name, email, true, List.of(USER_ORG), sub, oauthType, WhoUpdated.getAutoWhoUpdated());
			u = setUserPermission(u.getUuid(), USER_ORG, PermissionScope.ORGANIZATION, USER_ORG, pt, Set.of(), null, WhoUpdated.getWhoUpdated(UserData.dataFromRecord(u)));
			OrganizationData od = getOrganizationService.getOrganizationData(USER_ORG).get();
			sendEmailToOrgAdminsOnUserJoined(od, u.getUuid(), pt);
		} else if (InstallationType.DEMO == getInstallationType()) {
			PermissionType pt = isFirstUser ? PermissionType.ADMIN : PermissionType.READ_ONLY;
			if (!isFirstUser) {
				// TPF-167 (F-9): decided before anything is written, as on MANAGED_SERVICE
				SeatGate.Change seats = seatGate.beginSeatChange();
				seatGate.assertWouldFit(seats, newUserKey(email, sub), PermissionType.READ_ONLY);
			}
			u = createUser(name, email, true, List.of(USER_ORG), sub, oauthType, WhoUpdated.getAutoWhoUpdated());
			u = setUserPermission(u.getUuid(), USER_ORG, PermissionScope.ORGANIZATION, USER_ORG, pt,
					Set.of(PermissionFunction.FINDING_ANALYSIS_READ, PermissionFunction.ARTIFACT_DOWNLOAD),
					null, WhoUpdated.getWhoUpdated(UserData.dataFromRecord(u)));
			OrganizationData od = getOrganizationService.getOrganizationData(USER_ORG).get();
			sendEmailToOrgAdminsOnUserJoined(od, u.getUuid(), pt);
		} else if (InstallationType.MANAGED_SERVICE == getInstallationType()) {
			UUID defaultOrg = systemInfoService.getDefaultOrg();
			if ( null == defaultOrg && isFirstUser ) {
				// first user is registered should be global admin (the unseal); exempt from the seat check
				u = createUser(name, email, true, List.of(), sub, oauthType, WhoUpdated.getAutoWhoUpdated());
			} else if (null == defaultOrg) {
				throw new RelizaException("Your organization is not set, ask your administrator to set it");
			} else {
				u = createManagedServiceUser(creds, oauthType, sub, name, email, defaultOrg);
			}
		} else {
			Boolean isEmailVerified = Boolean.parseBoolean(creds.getClaimAsString("email_verified"));
			u = createUser(name, email, isEmailVerified, List.of(), sub, oauthType, WhoUpdated.getAutoWhoUpdated());
		}
		return u;
	}

	/**
	 * A first MANAGED_SERVICE sign-in into the default org (TPF-167, D6), everything in this transaction and
	 * nothing written before the decision. matched = active groups of the default org connected to a group
	 * the claim names. None matched and no pending invitation: REFUSE (the default) refuses NO_ROLE with an
	 * event naming the setting, CREATE_WITHOUT_ROLE creates the account with NONE. Some matched: the lock
	 * first, the would-be seat judged, then the account, its NONE permission and each matched membership,
	 * added directly here (the sync's own REQUIRES_NEW transaction would wait on this one's lock).
	 */
	private User createManagedServiceUser (Jwt creds, OauthType oauthType, String sub, String name, String email,
			UUID defaultOrg) throws RelizaException {
		List<String> groups = null != creds.getClaimAsStringList("groups") ? creds.getClaimAsStringList("groups") : new LinkedList<>();
		Set<String> claimed = new HashSet<>(groups);
		List<UserGroupData> matched = userGroupService.getUserGroupsByOrganization(defaultOrg).stream()
			.filter(g -> null != g.getConnectedSsoGroups() && !g.getConnectedSsoGroups().isEmpty()
					&& !Collections.disjoint(g.getConnectedSsoGroups(), claimed))
			.toList();
		OrganizationData od = getOrganizationService.getOrganizationData(defaultOrg).get();
		if (matched.isEmpty()) {
			boolean invited = organizationService.listAllOrganizationData().stream()
					.anyMatch(o -> o.hasPendingInvitationFor(email));
			if (!invited && od.getSettingsWithDefaults().getSsoUsersWithoutRoleOrDefault() == SsoUsersWithoutRole.REFUSE) {
				seatGate.noRoleRefused(defaultOrg, email);
				throw AccountRefusedException.noRole();
			}
			User u = createUser(name, email, true, List.of(defaultOrg), sub, oauthType, WhoUpdated.getAutoWhoUpdated());
			u = setUserPermission(u.getUuid(), defaultOrg, PermissionScope.ORGANIZATION, defaultOrg, PermissionType.NONE, Set.of(), null, WhoUpdated.getWhoUpdated(UserData.dataFromRecord(u)));
			sendEmailToOrgAdminsOnUserJoined(od, u.getUuid(), PermissionType.NONE);
			return u;
		}
		// the lock first, for any would-be seat (F-14): the funnel calls below reuse this handle
		SeatGate.Change seats = seatGate.beginSeatChange();
		seatGate.assertNewMemberFits(seats, newUserKey(email, sub), defaultOrg, matched);
		User u = createUser(name, email, true, List.of(defaultOrg), sub, oauthType, WhoUpdated.getAutoWhoUpdated());
		u = setUserPermission(u.getUuid(), defaultOrg, PermissionScope.ORGANIZATION, defaultOrg, PermissionType.NONE, Set.of(), null, WhoUpdated.getWhoUpdated(UserData.dataFromRecord(u)));
		for (UserGroupData g : matched) {
			userGroupService.addUserToGroup(g.getUuid(), u.getUuid(), WhoUpdated.getAutoWhoUpdated());
		}
		UserData newUserData = UserData.dataFromRecord(u);
		// Obtain combined permissions (user's own + from groups) and get highest org-wide permission
		var combinedPermissions = organizationService.obtainCombinedUserOrgPermissions(newUserData, defaultOrg);
		var orgWidePermission = combinedPermissions.getPermission(defaultOrg, PermissionScope.ORGANIZATION, defaultOrg);
		PermissionType effectivePermissionType = orgWidePermission.isPresent()
			? orgWidePermission.get().getType()
			: PermissionType.NONE;
		sendEmailToOrgAdminsOnUserJoined(od, u.getUuid(), effectivePermissionType);
		return u;
	}

	/**
	 * Stamps the last sign-in when absent or older than an hour (TPF-167, D11): an in-place bookkeeping
	 * write, no revision and no audit row. A racing whole-record save may overwrite it with its loaded copy;
	 * accepted (display-only, hourly), the next sign-in restamps.
	 */
	private void stampLastLoginIfStale (UserData ud) {
		Instant last = ud.getLastLoginInstant();
		Instant now = Instant.now();
		if (null == last || last.plus(LAST_LOGIN_STAMP_EVERY).isBefore(now)) {
			repository.stampLastLogin(ud.getUuid(), now.toString());
		}
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

	/** Deployment-wide UI home dashboard default (relizaprops.default-dashboard): BOARDS or APP; anything else resolves to APP. */
	public String getDefaultDashboard() {
		return "BOARDS".equalsIgnoreCase(relizaConfigProps.getDefaultDashboard()) ? "BOARDS" : "APP";
	}

	public boolean sendEmailToUsers(UUID org, Collection<UUID> users,
			String subject, String contentType, String contentStr) {
		Set<String> emails = activeRecipientEmails(org, users);
		if (emails.isEmpty()) {
			log.debug("No active recipients in org = {}, not sending '{}'", org, subject);
			return true;
		}
		return emailService.sendEmail(emails, subject, contentType, contentStr);
	}

	/**
	 * The org emails of those {@code users} who are active members of {@code org}. Others are
	 * skipped, not an error: an action may still hold a user deactivated or removed before
	 * deactivation scrubbed actions, and one such user must not stop the email to the rest.
	 */
	public Set<String> activeRecipientEmails(UUID org, Collection<UUID> users) {
		Set<String> emails = new LinkedHashSet<>();
		for (UUID user : new LinkedHashSet<>(users)) {
			Optional<UserData> member = getUserDataWithOrg(user, org);
			if (member.isPresent()) {
				emails.add(member.get().getEmail(org));
			} else {
				log.debug("Skipping action email recipient = {}: not an active member of org = {}", user, org);
			}
		}
		return emails;
	}

	public void sendEmailToOrgAdminsOnUserJoined(OrganizationData od, UUID userUuid, PermissionType permissionType) {
		EmailText joined = BrandedEmailText.userJoinedNotice(brandingService.current().values(), od.getName(),
				relizaConfigProps.getBaseuri(), userUuid, permissionType);
		sendEmailToOrgAdmins(joined.subject(), joined.body(), od.getUuid());
	}
	
	private void sendEmailToOrgAdmins(String subject, String content, UUID orgUuid) {
		List<String> adminEmails = listOrgAdminUsersDataByOrg(orgUuid)
					.stream().map(admin -> admin.getEmail()).toList();
		emailService.sendEmail(adminEmails, subject, "text/html", content);
	}

}
