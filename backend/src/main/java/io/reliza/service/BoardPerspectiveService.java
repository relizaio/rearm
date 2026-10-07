/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoard;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentBoardRepository;
import io.reliza.service.oss.OssPerspectiveService;
import lombok.extern.slf4j.Slf4j;

/**
 * A board's perspectives (board-permissions.md §3, D7-D11): which ones it may hang off, who may
 * change the set, and the document components that follow it.
 *
 * <p>Entries are real perspectives or PRODUCT components used as perspectives -- the duality the
 * PERSPECTIVE permission scope already has. The rules, applied wherever the set or the target
 * changes: every entry exists in the organization as the kind it claims (1); the board's target is
 * a member of every one (2); and each perspective added or removed is consented to by the caller,
 * who must hold BOARD_WRITE and CONFIGURATION_WRITE covering it (3).
 */
@Slf4j
@Service
public class BoardPerspectiveService {

	/** The marker a board file puts in front of a PRODUCT component used as a perspective. */
	public static final String PRODUCT_MARKER = "product:";

	/** What rule 3 asks covering a perspective added to or removed from a board, in the order a refusal names them. */
	public static final List<PermissionFunction> CONSENT_FUNCTIONS = List.of(PermissionFunction.BOARD_WRITE,
			PermissionFunction.CONFIGURATION_WRITE);

	/**
	 * Whether the caller may add or remove a perspective (rule 3). Built by the fetcher from the
	 * principal it authorized, so the service sees what the caller holds and nothing else.
	 */
	@FunctionalInterface
	public interface PerspectiveConsent {
		boolean allows(UUID perspective);

		/**
		 * The functions of the rule the caller lacks covering the perspective, for the refusal to
		 * name. A consent that cannot tell which names both.
		 */
		default List<PermissionFunction> lacking(UUID perspective) {
			return allows(perspective) ? List.of() : CONSENT_FUNCTIONS;
		}

		/** For a caller with no principal to ask: every change of the set is refused. */
		PerspectiveConsent NONE = p -> false;
	}

	@Autowired private OssPerspectiveService ossPerspectiveService;
	@Autowired private GetComponentService getComponentService;
	@Autowired @Lazy private ComponentService componentService;
	@Autowired private AgentBoardRepository boardRepository;
	@Autowired @Lazy private AgentBoardService agentBoardService;

	/** A perspective of the organization as a board may hold it: real, or a PRODUCT component. */
	public record BoardPerspective(UUID uuid, String name, boolean product) {
		/** As a board file writes it: the name, marked when it is a product. */
		public String entry() {
			return product ? PRODUCT_MARKER + name : name;
		}
	}

	/** An active perspective or PRODUCT component of the org, else empty. */
	public Optional<BoardPerspective> perspective(UUID orgUuid, UUID uuid) {
		if (null == uuid) return Optional.empty();
		return ossPerspectiveService.getPerspectiveData(uuid)
				.filter(pd -> orgUuid.equals(pd.getOrg()) && StatusEnum.ARCHIVED != pd.getStatus())
				.map(pd -> new BoardPerspective(pd.getUuid(), pd.getName(), PerspectiveType.PRODUCT == pd.getType()));
	}

	/**
	 * Resolve a board file's entries (§3.1): a name or uuid of a real perspective, or {@code
	 * product:} and a name or uuid of a PRODUCT component. Problems are collected, one per entry.
	 *
	 * <p>Names need not be unique. A name several match resolves to the one already in {@code stored},
	 * the board's set before the change, when exactly one of them is (architecture round 2 §2): an
	 * export written before another perspective took the name keeps applying. Otherwise, and always
	 * on a create, whose stored set is empty, it is refused.
	 */
	public List<UUID> resolveEntries(UUID orgUuid, List<String> entries, List<UUID> stored, List<String> problems) {
		List<UUID> out = new ArrayList<>();
		if (null == entries) return out;
		for (String raw : entries) {
			String entry = null == raw ? "" : raw.strip();
			boolean product = entry.regionMatches(true, 0, PRODUCT_MARKER, 0, PRODUCT_MARKER.length());
			String ref = product ? entry.substring(PRODUCT_MARKER.length()).strip() : entry;
			if (ref.isEmpty()) {
				problems.add("perspective entry '" + raw + "' names nothing");
				continue;
			}
			List<BoardPerspective> found = byRef(orgUuid, ref, product);
			if (found.size() > 1 && null != stored) {
				List<BoardPerspective> held = found.stream().filter(bp -> stored.contains(bp.uuid())).toList();
				if (held.size() == 1) found = held;
			}
			if (found.isEmpty()) {
				problems.add("perspective '" + raw + "' is not " + (product ? "a PRODUCT component" : "a perspective")
						+ " of this organization" + (product ? "" : "; mark a product with product:"));
			} else if (found.size() > 1) {
				problems.add("perspective '" + raw + "' names " + found.size() + " " + (product ? "PRODUCT components" : "perspectives")
						+ " of this organization; name it by uuid");
			} else if (!out.contains(found.get(0).uuid())) {
				out.add(found.get(0).uuid());
			}
		}
		return out;
	}

