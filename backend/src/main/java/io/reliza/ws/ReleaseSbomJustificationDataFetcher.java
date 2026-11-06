/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.InputArgument;

import lombok.extern.slf4j.Slf4j;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.JustifiedUnknown;
import io.reliza.model.JustifiedUnknownField;
import io.reliza.model.JustifiedUnknownReason;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseSbom;
import io.reliza.model.ReleaseSbomJustification;
import io.reliza.model.SupportBulkSelector;
import io.reliza.model.SupportTextBounds;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.service.AuthorizationService;
import io.reliza.service.ReleaseSbomJustificationService;
import io.reliza.service.ReleaseSbomJustificationService.Head;
import io.reliza.service.ReleaseSbomJustificationService.HashlessKeys;
import io.reliza.service.ReleaseSbomJustificationService.Overwrite;
import io.reliza.service.ReleaseSbomJustificationService.WriteResult;
import io.reliza.service.SbomComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;

/**
 * Hash justifications per release SBOM identity head (FDAW-14 3.3, 3.8). Every verb runs in the fixed
 * order: its keys, texts and selector bounded with no database access; the head loaded and gated
 * ({@link ReleaseSbomJustificationService#writableHead}: COMPONENT WRITE on the head's release's
 * component; {@link ReleaseSbomJustificationService#readableHead}: RELEASE READ); then the document read,
 * the match and the cap; then the write. An absent head, another organisation's head and a caller without
 * the right all read {@value ReleaseSbomJustificationService#NOT_FOUND}.
 */
@Slf4j
@DgsComponent
public class ReleaseSbomJustificationDataFetcher {

	@Autowired
	private ReleaseSbomJustificationService releaseSbomJustificationService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private UserService userService;

	// ------------------------------------------------------------------ mutations

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setReleaseSbomHashJustification")
	public Map<String, Object> setReleaseSbomHashJustification(
			@InputArgument("releaseSbom") UUID releaseSbom,
			@InputArgument("keys") List<String> keys,
			@InputArgument("code") JustifiedUnknownReason code,
			@InputArgument("text") String text,
			@InputArgument("reason") String reason) throws RelizaException {
		List<String> boundedKeys = refused(() -> SupportTextBounds.boundedKeys(keys,
				SbomComponentService.BULK_SUPPORT_MAX_IDS,
				ReleaseSbomJustificationService.explicitOverCap(null == keys ? 0 : keys.size())));
		String boundedText = boundedTextAndCode(code, text);
		String boundedReason = boundedReason(reason);
		UserData caller = caller();
		Head head = releaseSbomJustificationService.writableHead(releaseSbom, caller);
		WriteResult r = refused(() -> releaseSbomJustificationService.set(head, boundedKeys, code, boundedText,
				boundedReason, caller.getUuid(), null, Overwrite.REPLACE));
		return toJustificationsDto(r.row(), r.skippedKeys());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "clearReleaseSbomHashJustification")
	public Map<String, Object> clearReleaseSbomHashJustification(
			@InputArgument("releaseSbom") UUID releaseSbom,
			@InputArgument("keys") List<String> keys,
			@InputArgument("reason") String reason) throws RelizaException {
		List<String> boundedKeys = refused(() -> SupportTextBounds.boundedKeys(keys,
				SbomComponentService.BULK_SUPPORT_MAX_IDS,
				ReleaseSbomJustificationService.explicitOverCap(null == keys ? 0 : keys.size())));
		String boundedReason = boundedReason(reason);
		if (null == boundedReason) throw new RelizaException("justified unknown: clearing it needs a reason");
		UserData caller = caller();
		Head head = releaseSbomJustificationService.writableHead(releaseSbom, caller);
		WriteResult r = refused(() -> releaseSbomJustificationService.clear(head, boundedKeys, boundedReason,
				caller.getUuid()));
		return toJustificationsDto(r.row(), List.of());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "bulkSetReleaseSbomHashJustificationBySelector")
	public Map<String, Object> bulkSetReleaseSbomHashJustificationBySelector(
			@InputArgument("releaseSbom") UUID releaseSbom,
			@InputArgument("selector") Map<String, Object> selector,
			@InputArgument("code") JustifiedUnknownReason code,
			@InputArgument("text") String text,
			@InputArgument("reason") String reason) throws RelizaException {
		SupportBulkSelector sel = SbomComponentDataFetcher.selectorOf(selector);
		String boundedText = boundedTextAndCode(code, text);
		String boundedReason = boundedReason(reason);
		UserData caller = caller();
		Head head = releaseSbomJustificationService.writableHead(releaseSbom, caller);
		WriteResult r = refused(() -> releaseSbomJustificationService.setBySelector(head, sel, code, boundedText,
				boundedReason, caller.getUuid()));
		return toJustificationsDto(r.row(), r.skippedKeys());
	}

	// ------------------------------------------------------------------ queries

	/** The identity heads of a release (A1), RELEASE READ, each with its latest version's hashless count. */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releaseSboms")
	public List<Map<String, Object>> releaseSboms(@InputArgument("releaseUuid") UUID releaseUuid)
			throws RelizaException {
		UserData caller = caller();
		ReleaseData rd = sharedReleaseService.getReleaseData(releaseUuid)
				.orElseThrow(() -> new RelizaException("release not found"));
		authorizationService.isUserAuthorizedForObjectGraphQL(caller, PermissionFunction.RESOURCE,
				PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.READ);
		List<Map<String, Object>> out = new ArrayList<>();
		for (ReleaseSbom h : releaseSbomJustificationService.listByRelease(releaseUuid)) {
			Map<String, Object> dto = new LinkedHashMap<>();
			dto.put("uuid", h.getUuid());
			dto.put("profileName", h.getProfileName());
			dto.put("latestVersion", h.getLatestVersion());
			dto.put("hashlessCount", releaseSbomJustificationService.hashlessKeys(h).hashless().size());
			out.add(dto);
		}
		return out;
	}

