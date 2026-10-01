/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.Utils;
import io.reliza.dto.FindingSbomMatch;
import io.reliza.dto.FindingSbomMatch.FindingSbomMissReason;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.service.SbomComponentService.LatestVersion;
import io.reliza.service.SbomComponentService.ReleaseComponentPurls;

/**
 * Matches the package URL a finding carries to the components of a release's
 * SBOM: {@code release_artifact_index -> artifact_sbom_components ->
 * sbom_components}, with a PRODUCT's dependency releases folded in, the same
 * path as the release's component list.
 *
 * <p>A finding and a component match when their purls name the same package
 * at the same version with the same identity qualifiers, whatever the
 * percent-encoding: {@link Utils#purlSemanticKey} over
 * {@link Utils#canonicalizePurlPreservingEncoding}. Byte equality with
 * {@link Utils#canonicalizePurl} is not enough, because Dependency-Track and
 * rebom spell a Debian {@code +debNuN} or epoch {@code N:} version
 * differently. {@link Utils#purlIdentity} is not usable either: it drops the
 * {@code distro} qualifier the stored canonical keeps. This is the identity
 * rule of {@code SbomComponentService.searchSbomComponentByPurl}, applied to a
 * release's own components in memory rather than to the whole org by query,
 * so it cannot answer with a component the release does not contain.
 */
@Service
public class FindingPurlBridge {

	/** Whether a release's component inventory can be trusted to be complete. */
	public enum InventoryState {
		/** Indexed, and no reconcile is queued. */
		SETTLED,
		/**
		 * An SBOM reconcile of the release, or of a PRODUCT's dependency, is
		 * queued: a new BOM may not be indexed yet, or the last pass skipped one.
		 */
		INVENTORY_PENDING,
		/** The release resolves to no components. */
		NO_INVENTORY
	}

	@Autowired
	private SbomComponentService sbomComponentService;

	/**
	 * A release's components, indexed for matching, how far to trust them, and each
	 * component's latest version where one was looked up.
	 */
	public record ReleaseInventory(InventoryState state, ComponentIndex components,
			Map<UUID, LatestVersion> latestByComponent) {

		public static final ReleaseInventory NONE = new ReleaseInventory(InventoryState.NO_INVENTORY,
				ComponentIndex.EMPTY, Map.of());

		/** The component {@code findingPurl} names in this inventory, or why there is none. */
		public FindingSbomMatch match(String findingPurl) {
			if (!Utils.isPurl(findingPurl)) return FindingSbomMatch.missed(FindingSbomMissReason.NO_PURL);
			if (components.keyOf(findingPurl) == null) return FindingSbomMatch.missed(FindingSbomMissReason.UNPARSEABLE_PURL);
			UUID id = components.componentOf(findingPurl);
			if (id != null) {
				LatestVersion latest = latestByComponent.get(id);
				return FindingSbomMatch.matched(id, components.canonicalPurlOf(id),
						latest == null ? null : latest.version(), latest == null ? null : latest.checked());
			}
			return FindingSbomMatch.missed(switch (state) {
			case INVENTORY_PENDING -> FindingSbomMissReason.INVENTORY_PENDING;
			case NO_INVENTORY -> FindingSbomMissReason.NO_INVENTORY;
			case SETTLED -> FindingSbomMissReason.NOT_IN_INVENTORY;
			});
		}
	}

	/** Which of the purls asked about a release's inventory holds, and how far to trust the answer. */
	public record InventoryPresence(InventoryState state, Set<String> present) {}

	/**
	 * A set of SBOM components indexed by match key. Two components can share a
	 * key only as encoding-variant duplicates of one identity (none on the
	 * sandbox on 2026-09-29, in 1 071 releases); the lowest uuid answers for
	 * both, everywhere, so a finding is never counted twice. Keys of the purls
	 * asked about are kept, because a release repeats each purl across its
	 * vulnerabilities.
	 */
	public static final class ComponentIndex {

		static final ComponentIndex EMPTY = of(Map.of());

		/** Memo value of a purl that does not parse. */
		private static final String UNPARSEABLE = "";

		private final Map<String, UUID> byKey;
		private final Map<UUID, String> canonicalByComponent;
		private final Map<String, String> keyByPurl = new ConcurrentHashMap<>();

		private ComponentIndex(Map<String, UUID> byKey, Map<UUID, String> canonicalByComponent) {
			this.byKey = byKey;
			this.canonicalByComponent = canonicalByComponent;
		}

