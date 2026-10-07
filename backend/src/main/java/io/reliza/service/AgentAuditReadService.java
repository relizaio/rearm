/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.model.AgentBoard;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTask;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskRoleConfig;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.Audit;
import lombok.extern.slf4j.Slf4j;

/**
 * The earlier revisions of tasks, boards and role configs (task 22ddc644, gaps §1.5).
 *
 * <p>Every save of one of these writes the row as it was into {@code audit} before it is
 * overwritten, so revision {@code n} there is the entity between its n-th and (n+1)-th save and
 * the live row is the newest revision, not repeated here. Each snapshot is parsed into the data
 * class the live row uses, so a revision is served through the same resolvers: a task's
 * {@code usage}, {@code openReviewItems} and the rest compute over the snapshot as they do over the
 * live data.
 *
 * <p>A snapshot that no longer parses is served with a null body rather than failing the list:
 * the revision and its time are still true, and one unreadable row should not hide the others.
 */
@Slf4j
@Service
public class AgentAuditReadService {

	/** Revisions per page when the caller does not say. */
	public static final int DEFAULT_LIMIT = 50;
	/** The most one page returns: a task with many rounds is tens of KB per revision. */
	public static final int MAX_LIMIT = 200;

	@Autowired
	private AuditService auditService;

	public record AgentTaskRevision(int revision, ZonedDateTime at, AgentTaskData task) {}

	public record AgentBoardRevision(int revision, ZonedDateTime at, AgentBoardData board) {}

	public record AgentRoleConfigRevision(int revision, ZonedDateTime at, AgentTaskRoleConfigData roleConfig) {}

	/** A task's earlier revisions, newest first. */
	public List<AgentTaskRevision> taskHistory(UUID taskUuid, Integer limit, Integer offset) {
		return read(taskUuid, TableName.AGENT_TASKS, limit, offset, a -> {
			AgentTask t = new AgentTask();
			t.setUuid(a.getEntityUuid());
			t.setCreatedDate(a.getEntityCreatedDate());
			t.setSchemaVersion(a.getSchemaVersion());
			t.setRecordData(a.getRevisionRecordData());
			return AgentTaskData.dataFromRecord(t);
		}, (a, td) -> new AgentTaskRevision(a.getRevision(), a.getRevisionCreatedDate(), td));
	}

	/** A board's earlier revisions, newest first. */
	public List<AgentBoardRevision> boardHistory(UUID boardUuid, Integer limit, Integer offset) {
		return read(boardUuid, TableName.AGENT_BOARDS, limit, offset, a -> {
			AgentBoard b = new AgentBoard();
			b.setUuid(a.getEntityUuid());
			b.setCreatedDate(a.getEntityCreatedDate());
			b.setSchemaVersion(a.getSchemaVersion());
			b.setRecordData(a.getRevisionRecordData());
			return AgentBoardData.dataFromRecord(b);
		}, (a, bd) -> new AgentBoardRevision(a.getRevision(), a.getRevisionCreatedDate(), bd));
	}

	/** A role config's (or preset's) earlier revisions, newest first. */
	public List<AgentRoleConfigRevision> roleConfigHistory(UUID roleConfigUuid, Integer limit, Integer offset) {
		return read(roleConfigUuid, TableName.AGENT_TASK_ROLE_CONFIGS, limit, offset, a -> {
			AgentTaskRoleConfig rc = new AgentTaskRoleConfig();
			rc.setUuid(a.getEntityUuid());
			rc.setCreatedDate(a.getEntityCreatedDate());
			rc.setSchemaVersion(a.getSchemaVersion());
			rc.setRecordData(a.getRevisionRecordData());
			return AgentTaskRoleConfigData.dataFromRecord(rc);
		}, (a, rcd) -> new AgentRoleConfigRevision(a.getRevision(), a.getRevisionCreatedDate(), rcd));
	}

	/** The page size actually read: the default when unset or below one, never above the cap. */
	static int pageLimit(Integer limit) {
		if (limit == null || limit < 1) return DEFAULT_LIMIT;
		return Math.min(limit, MAX_LIMIT);
	}

	/**
	 * One page of an entity's audit rows, each rebuilt as the entity it snapshots and read through
	 * that entity's own dataFromRecord, the path the live row takes.
	 */
	private <D, R> List<R> read(UUID entityUuid, TableName table, Integer limit, Integer offset,
			Function<Audit, D> parse, BiFunction<Audit, D, R> revision) {
		int from = offset == null || offset < 0 ? 0 : offset;
		return auditService.getAuditForEntity(entityUuid, table, pageLimit(limit), from).stream()
				.map(a -> revision.apply(a, parsed(a, parse)))
				.toList();
	}

	private static <D> D parsed(Audit a, Function<Audit, D> parse) {
		try {
			return parse.apply(a);
		} catch (RuntimeException e) {
			log.error("Audit revision {} of {} {} does not parse", a.getRevision(), a.getEntityName(),
					a.getEntityUuid(), e);
			return null;
		}
	}
}
