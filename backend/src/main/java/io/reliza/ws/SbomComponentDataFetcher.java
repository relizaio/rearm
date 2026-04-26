/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseSbomComponent;
import io.reliza.model.RelizaObject;
import io.reliza.model.SbomComponent;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.service.AuthorizationService;
import io.reliza.service.SbomComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;

/**
 * GraphQL surface for the per-release SBOM component aggregation and the
 * forward / reverse dependency graph surfaced on each node.
 *
 * <p>The persisted edge direction is in-edges (parents): each row stores the
 * components that depend on it. {@code dependedOnBy} is therefore a direct
 * read of the row's {@code parents} jsonb, while {@code dependencies}
 * (forward edges) is reconstructed in memory by inverting parents across all
 * rows of the same release.
 */
@DgsComponent
public class SbomComponentDataFetcher {

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private UserService userService;

	@Autowired
	private SbomComponentService sbomComponentService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getReleaseSbomComponents")
	public List<Map<String, Object>> getReleaseSbomComponents(
			@InputArgument("releaseUuid") UUID releaseUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(
				oud.get(), PermissionFunction.RESOURCE, PermissionScope.RELEASE,
				releaseUuid, List.of(ro), CallType.READ);
		return sbomComponentService.listReleaseSbomComponents(releaseUuid).stream()
				.map(SbomComponentDataFetcher::toDto)
				.toList();
	}

	/**
	 * Operator force-reconcile entry point. Bypasses the every-minute queue
	 * and rebuilds the release's SBOM rows synchronously, surfacing any error
	 * to the caller. Used to recover releases stuck in the queue or to verify
	 * a fix without waiting for the next scheduler tick.
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "reconcileReleaseSbomComponents")
	public Boolean reconcileReleaseSbomComponents(
			@InputArgument("releaseUuid") UUID releaseUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(
				oud.get(), PermissionFunction.RESOURCE, PermissionScope.RELEASE,
				releaseUuid, List.of(ro), CallType.WRITE);
		sbomComponentService.reconcileReleaseSbomComponents(releaseUuid);
		return true;
	}

	@DgsData(parentType = "ReleaseSbomComponent", field = "component")
	public Map<String, Object> getComponent(DgsDataFetchingEnvironment dfe) {
		UUID componentUuid = extractUuid(dfe.getSource(), "sbomComponentUuid");
		if (componentUuid == null) return null;
		return sbomComponentService.getSbomComponent(componentUuid)
				.map(SbomComponentDataFetcher::toComponentDto)
				.orElse(null);
	}

	/**
	 * Forward edges (this component → its dependencies) reconstructed in
	 * memory from the per-release inverted index. Loads all rows for the
	 * release once, then collects rows whose {@code parents} list references
	 * the current row as a source — each such row becomes a forward edge
	 * with its declaringArtifacts copied through.
	 */
	@DgsData(parentType = "ReleaseSbomComponent", field = "dependencies")
	public List<Map<String, Object>> getDependencies(DgsDataFetchingEnvironment dfe) {
		UUID releaseUuid = extractUuid(dfe.getSource(), "releaseUuid");
		UUID sbomComponentUuid = extractUuid(dfe.getSource(), "sbomComponentUuid");
		if (releaseUuid == null || sbomComponentUuid == null) return List.of();
		String thisUuidStr = sbomComponentUuid.toString();
		List<Map<String, Object>> out = new ArrayList<>();
		for (ReleaseSbomComponent row : sbomComponentService.listReleaseSbomComponents(releaseUuid)) {
			List<Map<String, Object>> parents = row.getParents();
			if (parents == null) continue;
			for (Map<String, Object> parentEntry : parents) {
				if (parentEntry == null) continue;
				if (!thisUuidStr.equals(parentEntry.get("sourceSbomComponentUuid"))) continue;
				Map<String, Object> edge = new LinkedHashMap<>();
				edge.put("targetSbomComponentUuid", row.getSbomComponentUuid().toString());
				edge.put("targetCanonicalPurl", lookupCanonicalPurl(row));
				edge.put("relationshipType", parentEntry.get("relationshipType"));
				edge.put("declaringArtifacts", parentEntry.get("declaringArtifacts"));
				edge.put("releaseUuid", releaseUuid);
				out.add(edge);
			}
		}
		out.sort((a, b) -> {
			String ta = (String) a.get("targetCanonicalPurl");
			String tb = (String) b.get("targetCanonicalPurl");
			if (ta == null) ta = "";
			if (tb == null) tb = "";
			int byTarget = ta.compareTo(tb);
			if (byTarget != 0) return byTarget;
			String ra = (String) a.get("relationshipType");
			String rb = (String) b.get("relationshipType");
			if (ra == null) ra = "";
			if (rb == null) rb = "";
			return ra.compareTo(rb);
		});
		return out;
	}