		/** @param canonicalByComponent component uuid to its {@code sbom_components.canonical_purl} */
		public static ComponentIndex of(Map<UUID, String> canonicalByComponent) {
			Map<String, UUID> byKey = new HashMap<>();
			// sorted, so a duplicate identity always answers with the same component
			for (Map.Entry<UUID, String> e : new TreeMap<>(canonicalByComponent).entrySet()) {
				String key = matchKey(e.getValue());
				if (key != null) byKey.putIfAbsent(key, e.getKey());
			}
			return new ComponentIndex(byKey, new HashMap<>(canonicalByComponent));
		}

		public boolean isEmpty() {
			return canonicalByComponent.isEmpty();
		}

		/**
		 * The component {@code purl} names; null when none does or it does not
		 * parse. A component whose canonical carries no qualifiers at all also
		 * answers for the qualified purl of the same package at the same
		 * version: its SBOM named no distribution, so it is not another one.
		 * The other way round does not hold: a qualifier-less purl does not say
		 * which of a package's distribution builds it means.
		 */
		public UUID componentOf(String purl) {
			String key = keyOf(purl);
			if (key == null) return null;
			UUID exact = byKey.get(key);
			if (exact != null) return exact;
			// the key is PackageURL's canonical form: its only raw '?' starts the qualifiers
			int q = key.indexOf('?');
			return q < 0 ? null : byKey.get(key.substring(0, q));
		}

		public String canonicalPurlOf(UUID component) {
			return canonicalByComponent.get(component);
		}

		private String keyOf(String purl) {
			if (purl == null) return null;
			String key = keyByPurl.computeIfAbsent(purl, p -> {
				String k = matchKey(p);
				return k == null ? UNPARSEABLE : k;
			});
			return key.isEmpty() ? null : key;
		}
	}

	/**
	 * The key two purls match on: same type, namespace, name, version and
	 * identity qualifiers, encoding differences ignored. Null when
	 * {@code purl} is not a parseable pkg: purl.
	 */
	static String matchKey(String purl) {
		if (!Utils.isPurl(purl)) return null;
		return Utils.purlSemanticKey(Utils.canonicalizePurlPreservingEncoding(purl));
	}

	/** The release's components and how far to trust them: two indexed reads, plus the PRODUCT unwind. */
	public ReleaseInventory inventoryOf(UUID releaseUuid) {
		if (releaseUuid == null) return ReleaseInventory.NONE;
		ReleaseComponentPurls purls = sbomComponentService.resolveReleaseComponentPurls(releaseUuid);
		ComponentIndex index = ComponentIndex.of(purls.canonicalByComponent());
		InventoryState state = purls.reconcilePending() ? InventoryState.INVENTORY_PENDING
				: index.isEmpty() ? InventoryState.NO_INVENTORY : InventoryState.SETTLED;
		return new ReleaseInventory(state, index, purls.latestByComponent());
	}

	/**
	 * Which of {@code purls} name a component of the release, matched as
	 * findings are. For the fix-evidence resolver of FDA-Readiness-2 (design
	 * section 6.4): "P@version absent from R's components" is a fix only when
	 * the state is {@link InventoryState#SETTLED}; any other state is UNKNOWN,
	 * because the absence may be an SBOM not indexed yet. Use this rather than
	 * {@code SbomComponentService.releaseHasSbomComponents}, which reads only
	 * the release's own index rows: false for a PRODUCT whose components come
	 * from its dependencies, and true while an index row points at a BOM the
	 * reconcile has not parsed.
	 *
	 * @return the purls present, as given, in the order given
	 */
	public InventoryPresence presentInRelease(UUID releaseUuid, Collection<String> purls) {
		ReleaseInventory inventory = inventoryOf(releaseUuid);
		Set<String> present = new LinkedHashSet<>();
		if (purls != null) {
			for (String purl : purls) {
				if (inventory.components().componentOf(purl) != null) present.add(purl);
			}
		}
		return new InventoryPresence(inventory.state(), Collections.unmodifiableSet(present));
	}

	/**
	 * {@code findings} by the component each one's purl names in
	 * {@code index}, in their stored order; findings no component matches are
	 * left out.
	 */
	public static Map<UUID, List<VulnerabilityDto>> findingsByComponent(ComponentIndex index,
			List<VulnerabilityDto> findings) {
		Map<UUID, List<VulnerabilityDto>> out = new LinkedHashMap<>();
		if (index == null || findings == null) return out;
		for (VulnerabilityDto v : findings) {
			UUID c = v == null ? null : index.componentOf(v.purl());
			if (c != null) out.computeIfAbsent(c, k -> new ArrayList<>()).add(v);
		}
		return out;
	}
}
