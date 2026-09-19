/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CliSessionCodes;
import io.reliza.common.CommonVariables.UserStatus;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyOrigin;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.CliSession;
import io.reliza.model.CliSession.Status;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.CliSessionRepository;
import io.reliza.ws.RelizaConfigProps;
import lombok.extern.slf4j.Slf4j;

/**
 * CLI browser login (RFC 8628 device authorization) on top of API keys.
 *
 * <p>The CLI starts a request and shows the user a short code; the user, signed in to the SPA,
 * approves it by choosing which key the session acts as (a fresh personal key, one of their
 * personal keys, or a Free Form key they hold). The CLI then collects an opaque refresh token
 * (delivered once, stored hashed) and trades it for the usual one-hour access tokens. No key
 * secret is ever minted or shown. Expiry slides 30 days on each refresh, capped at 90 days
 * after approval. Revoking the session kills its access tokens through the token fingerprint.
 */
@Service
@Slf4j
public class CliSessionService {

	@Autowired private CliSessionRepository repository;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private UserService userService;
	@Autowired private RelizaConfigProps relizaConfigProps;

	/** What the CLI gets back when it starts a login. */
	public record DeviceAuthorization(String deviceCode, String userCode, String verificationUri, String verificationUriComplete, long expiresIn, int interval) {}

	/** What the CLI gets back when it collects an approved login. */
	public record Delivery(CliSession session, String refreshToken) {}

	public String verificationUri() {
		String base = relizaConfigProps.getBaseuri();
		return (base == null ? "" : base.replaceAll("/+$", "")) + "/cli-login";
	}

