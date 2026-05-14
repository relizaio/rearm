/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import graphql.execution.DataFetcherResult;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseSbomComponent;
import io.reliza.model.RelizaObject;
import io.reliza.model.SbomComponent;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.dto.CveSearchResultDto.ComponentWithBranches;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.SbomComponentService;
import io.reliza.service.SbomComponentService.ComponentPurlToSbom;
import io.reliza.service.SbomComponentService.SbomComponentSearchQuery;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.oss.OssPerspectiveService;

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

	@Autowired
	private GetOrganizationService getOrganizationService;

	@Autowired
	private GetComponentService getComponentService;

	@Autowired
	private OssPerspectiveService ossPerspectiveService;

	/**
	 * Per-request graph state shared across all field resolvers below via DGS
	 * {@code localContext}. Built once at the top-level query so that
	 * {@code component}, {@code dependencies}, {@code dependedOnBy} and
	 * {@code dependencies.target} are all O(1) map reads instead of N+1
	 * round-trips to the DB.
	 *
	 * @param rowByComponentUuid    the release's rows keyed by their canonical
	 *                              component uuid
	 * @param componentByUuid       canonical {@code sbom_components} rows
	 *                              referenced by any row or any parent edge
	 * @param forwardEdgesBySource  precomputed forward edges (sourceUuid →
	 *                              outgoing edge maps) — built once by
	 *                              inverting all {@code parents} entries
	 */
	private record ReleaseGraphContext(
			Map<UUID, ReleaseSbomComponent> rowByComponentUuid,
			Map<UUID, SbomComponent> componentByUuid,
			Map<UUID, List<Map<String, Object>>> forwardEdgesBySource) {}

	private static final Comparator<Map<String, Object>> EDGE_SORTER = (a, b) -> {
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
	};

	private record ReleaseGraphLoad(List<Map<String, Object>> dtos, ReleaseGraphContext ctx) {}

	/**
	 * Build the per-request graph state used by both the full-release query
	 * and the single-component graph query. Loads the release's rows once,
	 * bulk-fetches every {@code sbom_components} row referenced by any row
	 * or any parent edge, indexes rows by canonical component, and inverts
	 * {@code parents} into a forward-edge map keyed by source uuid so the
	 * downstream field resolvers are O(1) lookups instead of N+1 queries.
	 */
	private ReleaseGraphLoad loadReleaseGraph(UUID releaseUuid, UUID orgUuid) {
		List<ReleaseSbomComponent> rows = sbomComponentService.listReleaseSbomComponents(releaseUuid);

		Set<UUID> referencedComponentIds = new HashSet<>();
		for (ReleaseSbomComponent row : rows) {
			referencedComponentIds.add(row.getSbomComponentUuid());
			List<Map<String, Object>> parents = row.getParents();
			if (parents == null) continue;
			for (Map<String, Object> p : parents) {
				if (p == null) continue;
				UUID src = parseUuid(p.get("sourceSbomComponentUuid"));
				if (src != null) referencedComponentIds.add(src);
			}
		}
		Map<UUID, SbomComponent> componentByUuid = orgUuid == null
				? Map.of()
				: sbomComponentService.findSbomComponentsByIds(referencedComponentIds, orgUuid);

		Map<UUID, ReleaseSbomComponent> rowByComponentUuid = new HashMap<>(rows.size() * 2);
		for (ReleaseSbomComponent row : rows) {
			rowByComponentUuid.put(row.getSbomComponentUuid(), row);
		}

		Map<UUID, List<Map<String, Object>>> forwardEdgesBySource = new HashMap<>();
		for (ReleaseSbomComponent row : rows) {
			List<Map<String, Object>> parents = row.getParents();
			if (parents == null) continue;
			SbomComponent targetComponent = componentByUuid.get(row.getSbomComponentUuid());
			String targetCanonicalPurl = targetComponent == null ? null : targetComponent.getCanonicalPurl();
			for (Map<String, Object> parentEntry : parents) {
				if (parentEntry == null) continue;
				UUID sourceUuid = parseUuid(parentEntry.get("sourceSbomComponentUuid"));
				if (sourceUuid == null) continue;
				Map<String, Object> edge = new LinkedHashMap<>();
				edge.put("targetSbomComponentUuid", row.getSbomComponentUuid().toString());
				edge.put("targetCanonicalPurl", targetCanonicalPurl);
				edge.put("relationshipType", parentEntry.get("relationshipType"));
				edge.put("declaringArtifacts", parentEntry.get("declaringArtifacts"));
				edge.put("releaseUuid", releaseUuid);
				forwardEdgesBySource.computeIfAbsent(sourceUuid, k -> new ArrayList<>()).add(edge);
			}
		}
		for (List<Map<String, Object>> edges : forwardEdgesBySource.values()) {
			edges.sort(EDGE_SORTER);
		}

		ReleaseGraphContext ctx = new ReleaseGraphContext(
				rowByComponentUuid, componentByUuid, forwardEdgesBySource);
		List<Map<String, Object>> dtos = rows.stream()
				.map(SbomComponentDataFetcher::toDto)
				.toList();
		return new ReleaseGraphLoad(dtos, ctx);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getReleaseSbomComponents")
	public DataFetcherResult<List<Map<String, Object>>> getReleaseSbomComponents(
			@InputArgument("releaseUuid") UUID releaseUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(
				oud.get(), PermissionFunction.RESOURCE, PermissionScope.RELEASE,
				releaseUuid, List.of(ro), CallType.READ);

		UUID orgUuid = ord.map(ReleaseData::getOrg).orElse(null);
		ReleaseGraphLoad load = loadReleaseGraph(releaseUuid, orgUuid);
		return DataFetcherResult.<List<Map<String, Object>>>newResult()
				.data(load.dtos())
				.localContext(load.ctx())
				.build();
	}

	/**
	 * Single-component graph view: same merge semantics and field resolvers
	 * as {@code getReleaseSbomComponents}, but returns just the merged row
	 * for one canonical {@code sbom_components.uuid}. The graph page navigates
	 * by stable {@code (releaseUuid, sbomComponentUuid)} without having to
	 * fetch every row in the release and disambiguate client-side.
	 *
	 * <p>Returns null if the canonical component isn't present in the release
	 * (or its dep tree, for product releases). The full release graph
	 * {@link ReleaseGraphContext} is still attached as localContext so the
	 * {@code dependencies} / {@code dependedOnBy} / {@code target} field
	 * resolvers can resolve cross-component edges into other rows the page
	 * may walk to.
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getReleaseSbomComponentGraph")
	public DataFetcherResult<Map<String, Object>> getReleaseSbomComponentGraph(
			@InputArgument("releaseUuid") UUID releaseUuid,
			@InputArgument("sbomComponentUuid") UUID sbomComponentUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(
				oud.get(), PermissionFunction.RESOURCE, PermissionScope.RELEASE,
				releaseUuid, List.of(ro), CallType.READ);

		UUID orgUuid = ord.map(ReleaseData::getOrg).orElse(null);
		ReleaseGraphLoad load = loadReleaseGraph(releaseUuid, orgUuid);
		ReleaseSbomComponent target = load.ctx().rowByComponentUuid().get(sbomComponentUuid);
		if (target == null) {
			return DataFetcherResult.<Map<String, Object>>newResult()
					.data(null)
					.localContext(load.ctx())
					.build();
		}
		return DataFetcherResult.<Map<String, Object>>newResult()
				.data(toDto(target))
				.localContext(load.ctx())
				.build();
	}

	/**
	 * Operator force-reconcile entry point. Bypasses the every-minute queue
	 * and rebuilds the release's SBOM rows synchronously, surfacing any error
	 * to the caller. Used to recover releases stuck in the queue or to verify
	 * a fix without waiting for the next scheduler tick. For PRODUCT releases
	 * the call cascades to every transitive dependency so the read-time
	 * aggregation on the product reflects fresh dep state by the time this
	 * returns — see {@link SbomComponentService#forceReconcileWithDeps}.
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
		sbomComponentService.forceReconcileWithDeps(releaseUuid);
		return true;
	}

	/**
	 * Native (non-DependencyTrack) analogue of {@code releasesByDtrackProjects}.
	 * Given canonical sbom_component UUIDs, returns the org's releases that
	 * reference any of them via {@code release_sbom_components}, grouped into
	 * the same {@code ComponentWithBranches} shape.
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releasesBySbomComponents")
	public List<ComponentWithBranches> releasesBySbomComponents(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("sbomComponentUuids") List<UUID> sbomComponentUuids,
			@InputArgument("perspectiveUuid") UUID perspectiveUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		var od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		final Set<UUID> perspectiveComponentUuids;
		if (null == perspectiveUuid) {
			authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.READ);
			perspectiveComponentUuids = null;
		} else {
			var pd = ossPerspectiveService.getPerspectiveData(perspectiveUuid).orElseThrow();
			authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.PERSPECTIVE, perspectiveUuid, List.of(ro, pd), CallType.READ);
			perspectiveComponentUuids = getComponentService.listComponentsByPerspective(perspectiveUuid).stream()
					.map(ComponentData::getUuid)
					.collect(Collectors.toSet());
		}

		Set<UUID> releaseIds = sbomComponentService.findReleaseUuidsBySbomComponents(sbomComponentUuids, orgUuid);
		List<ComponentWithBranches> ret = sharedReleaseService.findReleaseDatasByReleaseIds(releaseIds, orgUuid);
		if (null != perspectiveComponentUuids) {
			ret = ret.stream()
					.filter(cwb -> perspectiveComponentUuids.contains(cwb.uuid()))
					.toList();
		}
		return ret;
	}

	/**
	 * Native analogue of {@code sbomComponentSearch}. Matches each (name,
	 * version) query against {@code sbom_components} narrowed to the org via
	 * {@code release_sbom_components}, returns canonical purl + sbom_component
	 * UUIDs grouped by purl. The UI feeds the resulting UUIDs into
	 * {@code releasesBySbomComponents}.
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "sbomComponentSearchNative")
	public List<ComponentPurlToSbom> sbomComponentSearchNative(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("queries") List<Map<String, String>> queries,
			@InputArgument("perspectiveUuid") UUID perspectiveUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		var od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		if (null == perspectiveUuid) {
			authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.ESSENTIAL_READ);
		} else {
			var pd = ossPerspectiveService.getPerspectiveData(perspectiveUuid).orElseThrow();
			authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.PERSPECTIVE, perspectiveUuid, List.of(ro, pd), CallType.ESSENTIAL_READ);
		}
		List<SbomComponentSearchQuery> searchQueries = queries.stream()
				.map(q -> new SbomComponentSearchQuery(q.get("name"), q.get("version")))
				.toList();
		return sbomComponentService.searchSbomComponentsBatch(searchQueries, orgUuid);
	}

	/**
	 * Native analogue of {@code searchDtrackComponentByPurlAndProjects}.
	 * Canonicalizes the purl (strips qualifiers + subpath) and returns the
	 * matching {@code sbom_components.uuid} within the caller's org, or null
	 * if none. With per-org pinning canonical purl is unique per (org,
	 * canonical_purl), so the org parameter is what scopes the lookup.
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "searchSbomComponentByPurl")
	public UUID searchSbomComponentByPurl(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("purl") String purl) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		var od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.ESSENTIAL_READ);
		return sbomComponentService.searchSbomComponentByPurl(purl, orgUuid);
	}

	@DgsData(parentType = "ReleaseSbomComponent", field = "component")
	public Map<String, Object> getComponent(DgsDataFetchingEnvironment dfe) {
		ReleaseGraphContext ctx = dfe.getLocalContext();
		UUID componentUuid = extractUuid(dfe.getSource(), "sbomComponentUuid");
		if (componentUuid == null) return null;
		if (ctx != null) {
			SbomComponent sc = ctx.componentByUuid().get(componentUuid);
			return sc == null ? null : toComponentDto(sc);
		}
		// Defensive fallback if this resolver is reached outside the top-level
		// query path (no localContext); behaves like the original single-row
		// fetch, so direct callers keep working.
		return sbomComponentService.getSbomComponent(componentUuid)
				.map(SbomComponentDataFetcher::toComponentDto)
				.orElse(null);
	}

	/**
	 * Forward edges (this component → its dependencies) read directly from the
	 * pre-built inverted index on the request's {@link ReleaseGraphContext} —
	 * O(1) instead of an O(N) scan of every release row per call.
	 */
	@DgsData(parentType = "ReleaseSbomComponent", field = "dependencies")
	public List<Map<String, Object>> getDependencies(DgsDataFetchingEnvironment dfe) {
		ReleaseGraphContext ctx = dfe.getLocalContext();
		if (ctx == null) return List.of();
		UUID sbomComponentUuid = extractUuid(dfe.getSource(), "sbomComponentUuid");
		if (sbomComponentUuid == null) return List.of();
		return ctx.forwardEdgesBySource().getOrDefault(sbomComponentUuid, List.of());
	}

	/**
	 * Reverse edges (components in the same release that depend on this one).
	 * Direct read of the row's {@code parents} jsonb; the source rows are
	 * resolved through the request-scoped {@code rowByComponentUuid} map so
	 * we don't reload every release row per call.
	 */
	@DgsData(parentType = "ReleaseSbomComponent", field = "dependedOnBy")
	public List<Map<String, Object>> getDependedOnBy(DgsDataFetchingEnvironment dfe) {
		ReleaseGraphContext ctx = dfe.getLocalContext();
		if (ctx == null) return List.of();
		List<Map<String, Object>> parents = extractParents(dfe.getSource());
		if (parents == null || parents.isEmpty()) return List.of();
		List<Map<String, Object>> out = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		for (Map<String, Object> parentEntry : parents) {
			if (parentEntry == null) continue;
			UUID sourceUuid = parseUuid(parentEntry.get("sourceSbomComponentUuid"));
			if (sourceUuid == null || !seen.add(sourceUuid)) continue;
			ReleaseSbomComponent row = ctx.rowByComponentUuid().get(sourceUuid);
			if (row != null) out.add(toDto(row));
		}
		return out;
	}

	@DgsData(parentType = "ReleaseSbomDependency", field = "target")
	public Map<String, Object> getDependencyTarget(DgsDataFetchingEnvironment dfe) {
		ReleaseGraphContext ctx = dfe.getLocalContext();
		if (ctx == null) return null;
		UUID targetUuid = extractUuid(dfe.getSource(), "targetSbomComponentUuid");
		if (targetUuid == null) return null;
		ReleaseSbomComponent row = ctx.rowByComponentUuid().get(targetUuid);
		return row == null ? null : toDto(row);
	}

	/**
	 * Transitive {@code dependedOnBy} closure, deduped, in BFS order from the
	 * source row up. Walks the in-memory parent chain via the per-request
	 * {@link ReleaseGraphContext} — no DB calls, no row re-fetch. Cycle-safe
	 * via a visited set. Use to render multi-hop "upstream paths to root":
	 * the returned ancestors carry their own {@code dependedOnBy}, {@code
	 * component}, etc., so the UI can build path lists by walking one hop at
	 * a time over this bounded subgraph instead of fetching the whole release.
	 *
	 * <p>Cost: O(V + E) over the ancestor subgraph per call, in memory. For
	 * the single-component graph view this is fine. For a release-wide list,
	 * selecting this on every row is O(rows × subgraph) — don't do that.
	 */
	@DgsData(parentType = "ReleaseSbomComponent", field = "ancestors")
	public List<Map<String, Object>> getAncestors(DgsDataFetchingEnvironment dfe) {
		ReleaseGraphContext ctx = dfe.getLocalContext();
		if (ctx == null) return List.of();
		UUID startUuid = extractUuid(dfe.getSource(), "sbomComponentUuid");
		if (startUuid == null) return List.of();

		Set<UUID> visited = new LinkedHashSet<>();
		Deque<UUID> queue = new ArrayDeque<>();
		queue.add(startUuid);
		while (!queue.isEmpty()) {
			UUID current = queue.poll();
			ReleaseSbomComponent row = ctx.rowByComponentUuid().get(current);
			if (row == null || row.getParents() == null) continue;
			for (Map<String, Object> parentEntry : row.getParents()) {
				if (parentEntry == null) continue;
				UUID parentUuid = parseUuid(parentEntry.get("sourceSbomComponentUuid"));
				if (parentUuid == null) continue;
				if (parentUuid.equals(startUuid)) continue;
				if (!visited.add(parentUuid)) continue;
				queue.add(parentUuid);
			}
		}

		List<Map<String, Object>> out = new ArrayList<>(visited.size());
		for (UUID u : visited) {
			ReleaseSbomComponent row = ctx.rowByComponentUuid().get(u);
			if (row != null) out.add(toDto(row));
		}
		return out;
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
		dto.put("type", sc.getPurlType());
		dto.put("group", sc.getPkgGroup());
		dto.put("name", sc.getName());
		dto.put("version", sc.getVersion());
		dto.put("isRoot", sc.isRoot());
		return dto;
	}

	private static UUID parseUuid(Object value) {
		if (value == null) return null;
		if (value instanceof UUID u) return u;
		if (value instanceof String s && !s.isBlank()) {
			try {
				return UUID.fromString(s);
			} catch (IllegalArgumentException iae) {
				return null;
			}
		}
		return null;
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
