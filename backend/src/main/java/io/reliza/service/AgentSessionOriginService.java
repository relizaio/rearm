/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.FederatedContext;
import io.reliza.model.AgentSessionData.AuthMethod;
import io.reliza.model.AgentSessionData.OwnerSource;
import io.reliza.model.AgentSessionData.SessionDevice;
import io.reliza.model.AgentSessionData.SessionFederation;
import io.reliza.model.AgentSessionData.SessionOrigin;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.UserData;
import lombok.extern.slf4j.Slf4j;

/**
 * How an agent session came to be opened -- the credential, the person behind it when anything
 * names one, and the device -- and who may see which part of that.
 *
 * <p>The split is by what identifies a person. Hostnames (often the owner's name), IP addresses
 * and a federated actor's login are shown only to org admins and to the session's owner; the
 * auth method, the owner, the OS, the time zone and the client are shown to anyone who can read
 * the session.
 */
@Service
@Slf4j
public class AgentSessionOriginService {

	@Autowired private ApiKeyService apiKeyService;
	@Autowired private CliSessionService cliSessionService;

	/** Longest value kept from anything a client reports about itself. */
	static final int MAX_REPORTED = 200;

	/**
	 * What a client says about its device on initialize, before it is clipped and stored. Every
	 * field is optional and self-reported; the server's own observation is kept apart from it.
	 */
	public static record DeviceReport(String hostname, String os, String timeZone, String client) {}

	/**
	 * Resolve the origin of a session being opened by this principal.
	 *
	 * @param apiKeyUuid the key the principal acts as, already verified by the caller
	 * @param observedIp the client address as this server saw it
	 * @param reported what the client said about its device, or null
	 */
	public SessionOrigin resolve(AuthHeaderParse ahp, UUID apiKeyUuid, String observedIp, DeviceReport reported) {
		AuthMethod method = null == ahp ? AuthMethod.KEY_SECRET
				: ahp.isFederated() ? AuthMethod.FEDERATED
				: null != ahp.getActorUser() ? AuthMethod.CLI_LOGIN
				: AuthMethod.KEY_SECRET;

		// Precedence is strongest claim first: a login names who approved this device, a personal
		// key names whose key it is, and a holder only who answers for a secret that may have been
		// handed on. A login made with a Free Form key is therefore attributed to the login's user.
		UUID owner = null;
		OwnerSource ownerSource = null;
		if (null != ahp && null != ahp.getActorUser()) {
			owner = ahp.getActorUser();
			ownerSource = OwnerSource.CLI_LOGIN;
		} else if (null != ahp && ApiTypeEnum.USER == ahp.getType() && null != ahp.getObjUuid()) {
			owner = ahp.getObjUuid();
			ownerSource = OwnerSource.USER_KEY;
		} else if (null != ahp && ApiTypeEnum.FREEFORM == ahp.getType() && null != apiKeyUuid) {
			UUID holder = apiKeyService.getApiKeyData(apiKeyUuid).map(ApiKeyData::getHolder).orElse(null);
			if (null != holder) {
				owner = holder;
				ownerSource = OwnerSource.KEY_HOLDER;
			}
		}

		UUID cliSession = null == ahp ? null : ahp.getCliSession();
		SessionDevice loginDevice = null;
		if (null != cliSession) {
			loginDevice = cliSessionService.get(cliSession)
					.map(cs -> CliSessionService.DeviceInfo.fromMap(cs.getDeviceInfo()))
					.map(d -> new SessionDevice(d.reportedHostname(), d.reportedOs(), d.reportedTimeZone(),
							d.reportedClient(), d.observedIp()))
					.orElse(null);
		}

		return new SessionOrigin(method, cliSession, owner, ownerSource, loginDevice,
				reportedDevice(reported), clip(observedIp),
				null == ahp ? null : federation(ahp.getFederation()), ZonedDateTime.now());
	}