	/** The two groups the approval page shows: what the CLI said about itself, and what the server saw. */
	public record DeviceInfo(String reportedHostname, String reportedOs, String reportedTimeZone, String reportedClient, String observedIp) {
		private static String clip(String v) { return v == null || v.isBlank() ? null : v.strip().substring(0, Math.min(200, v.strip().length())); }
		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			if (clip(reportedHostname) != null) m.put("reportedHostname", clip(reportedHostname));
			if (clip(reportedOs) != null) m.put("reportedOs", clip(reportedOs));
			if (clip(reportedTimeZone) != null) m.put("reportedTimeZone", clip(reportedTimeZone));
			if (clip(reportedClient) != null) m.put("reportedClient", clip(reportedClient));
			if (clip(observedIp) != null) m.put("observedIp", clip(observedIp));
			return m;
		}
		public static DeviceInfo fromMap(Map<String, Object> m) {
			if (m == null) return null;
			return new DeviceInfo(str(m, "reportedHostname"), str(m, "reportedOs"), str(m, "reportedTimeZone"), str(m, "reportedClient"), str(m, "observedIp"));
		}
		private static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : String.valueOf(v); }
	}

	@Transactional
	public DeviceAuthorization start(String requestedFrom) {
		return start(new DeviceInfo(requestedFrom, null, null, null, null));
	}

	@Transactional
	public DeviceAuthorization start(DeviceInfo device) {
		String requestedFrom = device == null ? null : device.reportedHostname();
		String deviceCode = CliSessionCodes.newOpaqueSecret();
		CliSession s = new CliSession();
		s.setStatus(Status.PENDING);
		s.setDeviceCodeHash(CliSessionCodes.sha256Hex(deviceCode));
		s.setRequestedFrom(requestedFrom == null ? null : requestedFrom.strip().substring(0, Math.min(200, requestedFrom.strip().length())));
		if (device != null && !device.toMap().isEmpty()) s.setDeviceInfo(device.toMap());
		s.setExpiresDate(ZonedDateTime.now().plus(CliSessionCodes.PENDING_TTL));
		// user codes are unique among pending rows (partial index); retry on the rare collision
		purgeExpiredPendingThrottled();
		CliSession saved = null;
		for (int attempt = 0; attempt < 5 && saved == null; attempt++) {
			String code = CliSessionCodes.newUserCode();
			if (repository.findPendingByUserCode(code).isPresent()) continue;
			s.setUserCode(code);
			try {
				saved = repository.saveAndFlush(s);
			} catch (org.springframework.dao.DataIntegrityViolationException e) {
				// another start allocated the same pending code in between: try the next one
				log.warn("user code collision on attempt {}, retrying", attempt + 1);
			}
		}
		if (saved == null) throw new IllegalStateException("could not allocate a user code");
		String uri = verificationUri();
		// the parameter is user_code, not code: a redirect_uri carrying "code=" collides with the OIDC
		// authorization-code handling when the SPA bounces through Keycloak, and the login page errors
		return new DeviceAuthorization(deviceCode, s.getUserCode(), uri, uri + "?user_code=" + s.getUserCode(),
				CliSessionCodes.PENDING_TTL.toSeconds(), CliSessionCodes.POLL_INTERVAL_SECONDS);
	}

	/** The pending request behind a user code, for the approval page; empty when unknown or expired. */
	/** Expired pending logins are removed as new ones start, at most once a minute per node, so an unauthenticated flood cannot pile up until the nightly purge. */
	private final java.util.concurrent.atomic.AtomicLong lastPendingPurge = new java.util.concurrent.atomic.AtomicLong();
	private void purgeExpiredPendingThrottled() {
		long now = System.currentTimeMillis();
		long last = lastPendingPurge.get();
		if (now - last < 60_000 || !lastPendingPurge.compareAndSet(last, now)) return;
		try {
			int n = repository.purgeExpiredPending();
			if (n > 0) log.info("purged {} expired pending CLI logins", n);
		} catch (RuntimeException e) {
			log.error("could not purge expired pending CLI logins", e);
		}
	}
	public Optional<CliSession> pending(String userCode) {
		return repository.findPendingByUserCode(CliSessionCodes.normalizeUserCode(userCode))
				.filter(s -> s.getExpiresDate().isAfter(ZonedDateTime.now()));
	}

	/**
	 * The user approves a pending request with an existing key of theirs: a personal key they own, or
	 * a Free Form key they hold. The key must be ACTIVE.
	 */
	@Transactional
	public CliSession approveWithKey(String userCode, UserData user, UUID apiKeyUuid) throws RelizaException {
		CliSession s = pending(userCode).orElseThrow(() -> new RelizaException("No pending login for this code; it may have expired, ask the CLI to start again"));
		ApiKey ak = apiKeyService.getApiKey(apiKeyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		boolean mine = (ak.getObjectType() == ApiTypeEnum.USER && user.getUuid().equals(ak.getObjectUuid()))
				|| (ak.getObjectType() == ApiTypeEnum.FREEFORM && user.getUuid().equals(akd.getHolder()));
		if (!mine) throw new RelizaException("Only a personal key you own or a Free Form key you hold can back a CLI session");
		if (akd.getStatus() != ApiKeyStatus.ACTIVE) throw new RelizaException("This key is not active");
		return activate(s, user, ak);
	}

	/** The user approves a pending request with a fresh personal key created for this session in the given org. */
	@Transactional
	public CliSession approveWithNewKey(String userCode, UserData user, UUID orgUuid, String notes, WhoUpdated wu) throws RelizaException {
		return approveWithNewKey(userCode, user, orgUuid, notes, null, List.of(), wu);
	}

	/**
	 * The same, giving the new key a permission set. The caller passes permissions already reduced to
	 * what the approver holds; key creation, the permission write and the activation share one
	 * transaction, so a rejected set leaves no approved session behind.
	 */
	@Transactional
	public CliSession approveWithNewKey(String userCode, UserData user, UUID orgUuid, String notes,
			PermissionType orgType, List<PermissionDto> permissions, WhoUpdated wu) throws RelizaException {
		CliSession s = pending(userCode).orElseThrow(() -> new RelizaException("No pending login for this code; it may have expired, ask the CLI to start again"));
		if (!user.isGlobalAdmin() && !user.getOrganizations().contains(orgUuid)) throw new RelizaException("Not a member of this organization");
		String n = (notes == null || notes.isBlank()) ? "CLI session" + (s.getRequestedFrom() == null ? "" : " on " + s.getRequestedFrom()) : notes;
		ApiKey ak = apiKeyService.createObjectApiKey(user.getUuid(), ApiTypeEnum.USER, orgUuid, UUID.randomUUID().toString(), n, wu);
		ak = apiKeyService.setApiKeyOrigin(ak.getUuid(), ApiKeyOrigin.CLI_SESSION, wu);
		boolean anyPermissions = (orgType != null && orgType != PermissionType.NONE) || (permissions != null && !permissions.isEmpty());
		if (anyPermissions) {
			apiKeyService.setPermissionsOnApiKey(ak.getUuid(), orgType == null ? PermissionType.NONE : orgType,
					permissions == null ? List.of() : permissions, wu);
		}
		return activate(s, user, ak);
	}

	private CliSession activate(CliSession s, UserData user, ApiKey ak) {
		ZonedDateTime now = ZonedDateTime.now();
		s.setStatus(Status.ACTIVE);
		s.setUser(user.getUuid());
		s.setApiKey(ak.getUuid());
		s.setOrg(ak.getOrg());
		s.setApprovedDate(now);
		s.setExpiresDate(CliSessionCodes.slidExpiry(now, now));
		log.info("CLI login {} approved by user {} with key {} (org {})", s.getUuid(), user.getUuid(), ak.getUuid(), ak.getOrg());
		return repository.save(s);
	}

	@Transactional
	public boolean deny(String userCode, UserData user) {
		Optional<CliSession> os = pending(userCode);
		if (os.isEmpty()) return false;
		CliSession s = os.get();
		s.setStatus(Status.DENIED);
		s.setUser(user.getUuid());
		s.setRevokedDate(ZonedDateTime.now());
		repository.save(s);
		return true;
	}

	/** RFC 8628 poll outcomes. */
	public enum PollOutcome { AUTHORIZATION_PENDING, ACCESS_DENIED, EXPIRED, INVALID, DELIVERED }
	public record PollResult(PollOutcome outcome, Delivery delivery) {}

	/**
	 * The CLI collects the approved login. The refresh token is generated here, at collection time,
	 * because only its hash is stored and the browser must never see it; the device code is single-use.
	 */
	@Transactional
	public PollResult poll(String deviceCode) {
		if (deviceCode == null || deviceCode.isBlank()) return new PollResult(PollOutcome.INVALID, null);
		Optional<CliSession> os = repository.findByDeviceCodeHashForUpdate(CliSessionCodes.sha256Hex(deviceCode));
		if (os.isEmpty()) return new PollResult(PollOutcome.INVALID, null);
		CliSession s = os.get();
		ZonedDateTime now = ZonedDateTime.now();
		switch (s.getStatus()) {
			case PENDING:
				return new PollResult(s.getExpiresDate().isAfter(now) ? PollOutcome.AUTHORIZATION_PENDING : PollOutcome.EXPIRED, null);
			case DENIED:
				return new PollResult(PollOutcome.ACCESS_DENIED, null);
			case REVOKED:
				return new PollResult(PollOutcome.INVALID, null);
			case ACTIVE:
			default:
				if (s.getDeliveredDate() != null) return new PollResult(PollOutcome.INVALID, null); // already collected: the device code is single-use
				String refresh = CliSessionCodes.newOpaqueSecret();
				s.setRefreshTokenHash(CliSessionCodes.sha256Hex(refresh));
				s.setDeliveredDate(now);
				s.setDeviceCodeHash(null);
				s.setLastUsedDate(now);
				repository.save(s);
				return new PollResult(PollOutcome.DELIVERED, new Delivery(s, refresh));
		}
	}

	/** A live session behind a refresh token, or empty. Slides the expiry (the refresh is the activity). */
	/** A refresh: the session, slid forward, and the refresh token the client must persist and use from now on. */
	public record Refreshed(CliSession session, String refreshToken) {}

	/** What a presented refresh token means for its session. */
	enum RefreshVerdict { CURRENT, PREVIOUS_IN_GRACE, REUSED, UNKNOWN }

	static RefreshVerdict verdict(CliSession s, String presentedHash, ZonedDateTime now) {
		if (s == null || s.getStatus() != Status.ACTIVE || !s.getExpiresDate().isAfter(now)) return RefreshVerdict.UNKNOWN;
		if (presentedHash.equals(s.getRefreshTokenHash())) return RefreshVerdict.CURRENT;
		if (presentedHash.equals(s.getPreviousRefreshTokenHash())) {
			boolean inGrace = s.getRotatedDate() != null && s.getRotatedDate().plus(CliSessionCodes.ROTATION_GRACE).isAfter(now);
			return inGrace ? RefreshVerdict.PREVIOUS_IN_GRACE : RefreshVerdict.REUSED;
		}
		return RefreshVerdict.UNKNOWN;
	}

	/**
	 * Rotation on every refresh: the presented token retires, a new one is issued, the session
	 * slides. The retired token is honoured for a short grace window and then answers the
	 * current token again (a client that crashed before persisting, or retried a timed-out
	 * request); after the window its reuse means two holders, and the session is revoked.
	 * The retired token cannot be returned to the caller in the grace case because only its hash
	 * is stored, so the grace path re-rotates: the caller gets a fresh token either way.
	 */
	@Transactional
	public Optional<Refreshed> refresh(String refreshToken, String ip) {
		if (refreshToken == null || refreshToken.isBlank()) return Optional.empty();
		String hash = CliSessionCodes.sha256Hex(refreshToken);
		ZonedDateTime now = ZonedDateTime.now();
		// the row is locked for the whole rotate-or-revoke, so a retried refresh cannot lose its update and a
		// refresh racing a reuse revocation cannot write the row back as active
		Optional<CliSession> os = repository.findByRefreshTokenHashForUpdate(hash);
		if (os.isEmpty()) os = repository.findByPreviousRefreshTokenHashForUpdate(hash);
		if (os.isEmpty()) return Optional.empty();
		CliSession s = os.get();
		RefreshVerdict verdict = verdict(s, hash, now);
		// the key behind the session must still be usable before anything on the row changes: a refusal here
		// must not retire the client's token for a session that is dead anyway, and the managed entity would
		// otherwise flush a half-rotation at commit even without an explicit save
		if (verdict == RefreshVerdict.CURRENT || verdict == RefreshVerdict.PREVIOUS_IN_GRACE) {
			boolean keyActive = s.getApiKey() != null && apiKeyService.getApiKey(s.getApiKey())
					.map(ak -> ApiKeyData.dataFromRecord(ak).getStatus() == ApiKeyStatus.ACTIVE).orElse(false);
			if (!keyActive) return Optional.empty();
		}
		switch (verdict) {
			case REUSED:
				log.warn("SECURITY: retired refresh token of CLI session {} presented again from {}; revoking the session (two holders)", s.getUuid(), ip);
				revoke(s, WhoUpdated.getApiWhoUpdated(s.getApiKey(), ip).withActor(s.getUser()));
				return Optional.empty();
			case UNKNOWN:
				return Optional.empty();
			case CURRENT:
				s.setPreviousRefreshTokenHash(s.getRefreshTokenHash());
				s.setRotatedDate(now);
				break;
			case PREVIOUS_IN_GRACE:
			default:
				// keep the retired hash and its window; the current token is replaced once more
				break;
		}
		String fresh = CliSessionCodes.newOpaqueSecret();
		s.setRefreshTokenHash(CliSessionCodes.sha256Hex(fresh));
		s.setExpiresDate(CliSessionCodes.slidExpiry(s.getApprovedDate(), now));
		s.setLastUsedDate(now);
		repository.save(s);
		return Optional.of(new Refreshed(s, fresh));
	}
	/** The session a refresh token (current, or retired within the grace window) belongs to, when still active. */
	private Optional<CliSession> live(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) return Optional.empty();
		String hash = CliSessionCodes.sha256Hex(refreshToken);
		ZonedDateTime now = ZonedDateTime.now();
		Optional<CliSession> os = repository.findByRefreshTokenHashForUpdate(hash);
		if (os.isEmpty()) os = repository.findByPreviousRefreshTokenHashForUpdate(hash);
		return os.filter(s -> { RefreshVerdict v = verdict(s, hash, now); return v == RefreshVerdict.CURRENT || v == RefreshVerdict.PREVIOUS_IN_GRACE; });
	}
	public Optional<CliSession> usable(UUID sessionUuid, String fingerprint) {
		return repository.findById(sessionUuid)
				.filter(s -> s.getStatus() == Status.ACTIVE && s.getExpiresDate().isAfter(ZonedDateTime.now()))
				.filter(s -> s.getRefreshTokenHash() != null && ApiTokenService.fingerprint(s).equals(fingerprint));
	}

	/** Logout: the CLI hands back its refresh token. A session key created for this login goes with it. */
	@Transactional
	public boolean revokeByRefreshToken(String refreshToken, String ip) {
		Optional<CliSession> os = live(refreshToken);
		if (os.isEmpty()) return false;
		revoke(os.get(), WhoUpdated.getApiWhoUpdated(os.get().getApiKey(), ip).withActor(os.get().getUser()));
		return true;
	}

	/** A user revokes one of their own sessions, or an org admin revokes any session in the org (checked by the caller). */
	@Transactional
	public void revoke(CliSession s, WhoUpdated wu) {
		if (s.getStatus() != Status.ACTIVE) return;
		s.setStatus(Status.REVOKED);
		s.setRevokedDate(ZonedDateTime.now());
		repository.save(s);
		// a key that exists only for this session is deleted with it, unless another session still uses it
		if (s.getApiKey() != null && repository.countOtherActiveByApiKey(s.getApiKey(), s.getUuid()) == 0) {
			apiKeyService.getApiKey(s.getApiKey()).ifPresent(ak -> {
				if (ApiKeyData.dataFromRecord(ak).getOrigin() == ApiKeyOrigin.CLI_SESSION) apiKeyService.deleteApiKey(ak.getUuid(), wu);
			});
		}
		log.info("CLI session {} revoked (key {})", s.getUuid(), s.getApiKey());
	}

	public Optional<CliSession> get(UUID uuid) { return repository.findById(uuid); }
	public List<CliSession> listMine(UUID user) { return repository.findActiveByUser(user); }
	public List<CliSession> listByKey(UUID apiKey) { return repository.findActiveByApiKey(apiKey); }
	public void touchLastUsed(UUID uuid) { try { repository.touchLastUsed(uuid); } catch (RuntimeException e) { log.error("could not record last use of CLI session {}", uuid, e); } }
	public int purge() { return repository.purge(ZonedDateTime.now().minus(CliSessionCodes.ENDED_RETENTION)); }

	/** Owner of a session for the actor claim and the listing; empty when the user is gone. */
	public Optional<UserData> owner(CliSession s) {
		return s.getUser() == null ? Optional.empty() : userService.getUserData(s.getUser()).filter(u -> u.getStatus() == UserStatus.ACTIVE);
	}
}
