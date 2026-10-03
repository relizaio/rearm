/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.Utils;
import io.reliza.model.ArtifactData;
import io.reliza.model.ComponentData;
import io.reliza.model.DeliverableData;
import io.reliza.model.HbomComponent;
import io.reliza.model.HbomComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.tea.Rebom.ParsedHbom;
import io.reliza.model.tea.Rebom.ParsedHbomComponent;
import io.reliza.repositories.HbomComponentRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds + caches per-release HBOM (hardware BOM) components. For a hardware
 * release, parses each of its BOM artifacts via rebom's parseHbomById (which
 * returns only the device/firmware nodes) and stores them keyed to the release.
 * Software BOM artifacts simply yield no hardware nodes, so it's safe to run
 * over every BOM artifact without distinguishing HBOM from SBOM.
 */
@Service
@Slf4j
public class HbomComponentService {

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private ArtifactService artifactService;

	@Autowired
	private RebomService rebomService;

	@Autowired
	private VariantService variantService;

	@Autowired
	private GetDeliverableService getDeliverableService;

	@Autowired
	private GetComponentService getComponentService;

	private final HbomComponentRepository repository;

	HbomComponentService(HbomComponentRepository repository) {
		this.repository = repository;
	}

	public List<HbomComponentData> listByRelease(UUID releaseUuid) {
		if (releaseUuid == null) return List.of();
		return repository.findByRelease(releaseUuid.toString()).stream()
				.map(HbomComponentData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/** Returns cached HBOM components; parses + stores on first access. */
	public List<HbomComponentData> getOrReconcile(UUID releaseUuid) {
		List<HbomComponentData> existing = listByRelease(releaseUuid);
		if (!existing.isEmpty()) return existing;
		reconcile(releaseUuid);
		return listByRelease(releaseUuid);
	}

	private Set<UUID> collectBomArtifactUuids(ReleaseData rd) {
		Set<UUID> artifactUuids = new LinkedHashSet<>();
		List<UUID> deliverableUuids = new ArrayList<>();
		if (rd.getInboundDeliverables() != null) deliverableUuids.addAll(rd.getInboundDeliverables());
		variantService.findBaseVariantForRelease(rd.getUuid())
				.ifPresent(v -> deliverableUuids.addAll(v.getOutboundDeliverables()));
		for (DeliverableData dd : getDeliverableService.getDeliverableDataList(deliverableUuids)) {
			if (dd.getArtifacts() != null) artifactUuids.addAll(dd.getArtifacts());
		}
		if (rd.getArtifacts() != null) artifactUuids.addAll(rd.getArtifacts());
		return artifactUuids;
	}

	@Transactional
	public void reconcile(UUID releaseUuid) {
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		if (ord.isEmpty()) return;
		ReleaseData rd = ord.get();
		UUID org = rd.getOrg();

		List<HbomComponent> toSave = new ArrayList<>();
		for (UUID artifactUuid : collectBomArtifactUuids(rd)) {
			Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
			if (oad.isEmpty()) continue;
			ArtifactData ad = oad.get();
			if (ad.getInternalBom() == null || ad.getInternalBom().id() == null) continue;
			ParsedHbom parsed;
			try {
				parsed = rebomService.parseHbom(ad.getInternalBom().id(), org);
			} catch (Exception e) {
				log.warn("HBOM parse failed for artifact {} bom {}: {}",
						ad.getUuid(), ad.getInternalBom().id(), e.getMessage());
				continue;
			}
			if (parsed == null || parsed.components() == null) continue;
			for (ParsedHbomComponent pc : parsed.components()) {
				HbomComponentData hd = new HbomComponentData();
				hd.setOrg(org);
				hd.setRelease(releaseUuid);
				hd.setBomRef(pc.bomRef());
				hd.setType(pc.type());
				hd.setOperator(pc.operator());
				hd.setName(pc.name());
				hd.setVersion(pc.version());
				hd.setDescription(pc.description());
				hd.setCategory(pc.category());
				hd.setSubcategory(pc.subcategory());
				hd.setParties(pc.parties());
				hd.setIdentifiers(pc.identifiers());
				hd.setBoardLocation(pc.boardLocation());
				hd.setDeviceType(pc.deviceType());
				hd.setQuantity(pc.quantity());
				hd.setParentRef(pc.parentRef());
				hd.setIsRoot(pc.isRoot());
				HbomComponent h = new HbomComponent();
				h.setRecordData(Utils.OM.convertValue(hd, new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
				toSave.add(h);
			}
		}
		repository.deleteByRelease(releaseUuid.toString());
		if (!toSave.isEmpty()) repository.saveAll(toSave);
	}

	/** One CDX #929 choice slot of a release tree, with its option nodes. */
	public record ComponentChoiceView(HbomComponentData choice, List<HbomComponentData> options) {}

	/** The release plus its constituent releases (product -> parentReleases), cycle-safe. */
	public List<ReleaseData> releaseTree(ReleaseData root) {
		List<ReleaseData> out = new ArrayList<>();
		walkReleaseTree(root, new HashSet<>(), out);
		return out;
	}

	private void walkReleaseTree(ReleaseData rd, Set<UUID> visited, List<ReleaseData> out) {
		if (rd == null || !visited.add(rd.getUuid())) return;
		out.add(rd);
		if (rd.getParentReleases() == null) return;
		rd.getParentReleases().forEach(pr -> {
			if (pr.getRelease() != null) {
				sharedReleaseService.getReleaseData(pr.getRelease())
						.ifPresent(child -> walkReleaseTree(child, visited, out));
			}
		});
	}

	/**
	 * HBOM rows of the whole release tree (deduped). Only hardware component
	 * releases are reconciled — software releases never carry HBOMs and skipping
	 * them avoids re-parsing their SBOMs on every call.
	 */
	public List<HbomComponentData> hbomComponentsOfReleaseTree(UUID releaseUuid) {
		Optional<ReleaseData> oRoot = sharedReleaseService.getReleaseData(releaseUuid);
		if (oRoot.isEmpty()) return List.of();
		List<HbomComponentData> out = new ArrayList<>();
		Set<UUID> seenRows = new HashSet<>();
		for (ReleaseData rd : releaseTree(oRoot.get())) {
			boolean hardware = rd.getComponent() != null && getComponentService
					.getComponentData(rd.getComponent())
					.map(cd -> cd.getNature() == ComponentData.ComponentNature.HARDWARE)
					.orElse(false);
			if (!hardware) continue;
			for (HbomComponentData hc : getOrReconcile(rd.getUuid())) {
				if (seenRows.add(hc.getUuid())) out.add(hc);
			}
		}
		return out;
	}

	/**
	 * The choice slots of a release tree — used by the ship form (a produced lot
	 * must resolve every slot) and by the repair flow (present approved
	 * alternatives instead of a single part).
	 */
	public List<ComponentChoiceView> releaseComponentChoices(UUID releaseUuid) {
		List<HbomComponentData> rows = hbomComponentsOfReleaseTree(releaseUuid);
		Map<String, List<HbomComponentData>> childrenByParent = new LinkedHashMap<>();
		for (HbomComponentData r : rows) {
			if (r.getParentRef() != null) {
				childrenByParent.computeIfAbsent(r.getParentRef(), k -> new ArrayList<>()).add(r);
			}
		}
		List<ComponentChoiceView> out = new ArrayList<>();
		for (HbomComponentData r : rows) {
			if ("component-choice".equals(r.getType()) && r.getBomRef() != null) {
				out.add(new ComponentChoiceView(r, childrenByParent.getOrDefault(r.getBomRef(), List.of())));
			}
		}
		return out;
	}
}
