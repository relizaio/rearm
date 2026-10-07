/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.AgentBoard;
import io.reliza.model.AgentBoardData;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.RearmSpecificationType;
import io.reliza.repositories.AgentBoardRepository;

/**
 * Which components hold a board's documents (board-documents.md §2). Read-only: recording and
 * creating stay with the publish path, which holds the board lock.
 *
 * <p>A board resolves a specification through its own map. A board from before the map has no
 * entry yet, so it falls back to the components its target is composed of that carry the
 * specification -- the legacy components, which the next publish under the board lock adopts. That
 * fallback leaves out anything that is plainly some other board's: a component another board of the
 * organization has recorded, and a component of kind BOARD_DOCUMENT that does not carry this board's name
 * (only a board creates one). Without that, a second board on the same target would read, and adopt,
 * the first board's series.
 *
 * <p>Only a board from before the map adopts a legacy component. A board created since takes
 * nothing but its own unrecorded BOARD_DOCUMENT components (task RD2-33): a second board on a target whose
 * first board had not yet published after the map arrived would otherwise take the first board's
 * whole series, since nothing marks it as theirs.
 */
@Service
public class BoardDocumentComponentService {

	@Autowired private BranchService branchService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private AgentBoardRepository boardRepository;

	/** The board's component for a specification: the recorded one, else the legacy one, if any. */
	public Optional<UUID> componentOf(AgentBoardData board, RearmSpecificationType spec) {
		Optional<UUID> recorded = recordedOf(board, spec);
		if (recorded.isPresent()) return recorded;
		return legacyComponentOf(board, spec);
	}

	/**
	 * What a board's document input of a specification may resolve to: the recorded component
	 * alone; before one is recorded, every legacy component of the target carrying the
	 * specification, as the scan offered them before the map existed.
	 */
	public List<UUID> candidatesOf(AgentBoardData board, RearmSpecificationType spec) {
		Optional<UUID> recorded = recordedOf(board, spec);
		if (recorded.isPresent()) return List.of(recorded.get());
		return legacyComponents(board).getOrDefault(spec, List.of());
	}

	/** The component the board has recorded for a specification. */
	public Optional<UUID> recordedOf(AgentBoardData board, RearmSpecificationType spec) {
		if (null == board || null == board.getDocumentComponents()) return Optional.empty();
		return Optional.ofNullable(board.getDocumentComponents().get(spec));
	}

	/**
	 * The component a board from before the map would adopt for a specification: the first of its
	 * target's components that carries the specification and is not another board's.
	 */
	public Optional<UUID> legacyComponentOf(AgentBoardData board, RearmSpecificationType spec) {
		return legacyComponents(board).getOrDefault(spec, List.of()).stream().findFirst();
	}

	/**
	 * Every component holding one of the board's documents: the recorded ones, and the legacy
	 * candidates of each specification that has no entry yet.
	 */
	public List<UUID> componentsOf(AgentBoardData board) {
		java.util.LinkedHashSet<UUID> out = new java.util.LinkedHashSet<>();
		Map<RearmSpecificationType, UUID> recorded = null == board.getDocumentComponents() ? Map.of()
				: board.getDocumentComponents();
		out.addAll(recorded.values());
		legacyComponents(board).forEach((spec, comps) -> {
			if (!recorded.containsKey(spec)) out.addAll(comps);
		});
		return new ArrayList<>(out);
	}

	/**
	 * The unrecorded candidates per specification, in the target's composition order. A board
	 * created since the map (RD2-33) has no legacy: it takes only a BOARD_DOCUMENT component carrying its
	 * own name -- one it made on a path that does not record, or left behind by a rolled-back
	 * publish -- and nothing else its target carries, which is another board's, recorded or not.
	 */
	private Map<RearmSpecificationType, List<UUID>> legacyComponents(AgentBoardData board) {
		Map<RearmSpecificationType, List<UUID>> out = new EnumMap<>(RearmSpecificationType.class);
		if (null == board || null == board.getTarget()) return out;
		boolean adoptsLegacy = board.getAdoptsLegacyDocuments();
		Optional<Branch> base = branchService.getBaseBranchOfComponent(board.getTarget());
		if (base.isEmpty()) return out;
		BranchData bd = BranchData.branchDataFromDbRecord(base.get());
		if (null == bd.getDependencies()) return out;
		Set<UUID> othersOwn = null;
		for (ChildComponent cc : bd.getDependencies()) {
			if (null == cc || null == cc.getUuid()) continue;
			Optional<ComponentData> ocd = getComponentService.getComponentData(cc.getUuid());
			if (ocd.isEmpty()) continue;
			ComponentData cd = ocd.get();
			List<RearmSpecificationType> specs = specificationsOf(cd);
			if (specs.isEmpty()) continue;
			if (!adoptsLegacy && ComponentKind.BOARD_DOCUMENT != cd.getKind()) continue;
			if (null == othersOwn) othersOwn = recordedByOtherBoards(board);
			if (othersOwn.contains(cc.getUuid())) continue;
			for (RearmSpecificationType spec : specs) {
				// A BOARD_DOCUMENT component is a board's own creation, recorded as it is made. One left
				// unrecorded -- its creation committed and the publish that made it then rolled back,
				// or it was made on a path that may not take the board lock -- is this board's only
				// when it carries this board's name for the specification; another board's never is.
				if (ComponentKind.BOARD_DOCUMENT == cd.getKind()
						&& !board.documentComponentName(spec).equalsIgnoreCase(cd.getName())) continue;
				out.computeIfAbsent(spec, k -> new ArrayList<>()).add(cc.getUuid());
			}
		}
		return out;
	}

	/** Components the organization's other boards have recorded as theirs. */
	private Set<UUID> recordedByOtherBoards(AgentBoardData board) {
		Set<UUID> out = new HashSet<>();
		if (null == board.getOrg()) return out;
		for (AgentBoard row : boardRepository.findByOrg(board.getOrg().toString())) {
			if (row.getUuid().equals(board.getUuid())) continue;
			AgentBoardData other = AgentBoardData.dataFromRecord(row);
			if (null != other.getDocumentComponents()) out.addAll(other.getDocumentComponents().values());
		}
		return out;
	}

	/** The specifications a component carries as SPECIFICATION identifiers. */
	static List<RearmSpecificationType> specificationsOf(ComponentData cd) {
		List<RearmSpecificationType> out = new ArrayList<>();
		List<RearmIdentifier> ids = cd.getIdentifiers();
		if (null == ids) return out;
		for (RearmIdentifier i : ids) {
			if (null == i || RearmIdentifierType.SPECIFICATION != i.getIdType() || null == i.getIdValue()) continue;
			// An identifier naming no known specification carries none of the board's documents.
			for (RearmSpecificationType spec : RearmSpecificationType.values()) {
				if (spec.name().equals(i.getIdValue())) out.add(spec);
			}
		}
		return out;
	}
}
