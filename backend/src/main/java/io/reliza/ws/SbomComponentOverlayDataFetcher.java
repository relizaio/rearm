/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.VendoredIdentifier;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.OverlayKind;
import io.reliza.model.RelizaObject;
import io.reliza.model.ReleaseData;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentOverlay;
import io.reliza.model.SbomComponentOverlayPatch;
import io.reliza.model.SupportAttestationRequest;
import io.reliza.model.SupportBulkSelector;
import io.reliza.model.SupportData;
import io.reliza.model.SupportMilestoneType;
import io.reliza.model.SupportParty;
import io.reliza.model.SupportState;
import io.reliza.model.SupportTextBounds;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.ReleaseService;
import io.reliza.service.SbomComponentOverlayService;
import io.reliza.service.SbomComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import tools.jackson.databind.JsonNode;

/**
 * The verbs and reads of the manual overlays (FDAW-15 3.7), the gate order of FDAW-14 3.8 fixed per verb:
 * inputs parsed and bounded with no database access; the object loaded, the org taken from it; the gates,
 * the throwing check first (RELEASE READ on the loaded release, then ORGANIZATION WRITE); then the service.
 * No verb takes a key, a purl or, the selector form apart, an org.
 */
@DgsComponent
public class SbomComponentOverlayDataFetcher {

	@Autowired private AuthorizationService authorizationService;
	@Autowired private UserService userService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ReleaseService releaseService;
	@Autowired private SbomComponentService sbomComponentService;
	@Autowired private SbomComponentOverlayService overlayService;
	@Autowired private SbomComponentDataFetcher sbomComponentDataFetcher;