	/** The hashless components of the head's latest version, in document order, each with its justification. */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releaseSbomHashlessComponents")
	public List<Map<String, Object>> releaseSbomHashlessComponents(@InputArgument("releaseSbom") UUID releaseSbom)
			throws RelizaException {
		Head head = releaseSbomJustificationService.readableHead(releaseSbom, caller());
		HashlessKeys doc = releaseSbomJustificationService.hashlessKeys(head.head());
		Map<String, JustifiedUnknown> hashes = releaseSbomJustificationService.current(head).getRecordData().hashes();
		Map<UUID, String> names = names(hashes.values());
		List<Map<String, Object>> out = new ArrayList<>();
		doc.hashless().values().forEach(n -> {
			Map<String, Object> dto = new LinkedHashMap<>();
			dto.put("key", n.key());
			dto.put("name", n.name());
			dto.put("version", n.version());
			dto.put("justification", SbomComponentDataFetcher.toUnknownDto(hashes.get(n.key()),
					JustifiedUnknownField.HASH, names));
			out.add(dto);
		});
		return out;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releaseSbomJustifications")
	public Map<String, Object> releaseSbomJustifications(@InputArgument("releaseSbom") UUID releaseSbom)
			throws RelizaException {
		Head head = releaseSbomJustificationService.readableHead(releaseSbom, caller());
		return toJustificationsDto(releaseSbomJustificationService.current(head), List.of());
	}

	/** The preview of a hash selector sweep: the matching hashless keys of the latest version, nothing written. */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releaseSbomHashlessSelectorCount")
	public int releaseSbomHashlessSelectorCount(
			@InputArgument("releaseSbom") UUID releaseSbom,
			@InputArgument("selector") Map<String, Object> selector) throws RelizaException {
		SupportBulkSelector sel = SbomComponentDataFetcher.selectorOf(selector);
		Head head = releaseSbomJustificationService.readableHead(releaseSbom, caller());
		return ReleaseSbomJustificationService.matching(
				releaseSbomJustificationService.hashlessKeys(head.head()), sel).size();
	}

	// ------------------------------------------------------------------ helpers

	private UserData caller() {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return userService.getUserDataByAuth(auth).orElseThrow();
	}

	/** The text bounded and the code checked against the HASH field, with no database access. */
	private static String boundedTextAndCode(JustifiedUnknownReason code, String text) throws RelizaException {
		return refused(() -> {
			String t = SupportTextBounds.boundedUnknownText(text);
			JustifiedUnknownField.HASH.validate(code, t);
			return t;
		});
	}

	private static String boundedReason(String reason) throws RelizaException {
		return refused(() -> SupportTextBounds.bounded(reason, "reason", SupportTextBounds.MAX_REASON_LENGTH));
	}

	private Map<String, Object> toJustificationsDto(ReleaseSbomJustification row, List<String> skippedKeys) {
		Map<String, JustifiedUnknown> hashes = row.getRecordData().hashes();
		Map<UUID, String> names = names(hashes.values());
		List<Map<String, Object>> items = new ArrayList<>();
		hashes.forEach((key, u) -> {
			Map<String, Object> dto = new LinkedHashMap<>();
			dto.put("key", key);
			dto.put("code", null == u.code() ? null : u.code().name());
			dto.put("text", u.text());
			dto.put("bulkAct", u.bulkAct());
			dto.put("assertedBy", u.assertedBy());
			dto.put("assertedByName", null == u.assertedBy() ? null : names.get(u.assertedBy()));
			dto.put("assertedAt", u.assertedAt());
			items.add(dto);
		});
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("releaseSbom", row.getReleaseSbom());
		out.put("revision", row.getRevision());
		out.put("hashes", items);
		out.put("skippedKeys", null == skippedKeys ? List.of() : skippedKeys);
		return out;
	}

	/** One display-name lookup per distinct asserter; an unresolvable user is absent (never throws). */
	private Map<UUID, String> names(Iterable<JustifiedUnknown> unknowns) {
		Set<UUID> users = new HashSet<>();
		for (JustifiedUnknown u : unknowns) {
			if (null != u && null != u.assertedBy()) users.add(u.assertedBy());
		}
		Map<UUID, String> names = new HashMap<>();
		for (UUID user : users) {
			try {
				userService.getUserData(user).map(UserData::getName).ifPresent(n -> names.put(user, n));
			} catch (RuntimeException e) {
				log.warn("Could not resolve the display name of justifier {}: {}", user, e.getMessage());
			}
		}
		return names;
	}

	@FunctionalInterface
	private interface Check<T> { T get(); }

	/** A service's {@link IllegalArgumentException} (a 3.11 text) as a GraphQL error. */
	private static <T> T refused(Check<T> check) throws RelizaException {
		try {
			return check.get();
		} catch (IllegalArgumentException e) {
			throw new RelizaException(e.getMessage());
		}
	}
}
