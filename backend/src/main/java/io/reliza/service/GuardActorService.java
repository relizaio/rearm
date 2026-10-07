/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.model.ApiKey;
import io.reliza.model.WhoUpdated;

/**
 * Who is acting, for an action guard's {@code action.actor} (task 3204c981, gaps §1.14).
 *
 * <p>A guard could say what was being done and to what, never by whom, so "READY_TO_SHIP only by a
 * person" could not be written. The call's {@link WhoUpdated} already knows: MANUAL is a signed-in
 * person, API an API key, AUTO the system. Always the same four keys, strings, empty when not
 * applicable -- the rule the {@code action} map follows -- so an expression never has to guard
 * against a key being absent.
 */
@Service
public class GuardActorService {

	public static final String USER = "USER";
	public static final String API_KEY = "API_KEY";
	public static final String SYSTEM = "SYSTEM";

	@Autowired private ApiKeyService apiKeyService;

	/** {@code action.actor} for this call; the key type is one read, for a key actor only. */
	public Map<String, Object> actor(WhoUpdated wu) {
		return of(wu, key -> apiKeyService.getApiKey(key).map(ApiKey::getObjectType).map(Enum::name));
	}

	/**
	 * @param keyTypeOf the key's type name (FREEFORM, USER, FEDERATED, ...) for an API actor
	 */
	public static Map<String, Object> of(WhoUpdated wu, Function<UUID, Optional<String>> keyTypeOf) {
		String kind = kindOf(null == wu ? null : wu.getCreatedType());
		UUID by = null == wu ? null : wu.getLastUpdatedBy();
		String keyType = API_KEY.equals(kind) && null != by ? keyTypeOf.apply(by).orElse("") : "";
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("kind", kind);
		m.put("id", SYSTEM.equals(kind) || null == by ? "" : by.toString());
		m.put("keyType", keyType);
		m.put("ip", null == wu || null == wu.getLastUpdatedIp() ? "" : wu.getLastUpdatedIp());
		return m;
	}

	/** MANUAL is a person, API a key; everything else -- AUTO, MANUAL_AND_AUTO, TEST, none -- the system. */
	static String kindOf(ProgrammaticType t) {
		if (ProgrammaticType.MANUAL == t) return USER;
		if (ProgrammaticType.API == t) return API_KEY;
		return SYSTEM;
	}
}