	static SessionDevice reportedDevice(DeviceReport r) {
		if (null == r) return null;
		SessionDevice d = new SessionDevice(clip(r.hostname()), clip(r.os()), clip(r.timeZone()),
				clip(r.client()), null);
		return null == d.hostname() && null == d.os() && null == d.timeZone() && null == d.client() ? null : d;
	}

	static SessionFederation federation(FederatedContext f) {
		if (null == f) return null;
		return new SessionFederation(f.ruleUuids(), f.provider(), f.issuer(), f.owner(), f.repository(),
				f.repositoryUri(), f.ref(), f.sha(), f.workflowRef(), f.environment(), f.event(), f.actor(),
				f.runId());
	}

	private static String clip(String v) {
		String t = StringUtils.trimToNull(v);
		return null == t ? null : t.substring(0, Math.min(MAX_REPORTED, t.length()));
	}

	// ---- visibility --------------------------------------------------------------------------

	/**
	 * Who is reading. A user of the web API, or a key of the programmatic one; for a key, the
	 * login user when the key's token came from a CLI login.
	 */
	public static record Viewer(UUID user, Predicate<UUID> adminOf, UUID apiKey, UUID actorUser) {
		public static Viewer ofUser(UserData ud) {
			return new Viewer(ud.getUuid(), org -> ud.isGlobalAdmin() || UserData.isOrgAdmin(ud, org), null, null);
		}
		public static Viewer ofKey(UUID apiKey, UUID actorUser) { return new Viewer(null, null, apiKey, actorUser); }
		public static Viewer nobody() { return new Viewer(null, null, null, null); }
	}

	/**
	 * Whether this viewer may see the personal fields of a session's origin: an org admin, the
	 * session's owner, or -- on the programmatic side -- the very key that opened the session,
	 * which is reading back what it reported about itself. A key with org-wide agent access is
	 * not a person and does not get other sessions' hostnames.
	 */
	public static boolean canSeePersonal(Viewer v, UUID sessionOrg, UUID sessionApiKey, SessionOrigin origin) {
		if (null == v) return false;
		UUID owner = null == origin ? null : origin.ownerUser();
		if (null != v.user()) {
			return (null != v.adminOf() && v.adminOf().test(sessionOrg)) || v.user().equals(owner);
		}
		if (null != v.apiKey() && v.apiKey().equals(sessionApiKey)) return true;
		return null != v.actorUser() && null != owner && owner.equals(v.actorUser());
	}

	/** Read side of {@link SessionOrigin}; see {@link #view}. */
	public static record SessionOriginView(AuthMethod authMethod, UUID cliSession, UUID ownerUser,
			OwnerSource ownerSource, SessionDevice loginDevice, SessionDevice reportedDevice,
			String observedIp, SessionFederation federation, ZonedDateTime capturedAt, boolean restricted) {}

	/**
	 * The origin as this viewer may see it. Withheld fields are null and {@code restricted} says
	 * so, so a UI can tell "hidden from you" from "never reported".
	 */
	public static SessionOriginView view(SessionOrigin o, boolean personal) {
		if (null == o) return null;
		if (personal) {
			return new SessionOriginView(o.authMethod(), o.cliSession(), o.ownerUser(), o.ownerSource(),
					o.loginDevice(), o.reportedDevice(), o.observedIp(), o.federation(), o.capturedAt(), false);
		}
		return new SessionOriginView(o.authMethod(), o.cliSession(), o.ownerUser(), o.ownerSource(),
				impersonal(o.loginDevice()), impersonal(o.reportedDevice()), null, impersonal(o.federation()),
				o.capturedAt(), true);
	}

	private static SessionDevice impersonal(SessionDevice d) {
		return null == d ? null : new SessionDevice(null, d.os(), d.timeZone(), d.client(), null);
	}

	private static SessionFederation impersonal(SessionFederation f) {
		return null == f ? null : new SessionFederation(f.ruleUuids(), f.provider(), f.issuer(), f.owner(),
				f.repository(), f.repositoryUri(), f.ref(), f.sha(), f.workflowRef(), f.environment(), f.event(),
				null, f.runId());
	}
}
