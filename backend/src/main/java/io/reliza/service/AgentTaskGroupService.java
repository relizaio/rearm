/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentBoardData.GroupStatus;
import io.reliza.model.AgentBoardData.TaskGroup;
import io.reliza.model.AgentTaskData;
import io.reliza.model.WhoUpdated;

/**
 * A board's task groups (task-groups-and-tags.md §2, task RD2-29): created, edited, closed, reopened
 * and deleted by key, each change under the board's row lock, as the task-key prefix is. What a group
 * does to its tasks -- membership, the gate, the level rung -- is {@link AgentTaskService}'s.
 */
@Service
public class AgentTaskGroupService {

	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;

	/** Lower case, a letter or digit first, then letters, digits and hyphens: 2 to 24 characters. */
	private static final Pattern GROUP_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{1,23}");

	/**
	 * What a person, the seat or a board file says about a group. Null leaves a field as it is on an
	 * update (a create takes the defaults); {@code defaultWorkLevelSet} says the level was sent at all, so
	 * null clears it. {@code uuid} names the group to edit when the key itself is changing.
	 */
	public record GroupInput(UUID uuid, String key, String name, String description, Integer order,
			List<String> dependsOn, Integer defaultWorkLevel, boolean defaultWorkLevelSet, GroupStatus status) {}

	/** A group key as typed, lower-cased and checked. */
	public static String checkedGroupKey(String key) throws RelizaException {
		String k = null == key ? "" : key.strip().toLowerCase(java.util.Locale.ROOT);
		if (!GROUP_KEY.matcher(k).matches()) {
			throw new RelizaException("A group key is 2 to 24 lower-case letters, digits and hyphens, starting with a"
					+ " letter or digit (got '" + (null == key ? "" : key) + "')");
		}
		return k;
	}

	/**
	 * Create a group, or edit the one this key (or uuid) names. Refused: a second group with a key the
	 * board has; a group depending on itself, on a key the board does not have, or round a loop; a new
	 * key for a group tasks already name.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public TaskGroup setGroup(UUID boardUuid, GroupInput in, WhoUpdated wu) throws RelizaException {
		String key = checkedGroupKey(in.key());
		AgentBoardData bd = agentBoardService.lockBoard(boardUuid);
		List<TaskGroup> groups = new ArrayList<>(null == bd.getGroups() ? List.of() : bd.getGroups());
		Optional<TaskGroup> existing = null != in.uuid() ? bd.groupByUuid(in.uuid()) : bd.groupByKey(key);
		if (null != in.uuid() && existing.isEmpty()) {
			throw new RelizaException("No group " + in.uuid() + " on board " + bd.getName());
		}
		Optional<TaskGroup> sameKey = bd.groupByKey(key);
		if (sameKey.isPresent() && (existing.isEmpty() || !sameKey.get().uuid().equals(existing.get().uuid()))) {
			throw new RelizaException("group " + key + " exists on this board");
		}
		if (existing.isPresent() && !existing.get().key().equals(key)) {
			long held = tasksOf(bd, existing.get().uuid()).size();
			if (held > 0) {
				throw new RelizaException("group " + existing.get().key() + " is referenced by " + held
						+ (held == 1 ? " task" : " tasks") + "; its key is immutable");
			}
		}
		UUID uuid = existing.map(TaskGroup::uuid).orElseGet(UUID::randomUUID);
		List<UUID> dependsOn = null == in.dependsOn()
				? existing.map(TaskGroup::dependsOn).orElse(List.of())
				: dependencyUuids(bd, key, in.dependsOn());
		Integer defaultWorkLevel = in.defaultWorkLevelSet() ? AgentTaskService.checkedWorkLevel(bd.getLadder(), in.defaultWorkLevel())
				: existing.map(TaskGroup::defaultWorkLevel).orElse(null);
		int order = null != in.order() ? in.order()
				: existing.map(TaskGroup::order).orElseGet(() -> groups.stream().mapToInt(TaskGroup::order).max().orElse(0) + 1);
		TaskGroup g = new TaskGroup(uuid, key,
				null != in.name() ? in.name().strip() : existing.map(TaskGroup::name).orElse(null),
				null != in.description() ? in.description() : existing.map(TaskGroup::description).orElse(null),
				order, dependsOn, defaultWorkLevel,
				null != in.status() ? in.status() : existing.map(TaskGroup::status).orElse(GroupStatus.OPEN),
				existing.map(TaskGroup::createdAt).orElseGet(ZonedDateTime::now));
		if (g.name() != null && (g.name().contains("\n") || g.name().contains("\r"))) {
			throw new RelizaException("A group name is one line");
		}
		groups.removeIf(x -> x.uuid().equals(uuid));
		groups.add(g);
		refuseCycle(groups, g);
		bd.setGroups(groups);
		agentBoardService.saveData(bd, wu);
		return g;
	}

	/** Close a group: it takes no new tasks and nothing else changes (D6). */
	@Transactional(rollbackFor = RelizaException.class)
	public TaskGroup setStatus(UUID boardUuid, String key, GroupStatus status, WhoUpdated wu) throws RelizaException {
		return setGroup(boardUuid, new GroupInput(null, key, null, null, null, null, null, false, status), wu);
	}