	/**
	 * What an entry names: by uuid, or by exact name as the board's target is resolved (§3.1), so the
	 * spelling in a file is the one its export writes back. Several matches is the caller's to refuse.
	 */
	private List<BoardPerspective> byRef(UUID orgUuid, String ref, boolean product) {
		UUID asUuid = asUuid(ref);
		if (null != asUuid) return perspective(orgUuid, asUuid).filter(p -> p.product() == product).stream().toList();
		if (product) {
			return componentService.listComponentDataByOrganization(orgUuid, ComponentType.PRODUCT).stream()
					.filter(c -> StatusEnum.ARCHIVED != c.getStatus() && ref.equals(c.getName()))
					.map(c -> new BoardPerspective(c.getUuid(), c.getName(), true))
					.toList();
		}
		return ossPerspectiveService.listRealPerspectivesOfOrg(orgUuid).stream()
				.filter(pd -> ref.equals(pd.getName()))
				.map(pd -> new BoardPerspective(pd.getUuid(), pd.getName(), false))
				.toList();
	}

	private static UUID asUuid(String s) {
		return s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
				? UUID.fromString(s) : null;
	}

	/**
	 * Check a board's perspective set against the rules (§3.2), collecting every problem: each
	 * uuid a perspective of the org (1), the target a member of each (2), and each perspective in
	 * or out of the set by the caller's consent (3). {@code stored} is the set before the change,
	 * empty for a new board.
	 */
	public List<String> problems(UUID orgUuid, UUID target, List<UUID> wanted, List<UUID> stored,
			PerspectiveConsent consent) {
		List<String> problems = new ArrayList<>();
		List<BoardPerspective> found = new ArrayList<>();
		for (UUID p : null == wanted ? List.<UUID>of() : wanted) {
			Optional<BoardPerspective> bp = perspective(orgUuid, p);
			if (bp.isEmpty()) problems.add("perspective " + p + " is not a perspective or PRODUCT component of this organization");
			else found.add(bp.get());
		}
		if (null != target && !found.isEmpty()) {
			List<String> missing = found.stream().filter(bp -> !isMember(target, bp)).map(BoardPerspective::entry).toList();
			if (!missing.isEmpty()) {
				String name = getComponentService.getComponentData(target).map(ComponentData::getName).orElse(String.valueOf(target));
				problems.add("target " + name + " is not a member of " + String.join(", ", missing) + "; add it first");
			}
		}
		Set<UUID> before = new LinkedHashSet<>(null == stored ? List.of() : stored);
		Set<UUID> after = new LinkedHashSet<>(null == wanted ? List.of() : wanted);
		Set<UUID> changed = new LinkedHashSet<>(after);
		changed.removeAll(before);
		Set<UUID> removed = new LinkedHashSet<>(before);
		removed.removeAll(after);
		changed.addAll(removed);
		for (UUID p : changed) {
			if (!consent.allows(p)) {
				String label = perspective(orgUuid, p).map(BoardPerspective::entry).orElse(String.valueOf(p));
				List<PermissionFunction> lacking = consent.lacking(p);
				String missing = (lacking.isEmpty() ? CONSENT_FUNCTIONS : lacking).stream().map(Enum::name)
						.collect(java.util.stream.Collectors.joining(" and "));
				problems.add((after.contains(p) ? "adding " : "removing ") + "perspective " + label
						+ " needs BOARD_WRITE and CONFIGURATION_WRITE covering it; the caller lacks " + missing
						+ " on " + label);
				break;
			}
		}
		return problems;
	}

	/**
	 * Rule 2 for one perspective: a real one lists the target among its members; a PRODUCT one is
	 * the target or has it in its dependency tree, as the permission walk reads a product.
	 */
	private boolean isMember(UUID target, BoardPerspective bp) {
		if (bp.product()) {
			if (bp.uuid().equals(target)) return true;
			return getComponentService.listComponentsByProduct(bp.uuid()).stream().anyMatch(c -> target.equals(c.getUuid()));
		}
		return getComponentService.listComponentsByPerspective(bp.uuid()).stream().anyMatch(c -> target.equals(c.getUuid()));
	}

	/** The board's real perspectives: those its document components join (D10). */
	public List<UUID> realPerspectives(AgentBoardData board) {
		if (null == board.getPerspectives()) return List.of();
		return board.getPerspectives().stream()
				.filter(p -> perspective(board.getOrg(), p).map(bp -> !bp.product()).orElse(false))
				.toList();
	}

	/** Add the board's real perspectives to a document component it created or adopted (§3.3). */
	public void joinDocumentComponent(AgentBoardData board, UUID component, WhoUpdated wu) throws RelizaException {
		changeMembership(component, realPerspectives(board), List.of(), wu);
	}