	/** A vendored verb's or read's name and version, bounded. */
	private record NodeName(String name, String version) {}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setSbomComponentOverlay")
	public Map<String, Object> setSbomComponentOverlay(
			@InputArgument("sbomComponentUuid") UUID sbomComponentUuid,
			@InputArgument("overlay") Map<String, Object> overlay,
			@InputArgument("reason") String reason) throws RelizaException {
		SbomComponentOverlayPatch patch = boundedPatch(overlay);
		String boundedReason = boundedReason(reason);
		UserData caller = caller();
		SbomComponent sc = sbomComponentService.getSbomComponent(sbomComponentUuid)
				.orElseThrow(() -> new RelizaException("sbom component not found"));
		requireOrgWrite(caller, sc.getOrg());
		refused(() -> overlayService.setComponentOverlay(sbomComponentUuid, patch, boundedReason, caller.getUuid()));
		return componentDto(sbomComponentUuid);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "bulkSetSbomComponentSupplierBySelector")
	public Map<String, Object> bulkSetSbomComponentSupplierBySelector(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("selector") Map<String, Object> selector,
			@InputArgument("releaseUuid") UUID releaseUuid,
			@InputArgument("supplier") String supplier,
			@InputArgument("reason") String reason) throws RelizaException {
		SupportBulkSelector sel = SbomComponentDataFetcher.selectorOf(selector);
		String boundedSupplier = bound(() -> SupportTextBounds.boundedRequiredSupplier(supplier));
		String boundedReason = boundedReason(reason);
		SbomComponentDataFetcher.SelectorScope scope = sbomComponentDataFetcher.selectorScope(orgUuid, releaseUuid);
		SbomComponentService.SupportBulkRun run = bound(() -> overlayService.bulkSetSupplierBySelector(orgUuid, sel,
				releaseUuid, scope.releaseComponents(), boundedSupplier, boundedReason, scope.caller().getUuid()));
		return SbomComponentDataFetcher.bulkDto(run, run.bulkAct());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "describeVendoredComponent")
	public Map<String, Object> describeVendoredComponent(
			@InputArgument("releaseUuid") UUID releaseUuid,
			@InputArgument("name") String name,
			@InputArgument("version") String version,
			@InputArgument("overlay") Map<String, Object> overlay,
			@InputArgument("reason") String reason) throws RelizaException {
		NodeName node = boundedNode(name, version);
		SbomComponentOverlayPatch patch = boundedPatch(overlay);
		String boundedReason = boundedReason(reason);
		UserData caller = caller();
		ReleaseData rd = readableRelease(caller, releaseUuid);
		requireOrgWrite(caller, rd.getOrg());
		SbomComponentOverlay row = bound(() -> overlayService.describeVendored(rd, node.name(), node.version(), patch,
				boundedReason, caller.getUuid()));
		String key = VendoredIdentifier.mint(rd.getOrg(), node.name(), node.version());
		return vendoredDto(key, node.name(), node.version(), null, null, row,
				sbomComponentService.findSbomComponentsByCanonicalPurls(rd.getOrg(), List.of(key)).get(key));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "removeVendoredDescription")
	public boolean removeVendoredDescription(
			@InputArgument("releaseUuid") UUID releaseUuid,
			@InputArgument("name") String name,
			@InputArgument("version") String version,
			@InputArgument("reason") String reason) throws RelizaException {
		NodeName node = boundedNode(name, version);
		String boundedReason = bound(() -> SbomComponentOverlayService.requiredRemovalReason(reason));
		UserData caller = caller();
		ReleaseData rd = readableRelease(caller, releaseUuid);
		requireOrgWrite(caller, rd.getOrg());
		refused(() -> overlayService.removeVendoredDescription(rd, node.name(), node.version(), boundedReason,
				caller.getUuid()));
		return true;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setVendoredComponentSupport")
	public Map<String, Object> setVendoredComponentSupport(
			@InputArgument("releaseUuid") UUID releaseUuid,
			@InputArgument("name") String name,
			@InputArgument("version") String version,
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
			@InputArgument("reason") String reason,
			@InputArgument("endOfSupportUnknown") Map<String, Object> endOfSupportUnknown,
			@InputArgument("clearEndOfSupportUnknown") Boolean clearEndOfSupportUnknown) throws RelizaException {
		NodeName node = boundedNode(name, version);
		SupportAttestationRequest request = SbomComponentDataFetcher.bounded(new SupportAttestationRequest(
				levelOfSupport, state, supportParty, justification, SbomComponentDataFetcher.parseAssessedAt(assessedAt),
				SbomComponentDataFetcher.parseIsoDate(endOfGuaranteedSupportDate, "endOfGuaranteedSupportDate"),
				SbomComponentDataFetcher.parseIsoDate(endOfSupportDate, "endOfSupportDate"),
				SbomComponentDataFetcher.parseIsoDate(endOfLifeDate, "endOfLifeDate"),
				supportNotes, null == clearMilestones ? null : Set.copyOf(clearMilestones), reason,
				SbomComponentDataFetcher.unknownInput(endOfSupportUnknown), Boolean.TRUE.equals(clearEndOfSupportUnknown)));
		UserData caller = caller();
		ReleaseData rd = readableRelease(caller, releaseUuid);
		requireOrgWrite(caller, rd.getOrg());
		UUID uuid = bound(() -> overlayService.saveVendoredSupport(rd, node.name(), node.version(), request, caller.getUuid()));
		return componentDto(uuid);
	}

	/** Every keyless node of the merged document, folded by normalised name and version (FDAW-6 3.4a). */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releaseUnidentifiedComponents")
	public List<Map<String, Object>> releaseUnidentifiedComponents(
			@InputArgument("releaseUuid") UUID releaseUuid) throws RelizaException {
		UserData caller = caller();
		ReleaseData rd = readableRelease(caller, releaseUuid);
		UUID org = rd.getOrg();
		Map<String, VendoredIdentifier.Vendored> nodes = new LinkedHashMap<>();
		Map<String, String> groups = new LinkedHashMap<>();
		for (JsonNode node : releaseService.keylessNodesOf(rd, WhoUpdated.getWhoUpdated(caller))) {
			Optional<VendoredIdentifier.Vendored> v = VendoredIdentifier.ofNode(org, node);
			if (v.isEmpty()) continue;
			String fold = v.get().name() + "\u0000" + (null == v.get().version() ? "" : v.get().version());
			if (null == nodes.putIfAbsent(fold, v.get()) && null != node.get("group") && node.get("group").isTextual()) {
				groups.put(fold, node.get("group").asText());
			}
		}
		List<String> keys = nodes.values().stream().map(VendoredIdentifier.Vendored::key)
				.filter(k -> null != k).toList();
		Map<String, SbomComponentOverlay> overlays = overlayService.overlaysFor(org, keys);
		Map<String, SbomComponent> born = sbomComponentService.findSbomComponentsByCanonicalPurls(org, keys);
		List<Map<String, Object>> out = new ArrayList<>(nodes.size());
		nodes.forEach((fold, v) -> out.add(vendoredDto(v.key(), v.name(), v.version(), groups.get(fold), v.refusal(),
				null == v.key() ? null : overlays.get(v.key()), null == v.key() ? null : born.get(v.key()))));
		return out;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "vendoredIdentifier")
	public String vendoredIdentifier(
			@InputArgument("releaseUuid") UUID releaseUuid,
			@InputArgument("name") String name,
			@InputArgument("version") String version) throws RelizaException {
		NodeName node = boundedNode(name, version);
		ReleaseData rd = readableRelease(caller(), releaseUuid);
		return VendoredIdentifier.mint(rd.getOrg(), node.name(), node.version());
	}

	/**
	 * The rows span every product of the organization and include {@code origin}, so RELEASE READ on one
	 * release, which a product-scoped grant gives, is not enough (F-17).
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "vendoredDescriptionsByName")
	public List<Map<String, Object>> vendoredDescriptionsByName(
			@InputArgument("releaseUuid") UUID releaseUuid,
			@InputArgument("name") String name) throws RelizaException {
		String bounded = bound(() -> SupportTextBounds.boundedRequiredName(name));
		UserData caller = caller();
		ReleaseData rd = readableRelease(caller, releaseUuid);
		requireOrgWrite(caller, rd.getOrg());
		List<SbomComponentOverlay> rows = overlayService.vendoredDescriptionsByName(rd.getOrg(), bounded);
		Map<UUID, String> names = sbomComponentDataFetcher.overlayAsserterNames(rows);
		return rows.stream().map(o -> SbomComponentDataFetcher.toOverlayDto(o, names)).toList();
	}

	private Map<String, Object> vendoredDto(String key, String name, String version, String group, String refusal,
			SbomComponentOverlay overlay, SbomComponent component) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("key", key);
		dto.put("name", name);
		dto.put("version", version);
		dto.put("group", group);
		dto.put("described", null != overlay && OverlayKind.VENDORED == overlay.getKind());
		dto.put("describable", null != key);
		dto.put("refusal", refusal);
		dto.put("overlay", null == overlay ? null : SbomComponentDataFetcher.toOverlayDto(overlay,
				sbomComponentDataFetcher.overlayAsserterNames(List.of(overlay))));
		dto.put("component", null == component ? null : sbomComponentDataFetcher.componentDtoOf(component,
				sbomComponentService.findSupportByComponentIds(component.getOrg(), Set.of(component.getUuid()))
						.get(component.getUuid())));
		return dto;
	}

	private Map<String, Object> componentDto(UUID sbomComponentUuid) throws RelizaException {
		SbomComponent sc = sbomComponentService.getSbomComponent(sbomComponentUuid)
				.orElseThrow(() -> new RelizaException("sbom component not found"));
		SupportData support = sbomComponentService.findSupportByComponentIds(sc.getOrg(), Set.of(sbomComponentUuid))
				.get(sbomComponentUuid);
		return sbomComponentDataFetcher.componentDtoOf(sc, support);
	}

	private UserData caller() {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return userService.getUserDataByAuth(auth).orElseThrow();
	}

	private ReleaseData readableRelease(UserData caller, UUID releaseUuid) throws RelizaException {
		ReleaseData rd = sharedReleaseService.getReleaseData(releaseUuid)
				.orElseThrow(() -> new RelizaException("release not found"));
		authorizationService.isUserAuthorizedForObjectGraphQL(caller, PermissionFunction.RESOURCE,
				PermissionScope.RELEASE, releaseUuid, List.of(rd), CallType.READ);
		return rd;
	}

	private void requireOrgWrite(UserData caller, UUID orgUuid) throws RelizaException {
		RelizaObject ro = getOrganizationService.getOrganizationData(orgUuid)
				.orElseThrow(() -> new RelizaException("organization not found"));
		authorizationService.isUserAuthorizedForObjectGraphQL(caller, PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.WRITE);
	}

	private static SbomComponentOverlayPatch boundedPatch(Map<String, Object> input) throws RelizaException {
		Map<String, Object> in = null == input ? Map.of() : input;
		return bound(() -> SupportTextBounds.boundedOverlay(new SbomComponentOverlayPatch(text(in, "supplier"),
				text(in, "licence"), text(in, "description"), text(in, "origin"))));
	}

	private static String text(Map<String, Object> input, String field) {
		Object v = input.get(field);
		return null == v ? null : v.toString();
	}

	private static NodeName boundedNode(String name, String version) throws RelizaException {
		return bound(() -> new NodeName(SupportTextBounds.boundedRequiredName(name),
				SupportTextBounds.boundedNodeVersion(version)));
	}

	private static String boundedReason(String reason) throws RelizaException {
		return bound(() -> SupportTextBounds.bounded(reason, "reason", SupportTextBounds.MAX_REASON_LENGTH));
	}

	private interface Bound<T> {
		T get();
	}

	/** A refusal as a GraphQL error: the handler answers any other IllegalArgumentException as a server error. */
	private static <T> T bound(Bound<T> check) throws RelizaException {
		try {
			return check.get();
		} catch (IllegalArgumentException e) {
			throw new RelizaException(e.getMessage());
		}
	}

	private static void refused(Runnable write) throws RelizaException {
		bound(() -> {
			write.run();
			return null;
		});
	}
}