	/** Delete an empty group; refused while a task names it. Another group depending on it drops the dependency. */
	@Transactional(rollbackFor = RelizaException.class)
	public void deleteGroup(UUID boardUuid, String key, WhoUpdated wu) throws RelizaException {
		String k = checkedGroupKey(key);
		AgentBoardData bd = agentBoardService.lockBoard(boardUuid);
		TaskGroup g = bd.groupByKey(k)
				.orElseThrow(() -> new RelizaException("No group " + k + " on board " + bd.getName()));
		int held = tasksOf(bd, g.uuid()).size();
		if (held > 0) {
			throw new RelizaException("group " + k + " holds " + held + (held == 1 ? " task" : " tasks")
					+ "; move them first");
		}
		List<TaskGroup> groups = new ArrayList<>();
		for (TaskGroup other : bd.getGroups()) {
			if (other.uuid().equals(g.uuid())) continue;
			if (other.dependsOn().contains(g.uuid())) {
				List<UUID> deps = new ArrayList<>(other.dependsOn());
				deps.remove(g.uuid());
				other = new TaskGroup(other.uuid(), other.key(), other.name(), other.description(), other.order(),
						deps, other.defaultWorkLevel(), other.status(), other.createdAt());
			}
			groups.add(other);
		}
		bd.setGroups(groups);
		agentBoardService.saveData(bd, wu);
	}