	/**
	 * Follow a change of the board's set in its document components: real perspectives added join
	 * each of them, removed ones leave. Products take no members.
	 */
	public void followSetChange(AgentBoardData board, List<UUID> before, List<UUID> after, WhoUpdated wu)
			throws RelizaException {
		List<UUID> added = realOnly(board.getOrg(), minus(after, before));
		List<UUID> removed = minus(before, after).stream()
				.filter(p -> perspective(board.getOrg(), p).map(bp -> !bp.product()).orElse(true))
				.toList();
		if (added.isEmpty() && removed.isEmpty()) return;
		if (null == board.getDocumentComponents()) return;
		for (UUID component : new LinkedHashSet<>(board.getDocumentComponents().values())) {
			changeMembership(component, added, removed, wu);
		}
	}

	private List<UUID> realOnly(UUID orgUuid, List<UUID> ps) {
		return ps.stream().filter(p -> perspective(orgUuid, p).map(bp -> !bp.product()).orElse(false)).toList();
	}

	private static List<UUID> minus(List<UUID> a, List<UUID> b) {
		List<UUID> out = new ArrayList<>(null == a ? List.of() : a);
		if (null != b) out.removeAll(b);
		return out;
	}

	private void changeMembership(UUID component, List<UUID> add, List<UUID> remove, WhoUpdated wu)
			throws RelizaException {
		Optional<ComponentData> ocd = getComponentService.getComponentData(component);
		if (ocd.isEmpty()) return;
		Set<UUID> current = new LinkedHashSet<>(null == ocd.get().getPerspectives() ? Set.of() : ocd.get().getPerspectives());
		Set<UUID> next = new LinkedHashSet<>(current);
		next.addAll(add);
		next.removeAll(remove);
		if (!next.equals(current)) componentService.setPerspectives(component, next, wu);
	}

	/**
	 * A deleted perspective leaves every board of the org (D11); a board left with none is covered
	 * from organization scope only. Its document components leave it too.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public void dropPerspective(UUID orgUuid, UUID perspective, WhoUpdated wu) throws RelizaException {
		for (AgentBoard row : boardRepository.findByOrg(orgUuid.toString())) {
			AgentBoardData bd = AgentBoardData.dataFromRecord(row);
			if (null == bd.getPerspectives() || !bd.getPerspectives().contains(perspective)) continue;
			AgentBoard locked = boardRepository.findByIdWriteLocked(bd.getUuid()).orElse(row);
			AgentBoardData fresh = AgentBoardData.dataFromRecord(locked);
			List<UUID> after = new ArrayList<>(fresh.getPerspectives());
			after.remove(perspective);
			if (null != fresh.getDocumentComponents()) {
				for (UUID component : new LinkedHashSet<>(fresh.getDocumentComponents().values())) {
					changeMembership(component, List.of(), List.of(perspective), wu);
				}
			}
			fresh.setPerspectives(after);
			agentBoardService.saveData(fresh, wu);
			log.info("Perspective {} deleted: dropped from board {}", perspective, fresh.getUuid());
		}
	}

	/**
	 * The entries as a board file writes them, in the board's order (architecture round 2 §1): the
	 * name when exactly one perspective, or PRODUCT component, of the organization carries it, else
	 * the uuid, so the export applies whatever else takes the name; {@code product:} marks a product
	 * either way. An entry that no longer resolves shows its uuid.
	 */
	public List<String> entries(AgentBoardData board) {
		List<String> out = new ArrayList<>();
		if (null == board.getPerspectives()) return out;
		for (UUID p : board.getPerspectives()) {
			out.add(perspective(board.getOrg(), p).map(bp -> entry(board.getOrg(), bp)).orElse(p.toString()));
		}
		return out;
	}

	/** The board's perspectives that still resolve, in the board's order. */
	public List<BoardPerspective> held(AgentBoardData board) {
		List<BoardPerspective> out = new ArrayList<>();
		if (null == board.getPerspectives()) return out;
		for (UUID p : board.getPerspectives()) perspective(board.getOrg(), p).ifPresent(out::add);
		return out;
	}

	/**
	 * The board's perspectives by name, in the board's order and one for one with {@code
	 * perspectives}: what the board shows, and what a client maps a configured name to a uuid by.
	 * Unlike {@link #entries} it never swaps a shared name for its uuid.
	 */
	public List<String> names(AgentBoardData board) {
		List<String> out = new ArrayList<>();
		if (null == board.getPerspectives()) return out;
		for (UUID p : board.getPerspectives()) {
			out.add(perspective(board.getOrg(), p).map(BoardPerspective::entry).orElse(p.toString()));
		}
		return out;
	}

	private String entry(UUID orgUuid, BoardPerspective bp) {
		if (byRef(orgUuid, bp.name(), bp.product()).size() == 1) return bp.entry();
		return bp.product() ? PRODUCT_MARKER + bp.uuid() : bp.uuid().toString();
	}
}