	/**
	 * Reverse edges (components in the same release that depend on this one).
	 * Direct read of the row's {@code parents} jsonb — for each parent entry
	 * we resolve the sourceSbomComponentUuid back to its release_sbom_components
	 * row in the current release and project it as a ReleaseSbomComponent.
	 */
	@DgsData(parentType = "ReleaseSbomComponent", field = "dependedOnBy")
	public List<Map<String, Object>> getDependedOnBy(DgsDataFetchingEnvironment dfe) {
		UUID releaseUuid = extractUuid(dfe.getSource(), "releaseUuid");
		Object src = dfe.getSource();
		List<Map<String, Object>> parents = extractParents(src);
		if (releaseUuid == null || parents == null || parents.isEmpty()) return List.of();
		List<ReleaseSbomComponent> releaseRows = sbomComponentService.listReleaseSbomComponents(releaseUuid);
		Map<String, ReleaseSbomComponent> byComponentUuid = new LinkedHashMap<>();
		for (ReleaseSbomComponent r : releaseRows) {
			byComponentUuid.put(r.getSbomComponentUuid().toString(), r);
		}
		List<Map<String, Object>> out = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (Map<String, Object> parentEntry : parents) {
			if (parentEntry == null) continue;
			String sourceUuid = (String) parentEntry.get("sourceSbomComponentUuid");
			if (sourceUuid == null || !seen.add(sourceUuid)) continue;
			ReleaseSbomComponent row = byComponentUuid.get(sourceUuid);
			if (row != null) out.add(toDto(row));
		}
		return out;
	}

	@DgsData(parentType = "ReleaseSbomDependency", field = "target")
	public Map<String, Object> getDependencyTarget(DgsDataFetchingEnvironment dfe) {
		// The edge source already carries targetSbomComponentUuid; resolve the
		// release_sbom_components row by (releaseUuid, targetSbomComponentUuid).
		Object src = dfe.getSource();
		UUID targetUuid = extractUuid(src, "targetSbomComponentUuid");
		if (targetUuid == null) return null;
		UUID releaseUuid = extractUuid(src, "releaseUuid");
		if (releaseUuid == null) return null;
		return sbomComponentService.listReleaseSbomComponents(releaseUuid).stream()
				.filter(r -> targetUuid.equals(r.getSbomComponentUuid()))
				.findFirst()
				.map(SbomComponentDataFetcher::toDto)
				.orElse(null);
	}

	private static Map<String, Object> toDto(ReleaseSbomComponent row) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("uuid", row.getUuid());
		dto.put("releaseUuid", row.getReleaseUuid());
		dto.put("sbomComponentUuid", row.getSbomComponentUuid());
		dto.put("artifactParticipations", row.getArtifactParticipations());
		dto.put("parents", row.getParents());
		dto.put("createdDate", row.getCreatedDate());
		dto.put("lastUpdatedDate", row.getLastUpdatedDate());
		return dto;
	}

	private static Map<String, Object> toComponentDto(SbomComponent sc) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("uuid", sc.getUuid());
		dto.put("canonicalPurl", sc.getCanonicalPurl());
		Map<String, Object> rd = sc.getRecordData();
		if (rd != null) {
			dto.put("type", rd.get("type"));
			dto.put("group", rd.get("group"));
			dto.put("name", rd.get("name"));
			dto.put("version", rd.get("version"));
			dto.put("isRoot", Boolean.TRUE.equals(rd.get("isRoot")));
		} else {
			dto.put("isRoot", false);
		}
		return dto;
	}

	private String lookupCanonicalPurl(ReleaseSbomComponent row) {
		return sbomComponentService.getSbomComponent(row.getSbomComponentUuid())
				.map(SbomComponent::getCanonicalPurl)
				.orElse(null);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> extractParents(Object source) {
		if (source instanceof Map<?, ?> map) {
			Object v = ((Map<String, Object>) map).get("parents");
			if (v instanceof List<?> list) return (List<Map<String, Object>>) list;
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static UUID extractUuid(Object source, String key) {
		if (source == null) return null;
		if (source instanceof Map<?, ?> map) {
			Object v = ((Map<String, Object>) map).get(key);
			if (v instanceof UUID u) return u;
			if (v instanceof String s && !s.isBlank()) {
				try {
					return UUID.fromString(s);
				} catch (IllegalArgumentException iae) {
					return null;
				}
			}
		}
		return null;
	}
}