	/**
	 * The groups a board holds once a board file or the board form declares its list (task RD2-30,
	 * declarative-boards D15): a listed group is created or updated by key -- or by uuid, from the
	 * form -- each member as declared (absent leaves it, null clears it); a group the list leaves out
	 * is CLOSED, never deleted; the list's order is the display order. An empty or null list deletes
	 * every group, refused while a group holds tasks. Nothing is thrown: every problem is added to
	 * {@code problems} and the caller writes nothing when there is one.
	 *
	 * @param held tasks per group uuid, asked only when a group would be deleted or renamed
	 */
	static List<TaskGroup> groupsAfter(List<TaskGroup> current, List<AgentBoardService.BoardGroupSpecDto> entries,
			java.util.function.Supplier<Map<UUID, Long>> held, AgentBoardData.Ladder ladder, List<String> problems) {
		List<TaskGroup> now = null == current ? List.of() : current;
		if (null == entries || entries.isEmpty()) {
			List<String> holding = new ArrayList<>();
			for (TaskGroup g : sortedByOrder(now)) {
				long n = held.get().getOrDefault(g.uuid(), 0L);
				if (n > 0) holding.add(g.key() + " holds " + n + (n == 1 ? " task" : " tasks"));
			}
			if (!holding.isEmpty()) problems.add("groups cannot be cleared: " + String.join(", ", holding));
			return List.of();
		}
		int before = problems.size();
		Map<String, TaskGroup> byKey = new HashMap<>();
		Map<UUID, TaskGroup> byUuid = new HashMap<>();
		for (TaskGroup g : now) {
			byKey.put(g.key(), g);
			byUuid.put(g.uuid(), g);
		}
		// Keys first: a dependency may name any group the list leaves on the board.
		List<String> keys = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		List<TaskGroup> existingOf = new ArrayList<>();
		int position = 0;
		for (AgentBoardService.BoardGroupSpecDto e : entries) {
			position++;
			String key;
			try {
				key = checkedGroupKey(null == e ? null : e.getKey());
			} catch (RelizaException ex) {
				problems.add("group #" + position + ": " + ex.getMessage());
				keys.add(null);
				existingOf.add(null);
				continue;
			}
			if (!seen.add(key)) problems.add("group " + key + " is declared more than once");
			TaskGroup existing = null != e.getUuid() ? byUuid.get(e.getUuid()) : byKey.get(key);
			if (null != e.getUuid() && null == existing) problems.add("No group " + e.getUuid() + " on this board");
			if (null != existing && !existing.key().equals(key)) {
				long n = held.get().getOrDefault(existing.uuid(), 0L);
				if (n > 0) {
					problems.add("group " + existing.key() + " is referenced by " + n + (n == 1 ? " task" : " tasks")
							+ "; its key is immutable");
				}
			}
			keys.add(key);
			existingOf.add(existing);
		}
		java.util.Set<UUID> listed = new java.util.HashSet<>();
		existingOf.stream().filter(java.util.Objects::nonNull).forEach(g -> listed.add(g.uuid()));
		for (TaskGroup g : now) {
			if (!listed.contains(g.uuid()) && seen.contains(g.key())) {
				problems.add("group " + g.key() + " exists on this board");
			}
		}
		if (problems.size() > before) return now;

		Map<String, UUID> uuidOfKey = new HashMap<>();
		List<UUID> uuids = new ArrayList<>();
		for (int i = 0; i < entries.size(); i++) {
			UUID u = null != existingOf.get(i) ? existingOf.get(i).uuid() : UUID.randomUUID();
			uuids.add(u);
			uuidOfKey.put(keys.get(i), u);
		}
		for (TaskGroup g : now) {
			if (!listed.contains(g.uuid())) uuidOfKey.putIfAbsent(g.key(), g.uuid());
		}

		List<TaskGroup> out = new ArrayList<>();
		for (int i = 0; i < entries.size(); i++) {
			AgentBoardService.BoardGroupSpecDto e = entries.get(i);
			TaskGroup was = existingOf.get(i);
			String key = keys.get(i);
			java.util.Set<String> d = e.getDeclared();
			String name = has(d, "name", e.getName()) ? (null == e.getName() ? null : e.getName().strip())
					: null == was ? null : was.name();
			if (null != name && (name.contains("\n") || name.contains("\r"))) {
				problems.add("group " + key + ": a group name is one line");
			}
			String description = has(d, "description", e.getDescription()) ? e.getDescription()
					: null == was ? null : was.description();
			Integer defaultWorkLevel = null == was ? null : was.defaultWorkLevel();
			if (has(d, "defaultWorkLevel", e.getDefaultWorkLevel())) {
				try {
					defaultWorkLevel = AgentTaskService.checkedWorkLevel(ladder, e.getDefaultWorkLevel());
				} catch (RelizaException ex) {
					problems.add("group " + key + ": " + ex.getMessage());
				}
			}
			GroupStatus status = has(d, "status", e.getStatus()) ? e.getStatus() : null == was ? null : was.status();
			List<UUID> dependsOn = null == was ? List.of() : was.dependsOn();
			if (has(d, "dependsOn", e.getDependsOn())) {
				LinkedHashSet<UUID> deps = new LinkedHashSet<>();
				for (String raw : null == e.getDependsOn() ? List.<String>of() : e.getDependsOn()) {
					String k = null == raw ? "" : raw.strip().toLowerCase(java.util.Locale.ROOT);
					if (k.equals(key)) {
						problems.add("group " + key + " depends on itself");
					} else if (!uuidOfKey.containsKey(k)) {
						problems.add("group " + key + " depends on " + raw + ", which is not a group of this board");
					} else {
						deps.add(uuidOfKey.get(k));
					}
				}
				dependsOn = new ArrayList<>(deps);
			}
			int order = null != e.getOrder() ? e.getOrder() : i + 1;
			out.add(new TaskGroup(uuids.get(i), key, name, description, order, dependsOn, defaultWorkLevel, status,
					null == was ? ZonedDateTime.now() : was.createdAt()));
		}
		// Left out: closed, after the listed ones, in the order they had.
		int next = out.stream().mapToInt(TaskGroup::order).max().orElse(0);
		for (TaskGroup g : sortedByOrder(now)) {
			if (listed.contains(g.uuid())) continue;
			out.add(new TaskGroup(g.uuid(), g.key(), g.name(), g.description(), ++next, g.dependsOn(),
					g.defaultWorkLevel(), GroupStatus.CLOSED, g.createdAt()));
		}
		if (problems.size() > before) return now;
		// One loop is one problem, however many of its groups the walk could start from.
		for (TaskGroup g : out) {
			try {
				refuseCycle(out, g);
			} catch (RelizaException ex) {
				problems.add(ex.getMessage());
				break;
			}
		}
		return problems.size() > before ? now : out;
	}

