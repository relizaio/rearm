/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Collections;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
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
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.ComponentData;
import io.reliza.model.DeviceLifecycle;
import io.reliza.model.DeviceSupportRisk;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseSbomComponent;
import io.reliza.model.RelizaObject;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentPage;
import io.reliza.model.SupportAttestationFilter;
import io.reliza.model.SupportAttestationRequest;
import io.reliza.model.SupportBulkResult;
import io.reliza.model.SupportData;
import io.reliza.model.SupportMilestoneFact;
import io.reliza.model.SupportMilestoneType;
import io.reliza.model.SupportParty;
import io.reliza.model.SupportState;
import io.reliza.model.SupportStatus;
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
			// Per-component support attestations, bulk-loaded once for the whole release list
			// (never per component) -- see SbomComponentService.findSupportByComponentIds.
			Map<UUID, SupportData> supportByComponentUuid,
			Map<UUID, List<Map<String, Object>>> forwardEdgesBySource,
			// The enclosing device's support window (ReleaseData.eos/eol) for the per-component
			// DeviceSupportRisk derivation; null on a non-PRODUCT release or when it declares neither.
			DeviceLifecycle deviceLifecycle) {}

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
	 * The device support window for a release's SBOM view, gated to PRODUCT (device) releases.
	 * On a plain component/library release {@code ReleaseData.eos}/{@code eol} is that library's own
	 * lifecycle, not a device horizon, so the DeviceSupportRisk concept does not apply and this
	 * returns null (every component then resolves to {@code UNKNOWN}). Null too when the device
	 * declares no support window.
	 */
	private DeviceLifecycle deviceLifecycleFor(Optional<ReleaseData> ord) {
		if (ord.isEmpty()) {
			return null;
		}
		ReleaseData rd = ord.get();
		if (rd.getComponent() == null) {
			return null;
		}
		boolean isProduct = getComponentService.getComponentData(rd.getComponent())
				.map(cd -> cd.getType() == ComponentData.ComponentType.PRODUCT)
				.orElse(false);
		if (!isProduct) {
			return null;
		}
		DeviceLifecycle dl = new DeviceLifecycle(rd.getEos(), rd.getEol());
		return dl.declaresAnyDate() ? dl : null;
	}

	/**
	 * Build the per-request graph state used by both the full-release query
	 * and the single-component graph query. Loads the release's rows once,
	 * bulk-fetches every {@code sbom_components} row referenced by any row
	 * or any parent edge, indexes rows by canonical component, and inverts
	 * {@code parents} into a forward-edge map keyed by source uuid so the
	 * downstream field resolvers are O(1) lookups instead of N+1 queries.
	 */
	private ReleaseGraphLoad loadReleaseGraph(UUID releaseUuid, UUID orgUuid, DeviceLifecycle deviceLifecycle) {
		return loadReleaseGraph(releaseUuid, orgUuid, deviceLifecycle, null, null);
	}

	/**
	 * @param restrictTo when non-null, load and hydrate ONLY these components. The two
	 *        hydration calls below are keyed by component id, so narrowing here is what
	 *        makes a page cost a page rather than a BOM. Both edge maps are then built from
	 *        the retained rows only, so on a restricted load ALL THREE graph fields are
	 *        page-scoped: {@code dependencies} (via {@code forwardEdgesBySource}),
	 *        {@code dependedOnBy} and {@code ancestors} (both via
	 *        {@code rowByComponentUuid}). That is why the graph view keeps using the
	 *        unrestricted call, and why the paged query's schema comment tells clients not
	 *        to select those fields.
	 * @param preResolved artifact rows the caller already resolved, to avoid resolving the
	 *        release a second time; null to resolve here.
	 */
	private ReleaseGraphLoad loadReleaseGraph(UUID releaseUuid, UUID orgUuid,
			DeviceLifecycle deviceLifecycle, Set<UUID> restrictTo,
			List<ArtifactSbomComponent> preResolved) {
		List<ReleaseSbomComponent> rows =
				sbomComponentService.listReleaseSbomComponents(releaseUuid, restrictTo, preResolved);

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
		Map<UUID, SupportData> supportByComponentUuid =
				sbomComponentService.findSupportByComponentIds(orgUuid, componentByUuid.keySet());

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
				rowByComponentUuid, componentByUuid, supportByComponentUuid, forwardEdgesBySource, deviceLifecycle);
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
				releaseUuid, Collections.singletonList(ro), CallType.READ);

		UUID orgUuid = ord.map(ReleaseData::getOrg).orElse(null);
		ReleaseGraphLoad load = loadReleaseGraph(releaseUuid, orgUuid, deviceLifecycleFor(ord));
		return DataFetcherResult.<List<Map<String, Object>>>newResult()
				.data(load.dtos())
				.localContext(load.ctx())
				.build();
	}

	/**
	 * Paged, filtered sibling of {@code getReleaseSbomComponents}, for the support
	 * attestation table.
	 *
	 * <p>The id set is sliced in SQL and only the page is hydrated. The slice is a KEYSET
	 * cursor over (canonical_purl, uuid), applied in the same statement as the ordering, so
	 * a walk visits every row exactly once even while the caller is attesting rows out of
	 * the UNATTESTED set as it goes. The order is then reimposed on the merged rows below,
	 * which come back through a HashMap whose iteration order would not survive a second
	 * request.
	 *
	 * <p>The coverage gauge is NOT recomputed here. It is release-scoped by design and the
	 * page must not narrow it: an operator paging through the undisclosed components would
	 * otherwise watch the number they are working to close shrink on every click.
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getReleaseSbomComponentsPage")
	public DataFetcherResult<Map<String, Object>> getReleaseSbomComponentsPage(
			@InputArgument("releaseUuid") UUID releaseUuid,
			@InputArgument("attestation") SupportAttestationFilter attestation,
			@InputArgument("search") String search,
			@InputArgument("limit") Integer limit,
			@InputArgument("after") UUID after) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(
				oud.get(), PermissionFunction.RESOURCE, PermissionScope.RELEASE,
				releaseUuid, Collections.singletonList(ro), CallType.READ);

		UUID orgUuid = ord.map(ReleaseData::getOrg).orElse(null);
		if (null == orgUuid) {
			return DataFetcherResult.<Map<String, Object>>newResult()
					.data(pageDto(List.of(), 0L, DEFAULT_PAGE_LIMIT, null, false))
					.build();
		}
		SbomComponentPage page = sbomComponentService.listReleaseSbomComponentPage(
				orgUuid, releaseUuid, attestation, search,
				null == limit ? DEFAULT_PAGE_LIMIT : limit, after);

		// LinkedHashSet, not the list: the restriction is a membership test, but the ORDER
		// comes from the SQL and has to be reimposed on the merged rows below, which come
		// back grouped by a map rather than in query order.
		Set<UUID> pageIds = new LinkedHashSet<>(page.componentUuids());
		ReleaseGraphLoad load = loadReleaseGraph(releaseUuid, orgUuid, deviceLifecycleFor(ord), pageIds,
				page.resolvedArtifactRows());
		Map<UUID, Map<String, Object>> dtoByComponent = new HashMap<>();
		for (Map<String, Object> dto : load.dtos()) {
			dtoByComponent.put((UUID) dto.get("sbomComponentUuid"), dto);
		}
		List<Map<String, Object>> ordered = new ArrayList<>(pageIds.size());
		for (UUID id : page.componentUuids()) {
			Map<String, Object> dto = dtoByComponent.get(id);
			if (dto != null) ordered.add(dto);
		}
		return DataFetcherResult.<Map<String, Object>>newResult()
				.data(pageDto(ordered, page.totalCount(), page.limit(), page.endCursor(),
						page.hasMore()))
				.localContext(load.ctx())
				.build();
	}

	/** Default page size when the caller does not ask. */
	private static final int DEFAULT_PAGE_LIMIT = 50;


	private static Map<String, Object> pageDto(List<Map<String, Object>> items, long totalCount,
			int limit, UUID endCursor, boolean hasMore) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("items", items);
		// Cast to int to match the sibling gauge resolver, which does the same for a GraphQL
		// Int field. graphql-java coerces an in-range Long anyway; the point is that one file
		// should not do it two ways.
		dto.put("totalCount", (int) totalCount);
		dto.put("limit", limit);
		dto.put("endCursor", endCursor);
		dto.put("hasMore", hasMore);
		return dto;
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
				releaseUuid, Collections.singletonList(ro), CallType.READ);

		UUID orgUuid = ord.map(ReleaseData::getOrg).orElse(null);
		ReleaseGraphLoad load = loadReleaseGraph(releaseUuid, orgUuid, deviceLifecycleFor(ord));
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
				releaseUuid, Collections.singletonList(ro), CallType.WRITE);
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
			authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, Collections.singletonList(ro), CallType.READ);
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
			authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, Collections.singletonList(ro), CallType.ESSENTIAL_READ);
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
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, Collections.singletonList(ro), CallType.ESSENTIAL_READ);
		return sbomComponentService.searchSbomComponentByPurl(purl, orgUuid);
	}

	/**
	 * FDA-Readiness-1: manufacturer (MANUAL) attestation of a component's support
	 * level / EOS-EOL dates. Org-scoped WRITE; the org is derived from the loaded
	 * component (IDOR guard), never from client input. supportSource is set
	 * server-side to MANUAL and the attester is the authenticated user; the derived
	 * status is computed on read, not set here. Retries a concurrent-reconcile
	 * optimistic-lock conflict a bounded number of times.
	 */
	/**
	 * FDA-Readiness-1: attest MANY components in one pass. Same authorization shape as the
	 * single-component mutation -- the org is derived from the loaded components, never from
	 * client input -- and every component must belong to it.
	 *
	 * <p>Returns per-component outcomes rather than a count, and does NOT throw when an
	 * individual component is skipped or rejected: that is the point of skip-and-report, and
	 * a caller needs the list to show which rows need attention.
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "bulkSetSbomComponentSupport")
	public Map<String, Object> bulkSetSbomComponentSupport(
			@InputArgument("sbomComponentUuids") List<UUID> sbomComponentUuids,
			@InputArgument("levelOfSupport") LevelOfSupport levelOfSupport,
			@InputArgument("justification") String justification,
			@InputArgument("assessedAt") String assessedAt,
			@InputArgument("endOfGuaranteedSupportDate") String endOfGuaranteedSupportDate,
			@InputArgument("endOfSupportDate") String endOfSupportDate,
			@InputArgument("endOfLifeDate") String endOfLifeDate,
			@InputArgument("supportNotes") String supportNotes,
			@InputArgument("supportParty") SupportParty supportParty,
			@InputArgument("reason") String reason,
			@InputArgument("batchId") UUID batchId) throws RelizaException {
		// Minted here, once, BEFORE the empty-input return, so every response carries the same
		// id the write used and there is exactly one place this value comes from.
		//
		// Server-ISSUED, and deliberately not server-ENFORCED. The server generates the id; the
		// intended client use is to echo back the one it was given, which is how batches 2..n of
		// a paged sweep join the first batch's id. But the argument is taken verbatim and NOT
		// validated: a scripted caller can send an arbitrary or repeated uuid and the audit rows
		// will carry it, asserting "these writes were one act" about writes that were not.
		//
		// Not validated because the obvious check makes the feature wrong. Requiring a supplied
		// id to already name rows in this org would reject the legitimate case where the first
		// batch of a paged sweep applied NOTHING -- every component came back SKIPPED_ATTESTED,
		// so no audit row exists to point at -- and batch two would then be refused for echoing
		// exactly what it was told to echo. A recency or same-user binding has the same shape.
		//
		// The exposure is bounded to audit fidelity WITHIN one org: the batch read is org-scoped
		// (see SbomComponentSupportAuditRepository), so a borrowed id cannot reach another
		// tenant's rows, and a caller who muddies their own audit trail is already the party the
		// trail is about. If that trade stops being acceptable, the fix is a server-issued id
		// with a short server-side TTL, not a lookup.
		UUID effectiveBatchId = (null == batchId) ? UUID.randomUUID() : batchId;
		if (null == sbomComponentUuids || sbomComponentUuids.isEmpty()) {
			return bulkDto(List.of(), effectiveBatchId);
		}
		// Bounded BEFORE anything touches the database, and before authorization, because the
		// cost this bounds is incurred by the request existing at all. RateLimitingFilter
		// charges ONE TOKEN PER REQUEST, not per item, so without this a single 100k-id
		// mutation costs a caller exactly what a 1-id mutation costs while occupying a
		// connection long enough to starve the pool for the whole tenant. Per-item rate
		// limiting is deliberately NOT the answer -- the cap bounds the amplification
		// factor, which is the actual problem.
		//
		// Our own client batches at 200, so it never approaches this; a caller who does is
		// scripting against the API directly and gets a named error rather than a timeout.
		if (sbomComponentUuids.size() > SbomComponentService.BULK_SUPPORT_MAX_IDS) {
			throw new RelizaException("a bulk support attestation is limited to "
					+ SbomComponentService.BULK_SUPPORT_MAX_IDS + " components per call; " + sbomComponentUuids.size()
					+ " were supplied. Split the sweep into batches and pass the batchId"
					+ " returned by the first call so they stay one correlated action.");
		}
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);

		// Org comes from the FIRST component and every other is then required to match, so a
		// caller cannot smuggle a foreign component into an authorized batch and have it
		// written under someone else's org.
		SbomComponent first = sbomComponentService.getSbomComponent(sbomComponentUuids.get(0))
				.orElseThrow(() -> new RelizaException("sbom component not found"));
		UUID orgUuid = first.getOrg();
		RelizaObject ro = getOrganizationService.getOrganizationData(orgUuid)
				.orElseThrow(() -> new RelizaException("organization not found"));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.WRITE);

		// state and clearMilestones are deliberately absent from this mutation -- see the
		// schema comment for why neither could ever succeed on a bulk write.
		SupportAttestationRequest request = new SupportAttestationRequest(
				levelOfSupport, null, supportParty, justification, parseAssessedAt(assessedAt),
				parseIsoDate(endOfGuaranteedSupportDate, "endOfGuaranteedSupportDate"),
				parseIsoDate(endOfSupportDate, "endOfSupportDate"),
				parseIsoDate(endOfLifeDate, "endOfLifeDate"),
				supportNotes, null, reason);

		return bulkDto(sbomComponentService.bulkSetSbomComponentSupport(
				orgUuid, sbomComponentUuids, request, oud.get().getUuid(), effectiveBatchId),
				effectiveBatchId);
	}

	/**
	 * The bulk response: the per-item list plus counts derived FROM that list.
	 *
	 * <p>Derived, not tallied alongside the loop, so a client that renders the summary and a
	 * client that iterates the results cannot disagree about the same run.
	 */
	private static Map<String, Object> bulkDto(List<SupportBulkResult> results, UUID batchId) {
		List<Map<String, Object>> items = new ArrayList<>(results.size());
		int applied = 0;
		int skipped = 0;
		int failed = 0;
		for (SupportBulkResult r : results) {
			Map<String, Object> dto = new LinkedHashMap<>();
			dto.put("sbomComponentUuid", r.sbomComponentUuid());
			dto.put("outcome", r.outcome().name());
			dto.put("message", r.message());
			items.add(dto);
			switch (r.outcome()) {
				case APPLIED -> applied++;
				case SKIPPED_ROOT, SKIPPED_ATTESTED -> skipped++;
				case FAILED -> failed++;
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("results", items);
		out.put("appliedCount", applied);
		out.put("skippedCount", skipped);
		out.put("failedCount", failed);
		// Non-null on every response, including the empty one, so a paged client can always
		// echo it back rather than having to decide whether it got one.
		out.put("batchId", batchId);
		return out;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setSbomComponentSupport")
	public Map<String, Object> setSbomComponentSupport(
			@InputArgument("sbomComponentUuid") UUID sbomComponentUuid,
			@InputArgument("levelOfSupport") LevelOfSupport levelOfSupport,
			@InputArgument("justification") String justification,
			@InputArgument("assessedAt") String assessedAt,
			@InputArgument("state") SupportState state,
			@InputArgument("endOfGuaranteedSupportDate") String endOfGuaranteedSupportDate,
			@InputArgument("endOfSupportDate") String endOfSupportDate,
			@InputArgument("endOfLifeDate") String endOfLifeDate,
			@InputArgument("supportNotes") String supportNotes,
			@InputArgument("supportParty") SupportParty supportParty,
			@InputArgument("clearMilestones") List<SupportMilestoneType> clearMilestones,
			@InputArgument("reason") String reason) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		SbomComponent sc = sbomComponentService.getSbomComponent(sbomComponentUuid)
				.orElseThrow(() -> new RelizaException("sbom component not found"));
		UUID orgUuid = sc.getOrg();
		RelizaObject ro = getOrganizationService.getOrganizationData(orgUuid)
				.orElseThrow(() -> new RelizaException("organization not found"));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.WRITE);

		LocalDate eogs = parseIsoDate(endOfGuaranteedSupportDate, "endOfGuaranteedSupportDate");
		LocalDate eos = parseIsoDate(endOfSupportDate, "endOfSupportDate");
		LocalDate eol = parseIsoDate(endOfLifeDate, "endOfLifeDate");
		UUID assertedBy = oud.get().getUuid();
		// CALLER-SUPPLIED assessment instant: when the human actually assessed, which is not
		// necessarily when they filed it. Null falls back to now inside the service. Rejected
		// loudly if malformed rather than silently defaulted -- a wrong date on a regulatory
		// record is worse than a refused write.
		ZonedDateTime assessed = parseAssessedAt(assessedAt);
		SupportAttestationRequest request = new SupportAttestationRequest(
				levelOfSupport, state, supportParty, justification, assessed, eogs, eos, eol,
				supportNotes,
				// Normalisation (null/empty -> Set.of(), defensive copy) is the compact
				// constructor's job; doing it here too would be a second place to keep right.
				null == clearMilestones ? null : Set.copyOf(clearMilestones),
				reason);
		// Bounded retry against a concurrent attestation write on the same component. (A BOM
		// reconcile can no longer conflict at all: the attestation is a separate row that the
		// reconcile path never touches.) The date-ordering check runs inside the service
		// against the EFFECTIVE dates -- staged this call plus whatever is already stored for
		// an untouched milestone -- so it is re-validated on every retry against the latest row.
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				sbomComponentService.setSbomComponentSupport(sbomComponentUuid, request, assertedBy);
				SbomComponent updated = sbomComponentService.getSbomComponent(sbomComponentUuid)
						.orElseThrow(() -> new RelizaException("sbom component not found"));
				// Mutation response is org-scoped (no enclosing release) -> no device-EOL
				// anchor; deviceSupportRisk resolves to UNKNOWN. The release-scoped list query
				// (which the client refetches after save) carries the real flag.
				return toComponentDto(updated,
						sbomComponentService.findSupportByComponentIds(orgUuid, Set.of(sbomComponentUuid))
								.get(sbomComponentUuid),
						null);
			} catch (OptimisticLockingFailureException ole) {
				// retry
			}
		}
		throw new RelizaException("concurrent update in progress, please retry");
	}

	/**
	 * FDA-Readiness-1: support-disclosure coverage for an org (attested vs total
	 * non-root components) -- a pre-submission completeness signal.
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "sbomComponentSupportCoverage")
	public Map<String, Object> sbomComponentSupportCoverage(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("releaseUuid") UUID releaseUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		// orgUuid is client-supplied: a non-existent id must not reach List.of(null)
		// (which NPEs) -- resolve to not-found first.
		RelizaObject ro = getOrganizationService.getOrganizationData(orgUuid)
				.orElseThrow(() -> new RelizaException("organization not found"));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, Collections.singletonList(ro), CallType.READ);
		// The release is CLIENT-SUPPLIED, so it must be proven to belong to the org the caller
		// was just authorized for. Without this the query answers 0/0 for another org's
		// release -- the org filter in the count queries means nothing leaks, but a valid
		// release reported as empty is a confusing answer to a question that should have been
		// refused. Fail closed and say why.
		if (null != releaseUuid) {
			UUID releaseOrg = sharedReleaseService.getReleaseData(releaseUuid)
					.map(ReleaseData::getOrg).orElse(null);
			if (!orgUuid.equals(releaseOrg)) {
				throw new RelizaException("release not found in this organization: " + releaseUuid);
			}
		}
		SbomComponentService.SupportCoverage cov =
				sbomComponentService.getSupportCoverage(orgUuid, releaseUuid);
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("total", (int) cov.total());
		dto.put("attested", (int) cov.attested());
		// Same response as the counts, deliberately -- see the schema comment. A client that
		// had to ask twice could render full coverage beside an export carrying nothing.
		dto.put("supportExportState", sbomComponentService.supportExportState(orgUuid).name());
		return dto;
	}

	private static LocalDate parseIsoDate(String value, String field) throws RelizaException {
		if (value == null || value.isBlank()) return null;
		try {
			LocalDate parsed = LocalDate.parse(value);
			// ISO_LOCAL_DATE accepts EXPANDED years ("+10000-01-01", "-0001-01-01"), which the
			// storage constraint rejects. Without this check the write reaches flush and fails
			// as a raw persistence error: the caller gets an opaque "Database error" instead of
			// a field-level message, and the server logs Postgres's DETAIL line, which contains
			// the whole support_data payload, the org uuid and the canonical purl. Reject it
			// here, where it is a bad request and nothing is logged.
			if (parsed.toString().length() != 10) {
				throw new RelizaException(field + " must be a plain ISO date (YYYY-MM-DD), got: " + value);
			}
			return parsed;
		} catch (DateTimeParseException dtpe) {
			throw new RelizaException(field + " must be an ISO date (YYYY-MM-DD), got: " + value);
		}
	}

	@DgsData(parentType = "ReleaseSbomComponent", field = "component")
	public Map<String, Object> getComponent(DgsDataFetchingEnvironment dfe) {
		ReleaseGraphContext ctx = dfe.getLocalContext();
		UUID componentUuid = extractUuid(dfe.getSource(), "sbomComponentUuid");
		if (componentUuid == null) return null;
		if (ctx != null) {
			SbomComponent sc = ctx.componentByUuid().get(componentUuid);
			if (sc == null) return null;
			return toComponentDto(sc, ctx.supportByComponentUuid().get(componentUuid),
					ctx.deviceLifecycle());
		}
		// Defensive fallback if this resolver is reached outside the top-level
		// query path (no localContext); behaves like the original single-row
		// fetch, so direct callers keep working. No release context here, so the
		// device support window is unavailable -> deviceSupportRisk resolves to UNKNOWN.
		return sbomComponentService.getSbomComponent(componentUuid)
				.map(sc -> toComponentDto(sc,
						sbomComponentService.findSupportByComponentIds(sc.getOrg(), Set.of(componentUuid))
								.get(componentUuid),
						null))
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

	private static Map<String, Object> toComponentDto(SbomComponent sc,
			SupportData support, DeviceLifecycle device) {
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
		Map<SupportMilestoneType, SupportMilestoneFact> milestones =
				support == null ? Map.of() : support.milestones();
		// Support disclosure. Dates + provenance live inside the attestation's JSONB (see
		// SupportData). Dates emitted as ISO-8601 (YYYY-MM-DD); instants as stored, which is
		// already UTC RFC-3339 with a trailing Z, never ZonedDateTime.toString().
		SupportMilestoneFact eogsM = milestones.get(SupportMilestoneType.END_OF_GUARANTEED_SUPPORT);
		SupportMilestoneFact eosM = milestones.get(SupportMilestoneType.END_OF_SUPPORT);
		SupportMilestoneFact eolM = milestones.get(SupportMilestoneType.END_OF_LIFE);
		LocalDate endOfGuaranteedSupport = eogsM == null ? null : eogsM.dateValue();
		LocalDate endOfSupport = eosM == null ? null : eosM.dateValue();
		LocalDate endOfLife = eolM == null ? null : eolM.dateValue();
		// Dates only, and end-of-life is not among them: since D5 it means end of SALE, which
		// is not a support state. derive() no longer takes a source either -- see D5.
		SupportStatus supportStatus = SupportStatus.derive(
				endOfGuaranteedSupport, endOfSupport, LocalDate.now(ZoneOffset.UTC));
		// TWO SEPARATE FIELDS, NEVER RECONCILED. supportStatus is what the DATES entail;
		// attestedLevelOfSupport is what a HUMAN claimed about upstream maintenance. When they
		// disagree, the client shows both and a reviewer judges -- collapsing them here would
		// discard exactly the signal that makes the disagreement worth surfacing.
		dto.put("supportStatus", supportStatus.name());
		dto.put("attestedLevelOfSupport", support == null || support.levelOfSupport() == null
				? null : support.levelOfSupport().name());
		// The wire form FDA and CycloneDX PR #186 expect, alongside the enum name the UI
		// binds to. Serving only the name would make every consumer re-map it.
		dto.put("attestedLevelOfSupportText", support == null || support.levelOfSupport() == null
				? null : support.levelOfSupport().getWireValue());
		// A level is never served bare: assessedAt and the attester travel with it so a
		// consumer can weigh the claim's age instead of trusting it indefinitely.
		dto.put("assessedAt", support == null ? null : support.assessedAt());
		dto.put("assertedBy", support == null ? null : support.assertedBy());
		dto.put("justification", support == null ? null : support.justification());
		dto.put("attestationState", support == null ? null : support.state().name());
		dto.put("endOfGuaranteedSupportDate", endOfGuaranteedSupport == null ? null : endOfGuaranteedSupport.toString());
		dto.put("endOfSupportDate", endOfSupport == null ? null : endOfSupport.toString());
		dto.put("endOfLifeDate", endOfLife == null ? null : endOfLife.toString());
		// Back-compat for the pre-milestone flat fields (PR2a export / PR3 UI): these mean
		// "the END_OF_SUPPORT milestone's own provenance," not a component-wide value.
		dto.put("supportSource", eosM == null || eosM.source() == null ? null : eosM.source().name());
		dto.put("supportLastAssessed", eosM == null ? null : eosM.lastAssessed());
		dto.put("supportNotes", eosM == null ? null : eosM.notes());
		dto.put("supportParty", support == null || support.party() == null ? null : support.party().name());
		dto.put("supportMilestones", milestones.entrySet().stream()
				.map(e -> toMilestoneDto(e.getKey(), e.getValue()))
				.toList());
		// DeviceSupportRisk is DERIVED (never stored) from the component's dates + the enclosing
		// device's support window. Emitted as the full enum ALWAYS on this API surface (incl.
		// UNKNOWN) -- e.g. org-scoped reads with no release context (device == null) resolve to
		// UNKNOWN. The device axis compares END-OF-SUPPORT only: EOGS is out of scope, and
		// since D5 end-of-life is too.
		dto.put("deviceSupportRisk", DeviceSupportRisk.derive(endOfSupport, device).name());
		return dto;
	}

	private static Map<String, Object> toMilestoneDto(SupportMilestoneType type, SupportMilestoneFact m) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("milestoneType", type.name());
		dto.put("date", m.date());
		dto.put("source", m.source() == null ? null : m.source().name());
		dto.put("lastAssessed", m.lastAssessed());
		dto.put("assertedBy", m.assertedBy());
		dto.put("notes", m.notes());
		return dto;
	}

	/**
	 * Parse the caller-supplied assessment instant. Accepts any RFC-3339 offset form and
	 * normalises to UTC. Null/blank means "not supplied" (the service uses now); anything
	 * else that will not parse is an error, never a silent fallback to now -- quietly
	 * substituting the wrong date is how an attestation stops being evidence.
	 */
	private static ZonedDateTime parseAssessedAt(String value) throws RelizaException {
		if (value == null || value.isBlank()) return null;
		try {
			return OffsetDateTime.parse(value).atZoneSameInstant(ZoneOffset.UTC);
		} catch (DateTimeParseException dtpe) {
			throw new RelizaException("assessedAt must be an RFC-3339 instant, got: " + value);
		}
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