	/** As the board file's presence rule: declared when the entry carried it; built in code, when set. */
	private static boolean has(java.util.Set<String> declared, String key, Object value) {
		if (null != declared) return declared.contains(key);
		return null != value;
	}

	/** Groups by display order, then key. */
	static List<TaskGroup> sortedByOrder(List<TaskGroup> groups) {
		List<TaskGroup> out = new ArrayList<>(null == groups ? List.of() : groups);
		out.sort(java.util.Comparator.comparingInt(TaskGroup::order).thenComparing(TaskGroup::key));
		return out;
	}

	private List<AgentTaskData> tasksOf(AgentBoardData bd, UUID group) {
		return agentTaskService.listByBoard(bd.getUuid(), null).stream()
				.filter(t -> group.equals(t.getGroup())).toList();
	}

	/** The keys a group depends on, as uuids of this board's groups; itself and unknown keys refused. */
	private static List<UUID> dependencyUuids(AgentBoardData bd, String key, List<String> keys) throws RelizaException {
		LinkedHashSet<UUID> out = new LinkedHashSet<>();
		for (String raw : keys) {
			String k = null == raw ? "" : raw.strip().toLowerCase(java.util.Locale.ROOT);
			if (k.equals(key)) throw new RelizaException("group " + key + " depends on itself");
			TaskGroup dep = bd.groupByKey(k).orElseThrow(() -> new RelizaException("a group depends on " + raw
					+ ", which is not a group of this board"));
			out.add(dep.uuid());
		}
		return new ArrayList<>(out);
	}

	/** Refuses a set of groups whose dependencies loop through {@code changed}, naming the loop. */
	static void refuseCycle(List<TaskGroup> groups, TaskGroup changed) throws RelizaException {
		Map<UUID, TaskGroup> byUuid = new HashMap<>();
		for (TaskGroup g : groups) byUuid.put(g.uuid(), g);
		List<UUID> path = new ArrayList<>();
		List<UUID> loop = walk(changed.uuid(), changed.uuid(), byUuid, path, new java.util.HashSet<>());
		if (null != loop) {
			List<String> keys = new ArrayList<>();
			for (UUID u : loop) keys.add(byUuid.get(u).key());
			keys.add(changed.key());
			throw new RelizaException("group cycle: " + String.join(" → ", keys));
		}
	}

	/** Depth-first from {@code at}; the path back to {@code start} when there is one. */
	private static List<UUID> walk(UUID start, UUID at, Map<UUID, TaskGroup> byUuid, List<UUID> path,
			java.util.Set<UUID> seen) {
		path.add(at);
		TaskGroup g = byUuid.get(at);
		if (null != g) {
			for (UUID dep : g.dependsOn()) {
				if (dep.equals(start)) return new ArrayList<>(path);
				if (seen.add(dep)) {
					List<UUID> found = walk(start, dep, byUuid, path, seen);
					if (null != found) return found;
				}
			}
		}
		path.remove(path.size() - 1);
		return null;
	}

}
