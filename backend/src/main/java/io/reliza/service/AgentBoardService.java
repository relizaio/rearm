/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Locale;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.Data;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.common.StrengthScale;
import io.reliza.model.ModelOntologyData;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoard;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.tracker.TrackerSource;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.InputResolution;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.ComponentData;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentBoardData.BoardPause;
import io.reliza.model.AgentBoardData.BoardPauseLevel;
import io.reliza.model.AgentBoardData.BoardStatus;
import io.reliza.model.AgentBoardData.CoordinatorSeat;
import io.reliza.model.AgentBoardData.PriorityType;
import io.reliza.model.AgentTaskRoleConfig;
import io.reliza.model.AgentTaskRoleConfigData.AgentCapability;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.AgentTask;
import io.reliza.model.AgentTaskData;
import io.reliza.model.DeclarativeProvenance;
import io.reliza.model.OrganizationData;
import io.reliza.service.DeclarativeConfigService.Action;
import io.reliza.service.DeclarativeConfigService.ApplyResult;
import io.reliza.service.DeclarativeConfigService.Change;
import io.reliza.service.DeclarativeConfigService.DeclarativeKind;
import io.reliza.service.DeclarativeConfigService.SourceDto;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import io.reliza.repositories.AgentBoardRepository;
import io.reliza.repositories.AgentTaskRoleConfigRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Boards: the unit of workflow governance for hub-and-spoke agent
 * task distribution. Owns board lifecycle, the two-tier lock, the
 * singleton coordinator seat, and the board-scoped role configs.
 * Full design: backend/ai-plans/agentic/task-boards.md.
 */
@Slf4j
@Service
public class AgentBoardService {

	/** Board events that need a person, as notifications (task 82880ea6). */
	@Autowired @org.springframework.context.annotation.Lazy
	private AgentBoardNotifier agentBoardNotifier;

	@Autowired
	private VcsRepositoryService vcsRepositoryService;

	@Autowired private GetComponentService getComponentService;
	@Autowired private JdbcTemplate jdbcTemplate;
	/** Absent where no implementation is deployed: gates are then accepted unchecked and SKIP at run time. */
	@Autowired(required = false) private ElementQueryEvaluator elementQueryEvaluator;

	@Autowired
	private AgentBoardRepository repository;

	@Autowired
	private AgentTaskRoleConfigRepository roleConfigRepository;

	@Autowired
	private io.reliza.repositories.ModelOntologyRepository modelOntologyRepository;

	@Autowired
	private AuditService auditService;

	@Autowired private io.reliza.repositories.AgentTaskRepository taskRepository;

	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired @org.springframework.context.annotation.Lazy private OrganizationService organizationService;

	@Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

	@jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

	// Both depend on this service, one way or another; lazy so neither cycle has to be broken.
	@Autowired @org.springframework.context.annotation.Lazy private ModelOntologyService modelOntologyService;

	@Autowired @org.springframework.context.annotation.Lazy private DeclarativeConfigService declarativeConfigService;
	@Autowired @org.springframework.context.annotation.Lazy private BoardPerspectiveService boardPerspectiveService;
	/** Its own proxy, so the retention sweep's per-board transactions are real ones (task 04dedcc5). */
	@Autowired @org.springframework.context.annotation.Lazy private AgentBoardService self;
	@Autowired @org.springframework.context.annotation.Lazy private AuthorizationService authorizationService;
	@Autowired @org.springframework.context.annotation.Lazy private ApiKeyService apiKeyService;

	// ---------- Board lifecycle (operator) ----------
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData createBoard(UUID orgUuid, String name, String description,
			List<String> sources, String coordinatorPrompt, Integer perAgentWipLimit,
			PriorityType priorityType, UUID target, Integer defaultWorkLevel, WhoUpdated wu) throws RelizaException {
		return createBoard(orgUuid, name, description, sources, coordinatorPrompt, perAgentWipLimit, priorityType,
				target, defaultWorkLevel, null, List.of(), BoardPerspectiveService.PerspectiveConsent.NONE, wu);
	}

	/**
	 * As above, with the task-key prefix: the one given, else derived from the name. Claimed in the
	 * organization's registry in the same transaction, so a board is never created without one and a
	 * refused prefix creates nothing.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData createBoard(UUID orgUuid, String name, String description,
			List<String> sources, String coordinatorPrompt, Integer perAgentWipLimit,
			PriorityType priorityType, UUID target, Integer defaultWorkLevel, String taskPrefix,
			WhoUpdated wu) throws RelizaException {
		return createBoard(orgUuid, name, description, sources, coordinatorPrompt, perAgentWipLimit, priorityType,
				target, defaultWorkLevel, taskPrefix, List.of(), BoardPerspectiveService.PerspectiveConsent.NONE, wu);
	}

	/**
	 * As above, in a set of perspectives (board-permissions.md §3): each must exist, the target must
	 * be a member of each, and the caller must consent to each -- a create adds them all.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData createBoard(UUID orgUuid, String name, String description,
			List<String> sources, String coordinatorPrompt, Integer perAgentWipLimit,
			PriorityType priorityType, UUID target, Integer defaultWorkLevel, List<UUID> perspectives,
			BoardPerspectiveService.PerspectiveConsent consent, WhoUpdated wu) throws RelizaException {
		return createBoard(orgUuid, name, description, sources, coordinatorPrompt, perAgentWipLimit, priorityType,
				target, defaultWorkLevel, null, perspectives, consent, wu);
	}

	/** Both: the task-key prefix and the perspectives. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData createBoard(UUID orgUuid, String name, String description,
			List<String> sources, String coordinatorPrompt, Integer perAgentWipLimit,
			PriorityType priorityType, UUID target, Integer defaultWorkLevel, String taskPrefix,
			List<UUID> perspectives, BoardPerspectiveService.PerspectiveConsent consent, WhoUpdated wu)
			throws RelizaException {
		List<UUID> wantedPerspectives = null == perspectives ? List.of()
				: new ArrayList<>(new java.util.LinkedHashSet<>(perspectives));
		if (!wantedPerspectives.isEmpty()) {
			List<String> problems = boardPerspectiveService.problems(orgUuid, target, wantedPerspectives, List.of(), consent);
			if (!problems.isEmpty()) throw new RelizaException(String.join("; ", problems));
		}
		if (orgUuid == null) throw new RelizaException("Board requires an org");
		if (StringUtils.isBlank(name)) throw new RelizaException("Board requires a name");
		if (repository.findByOrgAndName(orgUuid.toString(), name).isPresent()) {
			throw new RelizaException("Board named " + name + " already exists in this org");
		}
		if (target == null) throw new RelizaException("Board requires a target component -- the node it builds");
		validateTarget(target, orgUuid);
		AgentBoardData bd = new AgentBoardData();
		bd.setOrg(orgUuid);
		bd.setName(name);
		bd.setDescription(description);
		if (sources != null) bd.setSources(renderSources(sources));
		bd.setCoordinatorPrompt(coordinatorPrompt);
		if (perAgentWipLimit != null && perAgentWipLimit > 0) bd.setPerAgentWipLimit(perAgentWipLimit);
		if (priorityType != null) bd.setPriorityType(priorityType);
		bd.setTarget(target);
		if (defaultWorkLevel != null) bd.setDefaultWorkLevel(checkedDefaultWorkLevel(defaultWorkLevel));
		bd.setPerspectives(wantedPerspectives);
		// Nothing to number yet: every task of a new board is numbered at registration.
		bd.setTasksNumbered(true);
		// Nor any legacy document component to adopt (RD2-33): its first publish creates its own.
		bd.setAdoptsLegacyDocuments(false);
		String wanted = null == taskPrefix ? null : normaliseTaskPrefix(taskPrefix);
		bd = saveData(bd, wu);
		applyTaskPrefix(bd, wanted, wu);
		return saveData(bd, wu);
	}

	/**
	 * Set a board's perspectives from the form (board-permissions.md §3.2): the three rules against
	 * the stored set, then the document components follow the change (§3.3).
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData setPerspectives(UUID boardUuid, List<UUID> perspectives,
			BoardPerspectiveService.PerspectiveConsent consent, WhoUpdated wu) throws RelizaException {
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		entityManager.refresh(b);
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		List<UUID> stored = null == bd.getPerspectives() ? List.of() : bd.getPerspectives();
		List<UUID> wanted = null == perspectives ? List.of() : new ArrayList<>(new java.util.LinkedHashSet<>(perspectives));
		List<String> problems = boardPerspectiveService.problems(bd.getOrg(), bd.getTarget(), wanted, stored, consent);
		if (!problems.isEmpty()) throw new RelizaException(String.join("; ", problems));
		boardPerspectiveService.followSetChange(bd, stored, wanted, wu);
		bd.setPerspectives(wanted);
		return saveData(bd, wu);
	}

	// ---------- Task keys (board-documents.md §4) ----------

	private static final java.util.regex.Pattern TASK_PREFIX = java.util.regex.Pattern.compile("[A-Z0-9]{2,8}");

	/** A prefix as stored: trimmed and upper-cased, then 2 to 8 letters and digits, else refused. */
	public static String normaliseTaskPrefix(String raw) throws RelizaException {
		String p = null == raw ? "" : raw.strip().toUpperCase(Locale.ROOT);
		if (!TASK_PREFIX.matcher(p).matches()) {
			throw new RelizaException("taskPrefix '" + raw + "' must be 2 to 8 letters and digits, e.g. RD");
		}
		return p;
	}

	/**
	 * The prefix a board name suggests (D8): the initials of its words ({@code ReARM Dogfood} is
	 * {@code RD}), or the first two letters of a single word; letters and digits only, at most 8.
	 */
	static String derivedTaskPrefix(String name) {
		List<String> words = java.util.Arrays.stream((null == name ? "" : name).split("[^A-Za-z0-9]+"))
				.filter(w -> !w.isEmpty()).toList();
		String base;
		if (words.size() >= 2) {
			StringBuilder sb = new StringBuilder();
			words.forEach(w -> sb.append(w.charAt(0)));
			base = sb.toString();
		} else {
			base = words.isEmpty() ? "" : words.get(0).substring(0, Math.min(2, words.get(0).length()));
		}
		base = base.toUpperCase(Locale.ROOT);
		if (base.length() > 8) base = base.substring(0, 8);
		while (base.length() < 2) base += "B";
		return base;
	}

	/**
	 * Give the board a task-key prefix and claim it (D8-D10): the one wanted, else the derived one,
	 * with a digit appended until the registry has it free ({@code RD2}, {@code RD3}). A prefix the
	 * board already holds is a no-op claim; a change appends to the board's history and leaves every
	 * existing key alone. Mutates {@code bd} for the caller to save.
	 */
	void applyTaskPrefix(AgentBoardData bd, String wanted, WhoUpdated wu) throws RelizaException {
		lockTaskPrefixes(bd.getOrg());
		Map<String, OrganizationData.TaskPrefixClaim> registry = taskPrefixRegistry(bd.getOrg());
		String prefix;
		if (null != wanted) {
			prefix = wanted;
			refuseClaimedElsewhere(registry, prefix, bd.getUuid());
		} else {
			String base = derivedTaskPrefix(bd.getName());
			prefix = base;
			for (int n = 2; isClaimedElsewhere(registry, prefix, bd.getUuid()); n++) {
				String suffix = String.valueOf(n);
				prefix = base.substring(0, Math.min(base.length(), 8 - suffix.length())) + suffix;
			}
		}
		if (!registry.containsKey(prefix)) {
			organizationService.recordTaskPrefixClaim(bd.getOrg(), prefix, bd.getUuid(), wu);
		}
		if (!prefix.equals(bd.getTaskPrefix())) {
			bd.setTaskPrefix(prefix);
			if (null == bd.getTaskPrefixHistory()) bd.setTaskPrefixHistory(new ArrayList<>());
			if (!bd.getTaskPrefixHistory().contains(prefix)) bd.getTaskPrefixHistory().add(prefix);
		}
	}

	/**
	 * The board read under its row lock, fresh: for a caller that writes the board and must see
	 * every earlier write. Board before task, as always.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData lockBoard(UUID boardUuid) throws RelizaException {
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		entityManager.refresh(b);
		return AgentBoardData.dataFromRecord(b);
	}

	/**
	 * The board's next task number, drawn in place (task RD3-2): one field written, no revision. The
	 * caller holds the board lock ({@link #lockBoard}).
	 */
	public int nextTaskNumber(UUID boardUuid) throws RelizaException {
		Integer n = repository.nextTaskNumber(boardUuid);
		if (null == n) throw new RelizaException("Board not found: " + boardUuid);
		return n;
	}

	/** Set a board's task-key prefix from the form: the one given, or null to derive anew. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData setTaskPrefix(UUID boardUuid, String taskPrefix, WhoUpdated wu) throws RelizaException {
		String wanted = null == taskPrefix ? null : normaliseTaskPrefix(taskPrefix);
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		entityManager.refresh(b);
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		applyTaskPrefix(bd, wanted, wu);
		return saveData(bd, wu);
	}

	/** Which board of the organization holds a prefix, if any. */
	/**
	 * The board of the organization whose document component map holds this component (board-documents.md
	 * §5, task 36d0549e): the board a BOARD_DOCUMENT component belongs to, empty for one nobody owns.
	 */
	public Optional<AgentBoardData> boardOfDocumentComponent(UUID orgUuid, UUID component) {
		if (null == orgUuid || null == component) return Optional.empty();
		return listByOrg(orgUuid).stream()
				.filter(bd -> null != bd.getDocumentComponents() && bd.getDocumentComponents().containsValue(component))
				.findFirst();
	}

	public Optional<UUID> boardOfTaskPrefix(UUID orgUuid, String prefix) {
		OrganizationData.TaskPrefixClaim claim = taskPrefixRegistry(orgUuid).get(prefix);
		return Optional.ofNullable(null == claim ? null : claim.board());
	}

	/**
	 * Null when the prefix is free for the board or already its own; else why not, as the caller may
	 * be told: a board it does not see is not named ({@link TaskPrefixTaken#forCaller}).
	 */
	String taskPrefixProblem(UUID orgUuid, String prefix, UUID board, BoardAccess access) throws RelizaException {
		try {
			refuseClaimedElsewhere(taskPrefixRegistry(orgUuid), normaliseTaskPrefix(prefix), board);
			return null;
		} catch (TaskPrefixTaken taken) {
			return taken.forCaller(access).getMessage();
		} catch (RelizaException e) {
			return e.getMessage();
		}
	}

	/**
	 * A task-key prefix another board claims (D8-D10). Its message names the board and the claim's
	 * date, which only a caller who sees that board may read: {@link #forCaller} gives anyone else
	 * a refusal that says the prefix is taken -- unavoidable, prefixes are unique in the
	 * organization -- and nothing about whose it is (review S-1 on rearm-saas#765). The claim
	 * names one board whether the prefix is that board's current one or one it held before, so a
	 * retired prefix is judged by the same board; a claim whose board no longer resolves is seen
	 * by nobody.
	 */
	public static final class TaskPrefixTaken extends RelizaException {
		private static final long serialVersionUID = 1L;
		private final String prefix;
		private final UUID holder;

		TaskPrefixTaken(String message, String prefix, UUID holder) {
			super(message);
			this.prefix = prefix;
			this.holder = holder;
		}

		/** This refusal for a caller who sees the board holding the prefix; for anyone else, one that does not name it. */
		public RelizaException forCaller(BoardAccess access) throws RelizaException {
			if (null != access && null != holder && access.sees(holder)) return this;
			return new RelizaException("task prefix " + prefix
					+ " is not available: a task-key prefix is never reused in an organization");
		}
	}

	private Map<String, OrganizationData.TaskPrefixClaim> taskPrefixRegistry(UUID orgUuid) {
		return getOrganizationService.getOrganizationData(orgUuid)
				.map(OrganizationData::getAgentTaskPrefixes)
				.map(m -> (Map<String, OrganizationData.TaskPrefixClaim>) m)
				.orElse(Map.of());
	}

	private static boolean isClaimedElsewhere(Map<String, OrganizationData.TaskPrefixClaim> registry, String prefix,
			UUID board) {
		OrganizationData.TaskPrefixClaim claim = registry.get(prefix);
		return null != claim && !claim.board().equals(board);
	}

	private void refuseClaimedElsewhere(Map<String, OrganizationData.TaskPrefixClaim> registry, String prefix,
			UUID board) throws RelizaException {
		if (!isClaimedElsewhere(registry, prefix, board)) return;
		OrganizationData.TaskPrefixClaim claim = registry.get(prefix);
		String holder = repository.findById(claim.board()).map(AgentBoardData::dataFromRecord)
				.map(AgentBoardData::getName).orElse(String.valueOf(claim.board()));
		throw new TaskPrefixTaken(prefix + " is used by board " + holder + " since "
				+ (null == claim.claimedAt() ? "earlier" : claim.claimedAt().toLocalDate())
				+ "; a task-key prefix is never reused in an organization", prefix, claim.board());
	}

	/**
	 * The org's registry lock, held to the end of the transaction: claims are read-check-write, and
	 * two boards claiming one prefix at once must not both see it free.
	 */
	private void lockTaskPrefixes(UUID orgUuid) {
		entityManager.createNativeQuery("SELECT CAST(pg_advisory_xact_lock(?1, hashtext(?2)) AS text)")
				.setParameter(1, io.reliza.common.AdvisoryLockKey.AGENT_TASK_PREFIXES.getQueryVal())
				.setParameter(2, orgUuid.toString())
				.getSingleResult();
	}

	/**
	 * The board's node must be an active component of the board's own org. Deliberately not
	 * restricted to PRODUCT: a board may build a leaf component, and in the top-down flow the
	 * node is often a shell created alongside the board, before anything hangs off it.
	 */
	private void validateTarget(UUID target, UUID orgUuid) throws RelizaException {
		ComponentData cd = getComponentService.getComponentData(target)
				.orElseThrow(() -> new RelizaException("Target component not found: " + target));
		if (!orgUuid.equals(cd.getOrg())) throw new RelizaException("Target component belongs to another organization");
		if (StatusEnum.ARCHIVED == cd.getStatus()) throw new RelizaException("Target component is archived");
	}

	/**
	 * Set where this board's documents are written, and optionally the per-type path layout.
	 *
	 * <p>Takes a URI and stores the REPOSITORY ROW it resolves to. Resolving once, here, is what
	 * lets every later comparison be a uuid: publish, the sign-off lock scan and the source code
	 * entry all work from the row rather than re-deciding whether two spellings mean one
	 * repository.
	 *
	 * <p>The repository must correspond to one of the board's sources. That is not bookkeeping:
	 * the coordinator's rogue-activity duty watches the sources, so this is what puts document
	 * writes under the same watch. Sources are tracker references and may be written as
	 * {@code github:owner/repo}, so the check compares canonical forms -- the only place in the
	 * system that still needs to, because a source is not a repository row.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData setDocumentsConfig(UUID boardUuid, String documentsRepoUri,
			Map<RearmSpecificationType, String> documentPaths, WhoUpdated wu) throws RelizaException {
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		if (null != documentsRepoUri) {
			bd.setDocumentsRepo(documentsRepositoryFor(bd, documentsRepoUri, wu));
		}
		if (null != documentPaths) bd.setDocumentPaths(AgentBoardData.normaliseDocumentPaths(new LinkedHashMap<>(documentPaths)));
		return saveData(bd, wu);
	}

	/** The form's documents prefix alone: {@link #setDocumentsBlock} with only {@code prefix} sent. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData setDocumentsPrefix(UUID boardUuid, String prefix, WhoUpdated wu) throws RelizaException {
		Map<String, Object> block = new LinkedHashMap<>();
		block.put("prefix", prefix);
		return setDocumentsBlock(boardUuid, block, wu);
	}

	/**
	 * The form's documents block, with a board file's presence rules (D15): the block sent as null
	 * restores every default; within it, a member sent as null restores that one and a member left
	 * out stays. The caller only calls this when the form sent the block at all.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData setDocumentsBlock(UUID boardUuid, Map<String, Object> block, WhoUpdated wu)
			throws RelizaException {
		boolean whole = null == block;
		boolean hasPrefix = whole || block.containsKey("prefix");
		boolean hasShared = whole || block.containsKey("shared");
		boolean hasRoot = whole || block.containsKey("root");
		String prefix = whole ? null : (String) block.get("prefix");
		Boolean shared = whole ? null : (Boolean) block.get("shared");
		String root = whole ? null : (String) block.get("root");
		for (String problem : java.util.Arrays.asList(hasPrefix ? documentsPrefixProblem(prefix) : null,
				hasRoot ? documentsRootProblem(root) : null)) {
			if (null != problem) throw new RelizaException(problem);
		}
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		entityManager.refresh(b);
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		bd.setDocuments(documentsConfig(bd.getDocuments(), hasPrefix, prefix, hasShared, shared, hasRoot, root));
		return saveData(bd, wu);
	}

	/**
	 * Record a board's document component for a specification (board-documents.md D3): the one it
	 * just created, or the one it adopted from before the map existed. First writer wins, so a second
	 * record for the same specification is ignored and its component is returned instead.
	 *
	 * <p>Takes the board row lock, so call it only where the board lock is held or may be taken
	 * first: every publishing path takes the board before the task, and taking it here under a task
	 * lock would invert that order.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public UUID recordDocumentComponent(UUID boardUuid, RearmSpecificationType spec, UUID component,
			WhoUpdated wu) throws RelizaException {
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		entityManager.refresh(b);
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		if (null == bd.getDocumentComponents()) bd.setDocumentComponents(new LinkedHashMap<>());
		UUID existing = bd.getDocumentComponents().putIfAbsent(spec, component);
		if (null != existing) return existing;
		saveData(bd, wu);
		return component;
	}

	/** Null when a documents prefix is usable: absent, or with a letter or digit left once slugged. */
	static String documentsPrefixProblem(String prefix) {
		if (null == prefix || !AgentBoardData.slug(prefix).isEmpty()) return null;
		return "documents.prefix '" + prefix + "' names nothing: it needs a letter or a digit";
	}

	/**
	 * Null when a documents root is usable (board-documents.md §3.4): absent, empty (the repository's
	 * own root), or a relative path with no {@code ..} segment. A leading slash is stripped, not refused.
	 */
	static String documentsRootProblem(String root) {
		if (null == root) return null;
		for (String segment : root.split("/")) {
			if ("..".equals(segment.strip())) {
				return "documents.root '" + root + "' climbs out of the repository: a root has no '..'";
			}
		}
		return null;
	}

	/**
	 * The stored documents block after a change: each member declared takes its new value (null is
	 * the default), each member left out keeps the old one; nothing set at all is no block.
	 */
	private static AgentBoardData.DocumentsConfig documentsConfig(AgentBoardData.DocumentsConfig old,
			boolean hasPrefix, String prefix, boolean hasShared, Boolean shared, boolean hasRoot, String root) {
		AgentBoardData.DocumentsConfig next = new AgentBoardData.DocumentsConfig(
				hasPrefix ? (null == prefix ? null : prefix.strip()) : null == old ? null : old.prefix(),
				hasShared ? shared : null == old ? null : old.shared(),
				hasRoot ? normaliseDocumentsRoot(root) : null == old ? null : old.root());
		return next.isEmpty() ? null : next;
	}

	/** A root as stored: leading slashes dropped; empty stays empty, which is the repository root. */
	private static String normaliseDocumentsRoot(String root) {
		return null == root ? null : root.strip().replaceAll("^/+", "");
	}

	/**
	 * The repository row a board's documents go to, checked against the board's sources as they
	 * stand on {@code bd}, and provisioned when the organization does not have it yet. Shared by
	 * {@link #setDocumentsConfig} and the declarative apply, which writes sources first.
	 */
	private UUID documentsRepositoryFor(AgentBoardData bd, String documentsRepoUri, WhoUpdated wu)
			throws RelizaException {
		if (StringUtils.isBlank(documentsRepoUri)) {
			throw new RelizaException("documentsRepo cannot be blank; omit it to leave it unset");
		}
		// BOTH sides through the same parser. Canonicalising the left side only was the same
		// bug in a new place: canonicalVcsUri preserves case, while a source renders its
		// project lowercase for the hosts that resolve it that way, so
		// documentsRepo=https://github.com/Acme/Widget was refused on a board sourced as
		// github:acme/widget -- the exact pair D9 exists to make equal.
		String canonical = TrackerSource.parse(documentsRepoUri).canonicalRepositoryUri();
		// A board with no sources is exempt, because the rule's premise is absent. The check
		// exists so the documents repository falls under the coordinator's rogue-activity
		// watch, and that watch IS the source list: on a board whose tasks live only on the
		// board there is nothing to watch and nothing to smuggle past. Applied literally it
		// refused every documents repository on such a board, which left it unable to publish
		// a document at all -- no review items, no questions, no answers, so no loop.
		boolean watched = null != bd.getSources() && !bd.getSources().isEmpty();
		boolean known = !watched || bd.getSources().stream()
				.anyMatch(src -> canonical.equals(repositoryUriOfSource(src)));
		if (!known) {
			throw new RelizaException("documentsRepo must correspond to one of the board's "
					+ "sources, so the coordinator's rogue-activity watch covers it. Add "
					+ documentsRepoUri + " to sources first.");
		}
		return vcsRepositoryService.provisionVcsRepository(bd.getOrg(), documentsRepoUri, VcsType.GIT, wu);
	}

	/**
	 * Parse every incoming source and store the parser's rendering.
	 *
	 * <p>Refuses what does not parse, so a board cannot be wired to a source no reader can make
	 * sense of. Rendering rather than storing as written is what makes two spellings of one
	 * repository one source: `https://github.com/Acme/Widget` and `github:acme/widget` both store
	 * as the latter.
	 */
	private List<String> renderSources(List<String> sources) throws RelizaException {
		List<String> out = new LinkedList<>();
		for (String raw : sources) {
			out.add(TrackerSource.parse(raw).canonical());
		}
		return out;
	}

	/**
	 * The comparable repository form of a wired source, or null when it names no repository -- a
	 * project tracker (Jira, Trello) contributes nothing to repository scope, and a source that
	 * cannot be parsed contributes nothing either rather than blocking the board.
	 */
	private String repositoryUriOfSource(String rawSource) {
		try {
			return TrackerSource.parse(rawSource).canonicalRepositoryUri();
		} catch (RelizaException e) {
			log.error("Board source '{}' is unreadable; it names no repository until fixed", rawSource, e);
			return null;
		}
	}

	/**
	 * The board's mechanics settings: budgets and the stops.
	 *
	 * <p>Partial like {@link #updateBoard}: null leaves a setting alone, which is what lets an
	 * operator raise a budget without restating the cycle cap. Separate from updateBoard because
	 * these are the knobs that decide when work stops, and a caller changing one of them is doing
	 * something different from renaming a board.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData updateSettings(UUID boardUuid, Long budgetMicros, Integer softAlertPercent,
			Integer cycleCap, Integer noProgressRepeatsToStop, Integer blockingPriority,
			Integer completionPriority, WhoUpdated wu) throws RelizaException {
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		if (null != budgetMicros) bd.setBudgetMicros(budgetMicros);
		if (null != softAlertPercent) bd.setSoftAlertPercent(softAlertPercent);
		if (null != cycleCap) bd.setCycleCap(cycleCap);
		if (null != noProgressRepeatsToStop) bd.setNoProgressRepeatsToStop(noProgressRepeatsToStop);
		if (null != blockingPriority) bd.setBlockingPriority(blockingPriority);
		if (null != completionPriority) bd.setCompletionPriority(completionPriority);
		return saveData(bd, wu);
	}

	/**
	 * What the coordinator seat covers itself, validated: the tracker verbs are refused, since the
	 * coordinator always has them and listing them would make the declaration mean nothing.
	 */
	public static List<AgentCapability> coordinatorCapabilities(List<AgentCapability> caps) throws RelizaException {
		if (null == caps) return new ArrayList<>();
		for (AgentCapability c : caps) {
			if (AgentCapability.TRACKER_READ == c || AgentCapability.TRACKER_WRITE == c) {
				throw new RelizaException("coordinatorCapabilities cannot list " + c
						+ ": the coordinator always has the tracker verbs");
			}
		}
		return new ArrayList<>(new java.util.LinkedHashSet<>(caps));
	}

	/** Set what the coordinator covers; an empty list clears it. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData setCoordinatorCapabilities(UUID boardUuid, List<AgentCapability> caps, WhoUpdated wu)
			throws RelizaException {
		List<AgentCapability> valid = coordinatorCapabilities(caps);
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		bd.setCoordinatorCapabilities(valid);
		return saveData(bd, wu);
	}

	static final String DEFAULT_LEVEL_RANGE = "defaultWorkLevel is 0 to " + AgentTaskService.MAX_LEVEL;

	/** A board's default task level as given, or refused outside 0..9 (RD2-1); null passes. */
	static Integer checkedDefaultWorkLevel(Integer level) throws RelizaException {
		if (null != level && (level < 0 || level > AgentTaskService.MAX_LEVEL)) throw new RelizaException(DEFAULT_LEVEL_RANGE);
		return level;
	}

	/**
	 * Why a board's default task level does not fit its ladder (task RD3-6), or null: with a ladder the default
	 * is one of its rungs; without one, or with no default, anything fits, as the default is then ignored.
	 */
	static String defaultOffLadder(Integer defaultWorkLevel, AgentBoardData.Ladder ladder) {
		if (null == defaultWorkLevel || null == ladder || ladder.isEmpty() || defaultWorkLevel < ladder.levels().size()) return null;
		return "defaultWorkLevel " + defaultWorkLevel + " is not on the board's ladder: " + AgentTaskService.ladderText(ladder);
	}

	/**
	 * The board form's default task level and ladder checked together, before its save writes anything
	 * (task RD3-6): the default the board will have against the ladder it will have, each the form's when it
	 * sends one and the board's otherwise. Checked once, up front, so a save that grows the ladder and raises
	 * the default -- or lowers both -- is not refused by whichever of its writes lands first. {@code bd} is
	 * null for a board the form is creating.
	 */
	public void checkDefaultOnLadderInput(AgentBoardData bd, Map<String, Object> settings, boolean defaultGiven,
			Integer defaultWorkLevel) throws RelizaException {
		if (defaultGiven) checkedDefaultWorkLevel(defaultWorkLevel);
		AgentBoardData.Ladder ladder = null == bd ? null : bd.getLadder();
		if (null != settings && settings.containsKey("ladder")) {
			ladder = normalisedLadder(readSpec(settings, BoardSettingsSpecDto.class).getLadder());
		}
		String off = defaultOffLadder(defaultGiven ? defaultWorkLevel : (null == bd ? null : bd.getDefaultWorkLevel()), ladder);
		if (null != off) throw new RelizaException(off);
	}

	/**
	 * Set or clear a board's default task level (RD2-1): null clears it, so unset tasks have no
	 * level. Every unset task reads the new default at once; nothing is written onto the tasks.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData setDefaultWorkLevel(UUID boardUuid, Integer level, WhoUpdated wu) throws RelizaException {
		checkedDefaultWorkLevel(level);
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		entityManager.refresh(b);
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		bd.setDefaultWorkLevel(level);
		return saveData(bd, wu);
	}

	/** Partial update: only supplied fields change. Lock and seat have their own paths. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData updateBoard(UUID boardUuid, String description, List<String> sources,
			String coordinatorPrompt, Integer perAgentWipLimit,
			PriorityType priorityType, BoardStatus status, UUID target, Integer defaultWorkLevel,
			WhoUpdated wu) throws RelizaException {
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		if (target != null && !target.equals(bd.getTarget())) {
			validateTarget(target, bd.getOrg());
			// The new target must be a member of every perspective the board is in (rule 2).
			List<UUID> ps = null == bd.getPerspectives() ? List.of() : bd.getPerspectives();
			List<String> problems = boardPerspectiveService.problems(bd.getOrg(), target, ps, ps, p -> true);
			if (!problems.isEmpty()) throw new RelizaException(String.join("; ", problems));
			bd.setTarget(target);
		}
		if (defaultWorkLevel != null) bd.setDefaultWorkLevel(checkedDefaultWorkLevel(defaultWorkLevel));
		if (description != null) bd.setDescription(description);
		if (sources != null) bd.setSources(renderSources(sources));
		if (coordinatorPrompt != null) bd.setCoordinatorPrompt(coordinatorPrompt);
		if (perAgentWipLimit != null && perAgentWipLimit > 0) bd.setPerAgentWipLimit(perAgentWipLimit);
		if (priorityType != null) bd.setPriorityType(priorityType);
		if (status != null) bd.setStatus(status);
		return saveData(bd, wu);
	}

	public Optional<AgentBoardData> getBoardData(UUID uuid) {
		if (uuid == null) return Optional.empty();
		return repository.findById(uuid).map(AgentBoardData::dataFromRecord);
	}

	public List<AgentBoardData> listByOrg(UUID orgUuid) {
		if (orgUuid == null) return List.of();
		return repository.findByOrg(orgUuid.toString()).stream()
				.map(AgentBoardData::dataFromRecord)
				.collect(Collectors.toList());
	}

	// ---------- Pausing ----------

	/**
	 * Pause or resume the board. A pause stops NEW assignments only. Rules:
	 * a COORDINATOR-level actor can pause/resume only at COORDINATOR level
	 * and cannot touch an OPERATOR pause; an OPERATOR-level actor can
	 * pause/resume either.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData setPause(UUID boardUuid, BoardPauseLevel actorLevel, boolean pause,
			String reason, AgentActor actor, WhoUpdated wu) throws RelizaException {
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		BoardPauseLevel current = bd.getPause() != null ? bd.getPause().level() : BoardPauseLevel.NONE;
		if (actorLevel == BoardPauseLevel.COORDINATOR && current == BoardPauseLevel.OPERATOR) {
			throw new RelizaException("Board " + boardUuid
					+ " carries an OPERATOR pause; the coordinator cannot " + (pause ? "override" : "lift") + " it");
		}
		ZonedDateTime now = ZonedDateTime.now();
		if (pause) {
			bd.setPause(new BoardPause(actorLevel, reason, actor, now));
			String message = "[" + actorLevel + "] " + (reason != null ? reason : "no reason given");
			addEvent(bd, new AgentBoardData.BoardEvent(AgentBoardData.BoardEventKind.PAUSED, message, actor, now));
			agentBoardNotifier.boardEvent(bd, AgentBoardData.BoardEventKind.PAUSED, message);
		} else {
			bd.setPause(null);
			addEvent(bd, new AgentBoardData.BoardEvent(AgentBoardData.BoardEventKind.RESUMED,
					reason, actor, now));
		}
		return saveData(bd, wu);
	}

	/**
	 * Record that the soft-alert line has been reported, so it is reported once.
	 *
	 * <p>Under the board lock like every board write, and called after the task transaction it
	 * came from has committed.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData markSoftAlertPosted(UUID boardUuid, WhoUpdated wu) throws RelizaException {
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		if (bd.isSoftAlertPosted()) return bd;
		bd.setSoftAlertPosted(true);
		return saveData(bd, wu);
	}

	/**
	 * Coordinator-posted board notice (ALERT / INFO).
	 *
	 * <p>The one method here that does not roll back on a refusal. It refuses only before it
	 * writes, and the task service posts notices best-effort from inside its own transactions,
	 * catching a failure and carrying on: marking the shared transaction rollback-only here would
	 * turn a lost notice into a failed hold lift or human-gate sign-off at commit.
	 */
	@Transactional(noRollbackFor = RelizaException.class)
	public AgentBoardData postEvent(UUID boardUuid, AgentBoardData.BoardEventKind kind,
			String message, AgentActor actor, WhoUpdated wu) throws RelizaException {
		return postEvent(boardUuid, kind, message, actor, true, wu);
	}

	/**
	 * @param notify false for an ALERT a person is already notified of another way (task RD2-15): the
	 *        feed keeps it, and no second notification is written.
	 */
	public AgentBoardData postEvent(UUID boardUuid, AgentBoardData.BoardEventKind kind,
			String message, AgentActor actor, boolean notify, WhoUpdated wu) throws RelizaException {
		if (kind != AgentBoardData.BoardEventKind.ALERT && kind != AgentBoardData.BoardEventKind.INFO) {
			throw new RelizaException("Only ALERT and INFO events can be posted directly");
		}
		if (StringUtils.isBlank(message)) throw new RelizaException("Event requires a message");
		// The event is a log row, not a change of the board (task RD3-2): no board lock, no board save,
		// so no revision and no audit copy of the row. The board is read for its org and the notice.
		AgentBoardData bd = getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		addEvent(bd, new AgentBoardData.BoardEvent(kind, message, actor, ZonedDateTime.now()));
		// An ALERT is what a person is meant to see; it goes out as a notification once this commits,
		// unless another notification already tells them (the human gate's needs-a-person).
		if (notify) agentBoardNotifier.boardEvent(bd, kind, message);
		return bd;
	}

	// ---------- The event log (task 1c5442d2) ----------

	/** How many of its newest events a board read carries (AgentBoard.events): the dashboard's feed. */
	public static final int RECENT_EVENTS = 50;

	/** A board's newest {@link #RECENT_EVENTS} events from the log, oldest first, as the feed shows them. */
	public List<AgentBoardData.BoardEvent> recentEvents(UUID boardUuid) {
		if (null == boardUuid) return List.of();
		return recentEvents(List.of(boardUuid)).getOrDefault(boardUuid, List.of());
	}

	/**
	 * The newest {@link #RECENT_EVENTS} events of each board, oldest first, in one read: the boards list
	 * asks for every board's feed at once (task RD3-2, design risk 2). A board with none maps to an
	 * empty list.
	 */
	public Map<UUID, List<AgentBoardData.BoardEvent>> recentEvents(Collection<UUID> boardUuids) {
		Map<UUID, List<AgentBoardData.BoardEvent>> out = new LinkedHashMap<>();
		if (null == boardUuids || boardUuids.isEmpty()) return out;
		for (UUID b : boardUuids) out.put(b, new ArrayList<>());
		UUID[] boards = boardUuids.toArray(new UUID[0]);
		jdbcTemplate.query("SELECT board, kind, message, actor::text AS actor, event_at FROM ("
				+ " SELECT board, seq, kind, message, actor, event_at,"
				+ " row_number() OVER (PARTITION BY board ORDER BY seq DESC) AS n"
				+ " FROM rearm.agent_board_events WHERE board = ANY(?)) recent"
				+ " WHERE n <= ? ORDER BY board, seq",
				rs -> {
					out.computeIfAbsent(rs.getObject("board", UUID.class), k -> new ArrayList<>()).add(
							new AgentBoardData.BoardEvent(AgentBoardData.BoardEventKind.valueOf(rs.getString("kind")),
									rs.getString("message"), actorOf(rs.getString("actor")),
									rs.getTimestamp("event_at").toInstant().atZone(ZoneOffset.UTC)));
				},
				boards, RECENT_EVENTS);
		return out;
	}

	/** Default and ceiling of a page of the event log. */
	public static final int EVENTS_PAGE_DEFAULT = 200;
	public static final int EVENTS_PAGE_MAX = 1000;

	/** An event as the log holds it: the board's event with its cursor. */
	public record LoggedEvent(Long seq, UUID uuid, AgentBoardData.BoardEventKind kind, String message,
			AgentActor actor, ZonedDateTime eventAt) {}

	/**
	 * A page of the log, oldest first. {@code nextAfter} is the cursor to read on from: the last
	 * seq returned, or the one asked after when the page is empty, so a follower never loses its
	 * place.
	 *
	 * @param truncatedBefore where the board's retention window starts, now minus its
	 *                        eventRetentionDays; null when the board keeps everything (task 04dedcc5)
	 * @param gap             whether events the caller's after or since asked for were deleted by
	 *                        the retention sweep: the page starts at the oldest event still kept
	 */
	public record EventPage(List<LoggedEvent> events, Long nextAfter, boolean hasMore,
			ZonedDateTime truncatedBefore, boolean gap) {}

	/**
	 * Every event a board posts, into the log and nowhere else (task RD3-2): the row no longer
	 * carries a window of them. In the caller's transaction, so a lock or an apply that rolls back
	 * takes its event with it.
	 */
	private void addEvent(AgentBoardData bd, AgentBoardData.BoardEvent ev) {
		String actor;
		try {
			actor = null == ev.actor() ? null : Utils.OM.writeValueAsString(ev.actor());
		} catch (Exception e) {
			log.error("Could not serialise the actor of an event on board {}", bd.getUuid(), e);
			actor = null;
		}
		jdbcTemplate.update("INSERT INTO rearm.agent_board_events (uuid, org, board, kind, message, actor, event_at)"
				+ " VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)",
				UUID.randomUUID(), bd.getOrg(), bd.getUuid(), ev.kind().name(), null == ev.message() ? "" : ev.message(),
				actor, Timestamp.from(ev.eventAt().toInstant()));
	}

	/**
	 * The board's events since a point, oldest first (task 1c5442d2): after a seq (exclusive), from
	 * an instant (inclusive), or both. A follower keeps {@link EventPage#nextAfter}.
	 *
	 * @param limit how many; {@link #EVENTS_PAGE_DEFAULT} when null, at most {@link #EVENTS_PAGE_MAX}
	 */
	public EventPage eventsOf(UUID boardUuid, Long after, ZonedDateTime since, Integer limit) throws RelizaException {
		int n = null == limit ? EVENTS_PAGE_DEFAULT : limit;
		if (n < 1) throw new RelizaException("limit must be at least 1");
		n = Math.min(n, EVENTS_PAGE_MAX);
		AgentBoardData bd = getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		int days = bd.getEffectiveEventRetentionDays();
		ZonedDateTime truncatedBefore = days > 0 ? ZonedDateTime.now(ZoneOffset.UTC).minusDays(days) : null;
		boolean gap = lostTo(bd.getEventLogPruned(), after, since);
		List<LoggedEvent> rows = jdbcTemplate.query("SELECT seq, uuid, kind, message, actor::text AS actor, event_at"
				+ " FROM rearm.agent_board_events WHERE board = ? AND seq > ? AND event_at >= ?"
				+ " ORDER BY seq LIMIT ?",
				(rs, i) -> new LoggedEvent(rs.getLong("seq"), rs.getObject("uuid", UUID.class),
						AgentBoardData.BoardEventKind.valueOf(rs.getString("kind")), rs.getString("message"),
						actorOf(rs.getString("actor")),
						rs.getTimestamp("event_at").toInstant().atZone(ZoneOffset.UTC)),
				boardUuid, null == after ? 0L : after,
				Timestamp.from(null == since ? Instant.EPOCH : since.toInstant()), n + 1);
		boolean more = rows.size() > n;
		List<LoggedEvent> page = more ? rows.subList(0, n) : rows;
		Long next = page.isEmpty() ? after : page.get(page.size() - 1).seq();
		return new EventPage(List.copyOf(page), next, more, truncatedBefore, gap);
	}

	/**
	 * Whether the sweep deleted an event a reader asked for: one after its cursor and from its
	 * instant. Only a reader that gave either can have lost something; one reading from the start
	 * reads what is kept. The seq is shared by every board's log, so a cursor below the board's
	 * oldest row says nothing on its own; the sweep's own record of what it deleted does.
	 */
	static boolean lostTo(AgentBoardData.EventLogPruned pruned, Long after, ZonedDateTime since) {
		if (null == pruned || null == pruned.throughSeq() || (null == after && null == since)) return false;
		boolean afterLost = null == after || after < pruned.throughSeq();
		boolean sinceLost = null == since || null == pruned.throughAt() || !since.isAfter(pruned.throughAt());
		return afterLost && sinceLost;
	}

	/** One board's part of the retention sweep, a seam the sweep's test replaces. */
	@FunctionalInterface
	interface BoardEventPrune {
		int prune(UUID board, ZonedDateTime now) throws Exception;
	}

	/**
	 * The daily retention sweep (task 04dedcc5): each board's events older than its
	 * eventRetentionDays are deleted, each board in its own transaction. A board that fails is
	 * logged and the sweep goes on. The row's newest-50 list is never touched, and nothing is
	 * posted: an event about the sweep would itself be swept.
	 *
	 * @return how many events were deleted
	 */
	public int sweepEventLog(ZonedDateTime now) {
		List<UUID> boards = jdbcTemplate.queryForList("SELECT uuid FROM rearm.agent_boards ORDER BY created_date", UUID.class);
		return sweepEventLog(boards, now, self::sweepEventLogOf);
	}

	int sweepEventLog(List<UUID> boards, ZonedDateTime now, BoardEventPrune prune) {
		int total = 0;
		for (UUID board : boards) {
			try {
				int n = prune.prune(board, now);
				if (n > 0) log.debug("event retention: deleted {} event(s) from board {}", n, board);
				total += n;
			} catch (Exception e) {
				log.error("Event retention sweep failed for board {}", board, e);
			}
		}
		log.info("event retention: deleted {} event(s) across {} board(s)", total, boards.size());
		return total;
	}

	/**
	 * Delete one board's events older than its window, and record the newest one deleted on the
	 * board so a reader whose cursor falls below it is told. The record is written with jsonb_set
	 * under the board's lock rather than through a board save, so the sweep adds no revision.
	 *
	 * @return how many events were deleted; 0 for a board that keeps everything
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public int sweepEventLogOf(UUID boardUuid, ZonedDateTime now) throws RelizaException {
		Optional<AgentBoard> b = repository.findByIdWriteLocked(boardUuid);
		if (b.isEmpty()) return 0;
		AgentBoardData bd = AgentBoardData.dataFromRecord(b.get());
		int days = bd.getEffectiveEventRetentionDays();
		if (days <= 0) return 0;
		Timestamp cutoff = Timestamp.from(now.minusDays(days).toInstant());
		Map<String, Object> deleted = jdbcTemplate.queryForMap("WITH d AS (DELETE FROM rearm.agent_board_events"
				+ " WHERE board = ? AND event_at < ? RETURNING seq, event_at)"
				+ " SELECT count(*) AS n, max(seq) AS seq, max(event_at) AS at FROM d", boardUuid, cutoff);
		int n = ((Number) deleted.get("n")).intValue();
		if (n == 0) return 0;
		AgentBoardData.EventLogPruned before = bd.getEventLogPruned();
		long seq = ((Number) deleted.get("seq")).longValue();
		ZonedDateTime at = ((Timestamp) deleted.get("at")).toInstant().atZone(ZoneOffset.UTC);
		if (null != before && null != before.throughSeq() && before.throughSeq() > seq) seq = before.throughSeq();
		if (null != before && null != before.throughAt() && before.throughAt().isAfter(at)) at = before.throughAt();
		String pruned = Utils.OM.writeValueAsString(new AgentBoardData.EventLogPruned(seq, at));
		jdbcTemplate.update("UPDATE rearm.agent_boards SET record_data = jsonb_set(record_data, '{eventLogPruned}',"
				+ " CAST(? AS jsonb)) WHERE uuid = ?", pruned, boardUuid);
		return n;
	}

	private AgentActor actorOf(String json) {
		if (null == json) return null;
		try {
			return Utils.OM.readValue(json, AgentActor.class);
		} catch (Exception e) {
			log.error("Could not read an event actor from the log: {}", json, e);
			return null;
		}
	}

	// ---------- Coordinator seat (singleton) ----------

	/**
	 * Claim the coordinator seat for a session. Singleton per board:
	 * fails while another OPEN session holds it. The seat is held
	 * until the session closes; {@link #releaseSeatsForSession} runs
	 * on every session-close path.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData claimCoordinatorSeat(UUID boardUuid, UUID sessionUuid, UUID agentUuid,
			WhoUpdated wu) throws RelizaException {
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		CoordinatorSeat seat = bd.getCoordinatorSeat();
		if (seat != null && !sessionUuid.equals(seat.session())) {
			throw new RelizaException("Board " + boardUuid + " coordinator seat is held by session "
					+ seat.session() + " (operator may force-close a dead session to free it)");
		}
		bd.setCoordinatorSeat(new CoordinatorSeat(sessionUuid, agentUuid, ZonedDateTime.now()));
		return saveData(bd, wu);
	}

	public boolean isSeatHolder(AgentBoardData bd, UUID sessionUuid) {
		return bd.getCoordinatorSeat() != null && sessionUuid != null
				&& sessionUuid.equals(bd.getCoordinatorSeat().session());
	}

	/** True when the session holds the coordinator seat on ANY board (such a session takes no task assignments). */
	public boolean holdsAnySeat(UUID sessionUuid) {
		if (sessionUuid == null) return false;
		return !repository.findBySeatSession(sessionUuid.toString()).isEmpty();
	}

	/** The idle warning (task 6e7fe6fe) on every board whose coordinator seat the session holds. */
	public void idleWarningOnSeatBoards(UUID sessionUuid, String message, WhoUpdated wu) {
		if (sessionUuid == null) return;
		for (AgentBoard b : repository.findBySeatSession(sessionUuid.toString())) {
			try {
				postEvent(b.getUuid(), AgentBoardData.BoardEventKind.ALERT, message + " (it holds this board's coordinator seat)",
						AgentActor.system("idle-sweep"), wu);
			} catch (Exception e) {
				log.error("Failed to post the idle warning on board {} for session {}", b.getUuid(), sessionUuid, e);
			}
		}
	}

	/**
	 * Release coordinator seats held by a closing session, with an ALERT saying why (task 6e7fe6fe):
	 * a seat that frees itself silently leaves the board without a coordinator and nobody told.
	 * Called from every session-close path.
	 */
	public void releaseSeatsForSession(UUID sessionUuid, String reason, WhoUpdated wu) {
		if (sessionUuid == null) return;
		for (AgentBoard b : repository.findBySeatSession(sessionUuid.toString())) {
			try {
				AgentBoardData bd = AgentBoardData.dataFromRecord(b);
				bd.setCoordinatorSeat(null);
				String message = "Coordinator seat released: " + (null == reason ? "session " + sessionUuid + " closed" : reason);
				addEvent(bd, new AgentBoardData.BoardEvent(AgentBoardData.BoardEventKind.ALERT, message,
						AgentActor.ofSession(sessionUuid), ZonedDateTime.now()));
				agentBoardNotifier.boardEvent(bd, AgentBoardData.BoardEventKind.ALERT, message);
				saveData(bd, wu);
			} catch (Exception e) {
				log.error("Failed to release coordinator seat on board {} for session {}",
						b.getUuid(), sessionUuid, e);
			}
		}
	}

	// ---------- Role configs (board-scoped) and org presets ----------

	/**
	 * Partial-update spec for a role config or org preset — null
	 * fields are left untouched. Governance split (v1.1, option 3 +
	 * the human-review pass): {@code prompt}, {@code
	 * requiredCapabilities}, {@code kind}, {@code necessity} and
	 * {@code humanGate} are operator-only; the coordinator seat may
	 * only tune order / wipLimit / distinct-agent / active on
	 * existing roles.
	 */
	public record RoleConfigSpec(String name, String prompt, Integer orderIndex,
			Integer wipLimit, Boolean requireDistinctAgent, Boolean active,
			List<AgentCapability> requiredCapabilities,
			AgentTaskRoleConfigData.RoleKind kind,
			AgentTaskRoleConfigData.RoleNecessity necessity,
			AgentTaskRoleConfigData.HumanGate humanGate,
			List<RequiredInput> requiredInputs,
			List<ProducedOutput> producesOutputs,
			StrengthSpec strength,
			Long hopBudgetMicros,
			Boolean blindReview,
			AgentTaskRoleConfigData.Commissions commissions) {

		/** Pre-commissions constructor (task RD4-12), so existing call sites read unchanged. */
		public RoleConfigSpec(String name, String prompt, Integer orderIndex, Integer wipLimit,
				Boolean requireDistinctAgent, Boolean active, List<AgentCapability> requiredCapabilities,
				AgentTaskRoleConfigData.RoleKind kind, AgentTaskRoleConfigData.RoleNecessity necessity,
				AgentTaskRoleConfigData.HumanGate humanGate, List<RequiredInput> requiredInputs,
				List<ProducedOutput> producesOutputs, StrengthSpec strength, Long hopBudgetMicros, Boolean blindReview) {
			this(name, prompt, orderIndex, wipLimit, requireDistinctAgent, active, requiredCapabilities,
					kind, necessity, humanGate, requiredInputs, producesOutputs, strength, hopBudgetMicros, blindReview, null);
		}

		/** Pre-blind-review constructor, so existing call sites read unchanged. */
		public RoleConfigSpec(String name, String prompt, Integer orderIndex, Integer wipLimit,
				Boolean requireDistinctAgent, Boolean active, List<AgentCapability> requiredCapabilities,
				AgentTaskRoleConfigData.RoleKind kind, AgentTaskRoleConfigData.RoleNecessity necessity,
				AgentTaskRoleConfigData.HumanGate humanGate, List<RequiredInput> requiredInputs,
				List<ProducedOutput> producesOutputs, StrengthSpec strength, Long hopBudgetMicros) {
			this(name, prompt, orderIndex, wipLimit, requireDistinctAgent, active, requiredCapabilities,
					kind, necessity, humanGate, requiredInputs, producesOutputs, strength, hopBudgetMicros, null);
		}

		/** Pre-budget constructor, so existing call sites read unchanged. */
		public RoleConfigSpec(String name, String prompt, Integer orderIndex, Integer wipLimit,
				Boolean requireDistinctAgent, Boolean active, List<AgentCapability> requiredCapabilities,
				AgentTaskRoleConfigData.RoleKind kind, AgentTaskRoleConfigData.RoleNecessity necessity,
				AgentTaskRoleConfigData.HumanGate humanGate, List<RequiredInput> requiredInputs,
				List<ProducedOutput> producesOutputs, StrengthSpec strength) {
			this(name, prompt, orderIndex, wipLimit, requireDistinctAgent, active, requiredCapabilities,
					kind, necessity, humanGate, requiredInputs, producesOutputs, strength, null, null);
		}

		/** Pre-strength constructor, so existing call sites read unchanged. */
		public RoleConfigSpec(String name, String prompt, Integer orderIndex, Integer wipLimit,
				Boolean requireDistinctAgent, Boolean active, List<AgentCapability> requiredCapabilities,
				AgentTaskRoleConfigData.RoleKind kind, AgentTaskRoleConfigData.RoleNecessity necessity,
				AgentTaskRoleConfigData.HumanGate humanGate, List<RequiredInput> requiredInputs,
				List<ProducedOutput> producesOutputs) {
			this(name, prompt, orderIndex, wipLimit, requireDistinctAgent, active, requiredCapabilities,
					kind, necessity, humanGate, requiredInputs, producesOutputs, null);
		}

		public RoleConfigSpec(String name, String prompt, Integer orderIndex, Integer wipLimit,
				Boolean requireDistinctAgent, Boolean active, List<AgentCapability> requiredCapabilities,
				AgentTaskRoleConfigData.RoleKind kind, AgentTaskRoleConfigData.RoleNecessity necessity,
				AgentTaskRoleConfigData.HumanGate humanGate) {
			this(name, prompt, orderIndex, wipLimit, requireDistinctAgent, active,
					requiredCapabilities, kind, necessity, humanGate, null, null);
		}

		/** Pre-outputs constructor, so existing call sites read unchanged. */
		public RoleConfigSpec(String name, String prompt, Integer orderIndex, Integer wipLimit,
				Boolean requireDistinctAgent, Boolean active, List<AgentCapability> requiredCapabilities,
				AgentTaskRoleConfigData.RoleKind kind, AgentTaskRoleConfigData.RoleNecessity necessity,
				AgentTaskRoleConfigData.HumanGate humanGate, List<RequiredInput> requiredInputs) {
			this(name, prompt, orderIndex, wipLimit, requireDistinctAgent, active,
					requiredCapabilities, kind, necessity, humanGate, requiredInputs, null);
		}

		public static RoleConfigSpec ofBasics(String name, String prompt, Integer orderIndex,
				Integer wipLimit, Boolean requireDistinctAgent, Boolean active,
				List<AgentCapability> requiredCapabilities) {
			return new RoleConfigSpec(name, prompt, orderIndex, wipLimit, requireDistinctAgent,
					active, requiredCapabilities, null, null, null, null, null, null, null, null);
		}
	}

	/**
	 * A role's model-strength settings. Each part null means leave it as it is; an empty Optional
	 * clears the value, and an empty list clears the overrides.
	 *
	 * @param requiredStrength the floor a model must meet for this role
	 * @param strengthHeadroom how far above the floor a model may be; zero is an exact match
	 * @param strengthCategory which of a model's per-category strengths this role reads
	 * @param modelStrengths per-model overrides, replacing the list
	 */
	public record StrengthSpec(Optional<Double> requiredStrength, Double strengthHeadroom,
			Optional<ModelOntologyData.RoleCategory> strengthCategory,
			List<AgentTaskRoleConfigData.ModelStrength> modelStrengths) {

		/** Everything a role or preset holds, for copying it onto a board seeded from presets. */
		static StrengthSpec copyOf(AgentTaskRoleConfigData rc) {
			return new StrengthSpec(Optional.ofNullable(rc.getRequiredStrength()), rc.getStrengthHeadroom(),
					Optional.ofNullable(rc.getStrengthCategory()),
					null == rc.getModelStrengths() ? List.of() : rc.getModelStrengths());
		}
	}

	/**
	 * Check a strength spec against the scale and the org's catalogue. Overrides must name models
	 * of this org, once each: an override for another org's model could never apply, and two for
	 * one model would leave which one wins to list order.
	 */
	private void validateStrength(UUID orgUuid, StrengthSpec spec) throws RelizaException {
		if (null == spec) return;
		if (null != spec.requiredStrength() && spec.requiredStrength().isPresent()) {
			StrengthScale.validate("requiredStrength", spec.requiredStrength().get());
		}
		if (null != spec.strengthHeadroom()) StrengthScale.validate("strengthHeadroom", spec.strengthHeadroom());
		if (null == spec.modelStrengths()) return;
		Set<UUID> seen = new java.util.HashSet<>();
		for (AgentTaskRoleConfigData.ModelStrength ms : spec.modelStrengths()) {
			if (null == ms.model() || null == ms.strength()) {
				throw new RelizaException("A model strength override needs both a model and a strength");
			}
			if (!seen.add(ms.model())) {
				throw new RelizaException("Model " + ms.model() + " has more than one strength override");
			}
			StrengthScale.validate("strength override for model " + ms.model(), ms.strength());
			UUID modelOrg = modelOntologyRepository.findById(ms.model())
					.map(m -> ModelOntologyData.dataFromRecord(m).getOrg()).orElse(null);
			if (null == modelOrg || !modelOrg.equals(orgUuid)) {
				throw new RelizaException("Model " + ms.model() + " is not in this organization's catalogue");
			}
		}
	}

	/**
	 * Re-point every role and preset override for a merged-away model to the model it was merged
	 * into. Where a role already overrides the survivor, the survivor's value stands and the
	 * folded one is dropped: the operator set that one against the model that still exists.
	 * Runs inside the merge's transaction.
	 *
	 * @return the number of roles and presets changed
	 */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, rollbackFor = RelizaException.class)
	public int repointModelStrengths(UUID from, UUID into) {
		int changed = 0;
		for (AgentTaskRoleConfig row : roleConfigRepository.findByModelStrengthModel(from.toString())) {
			AgentTaskRoleConfigData rcd = AgentTaskRoleConfigData.dataFromRecord(row);
			boolean survivorOverridden = null != rcd.modelStrengthOverride(into);
			List<AgentTaskRoleConfigData.ModelStrength> next = new java.util.ArrayList<>();
			for (AgentTaskRoleConfigData.ModelStrength ms : rcd.getModelStrengths()) {
				if (!from.equals(ms.model())) {
					next.add(ms);
				} else if (!survivorOverridden) {
					next.add(new AgentTaskRoleConfigData.ModelStrength(into, ms.strength()));
				}
			}
			rcd.setModelStrengths(next);
			saveRoleConfigData(rcd, WhoUpdated.getAutoWhoUpdated());
			changed++;
		}
		return changed;
	}

	/**
	 * Upsert a board role config. Pass {@code allowPromptEdit=false}
	 * for the coordinator-seat path — role creation and every
	 * operator-only field of {@link RoleConfigSpec} are then rejected.
	 */
	public AgentTaskRoleConfigData upsertRoleConfig(AgentBoardData board, RoleConfigSpec spec,
			boolean allowPromptEdit, WhoUpdated wu) throws RelizaException {
		if (spec == null || StringUtils.isBlank(spec.name())) throw new RelizaException("Role config requires a name");
		if ("coordinator".equalsIgnoreCase(spec.name().trim())) {
			throw new RelizaException("The coordinator role is implicit on every board and cannot be configured as a task role");
		}
		Optional<AgentTaskRoleConfig> existing = roleConfigRepository.findByBoardAndName(
				board.getUuid().toString(), spec.name());
		AgentTaskRoleConfigData rcd;
		if (existing.isPresent()) {
			rcd = AgentTaskRoleConfigData.dataFromRecord(existing.get());
			if (spec.prompt() != null && !spec.prompt().equals(rcd.getPrompt()) && !allowPromptEdit) {
				throw new RelizaException("Role prompts are operator-only; the coordinator cannot edit them"
						+ " — escalate the prompt problem instead");
			}
			if (!allowPromptEdit && (spec.requiredCapabilities() != null || spec.kind() != null
					|| spec.necessity() != null || spec.humanGate() != null
					|| spec.requiredInputs() != null || spec.producesOutputs() != null
					|| spec.strength() != null || spec.hopBudgetMicros() != null || spec.blindReview() != null
					|| spec.commissions() != null)) {
				// Produced outputs belong here with the rest: they are the board's routing graph,
				// and a coordinator that could edit them could decide who answers its own
				// questions. Strength is the org's judgment of its models, not the coordinator's.
				throw new RelizaException("Capabilities, role kind, necessity, human gate, required"
						+ " inputs, produced outputs, model strength, blind review, commissions and the hop allowance"
						+ " are operator-only");
			}
		} else {
			if (!allowPromptEdit) {
				throw new RelizaException("Role creation is operator-only; the coordinator cannot create roles");
			}
			rcd = new AgentTaskRoleConfigData();
			rcd.setBoard(board.getUuid());
			rcd.setOrg(board.getOrg());
			rcd.setName(spec.name().trim());
		}
		validateStrength(board.getOrg(), spec.strength());
		List<AgentTaskRoleConfigData> roles = listRoleConfigs(board.getUuid());
		List<String> before = commissionProblems(roles, "board " + board.getName());
		applySpec(rcd, spec);
		refuseNewCommissionProblems(before, roles, rcd, "board " + board.getName());
		return saveRoleConfigData(rcd, wu);
	}

	/**
	 * Refuse a role or preset write that leaves the commissions of its set wrong where they were not (task RD4-12):
	 * a role commissioning one that does not produce the report, or a role dropping the report another commissions.
	 * Problems the set had before stay the operator's to fix and do not block an unrelated edit.
	 */
	private static void refuseNewCommissionProblems(List<String> before, List<AgentTaskRoleConfigData> set,
			AgentTaskRoleConfigData changed, String where) throws RelizaException {
		List<AgentTaskRoleConfigData> after = new ArrayList<>();
		boolean replaced = false;
		for (AgentTaskRoleConfigData rc : set) {
			boolean same = null != changed.getUuid() ? changed.getUuid().equals(rc.getUuid())
					: rc.getName().equalsIgnoreCase(changed.getName());
			if (same) {
				after.add(changed);
				replaced = true;
			} else {
				after.add(rc);
			}
		}
		if (!replaced) after.add(changed);
		List<String> added = new ArrayList<>(commissionProblems(after, where));
		added.removeAll(before);
		if (!added.isEmpty()) throw new RelizaException(String.join("; ", added));
	}

	/**
	 * What is wrong with the commissions of a set of roles, a board's or an organization's presets (task RD4-12):
	 * each role a role commissions, and the review it names, must be in the set, and each commissioned role must
	 * produce BOARD_INVESTIGATION_REPORT at TASK scope. One line per problem, naming the role.
	 */
	static List<String> commissionProblems(java.util.Collection<AgentTaskRoleConfigData> roles, String where) {
		List<String> out = new ArrayList<>();
		Map<String, AgentTaskRoleConfigData> byName = new LinkedHashMap<>();
		for (AgentTaskRoleConfigData rc : roles) {
			if (null != rc.getName()) byName.put(rc.getName().toLowerCase(java.util.Locale.ROOT), rc);
		}
		for (AgentTaskRoleConfigData rc : roles) {
			AgentTaskRoleConfigData.Commissions c = rc.getCommissions();
			if (null == c) continue;
			for (String name : null == c.roles() ? List.<String>of() : c.roles()) {
				AgentTaskRoleConfigData asked = null == name ? null : byName.get(name.strip().toLowerCase(java.util.Locale.ROOT));
				if (null == asked) {
					out.add("role " + rc.getName() + " commissions " + name + ", which is not a role on " + where);
				} else if (!asked.producesInvestigationReport()) {
					out.add("role " + rc.getName() + " commissions " + asked.getName() + ", which does not produce "
							+ RearmSpecificationType.BOARD_INVESTIGATION_REPORT + " at TASK scope; declare it in "
							+ asked.getName() + "'s producesOutputs");
				}
			}
			if (StringUtils.isNotBlank(c.review())
					&& !byName.containsKey(c.review().strip().toLowerCase(java.util.Locale.ROOT))) {
				out.add("role " + rc.getName() + " names " + c.review() + " to review its investigations, which is not"
						+ " a role on " + where);
			}
		}
		return out;
	}

	/** Whether a strength spec sets anything, as opposed to only clearing. */
	private static boolean hasStrength(StrengthSpec s) {
		return null != s && ((null != s.requiredStrength() && s.requiredStrength().isPresent())
				|| (null != s.strengthHeadroom() && s.strengthHeadroom() > 0)
				|| (null != s.strengthCategory() && s.strengthCategory().isPresent())
				|| (null != s.modelStrengths() && !s.modelStrengths().isEmpty()));
	}

	/** Apply non-null spec fields and enforce the cross-field rules shared by roles and presets. */
	private static void applySpec(AgentTaskRoleConfigData rcd, RoleConfigSpec spec) throws RelizaException {
		applySpec(rcd, spec, false);
	}

	/**
	 * @param fromFile a declarative apply, which may deactivate a REQUIRED role and keeps its
	 *        necessity, so declaring it again restores it exactly (declarative-boards D6). The UI
	 *        and the coordinator keep the rule that it must be made OPTIONAL first.
	 */
	private static void applySpec(AgentTaskRoleConfigData rcd, RoleConfigSpec spec, boolean fromFile)
			throws RelizaException {
		if (spec.prompt() != null) rcd.setPrompt(spec.prompt());
		if (spec.hopBudgetMicros() != null) {
			if (spec.hopBudgetMicros() < 0) throw new RelizaException("hopBudgetMicros cannot be negative");
			rcd.setHopBudgetMicros(spec.hopBudgetMicros());
		}
		if (spec.orderIndex() != null) rcd.setOrderIndex(spec.orderIndex());
		if (spec.wipLimit() != null) rcd.setWipLimit(spec.wipLimit() > 0 ? spec.wipLimit() : null);
		if (spec.requireDistinctAgent() != null) rcd.setRequireDistinctAgent(spec.requireDistinctAgent());
		if (spec.blindReview() != null) rcd.setBlindReview(spec.blindReview());
		if (spec.commissions() != null) rcd.setCommissions(checkedCommissions(spec.commissions()));
		if (spec.requiredCapabilities() != null) rcd.setRequiredCapabilities(spec.requiredCapabilities());
		if (spec.kind() != null) rcd.setKind(spec.kind());
		if (spec.necessity() != null) rcd.setNecessity(spec.necessity());
		if (spec.humanGate() != null) rcd.setHumanGate(spec.humanGate());
		// BOARD_QUESTIONS is not something a role can contract on, in either direction: a question is
		// what a hop emits when it cannot proceed, so a role requiring one could never start and a
		// role producing one would be its own answerer. Here rather than only at the GraphQL
		// boundary because presets and seeding reach this method without passing one.
		//
		// The board pinning a questions round as a TASK input is a different thing entirely and
		// goes nowhere near a role's contract.
		if (spec.requiredInputs() != null) {
			for (RequiredInput ri : spec.requiredInputs()) {
				if (RearmSpecificationType.BOARD_QUESTIONS == ri.specification()) {
					throw new RelizaException("BOARD_QUESTIONS cannot be a required input of a role");
				}
				// The board cuts a check report only for a document with elements, so a role
				// waiting on one could wait for ever; the checks gate the hand-over instead.
				if (RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT == ri.specification()) {
					throw new RelizaException("BOARD_ELEMENT_CHECK_REPORT cannot be a required input of a role: the board's"
							+ " blocking checks gate the hand-over of the document itself");
				}
				// A report comes back pinned on the task that commissioned it (task RD4-12); a role that
				// waited on one for every task could never start a task nobody commissioned anything from.
				if (RearmSpecificationType.BOARD_INVESTIGATION_REPORT == ri.specification()) {
					throw new RelizaException("BOARD_INVESTIGATION_REPORT cannot be a required input of a role: a report"
							+ " comes back pinned on the task that commissioned it");
				}
			}
			rcd.setRequiredInputs(spec.requiredInputs());
		}
		if (spec.producesOutputs() != null) {
			for (ProducedOutput po : spec.producesOutputs()) {
				if (RearmSpecificationType.BOARD_QUESTIONS == po.specification()) {
					throw new RelizaException("BOARD_QUESTIONS cannot be a produced output of a role");
				}
				if (RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT == po.specification()) {
					throw new RelizaException("BOARD_ELEMENT_CHECK_REPORT cannot be a produced output of a role: the board cuts it");
				}
				if (RearmSpecificationType.BOARD_INVESTIGATION_REPORT == po.specification() && InputScope.TASK != po.scope()) {
					throw new RelizaException("BOARD_INVESTIGATION_REPORT is one investigation's report: produce it at TASK"
							+ " scope");
				}
			}
			rcd.setProducesOutputs(spec.producesOutputs());
		}
		StrengthSpec strength = spec.strength();
		if (null != strength) {
			if (null != strength.requiredStrength()) rcd.setRequiredStrength(strength.requiredStrength().orElse(null));
			if (null != strength.strengthHeadroom()) rcd.setStrengthHeadroom(strength.strengthHeadroom());
			if (null != strength.strengthCategory()) rcd.setStrengthCategory(strength.strengthCategory().orElse(null));
			if (null != strength.modelStrengths()) rcd.setModelStrengths(new java.util.ArrayList<>(strength.modelStrengths()));
		}
		if (rcd.getKind() == AgentTaskRoleConfigData.RoleKind.HUMAN) {
			if (spec.wipLimit() != null || Boolean.TRUE.equals(spec.requireDistinctAgent())
					|| (spec.requiredCapabilities() != null && !spec.requiredCapabilities().isEmpty())
					|| hasStrength(spec.strength())) {
				throw new RelizaException("wipLimit, requireDistinctAgent, requiredCapabilities and model"
						+ " strength are agent concepts and do not apply to a HUMAN role");
			}
			// a role flipped to HUMAN sheds any stored agent-only config
			rcd.setWipLimit(null);
			rcd.setRequireDistinctAgent(false);
			rcd.setRequiredCapabilities(new java.util.ArrayList<>());
			rcd.setRequiredStrength(null);
			rcd.setStrengthHeadroom(0);
			rcd.setStrengthCategory(null);
			rcd.setModelStrengths(new java.util.ArrayList<>());
			if (rcd.getHumanGate() != AgentTaskRoleConfigData.HumanGate.NONE) {
				throw new RelizaException("A HUMAN role is itself the human stage; humanGate applies to AGENTIC roles only");
			}
		}
		if (spec.active() != null) {
			if (!spec.active() && !fromFile
					&& rcd.getNecessity() == AgentTaskRoleConfigData.RoleNecessity.REQUIRED) {
				throw new RelizaException("Role " + rcd.getName() + " is REQUIRED and cannot be deactivated;"
						+ " set necessity OPTIONAL first");
			}
			rcd.setActive(spec.active());
		}
	}

	/**
	 * A role's commissions as stored (task RD4-12): names trimmed and each once, a budget that is not negative.
	 * A block naming no role commissions nobody and is stored as none.
	 */
	static AgentTaskRoleConfigData.Commissions checkedCommissions(AgentTaskRoleConfigData.Commissions c)
			throws RelizaException {
		if (null == c) return null;
		if (null != c.defaultBudgetMicros() && c.defaultBudgetMicros() < 0) {
			throw new RelizaException("commissions.defaultBudgetMicros cannot be negative");
		}
		List<String> names = new ArrayList<>();
		for (String n : null == c.roles() ? List.<String>of() : c.roles()) {
			if (StringUtils.isBlank(n)) throw new RelizaException("commissions.roles has a blank role name");
			String t = n.strip();
			if (names.stream().anyMatch(t::equalsIgnoreCase)) {
				throw new RelizaException("commissions.roles names " + t + " twice");
			}
			names.add(t);
		}
		if (names.isEmpty()) return null;
		return new AgentTaskRoleConfigData.Commissions(names, c.effectiveIntake(), c.defaultBudgetMicros(),
				StringUtils.isBlank(c.review()) ? null : c.review().strip());
	}

	/**
	 * Upsert an org-level role preset (operator-only). A preset named
	 * "coordinator" is allowed here — it seeds a new board's
	 * coordinatorPrompt rather than becoming a task role.
	 */
	public AgentTaskRoleConfigData upsertPreset(UUID orgUuid, RoleConfigSpec spec, WhoUpdated wu)
			throws RelizaException {
		if (orgUuid == null) throw new RelizaException("Preset requires an org");
		if (spec == null || StringUtils.isBlank(spec.name())) throw new RelizaException("Preset requires a name");
		Optional<AgentTaskRoleConfig> existing = roleConfigRepository.findPresetByOrgAndName(
				orgUuid.toString(), spec.name());
		AgentTaskRoleConfigData rcd;
		if (existing.isPresent()) {
			rcd = AgentTaskRoleConfigData.dataFromRecord(existing.get());
		} else {
			rcd = new AgentTaskRoleConfigData();
			rcd.setBoard(null);
			rcd.setOrg(orgUuid);
			rcd.setName(spec.name().trim());
		}
		validateStrength(orgUuid, spec.strength());
		List<AgentTaskRoleConfigData> presets = listPresets(orgUuid);
		List<String> before = commissionProblems(presets, "this organization's presets");
		applySpec(rcd, spec);
		refuseNewCommissionProblems(before, presets, rcd, "this organization's presets");
		return saveRoleConfigData(rcd, wu);
	}

	public List<AgentTaskRoleConfigData> listPresets(UUID orgUuid) {
		if (orgUuid == null) return List.of();
		return roleConfigRepository.findPresetsByOrg(orgUuid.toString()).stream()
				.map(AgentTaskRoleConfigData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/**
	 * Copy the org's active presets onto a fresh board (copy semantics,
	 * no ripple). A preset named "coordinator" seeds coordinatorPrompt
	 * when the board doesn't carry one yet.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	/**
	 * The coordinator preset a board of this shape seeds from (D8).
	 *
	 * <p>Two prompts, because the two boards differ in what the coordinator does: one scans wired
	 * sources and keeps a tracker in step, the other has no tracker to scan and takes its tasks
	 * from humans. There is no fallback between them. Seeding a tracker board with the
	 * board-truth prompt would tell a coordinator with sources wired that there is nothing to
	 * scan, and it would quietly never do intake; an absent prompt is a visible problem, a
	 * confidently wrong one is not.
	 */
	public static String coordinatorPresetFor(AgentBoardData board) {
		return null != board.getSources() && !board.getSources().isEmpty()
				? "coordinator-tracker"
				: "coordinator-board-truth";
	}

	public AgentBoardData seedFromPresets(AgentBoardData board, WhoUpdated wu) throws RelizaException {
		String wanted = coordinatorPresetFor(board);
		boolean seeded = false;
		for (AgentTaskRoleConfigData preset : listPresets(board.getOrg())) {
			if (!preset.isActive()) continue;
			if (wanted.equalsIgnoreCase(preset.getName())) {
				if (StringUtils.isBlank(board.getCoordinatorPrompt())) {
					board.setCoordinatorPrompt(preset.getPrompt());
					board = saveData(board, wu);
				}
				seeded = true;
				continue;
			}
			// The other coordinator prompt, and the legacy name, are not roles. Skipping them
			// keeps a board from growing a "coordinator-tracker" role alongside its coordinator.
			if (isCoordinatorPresetName(preset.getName())) continue;
			// Both lists come across with the rest. Presets and role configs are the same record
			// through the same input, so setting the contract once on the org's preset library is
			// all "configure it per org" needs to mean.
			upsertRoleConfig(board, new RoleConfigSpec(preset.getName(), preset.getPrompt(),
					preset.getOrderIndex(), preset.getWipLimit(), preset.isRequireDistinctAgent(),
					true, preset.getRequiredCapabilities(), preset.getKind(), preset.getNecessity(),
					preset.getHumanGate(), preset.getRequiredInputs(), preset.getProducesOutputs(),
					StrengthSpec.copyOf(preset), null, preset.isBlindReview(), preset.getCommissions()),
					true, wu);
		}
		if (!seeded && StringUtils.isBlank(board.getCoordinatorPrompt())) {
			postEvent(board.getUuid(), AgentBoardData.BoardEventKind.ALERT,
					"Board created with no coordinator prompt: this organization has no active"
							+ " preset named " + wanted + ". Create one and reseed the board.",
					AgentActor.system("boards"), wu);
		}
		return board;
	}

	/** The coordinator prompts, under either template name or the pre-D8 name. */
	private static boolean isCoordinatorPresetName(String name) {
		return "coordinator".equalsIgnoreCase(name) || "coordinator-tracker".equalsIgnoreCase(name)
				|| "coordinator-board-truth".equalsIgnoreCase(name);
	}

	/**
	 * Copy a named preset's prompt onto a board's coordinator prompt.
	 *
	 * <p>How an existing board moves between the two templates. Nothing switches automatically
	 * when a board gains or loses sources: the prompt is operator-curated text, and rewriting it
	 * under them because they wired a tracker would be the wrong kind of helpful.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData reseedCoordinatorPrompt(UUID boardUuid, String presetName, AgentActor by,
			WhoUpdated wu) throws RelizaException {
		AgentBoardData board = getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		AgentTaskRoleConfigData preset = listPresets(board.getOrg()).stream()
				.filter(p -> p.getName().equalsIgnoreCase(presetName))
				.findFirst()
				.orElseThrow(() -> new RelizaException("No preset named " + presetName
						+ " in this organization"));
		board.setCoordinatorPrompt(preset.getPrompt());
		AgentBoardData saved = saveData(board, wu);
		postEvent(boardUuid, AgentBoardData.BoardEventKind.INFO,
				"Coordinator prompt reseeded from preset " + preset.getName(), by, wu);
		return saved;
	}

	/**
	 * Delivery-minimum completeness alert (v1.1): capabilities covered neither by an ACTIVE role
	 * nor by the coordinator. The coordinator always has the tracker verbs, so the minimum is
	 * CODE_PUSH + PR_MERGE; a board where the coordinator merges declares that
	 * ({@code coordinatorCapabilities}, gaps §1.20) instead of warning forever.
	 */
	public List<AgentCapability> missingCapabilities(UUID boardUuid) {
		Set<AgentCapability> covered = listRoleConfigs(boardUuid).stream()
				.filter(AgentTaskRoleConfigData::isActive)
				.flatMap(rc -> rc.getRequiredCapabilities() == null
						? java.util.stream.Stream.<AgentCapability>empty()
						: rc.getRequiredCapabilities().stream())
				.collect(Collectors.toSet());
		getBoardData(boardUuid).map(AgentBoardData::getCoordinatorCapabilities)
				.ifPresent(covered::addAll);
		return LOOP_ROLE_MINIMUM.stream().filter(c -> !covered.contains(c)).collect(Collectors.toList());
	}

	private static final List<AgentCapability> LOOP_ROLE_MINIMUM =
			List.of(AgentCapability.CODE_PUSH, AgentCapability.PR_MERGE);

	/** A board function no key can exercise on a board, with the line the board panel shows. */
	public record CoverageGap(PermissionFunction function, String message) {}

	static final String NO_AGENT_KEY = "no key with BOARD_AGENT covers this board; its agent roles cannot take work";
	static final String NO_COORDINATOR_KEY = "no key with BOARD_WRITE can coordinate this board";

	/**
	 * What no key of the organization can do on a board (board-permissions.md D13; task 5c70990d),
	 * beside {@link #missingCapabilities}: with board enforcement a board can sit idle because no key
	 * holds BOARD_AGENT on it, or stall because no key can take its empty coordinator seat.
	 *
	 * <ul>
	 * <li>BOARD_AGENT, when an active AGENTIC role exists and no key clears BOARD_AGENT at
	 * ESSENTIAL_READ on the board -- the level task next and assign are checked at;</li>
	 * <li>BOARD_WRITE, when the coordinator seat is empty and no key clears BOARD_WRITE at WRITE.</li>
	 * </ul>
	 * Each key is judged as it would be on a call ({@link AuthorizationService#boardPermission(io.reliza.model.ApiKey,
	 * UUID, UUID, PermissionFunction, CallType)}); the org's admin keys clear, as everywhere, and so do
	 * its usable federated trust rules for the identities they admit. Keys are read only when a line
	 * could apply.
	 */
	public List<CoverageGap> missingCoverage(AgentBoardData board) {
		if (null == board) return List.of();
		List<CoverageGap> gaps = new ArrayList<>();
		java.util.function.Supplier<List<io.reliza.model.ApiKey>> keys = new java.util.function.Supplier<>() {
			private List<io.reliza.model.ApiKey> read;
			@Override public List<io.reliza.model.ApiKey> get() {
				if (null == read) read = apiKeyService.listApiKeyByOrg(board.getOrg());
				return read;
			}
		};
		boolean agentRoles = listRoleConfigs(board.getUuid()).stream().filter(AgentTaskRoleConfigData::isActive)
				.anyMatch(rc -> AgentTaskRoleConfigData.RoleKind.AGENTIC == rc.getKind());
		if (agentRoles && noKeyClears(keys.get(), board, PermissionFunction.BOARD_AGENT, CallType.ESSENTIAL_READ)) {
			gaps.add(new CoverageGap(PermissionFunction.BOARD_AGENT, NO_AGENT_KEY));
		}
		boolean seatEmpty = null == board.getCoordinatorSeat() || null == board.getCoordinatorSeat().session();
		if (seatEmpty && noKeyClears(keys.get(), board, PermissionFunction.BOARD_WRITE, CallType.WRITE)) {
			gaps.add(new CoverageGap(PermissionFunction.BOARD_WRITE, NO_COORDINATOR_KEY));
		}
		return gaps;
	}

	/** No stored key clears it, and no trust rule gives it to the identities it admits. */
	private boolean noKeyClears(List<io.reliza.model.ApiKey> keys, AgentBoardData board, PermissionFunction fn, CallType ct) {
		return keys.stream().noneMatch(k -> authorizationService.boardPermission(k, board.getOrg(), board.getUuid(), fn, ct))
				&& !authorizationService.federatedRulesCoverBoard(board.getOrg(), board.getUuid(), fn, ct);
	}

	/**
	 * The scope a board produces a type at: TASK for the built-in index types, or when any active
	 * role declares the type in {@code producesOutputs} at TASK scope; otherwise COMPONENT.
	 */
	public static InputScope documentScope(RearmSpecificationType spec, List<AgentTaskRoleConfigData> roles) {
		if (AgentBoardData.isIndexType(spec)) return InputScope.TASK;
		// One investigation's report, whatever the roles declare (task RD4-12).
		if (RearmSpecificationType.BOARD_INVESTIGATION_REPORT == spec) return InputScope.TASK;
		boolean perTask = roles.stream().filter(AgentTaskRoleConfigData::isActive)
				.filter(rc -> null != rc.getProducesOutputs())
				.flatMap(rc -> rc.getProducesOutputs().stream())
				.anyMatch(po -> po.specification() == spec && InputScope.TASK == po.scope());
		return perTask ? InputScope.TASK : InputScope.COMPONENT;
	}

	/**
	 * Every type's template on this board after overrides and scope defaults, with the scopes
	 * worked out from the board's roles once.
	 */
	/**
	 * A board's element families as declared and checked (gaps §2.A; grammar 1.2, task RD4-6).
	 *
	 * @param names prefix to family name; null when the board names none
	 * @param definedIn prefix to the board's own defining types, in order; null when the board sets none
	 */
	record DeclaredFamilies(Map<String, String> names, Map<String, List<RearmSpecificationType>> definedIn) {
		static final DeclaredFamilies NONE = new DeclaredFamilies(null, null);
	}

	/**
	 * A board's element families as declared, checked: each prefix is an id token's family part
	 * ({@code [A-Z][A-Z0-9]*}) and names a family. An entry is the family's name, or (grammar 1.2) an object
	 * {@code {family, definedIn}} whose {@code definedIn} lists the specification types that define the prefix's
	 * ids, in order of precedence: absent takes the family's default, empty means the ids are only referenced. The
	 * family may be left out of the object for a default prefix. Null or empty means the defaults alone.
	 */
	static DeclaredFamilies elementFamilies(Map<String, ?> declared) throws RelizaException {
		if (null == declared || declared.isEmpty()) return DeclaredFamilies.NONE;
		Map<String, String> names = new LinkedHashMap<>();
		Map<String, List<RearmSpecificationType>> definedIn = new LinkedHashMap<>();
		for (Map.Entry<String, ?> e : declared.entrySet()) {
			String prefix = null == e.getKey() ? "" : e.getKey().strip();
			if (!prefix.matches("[A-Z][A-Z0-9]*")) {
				throw new RelizaException("elementFamilies: " + e.getKey() + " is not a family prefix;"
						+ " a prefix is capital letters and digits, starting with a letter, e.g. REQ");
			}
			Object value = e.getValue();
			String family;
			if (value instanceof Map<?, ?> entry) {
				for (Object k : entry.keySet()) {
					if (!"family".equals(k) && !"definedIn".equals(k)) {
						throw new RelizaException("elementFamilies: " + prefix + " has " + k
								+ "; an entry is a family name, or {family, definedIn}");
					}
				}
				family = null == entry.get("family") ? null : String.valueOf(entry.get("family"));
				if (StringUtils.isBlank(family) && !AgentBoardData.DEFAULT_ELEMENT_FAMILIES.containsKey(prefix)) {
					throw new RelizaException("elementFamilies: " + prefix + " names no family");
				}
				if (entry.containsKey("definedIn")) definedIn.put(prefix, definedInList(prefix, entry.get("definedIn")));
			} else {
				family = null == value ? null : String.valueOf(value);
				if (StringUtils.isBlank(family)) {
					throw new RelizaException("elementFamilies: " + prefix + " names no family");
				}
			}
			if (StringUtils.isNotBlank(family)) names.put(prefix, family.strip());
		}
		return new DeclaredFamilies(names.isEmpty() ? null : names, definedIn.isEmpty() ? null : definedIn);
	}

	/** A declared {@code definedIn}: specification types, each once; BOARD_ELEMENT_CHECK_REPORT, which the board cuts, defines nothing. */
	private static List<RearmSpecificationType> definedInList(String prefix, Object raw) throws RelizaException {
		if (null == raw) {
			throw new RelizaException("elementFamilies: " + prefix + " has definedIn null; leave it out for the family's"
					+ " default, or give [] for references only");
		}
		if (!(raw instanceof java.util.Collection<?> items)) {
			throw new RelizaException("elementFamilies: " + prefix + ".definedIn is a list of specification types");
		}
		List<RearmSpecificationType> out = new ArrayList<>();
		for (Object item : items) {
			RearmSpecificationType t = null == item ? null : RearmSpecificationType.fromValue(String.valueOf(item).strip());
			if (null == t || RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT == t) {
				throw new RelizaException("elementFamilies: " + prefix + ".definedIn has " + item
						+ ", which is not a specification type a document is published as; known: "
						+ java.util.Arrays.stream(RearmSpecificationType.values())
								.filter(x -> RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT != x).map(Enum::name)
								.collect(java.util.stream.Collectors.joining(", ")));
			}
			// References only (task RD4-12): an investigation report cites elements and defines none.
			if (io.reliza.model.ElementFamilies.REFERENCE_ONLY_TYPES.contains(t)) {
				throw new RelizaException("elementFamilies: " + prefix + ".definedIn has " + t
						+ ", whose element grammar is references only: it cites ids and defines none");
			}
			if (out.contains(t)) {
				throw new RelizaException("elementFamilies: " + prefix + ".definedIn names " + t + " twice");
			}
			out.add(t);
		}
		return out;
	}

	/**
	 * A board's check policy as declared, checked against the catalogue (elements.md §7): every name
	 * in blocking is a check, every mandatory field an attribute an element can carry, every orphan
	 * family one the check judges. Null means the defaults.
	 */
	io.reliza.model.ElementCheckPolicy elementCheckPolicy(io.reliza.model.ElementCheckPolicy declared) throws RelizaException {
		if (null == declared) return null;
		List<String> problems = new ArrayList<>();
		java.util.Set<String> gates = declared.coverage().keySet();
		List<String> unknown = declared.blocking().stream().filter(c -> !ElementCheckCatalogueService.knownElementCheck(c, gates)).toList();
		if (!unknown.isEmpty()) {
			List<String> known = new ArrayList<>(ElementCheckCatalogueService.CHECKS);
			gates.forEach(g -> known.add(ElementCheckCatalogueService.COVERAGE_PREFIX + g));
			problems.add("elementChecks.blocking: unknown check(s) " + unknown + "; the catalogue ("
					+ ElementCheckCatalogueService.CATALOGUE_VERSION + ") and this file's gates have " + known);
		}
		// Coverage gates are operator code: a name the check can carry, and expressions that compile
		// and read as boolean -- refused here, with nothing written, rather than failing every
		// element of every publish afterwards (elements.md §7.5).
		for (Map.Entry<String, io.reliza.model.ElementCheckPolicy.CoverageGate> g : declared.coverage().entrySet()) {
			if (null == g.getKey() || !ElementCheckCatalogueService.GATE_NAME.matcher(g.getKey()).matches()) {
				problems.add("elementChecks.coverage: " + g.getKey() + " is not a gate name; lower case letters, digits and -");
				continue;
			}
			if (null != elementQueryEvaluator) {
				problems.addAll(elementQueryEvaluator.validate(g.getKey(), g.getValue()));
			} else if (null == g.getValue() || StringUtils.isAnyBlank(g.getValue().select(), g.getValue().require())) {
				problems.add("elementChecks.coverage." + g.getKey() + " needs select and require");
			}
		}
		for (Map.Entry<Integer, List<String>> e : declared.mandatoryFields().entrySet()) {
			if (null == e.getKey() || e.getKey() < 0) {
				problems.add("elementChecks.mandatoryFields: " + e.getKey() + " is not a level");
				continue;
			}
			List<String> bad = (null == e.getValue() ? List.<String>of() : e.getValue()).stream()
					.filter(f -> !ElementCheckCatalogueService.MANDATORY_ATTRIBUTES.contains(f)).toList();
			if (!bad.isEmpty()) {
				problems.add("elementChecks.mandatoryFields." + e.getKey() + ": " + bad + " are not element attributes; one of "
						+ new java.util.TreeSet<>(ElementCheckCatalogueService.MANDATORY_ATTRIBUTES));
			}
		}
		List<String> badOrphans = declared.orphans().stream()
				.filter(f -> !ElementCheckCatalogueService.ORPHAN_FAMILIES.contains(f)).toList();
		if (!badOrphans.isEmpty()) {
			problems.add("elementChecks.orphans: " + badOrphans + " cannot be judged; one of "
					+ new java.util.TreeSet<>(ElementCheckCatalogueService.ORPHAN_FAMILIES));
		}
		if (!problems.isEmpty()) throw new RelizaException(String.join("; ", problems));
		return declared;
	}

	/** Set a board's check policy; null restores the defaults. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData setElementCheckPolicy(UUID boardUuid, io.reliza.model.ElementCheckPolicy declared, WhoUpdated wu)
			throws RelizaException {
		io.reliza.model.ElementCheckPolicy checked = elementCheckPolicy(declared);
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		bd.setElementCheckPolicy(checked);
		return saveData(bd, wu);
	}

	/** Set how a board's tasks prove delivery (task 18c5c293); null restores the default, PR_ROWS. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData setDeliveryPolicy(UUID boardUuid, AgentBoardData.DeliveryPolicy declared, WhoUpdated wu)
			throws RelizaException {
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		bd.setDeliveryPolicy(normalised(declared));
		return saveData(bd, wu);
	}

	/** Set a board's element families; null or empty restores the defaults. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData setElementFamilies(UUID boardUuid, Map<String, ?> declared, WhoUpdated wu)
			throws RelizaException {
		DeclaredFamilies checked = elementFamilies(declared);
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		bd.setElementFamilies(checked.names());
		bd.setElementFamilyDefinedIn(checked.definedIn());
		return saveData(bd, wu);
	}

	public Map<RearmSpecificationType, String> effectiveDocumentPaths(AgentBoardData board) {
		List<AgentTaskRoleConfigData> roles = listRoleConfigs(board.getUuid());
		Map<RearmSpecificationType, String> out = new LinkedHashMap<>();
		for (RearmSpecificationType spec : RearmSpecificationType.values()) {
			// A check report is cut by the board and never written to the repository.
			if (RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT == spec) continue;
			out.put(spec, board.documentPathTemplate(spec, documentScope(spec, roles)));
		}
		return out;
	}

	public List<AgentTaskRoleConfigData> listRoleConfigs(UUID boardUuid) {
		if (boardUuid == null) return List.of();
		return roleConfigRepository.findByBoard(boardUuid.toString()).stream()
				.map(AgentTaskRoleConfigData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/** A role config or preset by its own uuid, active or not: the history read's anchor (22ddc644). */
	public Optional<AgentTaskRoleConfigData> getRoleConfigData(UUID uuid) {
		if (uuid == null) return Optional.empty();
		return roleConfigRepository.findById(uuid).map(AgentTaskRoleConfigData::dataFromRecord);
	}

	public Optional<AgentTaskRoleConfigData> getRoleConfig(UUID boardUuid, String name) {
		if (boardUuid == null || StringUtils.isBlank(name)) return Optional.empty();
		return roleConfigRepository.findByBoardAndName(boardUuid.toString(), name)
				.map(AgentTaskRoleConfigData::dataFromRecord);
	}

	/**
	 * Short content hash of a served prompt, recorded on assignments
	 * and sign-offs so a prompt edit never silently changes what a
	 * historical record meant.
	 */
	public static String promptVersion(String prompt) {
		if (prompt == null) return null;
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(md.digest(prompt.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	/** First line of {@link #routingRules}; the role templates point at the block by it. */
	public static final String ROUTING_RULES_HEADING =
			"## This board's routing rules (server-composed from the board's settings)";

	/**
	 * The board's routing thresholds, as the block every served role prompt ends with (gaps §1.28).
	 *
	 * <p>The templates were written for a board that passes with lower-priority items open, and
	 * boards ship strict: a PASSED with a P3 open routes upstream exactly as a REJECTED, and the
	 * same id open after a fix parks the task at the first repeat. The poll is the one point where
	 * an agent learns its rules, so the thresholds it is judged by are stated there, from the
	 * settings, rather than left to a prompt an operator copied before the settings changed.
	 */
	public static String routingRules(AgentBoardData board) {
		return routingRules(board, null);
	}

	/**
	 * The block with the board's merge procedure as its last bullet (task 71a3dd22): every role is
	 * told who merges and how, so a coder knows a squash collapses its commits and a coordinator
	 * knows when merging is not its job. Null leaves the bullet out.
	 */
	public static String routingRules(AgentBoardData board, AgentBoardData.MergeProcedure merge) {
		Integer blocking = board.getBlockingPriority();
		Integer completion = board.getCompletionPriority();
		StringBuilder sb = new StringBuilder(ROUTING_RULES_HEADING).append('\n');
		sb.append(null == blocking
				? "- Blocking: every open item blocks -- there is no priority threshold on this board; PASSED requires"
						+ " nothing OPEN.\n"
				: "- Blocking: items with priority ≤ " + blocking + " block; lower ones are recorded,"
						+ " never routed on.\n");
		sb.append("- Stops: a pair of roles going round more than ").append(board.effectiveCycleCap())
				.append(" times, or the same open ids after ").append(board.effectiveNoProgressRepeats())
				.append(" consecutive round(s), or the budget. ");
		if (null != completion) {
			sb.append("At a stop any blocking item with priority ≤ ").append(completion)
					.append(" needs a person; the rest is accepted by policy.\n");
		} else {
			sb.append(null == blocking
					? "At a stop any open item needs a person.\n"
					: "At a stop any blocking item still open needs a person.\n");
		}
		sb.append(board.getEffectiveCoordinatorStopLift()
				? "- Before a person: a no-progress or cycle-cap stop parks for the coordinator first, which may"
						+ " lift it once per stop kind per task or escalate it; the next identical stop, and every"
						+ " budget stop, goes straight to the operator.\n"
				: "- Every stop parks for the operator; the coordinator cannot lift it.\n");
		sb.append("- A PASSED with a blocking item OPEN is refused at sign-off; say in your note what is OPEN,"
				+ " if anything.\n");
		sb.append("- A review item the producer fixed in part is RESOLVED in the round that saw the fix;"
				+ " what remains is re-raised under a new id whose title names the old one. The same"
				+ " id still OPEN after the producer's round counts as no progress.\n");
		if (null != merge) sb.append(deliveryLine(merge));
		return sb.toString();
	}

	/**
	 * What an agent is served for a role: the operator's prompt, then the board's routing rules.
	 *
	 * <p>Only the prompt is versioned ({@link #promptVersion}): the block follows the settings, and
	 * a settings change must not read as a prompt edit on the hops it serves. On a board with a ladder
	 * the ladder section follows the block and is versioned with the prompt
	 * ({@link #promptVersion(AgentBoardData, String)}), so a hop records which ladder text it read.
	 */
	public static String servedPrompt(AgentBoardData board, String prompt) {
		return servedPrompt(board, prompt, null);
	}

	/** As {@link #servedPrompt(AgentBoardData, String)}, with the merge procedure's bullet. */
	public static String servedPrompt(AgentBoardData board, String prompt, AgentBoardData.MergeProcedure merge) {
		String rules = routingRules(board, merge);
		String served = StringUtils.isBlank(prompt) ? rules : prompt + "\n\n" + rules;
		String ladder = ladderSection(board);
		return null == ladder ? served : served + "\n\n" + ladder;
	}

	/** Where the default ladder section lives (task RD3-6). */
	static final String LADDER_SECTION_RESOURCE = "static/agents/ladder.md";

	private static volatile String defaultLadderSection;

	/** The default ladder section's text, read once. */
	static String defaultLadderSection() {
		if (null == defaultLadderSection) {
			try (java.io.InputStream in = AgentBoardService.class.getClassLoader().getResourceAsStream(LADDER_SECTION_RESOURCE)) {
				defaultLadderSection = null == in ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (java.io.IOException e) {
				log.error("Could not read the default ladder section {}", LADDER_SECTION_RESOURCE, e);
				return "";
			}
		}
		return defaultLadderSection;
	}

	/**
	 * The ladder section a board's roles and coordinator are served (task RD3-6): the board's own text
	 * ({@code settings.ladder.prompt}) or the default, with the board's levels rendered in where it says
	 * {@code {{levels}}} (appended after the text when it does not) and the last level where it says
	 * {@code {{last}}}. Null on a board without a ladder, whose prompts are served as they were.
	 */
	public static String ladderSection(AgentBoardData board) {
		if (null == board || !board.hasLadder()) return null;
		AgentBoardData.Ladder ladder = board.getLadder();
		String text = StringUtils.isNotBlank(ladder.prompt()) ? ladder.prompt() : defaultLadderSection();
		StringBuilder levels = new StringBuilder();
		for (int i = 0; i < ladder.levels().size(); i++) {
			AgentBoardData.LadderLevel l = ladder.levels().get(i);
			levels.append("- ").append(i).append(" · ").append(l.name());
			if (StringUtils.isNotBlank(l.description())) levels.append(" — ").append(l.description());
			if (i < ladder.levels().size() - 1) levels.append('\n');
		}
		String out = text.contains("{{levels}}") ? text.replace("{{levels}}", levels) : text.strip() + "\n\n" + levels;
		return out.replace("{{last}}", String.valueOf(ladder.levels().size() - 1)).strip();
	}

	/**
	 * The version a hop records of the prompt it was served (task RD3-6): the prompt's, as before; on a board with a
	 * ladder, of the prompt with the ladder section, so a hop records which ladder text it read.
	 */
	public static String promptVersion(AgentBoardData board, String prompt) {
		String ladder = ladderSection(board);
		if (null == ladder) return promptVersion(prompt);
		return promptVersion((null == prompt ? "" : prompt) + "\n\n" + ladder);
	}

	/** What a role is served on this board: its prompt, then the rules with the merge procedure. */
	public String servedPromptFor(AgentBoardData board, String prompt) {
		return servedPrompt(board, prompt, effectiveMerge(board));
	}

	/** Heading of the merge-procedure part of the coordinator's served prompt. */
	public static final String MERGE_PROCEDURE_HEADING = "## This board's merge procedure (server-composed from delivery.merge)";

	/**
	 * The coordinator seat's prompt as served (task 71a3dd22): the operator's prompt, then who merges
	 * on this board and how. The stored coordinatorPrompt stays as written, so a form that edits it
	 * never saves the served part back.
	 */
	public String servedCoordinatorPrompt(AgentBoardData board) {
		AgentBoardData.MergeProcedure m = effectiveMerge(board);
		StringBuilder sb = new StringBuilder(MERGE_PROCEDURE_HEADING).append('\n').append(deliveryLine(m));
		if (AgentBoardData.MergeBy.COORDINATOR != m.byKind()) {
			sb.append("- Merging is not yours on this board: leave a passed task in DELIVERING; if it still waits on a")
					.append(" merge after a day, post an ALERT that it does.\n");
		}
		String prompt = board.getCoordinatorPrompt();
		String served = StringUtils.isBlank(prompt) ? sb.toString() : prompt + "\n\n" + sb;
		// The ladder section (task RD3-6), on a board that declares a ladder.
		String ladder = ladderSection(board);
		return null == ladder ? served : served.stripTrailing() + "\n\n" + ladder;
	}

	// ---------- The merge procedure (task 71a3dd22) ----------

	/** A role as the merge procedure sees it: its name, whether active, whether it carries PR_MERGE. */
	public record MergeRole(String name, boolean active, boolean merges) {}

	static List<MergeRole> mergeRolesOf(List<AgentTaskRoleConfigData> roles) {
		return roles.stream().map(rc -> new MergeRole(rc.getName(), rc.isActive(), null != rc.getRequiredCapabilities()
				&& rc.getRequiredCapabilities().contains(AgentCapability.PR_MERGE))).toList();
	}

	/**
	 * The board's merge procedure with every default resolved. Who merges, when not declared: the
	 * coordinator when its capabilities cover PR_MERGE, else the first active role that carries
	 * PR_MERGE, else a person -- what the board did before the setting existed, now stated. An
	 * DECLARED board declares every merge whatever it declares.
	 */
	static AgentBoardData.MergeProcedure effectiveMerge(AgentBoardData.DeliveryPolicy policy,
			List<AgentCapability> coordinatorCaps, List<MergeRole> roles) {
		AgentBoardData.MergeProcedure d = null == policy ? null : policy.merge();
		String by = null == d || null == d.byKind() ? null : normalisedBy(d);
		if (null == by) {
			if (null != coordinatorCaps && coordinatorCaps.contains(AgentCapability.PR_MERGE)) {
				by = AgentBoardData.MergeBy.COORDINATOR.name();
			} else {
				by = roles.stream().filter(r -> r.active() && r.merges()).map(r -> AgentBoardData.MergeBy.ROLE + ":" + r.name())
						.findFirst().orElse(AgentBoardData.MergeBy.PERSON.name());
			}
		}
		boolean declared = null != policy && AgentBoardData.DeliveryMode.DECLARED == policy.mode();
		return new AgentBoardData.MergeProcedure(by,
				null == d || null == d.method() ? AgentBoardData.MergeMethod.MERGE : d.method(),
				null == d || !Boolean.FALSE.equals(d.atTestedHead()),
				declared || (null != d && Boolean.TRUE.equals(d.requireDeclaration())),
				null == d || null == d.order() ? AgentBoardData.MergeOrder.NOTE_ORDER : d.order());
	}

	/**
	 * The roles a board has once a file is applied, as the merge procedure sees them: a file that
	 * lists roles switches off the ones it leaves out, and activates the ones it lists unless they
	 * say otherwise; a listed role keeps its capabilities unless it declares them. A file that
	 * leaves `roles` out leaves them as they are (D5).
	 */
	private List<MergeRole> rolesAfterApply(AgentBoardData bd, BoardSpecDto spec, Declared d) {
		boolean rolesDeclared = d.has("roles", spec.getRoles());
		Map<String, MergeRole> after = new LinkedHashMap<>();
		for (AgentTaskRoleConfigData rc : null == bd ? List.<AgentTaskRoleConfigData>of() : listRoleConfigs(bd.getUuid())) {
			after.put(rc.getName().toLowerCase(java.util.Locale.ROOT), new MergeRole(rc.getName(), !rolesDeclared && rc.isActive(),
					null != rc.getRequiredCapabilities() && rc.getRequiredCapabilities().contains(AgentCapability.PR_MERGE)));
		}
		if (rolesDeclared) {
			for (BoardRoleSpecDto r : spec.getRoles()) {
				if (null == r.getName() || r.getName().isBlank()) continue;
				String name = r.getName().trim();
				String key = name.toLowerCase(java.util.Locale.ROOT);
				Declared rd = new Declared(r.getDeclared());
				MergeRole before = after.get(key);
				boolean active = !(rd.has("active", r.getActive()) && Boolean.FALSE.equals(r.getActive()));
				boolean merges = rd.has("requiredCapabilities", r.getRequiredCapabilities())
						? null != r.getRequiredCapabilities() && r.getRequiredCapabilities().contains(AgentCapability.PR_MERGE)
						: null != before && before.merges();
				after.put(key, new MergeRole(name, active, merges));
			}
		}
		return new ArrayList<>(after.values());
	}

	/** The board's merge procedure, resolved against its coordinator capabilities and its roles. */
	public AgentBoardData.MergeProcedure effectiveMerge(AgentBoardData board) {
		return effectiveMerge(board.getEffectiveDeliveryPolicy(), board.getCoordinatorCapabilities(),
				null == board.getUuid() ? List.of() : mergeRolesOf(listRoleConfigs(board.getUuid())));
	}

	/** The board's delivery policy with its merge procedure resolved, as effectiveDeliveryPolicy serves it. */
	public AgentBoardData.DeliveryPolicy effectiveDeliveryPolicy(AgentBoardData board) {
		AgentBoardData.DeliveryPolicy p = board.getEffectiveDeliveryPolicy();
		return new AgentBoardData.DeliveryPolicy(p.mode(), p.awaitDeclaration(), effectiveMerge(board));
	}

	/** COORDINATOR, PERSON, or ROLE:&lt;name&gt;, whatever case and spacing it was declared in. */
	private static String normalisedBy(AgentBoardData.MergeProcedure m) {
		AgentBoardData.MergeBy k = m.byKind();
		return AgentBoardData.MergeBy.ROLE == k ? k + ":" + m.byRole() : k.name();
	}

	/** A declared policy with its merge's {@code by} normalised; unchanged otherwise. */
	static AgentBoardData.DeliveryPolicy normalised(AgentBoardData.DeliveryPolicy p) {
		if (null == p || null == p.merge() || null == p.merge().byKind()) return p;
		AgentBoardData.MergeProcedure m = p.merge();
		return new AgentBoardData.DeliveryPolicy(p.mode(), p.awaitDeclaration(), new AgentBoardData.MergeProcedure(normalisedBy(m),
				m.method(), m.atTestedHead(), m.requireDeclaration(), m.order()));
	}

	/**
	 * What is wrong with a declared merge procedure against the board it would apply to: the
	 * coordinator capabilities and roles it will have. Empty when nothing is, or nothing is declared.
	 */
	static List<String> mergeProblems(AgentBoardData.DeliveryPolicy declared, List<AgentCapability> coordinatorCaps,
			List<MergeRole> roles) {
		if (null == declared || null == declared.merge()) return List.of();
		AgentBoardData.MergeProcedure m = declared.merge();
		List<String> out = new ArrayList<>();
		AgentBoardData.MergeBy kind = m.byKind();
		if (null != m.by() && !m.by().isBlank() && null == kind) {
			out.add("delivery.merge.by is COORDINATOR, PERSON or ROLE:<name>, not " + m.by().strip());
		}
		if (AgentBoardData.MergeBy.COORDINATOR == kind
				&& (null == coordinatorCaps || !coordinatorCaps.contains(AgentCapability.PR_MERGE))) {
			out.add("delivery.merge.by COORDINATOR: the coordinator does not merge on this board;"
					+ " declare coordinatorCapabilities: [PR_MERGE] or name a role");
		}
		if (AgentBoardData.MergeBy.ROLE == kind) {
			String role = m.byRole();
			boolean found = roles.stream().anyMatch(r -> r.active() && r.merges() && role.equalsIgnoreCase(r.name()));
			if (!found) {
				out.add("delivery.merge.by ROLE:" + role + ": no active role of that name carries PR_MERGE");
			}
		}
		if (AgentBoardData.DeliveryMode.DECLARED == declared.mode() && Boolean.FALSE.equals(m.requireDeclaration())) {
			out.add("delivery.merge.requireDeclaration cannot be false on a DECLARED board: a DECLARED board"
					+ " declares every merge");
		}
		return out;
	}

	/**
	 * The form's delivery policy and coordinator capabilities checked together before anything is
	 * written (task 71a3dd22): the policy and capabilities the board will have, against the roles it
	 * will have. {@code board} is null on create, where the roles are the presets it is seeded from.
	 */
	public void checkDeliveryInput(UUID orgUuid, AgentBoardData board, boolean deliveryGiven,
			AgentBoardData.DeliveryPolicy delivery, List<AgentCapability> coordinatorCaps, boolean seedFromPresets)
			throws RelizaException {
		AgentBoardData.DeliveryPolicy policy = deliveryGiven ? delivery : null == board ? null : board.getDeliveryPolicy();
		List<AgentCapability> caps = null != coordinatorCaps ? coordinatorCaps
				: null == board ? null : board.getCoordinatorCapabilities();
		List<AgentTaskRoleConfigData> roles = null != board ? listRoleConfigs(board.getUuid())
				: seedFromPresets ? listPresets(orgUuid) : List.of();
		List<String> problems = mergeProblems(policy, caps, mergeRolesOf(roles));
		if (!problems.isEmpty()) throw new RelizaException(String.join("; ", problems));
	}

	/** The merge-procedure bullet of the routing-rules block. */
	static String deliveryLine(AgentBoardData.MergeProcedure m) {
		String who = switch (m.byKind()) {
			case COORDINATOR -> "the coordinator";
			case ROLE -> "the " + m.byRole() + " role";
			default -> "a person";
		};
		return "- Delivery: merges on this board are made by " + who + ", method " + m.method() + ", "
				+ (Boolean.FALSE.equals(m.atTestedHead()) ? "not held to the tested head" : "at the tested head")
				+ ", declared: " + (Boolean.TRUE.equals(m.requireDeclaration()) ? "yes" : "no") + "; order: "
				+ (AgentBoardData.MergeOrder.OLDEST_PASS_FIRST == m.order() ? "oldest pass first" : "as the notes say")
				+ ".\n";
	}

	// ---------- Persistence ----------

	/** Boards that set a staleness block, for the staleness sweep (task RD3-4). */
	public List<AgentBoardData> listWatchingStaleness() {
		return repository.findWatchingStaleness().stream().map(AgentBoardData::dataFromRecord).toList();
	}

	/** The staleness sweep's last-alerted times, written whole without a revision (task RD3-4). */
	public void stampStalenessAlerted(UUID boardUuid, Map<String, Long> alerted) {
		try {
			repository.stampStalenessAlerted(boardUuid, Utils.OM.writeValueAsString(alerted));
		} catch (RuntimeException e) {
			log.error("Could not record the staleness alerts of board {}", boardUuid, e);
		}
	}

	/** Boards with a humanQueueAgeMinutes above 0, for the queue-age sweep (task 82880ea6). */
	public List<AgentBoardData> listWatchingHumanQueueAge() {
		return repository.findWatchingHumanQueueAge().stream().map(AgentBoardData::dataFromRecord).toList();
	}

	public AgentBoardData saveData(AgentBoardData bd, WhoUpdated wu) {
		AgentBoard b = bd.getUuid() != null
				? repository.findById(bd.getUuid()).orElseGet(AgentBoard::new)
				: new AgentBoard();
		Map<String, Object> recordData = Utils.dataToRecord(bd);
		Optional<AgentBoard> existing = repository.findById(b.getUuid());
		if (existing.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.AGENT_BOARDS, b);
			b.setRevision(b.getRevision() + 1);
			b.setLastUpdatedDate(ZonedDateTime.now());
		}
		b.setRecordData(recordData);
		b = (AgentBoard) WhoUpdated.injectWhoUpdatedData(b, wu);
		return AgentBoardData.dataFromRecord(repository.save(b));
	}

	private AgentTaskRoleConfigData saveRoleConfigData(AgentTaskRoleConfigData rcd, WhoUpdated wu) {
		AgentTaskRoleConfig rc = rcd.getUuid() != null
				? roleConfigRepository.findById(rcd.getUuid()).orElseGet(AgentTaskRoleConfig::new)
				: new AgentTaskRoleConfig();
		Map<String, Object> recordData = Utils.dataToRecord(rcd);
		Optional<AgentTaskRoleConfig> existing = roleConfigRepository.findById(rc.getUuid());
		if (existing.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.AGENT_TASK_ROLE_CONFIGS, rc);
			rc.setRevision(rc.getRevision() + 1);
			rc.setLastUpdatedDate(ZonedDateTime.now());
		}
		rc.setRecordData(recordData);
		rc = (AgentTaskRoleConfig) WhoUpdated.injectWhoUpdatedData(rc, wu);
		return AgentTaskRoleConfigData.dataFromRecord(roleConfigRepository.save(rc));
	}

	// ---------- Declarative board spec (export) ----------

	/**
	 * A board as configuration: everything needed to stand the same board up elsewhere, and
	 * nothing that is runtime state. Prompts are in, because a board without them is not the
	 * same board; tasks are out, because they are the work, not the workflow.
	 *
	 * <p>Shaped to be applied, not only read. Apply is a later increment, but the format is the
	 * contract either way -- a convenient export that could not be fed back would have to break
	 * to become useful.
	 */
	@Data public static class BoardSpecDto {
		private DeclarativeConfigService.DeclarativeKind kind = DeclarativeConfigService.DeclarativeKind.BOARD;
		private Integer version = 1;
		private String name;
		private String description;
		/** Name of the component this board builds, not its uuid -- a spec must be portable. */
		private String target;
		private Integer defaultWorkLevel;
		private InputResolution defaultInputResolution;
		private AgentBoardData.PriorityType priorityType;
		private Integer perAgentWipLimit;
		private List<String> sources = new LinkedList<>();
		/**
		 * The documents repository as a URI, not the row uuid: a spec has to survive being
		 * applied in another organization, where that uuid resolves to nothing.
		 */
		private String documentsRepo;
		private Map<RearmSpecificationType, String> documentPaths = new LinkedHashMap<>();
		/**
		 * Element id families over the defaults (gaps §2.A); declared null restores the defaults. An entry is a
		 * family name or {@code {family, definedIn}} (grammar 1.2, task RD4-6).
		 */
		private Map<String, Object> elementFamilies;
		/** The element checks: which block, mandatory fields, orphan families; declared null restores the defaults. */
		private io.reliza.model.ElementCheckPolicy elementChecks;
		/** How a task proves it was delivered (task 18c5c293); declared null restores the default, PR_ROWS. */
		private AgentBoardData.DeliveryPolicy delivery;
		/** How the board names its documents (board-documents.md D2); declared null restores the defaults. */
		private DocumentsSpecDto documents;
		/**
		 * Perspectives by name or uuid, product: marking a PRODUCT component (board-permissions.md §3);
		 * declared null clears. The export writes a name only when it is unique in the organization.
		 */
		private List<String> perspectives;
		/** The task-key prefix (board-documents.md D8); declared null derives it anew from the name. */
		private String taskPrefix;
		private String coordinatorPrompt;
		/** Verbs the coordinator seat covers itself; empty or absent means only the tracker verbs. */
		private List<AgentCapability> coordinatorCapabilities;
		/** The board's mechanics: budget and stops (declarative-boards D4). */
		private BoardSettingsSpecDto settings;
		/**
		 * The task groups, in display order (task RD2-30): absent leaves them; null or empty deletes
		 * them, refused while one holds tasks; a group the list leaves out is closed.
		 */
		private List<BoardGroupSpecDto> groups;
		private List<BoardRoleSpecDto> roles = new LinkedList<>();
		/**
		 * Keys the incoming spec carried, null or not, with the settings as {@code settings.<key>}.
		 * Presence is what separates a field declared null, which clears (D15), from one left out,
		 * which is untouched -- the typed fields cannot tell the two apart. Null when the spec was
		 * built in code rather than read from input: then a non-null field counts as declared.
		 */
		@JsonIgnore private Set<String> declared;
		/** The spec as it arrived, so its hash covers what was sent, nulls included. */
		@JsonIgnore private Map<String, Object> raw;
	}

	/**
	 * One task group as a board file declares it (task RD2-30): configuration only, dependencies by
	 * key. {@code uuid} and {@code order} come only from the board form, to rename a group or place it.
	 */
	@Data public static class BoardGroupSpecDto {
		private String key;
		private String name;
		private String description;
		private List<String> dependsOn;
		private Integer defaultWorkLevel;
		private AgentBoardData.GroupStatus status;
		@JsonIgnore private UUID uuid;
		@JsonIgnore private Integer order;
		/** As {@link BoardSpecDto#getDeclared}: a member declared null clears, one left out stays. */
		@JsonIgnore private Set<String> declared;
	}

	/** A board file's documents block: {@code prefix} names the document components instead of the board. */
	@Data public static class DocumentsSpecDto {
		private String prefix;
		/** The repository serves several boards: the default root is boards/{board}/ (board-documents.md D6). */
		private Boolean shared;
		/** The root outright, relative to the repository; empty is the repository root. */
		private String root;
	}

	/** The board's mechanics settings, each null meaning the board default. */
	@Data public static class BoardSettingsSpecDto {
		private Long budgetMicros;
		private Integer softAlertPercent;
		private Integer cycleCap;
		private Integer noProgressRepeatsToStop;
		private Integer humanQueueAgeMinutes;
		private Integer blockingPriority;
		private Integer completionPriority;
		/** Whether a loop stop parks for the coordinator first (task c0a2134c); null is on. */
		private Boolean coordinatorStopLift;
		/** Days the event log keeps an event (task 04dedcc5); null is 15, 0 keeps everything. */
		private Integer eventRetentionDays;
		/**
		 * The staleness thresholds (task RD3-4), one block: declared, it replaces the board's whole
		 * block (a key left out inside it is off); declared null turns every rule off.
		 */
		private AgentBoardData.Staleness staleness;
		/**
		 * The level ladder (task RD3-6), one block: declared, it replaces the board's ladder; declared null
		 * removes it, refused while a task carries a level.
		 */
		private AgentBoardData.Ladder ladder;
	}

	/** A role's model strength, models by reference so the spec travels between organizations. */
	@Data public static class BoardStrengthSpecDto {
		private Double requiredStrength;
		private Double strengthHeadroom;
		private ModelOntologyData.RoleCategory strengthCategory;
		private List<BoardModelStrengthSpecDto> modelStrengths;
	}

	/** One per-model override; {@code model} resolves through the organization's model aliases. */
	@Data public static class BoardModelStrengthSpecDto {
		private String model;
		private Double strength;
	}

	/** An organization's role presets as one file (declarative-boards §2.2). */
	@Data public static class RolePresetsSpecDto {
		private DeclarativeConfigService.DeclarativeKind kind = DeclarativeConfigService.DeclarativeKind.ROLE_PRESETS;
		private Integer version = 1;
		/** When true, presets the file does not list are deactivated. */
		private Boolean authoritative = false;
		private List<BoardRoleSpecDto> presets = new LinkedList<>();
		@JsonIgnore private Map<String, Object> raw;
	}

	@Data public static class BoardRoleSpecDto {
		private String name;
		private String prompt;
		private Integer orderIndex;
		private Integer wipLimit;
		private Boolean requireDistinctAgent;
		private Boolean active;
		private AgentTaskRoleConfigData.RoleKind kind;
		private AgentTaskRoleConfigData.RoleNecessity necessity;
		private AgentTaskRoleConfigData.HumanGate humanGate;
		private List<AgentCapability> requiredCapabilities = new LinkedList<>();
		private List<BoardRequiredInputSpecDto> requiredInputs = new LinkedList<>();
		private List<BoardProducedOutputSpecDto> producesOutputs = new LinkedList<>();
		private Long hopBudgetMicros;
		private Boolean blindReview;
		/** Who the role may commission for a report (task RD4-12): roles by name, so the file travels. */
		private AgentTaskRoleConfigData.Commissions commissions;
		private BoardStrengthSpecDto strength;
		/** As {@link BoardSpecDto#getDeclared}, with the strength parts as {@code strength.<key>}. */
		@JsonIgnore private Set<String> declared;
	}

	/** The other half of the contract, in the same portable form. */
	@Data public static class BoardProducedOutputSpecDto {
		private RearmSpecificationType specification;
		private InputScope scope;
		private Boolean required;
	}

	/** A requirement in portable form: components by name, so the spec survives a different org. */
	@Data public static class BoardRequiredInputSpecDto {
		private InputKind kind;
		private RearmSpecificationType specification;
		private InputScope scope;
		private String component;
		private ReleaseLifecycle minLifecycle;
		private InputResolution resolution;
	}

	// ---------- Declarative apply (ai-plans/agentic/declarative-boards.md) ----------

	private static final List<String> SETTINGS_KEYS = List.of("budgetMicros", "softAlertPercent", "cycleCap",
			"noProgressRepeatsToStop", "blockingPriority", "completionPriority", "humanQueueAgeMinutes",
			"coordinatorStopLift", "eventRetentionDays", "staleness", "ladder");

	/**
	 * Read a board spec from input, keeping which keys it carried (D15). A key present with a null
	 * value clears the field; a key left out leaves it alone. The typed DTO cannot tell those two
	 * apart, so presence is recorded beside it.
	 */
	@SuppressWarnings("unchecked")
	public static BoardSpecDto boardSpecFromInput(Map<String, Object> raw) throws RelizaException {
		if (null == raw) throw new RelizaException("A board spec is required");
		BoardSpecDto spec = readSpec(raw, BoardSpecDto.class);
		spec.setDeclared(declaredKeys(raw, "settings", "documents"));
		spec.setRaw(raw);
		markRoles(raw.get("roles"), spec.getRoles());
		markGroups(raw.get("groups"), spec.getGroups());
		return spec;
	}

	/** Read a presets spec from input, with presence per preset as for a board's roles. */
	public static RolePresetsSpecDto rolePresetsSpecFromInput(Map<String, Object> raw) throws RelizaException {
		if (null == raw) throw new RelizaException("A presets spec is required");
		RolePresetsSpecDto spec = readSpec(raw, RolePresetsSpecDto.class);
		spec.setRaw(raw);
		markRoles(raw.get("presets"), spec.getPresets());
		return spec;
	}

	@SuppressWarnings("unchecked")
	private static void markRoles(Object rawRoles, List<BoardRoleSpecDto> roles) {
		if (!(rawRoles instanceof List<?> list) || null == roles) return;
		for (int i = 0; i < list.size() && i < roles.size(); i++) {
			if (list.get(i) instanceof Map<?, ?> m) {
				roles.get(i).setDeclared(declaredKeys((Map<String, Object>) m, "strength"));
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static void markGroups(Object rawGroups, List<BoardGroupSpecDto> groups) {
		if (!(rawGroups instanceof List<?> list) || null == groups) return;
		for (int i = 0; i < list.size() && i < groups.size(); i++) {
			if (list.get(i) instanceof Map<?, ?> m && null != groups.get(i)) {
				groups.get(i).setDeclared(declaredKeys((Map<String, Object>) m));
			}
		}
	}

	private static <T> T readSpec(Map<String, Object> raw, Class<T> type) throws RelizaException {
		try {
			return Utils.OM.convertValue(raw, type);
		} catch (RuntimeException e) {
			// Jackson 3 throws its own unchecked exception; its message names the field.
			throw new RelizaException("Could not read the spec: " + e.getMessage());
		}
	}

	/** The keys a map carries, null or not, and one nested map's keys as {@code nested.key}. */
	@SuppressWarnings("unchecked")
	static Set<String> declaredKeys(Map<String, Object> raw, String... nested) {
		Set<String> out = new java.util.LinkedHashSet<>(raw.keySet());
		for (String n : nested) {
			if (raw.get(n) instanceof Map<?, ?> m) {
				((Map<String, Object>) m).keySet().forEach(k -> out.add(n + "." + k));
			}
		}
		return out;
	}

	/**
	 * What a spec declares. From input, presence (D15); from code, where there is no presence to
	 * read, any field with a value.
	 */
	private record Declared(Set<String> keys) {
		boolean has(String key, Object value) {
			if (null != keys) return keys.contains(key);
			if (value instanceof java.util.Collection<?> c) return !c.isEmpty();
			if (value instanceof Map<?, ?> m) return !m.isEmpty();
			return null != value;
		}

		/** A nested field, declared when it is, or when its parent is declared null (clear it all). */
		boolean hasNested(String parent, String key, Object parentValue, Object value) {
			if (null != keys) return keys.contains(parent + "." + key) || (keys.contains(parent) && null == parentValue);
			return null != value;
		}
	}

	/**
	 * Apply one board file: the board, its settings and its roles, as one transaction
	 * (declarative-boards §3.1).
	 *
	 * <p>Any problem refuses the whole file, and the result lists every problem found rather than
	 * the first (D13). A dry run is the same apply rolled back (D18): the refusals live inside the
	 * write paths, so skipping the writes could pass a file its apply would refuse.
	 */
	public ApplyResult applyBoard(UUID orgUuid, BoardSpecDto spec, boolean dryRun, SourceDto source,
			AgentActor actor, WhoUpdated wu) throws RelizaException {
		return applyBoard(orgUuid, spec, dryRun, source, actor, BoardPerspectiveService.PerspectiveConsent.NONE, wu);
	}

	/** As above, with the caller's consent for the perspectives the file adds or removes (§3.2). */
	public ApplyResult applyBoard(UUID orgUuid, BoardSpecDto spec, boolean dryRun, SourceDto source,
			AgentActor actor, BoardPerspectiveService.PerspectiveConsent consent, WhoUpdated wu) throws RelizaException {
		return applyBoard(orgUuid, spec, dryRun, source, actor, consent, BoardAccess.ALL, wu);
	}

	/**
	 * As above, for a caller whose board access is checked once the file's board is resolved
	 * (architecture d8e7bd7e §3.3): BOARD_WRITE and CONFIGURATION_WRITE on it to update it; to create
	 * one, the consent rule for each perspective, or both functions at the organization when it hangs
	 * off none. A dry run refuses the same.
	 */
	public ApplyResult applyBoard(UUID orgUuid, BoardSpecDto spec, boolean dryRun, SourceDto source,
			AgentActor actor, BoardPerspectiveService.PerspectiveConsent consent, BoardAccess access, WhoUpdated wu)
			throws RelizaException {
		if (null == orgUuid) throw new RelizaException("A board file applies to an organization");
		if (null == spec) throw new RelizaException("A board spec is required");
		checkEnvelope(spec.getKind(), spec.getVersion(), DeclarativeKind.BOARD);
		if (StringUtils.isBlank(spec.getName())) throw new RelizaException("A board file needs the board's name");
		return rolledBackUnlessApplied(dryRun, () -> applyBoardWrites(orgUuid, spec, dryRun, source, actor, consent,
				null == access ? BoardAccess.ALL : access, wu));
	}

	/**
	 * Archive a board by name: what deleting it from Terraform does (declarative-boards D7). Its
	 * tasks, roles and history stay; an archived board takes no new work, and a file naming it is
	 * refused until it is unarchived (D17). Archiving an archived board changes nothing.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData archiveBoardByName(UUID orgUuid, String name, AgentActor actor, WhoUpdated wu)
			throws RelizaException {
		return archiveBoardByName(orgUuid, name, actor, BoardAccess.ALL, wu);
	}

	/** As above for a caller that must hold BOARD_WRITE and CONFIGURATION_WRITE on the board, as apply. */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData archiveBoardByName(UUID orgUuid, String name, AgentActor actor, BoardAccess access,
			WhoUpdated wu) throws RelizaException {
		AgentBoard row = repository.findByOrgAndName(orgUuid.toString(), null == name ? "" : name.trim())
				.orElseThrow(() -> new RelizaException("Board " + name + " not found"));
		AgentBoardData bd = AgentBoardData.dataFromRecord(row);
		// A board the caller holds nothing on is not found, word for word: the refusal below would confirm it.
		if (!access.sees(bd.getUuid())) throw new RelizaException("Board " + name + " not found");
		if (!access.onBoard(bd.getUuid(), CallType.WRITE, PermissionFunction.BOARD_WRITE,
				PermissionFunction.CONFIGURATION_WRITE)) {
			throw new RelizaException("Archiving board " + bd.getName() + " needs BOARD_WRITE and CONFIGURATION_WRITE on it");
		}
		if (BoardStatus.ARCHIVED == bd.getStatus()) return bd;
		bd = updateBoard(bd.getUuid(), null, null, null, null, null, BoardStatus.ARCHIVED, null, null, wu);
		postEvent(bd.getUuid(), AgentBoardData.BoardEventKind.INFO, "Board archived by a declarative delete",
				null == actor ? AgentActor.system("declarative apply") : actor, wu);
		return getBoardData(bd.getUuid()).orElse(bd);
	}

	/** Apply an organization's presets file (declarative-boards §3.2), with the board's rules. */
	public ApplyResult applyRolePresets(UUID orgUuid, RolePresetsSpecDto spec, boolean dryRun, SourceDto source,
			WhoUpdated wu) throws RelizaException {
		if (null == orgUuid) throw new RelizaException("A presets file applies to an organization");
		if (null == spec) throw new RelizaException("A presets spec is required");
		checkEnvelope(spec.getKind(), spec.getVersion(), DeclarativeKind.ROLE_PRESETS);
		return rolledBackUnlessApplied(dryRun, () -> applyPresetWrites(orgUuid, spec, dryRun, source, wu));
	}

	private static void checkEnvelope(DeclarativeKind kind, Integer version, DeclarativeKind expected)
			throws RelizaException {
		if (null != kind && kind != expected) {
			throw new RelizaException("A " + kind + " file cannot be applied as " + expected);
		}
		if (null != version && version != 1) {
			throw new RelizaException("Unsupported " + expected + " file version " + version + "; this server reads version 1");
		}
	}

	@FunctionalInterface
	private interface ApplyWork {
		ApplyResult run() throws RelizaException;
	}

	/** A refusal carried out of the transaction callback, which cannot throw checked exceptions. */
	private static final class ApplyRefused extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final RelizaException refusal;

		ApplyRefused(RelizaException refusal) {
			super(refusal.getMessage(), refusal);
			this.refusal = refusal;
		}
	}

	/**
	 * Run an apply in a transaction of its own, and roll it back when it was a dry run or found a
	 * problem. Its own transaction so a caller's cannot be marked rollback-only by it; programmatic
	 * so the result can come back from a transaction that did not commit.
	 */
	private ApplyResult rolledBackUnlessApplied(boolean dryRun, ApplyWork work) throws RelizaException {
		TransactionTemplate tt = new TransactionTemplate(transactionManager);
		tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		try {
			return tt.execute(status -> {
				try {
					ApplyResult r = work.run();
					if (dryRun || r.getErrors() > 0) status.setRollbackOnly();
					return r;
				} catch (RelizaException e) {
					throw new ApplyRefused(e);
				}
			});
		} catch (ApplyRefused e) {
			throw e.refusal;
		}
	}

	/** A role entry with its references resolved, ready to apply. */
	private record ResolvedRole(BoardRoleSpecDto entry, Declared declared, List<RequiredInput> requiredInputs,
			List<AgentTaskRoleConfigData.ModelStrength> modelStrengths) {}

	private ApplyResult applyBoardWrites(UUID orgUuid, BoardSpecDto spec, boolean dryRun, SourceDto source,
			AgentActor actor, BoardPerspectiveService.PerspectiveConsent consent, BoardAccess access, WhoUpdated wu)
			throws RelizaException {
		ApplyResult result = new ApplyResult();
		result.setKind(DeclarativeKind.BOARD);
		result.setDryRun(dryRun);
		result.setSpecHash(DeclarativeConfigService.specHash(null != spec.getRaw() ? spec.getRaw() : spec));
		String boardName = spec.getName().trim();
		Declared d = new Declared(spec.getDeclared());

		AgentBoardData bd = null;
		Optional<AgentBoard> byName = repository.findByOrgAndName(orgUuid.toString(), boardName);
		if (byName.isPresent()) {
			AgentBoard locked = repository.findByIdWriteLocked(byName.get().getUuid())
					.orElseThrow(() -> new RelizaException("Board not found: " + boardName));
			// The name lookup already loaded this row; the lock statement does not replace that
			// copy, so read it again under the lock.
			entityManager.refresh(locked);
			bd = AgentBoardData.dataFromRecord(locked);
			// A board the caller holds nothing on (operator report 2026-10-02): the name is taken, which
			// the refusal cannot help saying, and nothing more -- not its status, perspectives, ladder or
			// roles, which the checks below would read out of it into their problems.
			if (!access.sees(bd.getUuid())) {
				result.add(error(boardName, "applying a file to board " + boardName
						+ " needs BOARD_WRITE and CONFIGURATION_WRITE on it"));
				return result;
			}
			if (BoardStatus.ARCHIVED == bd.getStatus()) {
				result.add(Change.of(DeclarativeKind.BOARD, boardName, Action.ERROR, null, "Board " + boardName
						+ " is archived, and a file cannot apply to an archived board yet. Unarchive it, or name"
						+ " a new board."));
				return result;
			}
		}
		boolean creating = null == bd;

		// ---- resolve and check everything before writing anything (D11, D13) ----
		List<Change> problems = new ArrayList<>();
		UUID target = null;
		if (d.has("target", spec.getTarget())) {
			target = componentNamed(orgUuid, spec.getTarget(), "target", boardName, problems);
		} else if (creating) {
			problems.add(error(boardName, "a new board needs a target: the component it builds"));
		}
		// Perspectives (board-permissions.md §3): declared null clears, absent keeps. The rules run when
		// the set or the target changes, the consent one only for what the set adds or removes.
		List<UUID> perspectivesBefore = creating || null == bd.getPerspectives() ? List.of() : bd.getPerspectives();
		List<UUID> perspectivesAfter = perspectivesBefore;
		boolean perspectivesDeclared = d.has("perspectives", spec.getPerspectives());
		if (perspectivesDeclared) {
			List<String> entryProblems = new ArrayList<>();
			perspectivesAfter = boardPerspectiveService.resolveEntries(orgUuid,
					null == spec.getPerspectives() ? List.of() : spec.getPerspectives(), perspectivesBefore, entryProblems);
			entryProblems.forEach(p -> problems.add(error(boardName, p)));
		}
		// Who may apply this file (architecture d8e7bd7e §3.3), judged before anything is written.
		if (!creating && !access.onBoard(bd.getUuid(), CallType.WRITE, PermissionFunction.BOARD_WRITE,
				PermissionFunction.CONFIGURATION_WRITE)) {
			problems.add(error(boardName, "applying a file to board " + boardName
					+ " needs BOARD_WRITE and CONFIGURATION_WRITE on it"));
		} else if (creating && perspectivesAfter.isEmpty() && !access.atOrganization(CallType.WRITE,
				PermissionFunction.BOARD_WRITE, PermissionFunction.CONFIGURATION_WRITE)) {
			problems.add(error(boardName, "creating board " + boardName + " in no perspective needs BOARD_WRITE and"
					+ " CONFIGURATION_WRITE at the organization; name perspectives you may add it to"));
		}
		// Only on a change (§3.2): a file repeating the target and set it already has -- an export
		// applied again, the provider's full state -- is not re-judged by them.
		boolean setChanged = perspectivesDeclared
				&& !new java.util.HashSet<>(perspectivesAfter).equals(new java.util.HashSet<>(perspectivesBefore));
		boolean targetChanged = null != target && (creating || !target.equals(bd.getTarget()));
		if (setChanged || targetChanged) {
			UUID effectiveTarget = null != target ? target : creating ? null : bd.getTarget();
			boardPerspectiveService.problems(orgUuid, effectiveTarget, perspectivesAfter, perspectivesBefore, consent)
					.forEach(p -> problems.add(error(boardName, p)));
		}
		List<String> sources = null;
		if (d.has("sources", spec.getSources())) {
			try {
				sources = renderSources(null == spec.getSources() ? List.of() : spec.getSources());
			} catch (RelizaException e) {
				problems.add(error(boardName, "sources: " + e.getMessage()));
			}
		}
		if (d.has("perAgentWipLimit", spec.getPerAgentWipLimit()) && null != spec.getPerAgentWipLimit()
				&& spec.getPerAgentWipLimit() < 1) {
			problems.add(error(boardName, "perAgentWipLimit must be at least 1"));
		}
		if (null != spec.getDefaultWorkLevel()
				&& (spec.getDefaultWorkLevel() < 0 || spec.getDefaultWorkLevel() > AgentTaskService.MAX_LEVEL)) {
			problems.add(error(boardName, DEFAULT_LEVEL_RANGE));
		}
		// With a ladder in effect, the default in effect is one of its rungs (task RD3-6); without one it is ignored.
		if (null != orgUuid && null != boardName) {
			Optional<AgentBoardData> current = repository.findByOrgAndName(orgUuid.toString(), boardName.trim())
					.map(AgentBoardData::dataFromRecord);
			Integer defaultInEffect = d.has("defaultWorkLevel", spec.getDefaultWorkLevel()) ? spec.getDefaultWorkLevel()
					: current.map(AgentBoardData::getDefaultWorkLevel).orElse(null);
			String off = defaultOffLadder(defaultInEffect,
					ladderAfter(spec, d, current.map(AgentBoardData::getLadder).orElse(null)));
			if (null != off) problems.add(error(boardName, off));
		}
		if (null != spec.getElementFamilies()) {
			try {
				elementFamilies(spec.getElementFamilies());
			} catch (RelizaException e) {
				problems.add(error(boardName, e.getMessage()));
			}
		}
		try {
			elementCheckPolicy(spec.getElementChecks());
		} catch (RelizaException e) {
			problems.add(error(boardName, e.getMessage()));
		}
		checkSettings(orgUuid, boardName, spec.getSettings(), d, problems);
		try {
			coordinatorCapabilities(spec.getCoordinatorCapabilities());
		} catch (RelizaException e) {
			problems.add(error(boardName, e.getMessage()));
		}
		List<ResolvedRole> roles = new ArrayList<>();
		Set<String> names = new java.util.HashSet<>();
		int position = 0;
		for (BoardRoleSpecDto r : spec.getRoles()) {
			position++;
			String name = null == r.getName() ? "" : r.getName().trim();
			if (name.isEmpty()) {
				problems.add(error(boardName, "role #" + position + " has no name"));
				continue;
			}
			if (!names.add(name.toLowerCase(java.util.Locale.ROOT))) {
				problems.add(error(name, "declared more than once"));
				continue;
			}
			if (isCoordinatorPresetName(name)) {
				problems.add(error(name, "the coordinator is implicit on every board and is not a task role;"
						+ " set coordinatorPrompt instead"));
				continue;
			}
			roles.add(resolveRole(orgUuid, r, problems));
		}
		// The merge procedure against the board this file leaves: its coordinator capabilities and
		// its roles, which are the file's (task 71a3dd22).
		AgentBoardData.DeliveryPolicy delivery = d.has("delivery", spec.getDelivery()) ? spec.getDelivery()
				: creating ? null : bd.getDeliveryPolicy();
		List<AgentCapability> caps = d.has("coordinatorCapabilities", spec.getCoordinatorCapabilities())
				? spec.getCoordinatorCapabilities() : creating ? null : bd.getCoordinatorCapabilities();
		for (String problem : mergeProblems(delivery, caps, rolesAfterApply(bd, spec, d))) {
			problems.add(error(boardName, problem));
		}
		String prefixProblem = documentsPrefixProblem(null == spec.getDocuments() ? null : spec.getDocuments().getPrefix());
		if (d.hasNested("documents", "prefix", spec.getDocuments(), null == spec.getDocuments() ? null
				: spec.getDocuments().getPrefix()) && null != prefixProblem) {
			problems.add(error(boardName, prefixProblem));
		}
		String rootProblem = documentsRootProblem(null == spec.getDocuments() ? null : spec.getDocuments().getRoot());
		if (null != rootProblem) problems.add(error(boardName, rootProblem));
		if (d.has("taskPrefix", spec.getTaskPrefix()) && null != spec.getTaskPrefix()) {
			String prefixProblemMsg = taskPrefixProblem(orgUuid, spec.getTaskPrefix(), creating ? null : bd.getUuid(), access);
			if (null != prefixProblemMsg) problems.add(error(boardName, prefixProblemMsg));
		}
		// Task groups (task RD2-30): absent leaves them; the list is the board's, a group it leaves out closed.
		boolean groupsDeclared = d.has("groups", spec.getGroups());
		List<AgentBoardData.TaskGroup> groupsBefore = creating || null == bd.getGroups() ? List.of() : bd.getGroups();
		List<AgentBoardData.TaskGroup> groupsAfter = groupsBefore;
		if (groupsDeclared) {
			List<String> groupProblems = new ArrayList<>();
			final UUID heldBoard = creating ? null : bd.getUuid();
			groupsAfter = AgentTaskGroupService.groupsAfter(groupsBefore, spec.getGroups(),
					() -> tasksPerGroup(heldBoard), ladderAfter(spec, d, creating ? null : bd.getLadder()), groupProblems);
			groupProblems.forEach(p -> problems.add(error(boardName, p)));
		}
		if (!problems.isEmpty()) {
			problems.forEach(result::add);
			return result;
		}

		// ---- the board ----
		if (creating) {
			bd = createBoard(orgUuid, boardName, null, sources, null, null, null, target, null,
					d.has("taskPrefix", spec.getTaskPrefix()) ? spec.getTaskPrefix() : null, wu);
			if (!d.has("coordinatorPrompt", spec.getCoordinatorPrompt())) {
				// A new board takes its coordinator prompt from the presets, as the UI's create
				// does. Only the prompt: the file's roles are the board's roles.
				String wanted = coordinatorPresetFor(bd);
				final AgentBoardData created = bd;
				listPresets(orgUuid).stream()
						.filter(AgentTaskRoleConfigData::isActive)
						.filter(p -> wanted.equalsIgnoreCase(p.getName()))
						.findFirst()
						.ifPresent(p -> created.setCoordinatorPrompt(p.getPrompt()));
			}
		}
		Map<String, Object> boardBefore = creating ? Map.of() : boardState(bd);
		List<Change> roleChanges = new ArrayList<>();
		List<String> alerts = new ArrayList<>();
		try {
			applyBoardFields(bd, spec, d, target, sources, wu);
			if (perspectivesDeclared) {
				boardPerspectiveService.followSetChange(bd, perspectivesBefore, perspectivesAfter, wu);
				bd.setPerspectives(new ArrayList<>(perspectivesAfter));
			}
			if (groupsDeclared) bd.setGroups(new ArrayList<>(groupsAfter));
		} catch (TaskPrefixTaken taken) {
			// Claimed between the check above and the write: told as the check would tell it.
			result.add(error(boardName, taken.forCaller(access).getMessage()));
			return result;
		} catch (RelizaException e) {
			result.add(error(boardName, e.getMessage()));
			return result;
		}

		// ---- roles ----
		List<AgentTaskRoleConfigData> current = listRoleConfigs(bd.getUuid());
		// The commissions check reads the roles as the file leaves them (task RD4-12): the rows below are changed
		// in place, and a new role joins them once its entry applies.
		List<String> commissionsBefore = commissionProblems(current, "board " + boardName);
		List<AgentTaskRoleConfigData> rolesAfter = new ArrayList<>(current);
		position = 0;
		for (ResolvedRole rr : roles) {
			position++;
			String name = rr.entry().getName().trim();
			Optional<AgentTaskRoleConfigData> existing = current.stream()
					.filter(rc -> rc.getName().equalsIgnoreCase(name)).findFirst();
			AgentTaskRoleConfigData rc;
			if (existing.isPresent()) {
				rc = existing.get();
			} else {
				rc = new AgentTaskRoleConfigData();
				rc.setBoard(bd.getUuid());
				rc.setOrg(bd.getOrg());
				rc.setName(name);
			}
			Map<String, Object> before = existing.isPresent() ? roleState(rc) : Map.of();
			boolean wasActive = existing.isPresent() && rc.isActive();
			try {
				applyRoleEntry(bd.getOrg(), rc, rr, existing.isEmpty() ? 10 * position : null, true);
			} catch (RelizaException e) {
				roleChanges.add(error(name, e.getMessage()));
				continue;
			}
			if (existing.isEmpty()) rolesAfter.add(rc);
			List<String> changed = changedFields(before, roleState(rc));
			Change c;
			if (existing.isEmpty()) {
				saveRoleConfigData(rc, wu);
				c = Change.of(DeclarativeKind.BOARD, name, Action.CREATE, changed, "role");
			} else if (changed.isEmpty()) {
				c = Change.of(DeclarativeKind.BOARD, name, Action.UNCHANGED, null, "role");
			} else {
				saveRoleConfigData(rc, wu);
				c = Change.of(DeclarativeKind.BOARD, name, Action.UPDATE, changed, "role");
			}
			if (wasActive && !rc.isActive()) warnStranded(bd, rc, c, alerts);
			roleChanges.add(c);
		}
		// Only a file that lists roles speaks for the role list. One that leaves `roles` out leaves
		// the roles alone, like any other field it leaves out (D5); without this a settings-only
		// file, or a Terraform board with no role blocks, would switch every role off.
		boolean rolesDeclared = d.has("roles", spec.getRoles());
		for (AgentTaskRoleConfigData rc : current) {
			if (!rolesDeclared) break;
			if (!rc.isActive() || names.contains(rc.getName().toLowerCase(java.util.Locale.ROOT))) continue;
			// Deactivated, never deleted: tasks and sign-offs point at the row (D6).
			rc.setActive(false);
			saveRoleConfigData(rc, wu);
			Change c = Change.of(DeclarativeKind.BOARD, rc.getName(), Action.ARCHIVE, List.of("active"),
					"absent from the board's file");
			warnStranded(bd, rc, c, alerts);
			roleChanges.add(c);
		}

		// A role may commission only a role that produces the report (task RD4-12); what the board had wrong before
		// the file stays the operator's and does not refuse it.
		List<String> commissionsAfter = new ArrayList<>(commissionProblems(rolesAfter, "board " + boardName));
		commissionsAfter.removeAll(commissionsBefore);
		for (String p : commissionsAfter) roleChanges.add(error(boardName, p));

		// ---- the board's own change, provenance and the record (D9) ----
		List<String> boardChanged = changedFields(boardBefore, boardState(bd));
		Change boardChange = creating
				? Change.of(DeclarativeKind.BOARD, boardName, Action.CREATE, boardChanged, "board")
				: boardChanged.isEmpty()
						? Change.of(DeclarativeKind.BOARD, boardName, Action.UNCHANGED, null, "board")
						: Change.of(DeclarativeKind.BOARD, boardName, Action.UPDATE, boardChanged, "board");
		result.add(boardChange);
		if (groupsDeclared) groupChanges(groupsBefore, groupsAfter, spec.getGroups()).forEach(result::add);
		roleChanges.forEach(result::add);
		boolean wrote = creating || !boardChanged.isEmpty()
				|| roleChanges.stream().anyMatch(c -> Action.UNCHANGED != c.getAction());
		if (wrote && result.getErrors() == 0) {
			DeclarativeProvenance prov = DeclarativeConfigService.provenance(result.getSpecHash(), source);
			bd.setDeclarative(prov);
			saveData(bd, wu);
			AgentActor by = null == actor ? AgentActor.system("declarative apply") : actor;
			postEvent(bd.getUuid(), AgentBoardData.BoardEventKind.INFO, "Board configuration applied from "
					+ describeSource(result.getSpecHash(), source), by, wu);
			for (String a : alerts) postEvent(bd.getUuid(), AgentBoardData.BoardEventKind.ALERT, a, by, wu);
		}
		return result;
	}

	private ApplyResult applyPresetWrites(UUID orgUuid, RolePresetsSpecDto spec, boolean dryRun, SourceDto source,
			WhoUpdated wu) throws RelizaException {
		ApplyResult result = new ApplyResult();
		result.setKind(DeclarativeKind.ROLE_PRESETS);
		result.setDryRun(dryRun);
		result.setSpecHash(DeclarativeConfigService.specHash(null != spec.getRaw() ? spec.getRaw() : spec));
		DeclarativeProvenance prov = DeclarativeConfigService.provenance(result.getSpecHash(), source);
		List<Change> problems = new ArrayList<>();
		List<ResolvedRole> presets = new ArrayList<>();
		Set<String> names = new java.util.HashSet<>();
		int position = 0;
		for (BoardRoleSpecDto r : spec.getPresets()) {
			position++;
			String name = null == r.getName() ? "" : r.getName().trim();
			if (name.isEmpty()) {
				problems.add(Change.of(DeclarativeKind.ROLE_PRESETS, "", Action.ERROR, null,
						"preset #" + position + " has no name"));
				continue;
			}
			if (!names.add(name.toLowerCase(java.util.Locale.ROOT))) {
				problems.add(Change.of(DeclarativeKind.ROLE_PRESETS, name, Action.ERROR, null, "declared more than once"));
				continue;
			}
			List<Change> own = new ArrayList<>();
			presets.add(resolveRole(orgUuid, r, own));
			own.forEach(c -> c.setKind(DeclarativeKind.ROLE_PRESETS));
			problems.addAll(own);
		}
		if (!problems.isEmpty()) {
			problems.forEach(result::add);
			return result;
		}
		List<AgentTaskRoleConfigData> current = listPresets(orgUuid);
		List<String> commissionsBefore = commissionProblems(current, "this organization's presets");
		List<AgentTaskRoleConfigData> presetsAfter = new ArrayList<>(current);
		position = 0;
		for (ResolvedRole rr : presets) {
			position++;
			String name = rr.entry().getName().trim();
			Optional<AgentTaskRoleConfigData> existing = current.stream()
					.filter(p -> p.getName().equalsIgnoreCase(name)).findFirst();
			AgentTaskRoleConfigData rc;
			if (existing.isPresent()) {
				rc = existing.get();
			} else {
				rc = new AgentTaskRoleConfigData();
				rc.setBoard(null);
				rc.setOrg(orgUuid);
				rc.setName(name);
			}
			Map<String, Object> before = existing.isPresent() ? roleState(rc) : Map.of();
			try {
				applyRoleEntry(orgUuid, rc, rr, existing.isEmpty() ? 10 * position : null, true);
			} catch (RelizaException e) {
				result.add(Change.of(DeclarativeKind.ROLE_PRESETS, name, Action.ERROR, null, e.getMessage()));
				continue;
			}
			if (existing.isEmpty()) presetsAfter.add(rc);
			List<String> changed = changedFields(before, roleState(rc));
			if (existing.isPresent() && changed.isEmpty()) {
				result.add(Change.of(DeclarativeKind.ROLE_PRESETS, name, Action.UNCHANGED, null, null));
				continue;
			}
			rc.setDeclarative(prov);
			saveRoleConfigData(rc, wu);
			result.add(Change.of(DeclarativeKind.ROLE_PRESETS, name,
					existing.isPresent() ? Action.UPDATE : Action.CREATE, changed, null));
		}
		if (Boolean.TRUE.equals(spec.getAuthoritative())) {
			for (AgentTaskRoleConfigData rc : current) {
				if (!rc.isActive() || names.contains(rc.getName().toLowerCase(java.util.Locale.ROOT))) continue;
				rc.setActive(false);
				rc.setDeclarative(prov);
				saveRoleConfigData(rc, wu);
				result.add(Change.of(DeclarativeKind.ROLE_PRESETS, rc.getName(), Action.ARCHIVE, List.of("active"),
						"absent from the authoritative presets file"));
			}
		}
		// Presets commission presets (task RD4-12), checked as the file leaves them.
		List<String> commissionsAfter = new ArrayList<>(commissionProblems(presetsAfter, "this organization's presets"));
		commissionsAfter.removeAll(commissionsBefore);
		for (String p : commissionsAfter) {
			result.add(Change.of(DeclarativeKind.ROLE_PRESETS, "", Action.ERROR, null, p));
		}
		return result;
	}

	private static Change error(String name, String message) {
		return Change.of(DeclarativeKind.BOARD, name, Action.ERROR, null, message);
	}

	/** Resolve one role entry's references: required-input components and override models (D11). */
	private ResolvedRole resolveRole(UUID orgUuid, BoardRoleSpecDto r, List<Change> problems) {
		String name = r.getName().trim();
		Declared d = new Declared(r.getDeclared());
		List<RequiredInput> inputs = null;
		if (d.has("requiredInputs", r.getRequiredInputs())) {
			inputs = new ArrayList<>();
			for (BoardRequiredInputSpecDto in : null == r.getRequiredInputs()
					? List.<BoardRequiredInputSpecDto>of() : r.getRequiredInputs()) {
				UUID comp = StringUtils.isBlank(in.getComponent()) ? null
						: componentNamed(orgUuid, in.getComponent(), "required input component", name, problems);
				inputs.add(new RequiredInput(in.getKind(), in.getSpecification(), in.getScope(), comp,
						in.getMinLifecycle(), in.getResolution()));
			}
		}
		List<AgentTaskRoleConfigData.ModelStrength> overrides = null;
		BoardStrengthSpecDto st = r.getStrength();
		if (d.hasNested("strength", "modelStrengths", st, null == st ? null : st.getModelStrengths())) {
			overrides = new ArrayList<>();
			for (BoardModelStrengthSpecDto o : null == st || null == st.getModelStrengths()
					? List.<BoardModelStrengthSpecDto>of() : st.getModelStrengths()) {
				Optional<ModelOntologyData> m = modelOntologyService.findByReference(orgUuid, o.getModel());
				if (m.isEmpty()) {
					problems.add(error(name, "model '" + o.getModel() + "' matches no model of this organization,"
							+ " or more than one; name it by its canonical id or as name-version"));
					continue;
				}
				overrides.add(new AgentTaskRoleConfigData.ModelStrength(m.get().getUuid(), o.getStrength()));
			}
		}
		return new ResolvedRole(r, d, inputs, overrides);
	}

	/**
	 * Apply one role entry onto a row, through the same rules as the role upsert. A field the entry
	 * leaves out keeps its value; one it declares null is cleared where D15 allows. Declaring a role
	 * makes it active unless the entry says otherwise: reactivating is declaring it again (D6).
	 */
	private void applyRoleEntry(UUID orgUuid, AgentTaskRoleConfigData rc, ResolvedRole rr, Integer defaultOrder,
			boolean fromFile) throws RelizaException {
		BoardRoleSpecDto r = rr.entry();
		Declared d = rr.declared();
		BoardStrengthSpecDto st = r.getStrength();
		StrengthSpec strength = null;
		boolean strengthDeclared = d.has("strength", st);
		if (strengthDeclared) {
			strength = new StrengthSpec(
					d.hasNested("strength", "requiredStrength", st, null == st ? null : st.getRequiredStrength())
							? Optional.ofNullable(null == st ? null : st.getRequiredStrength()) : null,
					d.hasNested("strength", "strengthHeadroom", st, null == st ? null : st.getStrengthHeadroom())
							? (null == st || null == st.getStrengthHeadroom() ? 0.0 : st.getStrengthHeadroom()) : null,
					d.hasNested("strength", "strengthCategory", st, null == st ? null : st.getStrengthCategory())
							? Optional.ofNullable(null == st ? null : st.getStrengthCategory()) : null,
					rr.modelStrengths());
			validateStrength(orgUuid, strength);
		}
		Integer order = d.has("orderIndex", r.getOrderIndex()) && null != r.getOrderIndex() ? r.getOrderIndex() : defaultOrder;
		Boolean active = d.has("active", r.getActive()) && null != r.getActive() ? r.getActive() : Boolean.TRUE;
		List<ProducedOutput> outputs = null;
		if (d.has("producesOutputs", r.getProducesOutputs())) {
			outputs = new ArrayList<>();
			for (BoardProducedOutputSpecDto o : null == r.getProducesOutputs()
					? List.<BoardProducedOutputSpecDto>of() : r.getProducesOutputs()) {
				outputs.add(new ProducedOutput(o.getSpecification(), o.getScope(), Boolean.TRUE.equals(o.getRequired())));
			}
		}
		RoleConfigSpec spec = new RoleConfigSpec(rc.getName(),
				d.has("prompt", r.getPrompt()) ? r.getPrompt() : null,
				order,
				d.has("wipLimit", r.getWipLimit()) ? r.getWipLimit() : null,
				d.has("requireDistinctAgent", r.getRequireDistinctAgent()) ? r.getRequireDistinctAgent() : null,
				active,
				d.has("requiredCapabilities", r.getRequiredCapabilities())
						? (null == r.getRequiredCapabilities() ? new ArrayList<>() : new ArrayList<>(r.getRequiredCapabilities()))
						: null,
				d.has("kind", r.getKind()) ? r.getKind() : null,
				d.has("necessity", r.getNecessity()) ? r.getNecessity() : null,
				d.has("humanGate", r.getHumanGate()) ? r.getHumanGate() : null,
				rr.requiredInputs(),
				outputs,
				strength,
				d.has("hopBudgetMicros", r.getHopBudgetMicros()) ? r.getHopBudgetMicros() : null,
				d.has("blindReview", r.getBlindReview()) ? r.getBlindReview() : null,
				d.has("commissions", r.getCommissions()) ? r.getCommissions() : null);
		applySpec(rc, spec, fromFile);
		// Declared null clears (D15). The upsert reads null as "leave it", so the clears are here.
		if (d.has("wipLimit", r.getWipLimit()) && null == r.getWipLimit()) rc.setWipLimit(null);
		if (d.has("hopBudgetMicros", r.getHopBudgetMicros()) && null == r.getHopBudgetMicros()) rc.setHopBudgetMicros(null);
		if (d.has("blindReview", r.getBlindReview()) && null == r.getBlindReview()) rc.setBlindReview(false);
		// Declared null, or naming no role, commissions nobody (task RD4-12).
		if (d.has("commissions", r.getCommissions()) && null == checkedCommissions(r.getCommissions())) {
			rc.setCommissions(null);
		}
	}

	/** Settings are written with the board, checked here first so a bad value refuses the file. */
	private void checkSettings(UUID orgUuid, String boardName, BoardSettingsSpecDto s, Declared d, List<Change> problems) {
		if (null == s) return;
		int levels = getOrganizationService.getOrganizationData(orgUuid)
				.map(OrganizationData::getSettings)
				.map(OrganizationData.Settings::getReviewItemPriorityLevels)
				.orElse(OrganizationData.DEFAULT_REVIEW_ITEM_PRIORITY_LEVELS);
		if (null != s.getBudgetMicros() && s.getBudgetMicros() < 0) problems.add(error(boardName, "settings.budgetMicros cannot be negative"));
		if (null != s.getSoftAlertPercent() && (s.getSoftAlertPercent() < 1 || s.getSoftAlertPercent() > 100)) {
			problems.add(error(boardName, "settings.softAlertPercent must be 1..100"));
		}
		if (null != s.getCycleCap() && s.getCycleCap() < 1) problems.add(error(boardName, "settings.cycleCap must be at least 1"));
		if (null != s.getNoProgressRepeatsToStop() && s.getNoProgressRepeatsToStop() < 1) {
			problems.add(error(boardName, "settings.noProgressRepeatsToStop must be at least 1"));
		}
		if (null != s.getHumanQueueAgeMinutes() && s.getHumanQueueAgeMinutes() < 0) {
			problems.add(error(boardName, "settings.humanQueueAgeMinutes cannot be negative (0 is off)"));
		}
		if (null != s.getEventRetentionDays() && s.getEventRetentionDays() < 0) {
			problems.add(error(boardName, "settings.eventRetentionDays cannot be negative (0 keeps everything)"));
		}
		for (String p : stalenessProblems(s.getStaleness())) problems.add(error(boardName, p));
		for (String p : ladderProblems(s.getLadder())) problems.add(error(boardName, p));
		if (d.hasNested("settings", "ladder", s, s.getLadder())) {
			String stranded = levelsStrandedBy(orgUuid, boardName, s.getLadder());
			if (null != stranded) problems.add(error(boardName, stranded));
		}
		for (Integer p : java.util.Arrays.asList(s.getBlockingPriority(), s.getCompletionPriority())) {
			if (null != p && (p < 1 || p > levels)) {
				problems.add(error(boardName, "a priority threshold of " + p + " is outside this organization's 1.."
						+ levels + " levels"));
			}
		}
	}

	/** Write the declared board fields onto the in-memory board; sources before the documents repository. */
	private void applyBoardFields(AgentBoardData bd, BoardSpecDto spec, Declared d, UUID target, List<String> sources,
			WhoUpdated wu) throws RelizaException {
		if (d.has("description", spec.getDescription())) bd.setDescription(spec.getDescription());
		if (null != target) bd.setTarget(target);
		if (null != sources) bd.setSources(sources);
		if (d.has("priorityType", spec.getPriorityType()) && null != spec.getPriorityType()) bd.setPriorityType(spec.getPriorityType());
		if (d.has("perAgentWipLimit", spec.getPerAgentWipLimit()) && null != spec.getPerAgentWipLimit()) {
			bd.setPerAgentWipLimit(spec.getPerAgentWipLimit());
		}
		if (d.has("defaultWorkLevel", spec.getDefaultWorkLevel()) && null != spec.getDefaultWorkLevel()) {
			bd.setDefaultWorkLevel(spec.getDefaultWorkLevel());
		}
		if (d.has("defaultInputResolution", spec.getDefaultInputResolution()) && null != spec.getDefaultInputResolution()) {
			bd.setDefaultInputResolution(spec.getDefaultInputResolution());
		}
		if (d.has("coordinatorPrompt", spec.getCoordinatorPrompt()) && null != spec.getCoordinatorPrompt()) {
			bd.setCoordinatorPrompt(spec.getCoordinatorPrompt());
		}
		// Declared null clears, as [] does; absent leaves it (D15). Validated with the file's checks.
		if (d.has("coordinatorCapabilities", spec.getCoordinatorCapabilities())) {
			bd.setCoordinatorCapabilities(coordinatorCapabilities(spec.getCoordinatorCapabilities()));
		}
		if (d.has("documentsRepo", spec.getDocumentsRepo()) && null != spec.getDocumentsRepo()) {
			bd.setDocumentsRepo(documentsRepositoryFor(bd, spec.getDocumentsRepo(), wu));
		}
		if (d.has("documentPaths", spec.getDocumentPaths())) {
			bd.setDocumentPaths(null == spec.getDocumentPaths() ? new LinkedHashMap<>()
					: AgentBoardData.normaliseDocumentPaths(new LinkedHashMap<>(spec.getDocumentPaths())));
		}
		if (d.has("elementFamilies", spec.getElementFamilies())) {
			DeclaredFamilies families = elementFamilies(spec.getElementFamilies());
			bd.setElementFamilies(families.names());
			bd.setElementFamilyDefinedIn(families.definedIn());
		}
		if (d.has("elementChecks", spec.getElementChecks())) {
			bd.setElementCheckPolicy(elementCheckPolicy(spec.getElementChecks()));
		}
		if (d.has("delivery", spec.getDelivery())) {
			bd.setDeliveryPolicy(normalised(spec.getDelivery()));
		}
		DocumentsSpecDto docs = spec.getDocuments();
		boolean prefixDeclared = d.hasNested("documents", "prefix", docs, null == docs ? null : docs.getPrefix());
		boolean sharedDeclared = d.hasNested("documents", "shared", docs, null == docs ? null : docs.getShared());
		boolean rootDeclared = d.hasNested("documents", "root", docs, null == docs ? null : docs.getRoot());
		if (prefixDeclared || sharedDeclared || rootDeclared) {
			bd.setDocuments(documentsConfig(bd.getDocuments(), prefixDeclared, null == docs ? null : docs.getPrefix(),
					sharedDeclared, null == docs ? null : docs.getShared(), rootDeclared, null == docs ? null : docs.getRoot()));
		}
		// Declared null derives a prefix anew; a value is claimed. Either way the old keys stay (D10).
		if (d.has("taskPrefix", spec.getTaskPrefix())) {
			applyTaskPrefix(bd, null == spec.getTaskPrefix() ? null : normaliseTaskPrefix(spec.getTaskPrefix()), wu);
		}
		applySettings(bd, spec.getSettings(), d);
	}

	/**
	 * A board's settings from a spec's settings block, with its presence rules (D15). One method for
	 * the board file and the board form (task 40f270be), so the two cannot apply them differently.
	 */
	private static void applySettings(AgentBoardData bd, BoardSettingsSpecDto s, Declared d) {
		if (d.hasNested("settings", "budgetMicros", s, null == s ? null : s.getBudgetMicros())) bd.setBudgetMicros(null == s ? null : s.getBudgetMicros());
		if (d.hasNested("settings", "softAlertPercent", s, null == s ? null : s.getSoftAlertPercent())) bd.setSoftAlertPercent(null == s ? null : s.getSoftAlertPercent());
		if (d.hasNested("settings", "cycleCap", s, null == s ? null : s.getCycleCap())) bd.setCycleCap(null == s ? null : s.getCycleCap());
		if (d.hasNested("settings", "noProgressRepeatsToStop", s, null == s ? null : s.getNoProgressRepeatsToStop())) {
			bd.setNoProgressRepeatsToStop(null == s ? null : s.getNoProgressRepeatsToStop());
		}
		if (d.hasNested("settings", "blockingPriority", s, null == s ? null : s.getBlockingPriority())) bd.setBlockingPriority(null == s ? null : s.getBlockingPriority());
		if (d.hasNested("settings", "completionPriority", s, null == s ? null : s.getCompletionPriority())) {
			bd.setCompletionPriority(null == s ? null : s.getCompletionPriority());
		}
		if (d.hasNested("settings", "humanQueueAgeMinutes", s, null == s ? null : s.getHumanQueueAgeMinutes())) {
			bd.setHumanQueueAgeMinutes(null == s ? null : s.getHumanQueueAgeMinutes());
		}
		if (d.hasNested("settings", "coordinatorStopLift", s, null == s ? null : s.getCoordinatorStopLift())) {
			bd.setCoordinatorStopLift(null == s ? null : s.getCoordinatorStopLift());
		}
		if (d.hasNested("settings", "eventRetentionDays", s, null == s ? null : s.getEventRetentionDays())) {
			bd.setEventRetentionDays(null == s ? null : s.getEventRetentionDays());
		}
		if (d.hasNested("settings", "staleness", s, null == s ? null : s.getStaleness())) {
			AgentBoardData.Staleness st = null == s ? null : s.getStaleness();
			bd.setStaleness(null == st || st.isEmpty() ? null : st);
		}
		if (d.hasNested("settings", "ladder", s, null == s ? null : s.getLadder())) {
			bd.setLadder(normalisedLadder(null == s ? null : s.getLadder()));
		}
	}

	/** The ladder a board file leaves in effect: the one it declares, else the board's (task RD3-6). */
	private static AgentBoardData.Ladder ladderAfter(BoardSpecDto spec, Declared d, AgentBoardData.Ladder current) {
		BoardSettingsSpecDto s = spec.getSettings();
		return d.hasNested("settings", "ladder", s, null == s ? null : s.getLadder())
				? normalisedLadder(null == s ? null : s.getLadder()) : current;
	}

	/** The most rungs a ladder may have: the levels a task may carry (RD2-1). */
	public static final int MAX_LADDER_RUNGS = AgentTaskService.MAX_LEVEL + 1;

	/**
	 * What is wrong with a ladder (task RD3-6): each rung named, no name twice, at most
	 * {@link #MAX_LADDER_RUNGS}; a rung's number, when given, is its place from 0 without gaps.
	 */
	static List<String> ladderProblems(AgentBoardData.Ladder ladder) {
		List<String> out = new ArrayList<>();
		if (null == ladder || ladder.isEmpty()) return out;
		List<AgentBoardData.LadderLevel> levels = ladder.levels();
		if (levels.size() > MAX_LADDER_RUNGS) out.add("settings.ladder has " + levels.size() + " levels; at most " + MAX_LADDER_RUNGS);
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < levels.size(); i++) {
			AgentBoardData.LadderLevel l = levels.get(i);
			if (null == l || StringUtils.isBlank(l.name())) {
				out.add("settings.ladder level " + i + " needs a name");
				continue;
			}
			if (null != l.number() && l.number() != i) {
				out.add("settings.ladder levels are numbered from 0 without gaps: '" + l.name().strip() + "' is number " + l.number()
						+ " in place " + i);
			}
			if (!seen.add(l.name().strip().toLowerCase(java.util.Locale.ROOT))) {
				out.add("settings.ladder names level '" + l.name().strip() + "' twice");
			}
		}
		return out;
	}

	/** The ladder as stored: each rung numbered by its place, names and descriptions trimmed; empty is none. */
	static AgentBoardData.Ladder normalisedLadder(AgentBoardData.Ladder ladder) {
		if (null == ladder || ladder.isEmpty()) return null;
		List<AgentBoardData.LadderLevel> levels = new ArrayList<>();
		for (int i = 0; i < ladder.levels().size(); i++) {
			AgentBoardData.LadderLevel l = ladder.levels().get(i);
			levels.add(new AgentBoardData.LadderLevel(i, l.name().strip(),
					StringUtils.isBlank(l.description()) ? null : l.description().strip()));
		}
		return new AgentBoardData.Ladder(levels, StringUtils.isBlank(ladder.prompt()) ? null : ladder.prompt());
	}

	/**
	 * Why a new ladder -- or none -- would leave tasks at a level it does not have (task RD3-6): removing a
	 * ladder, or shortening it, is refused while a task, or a group's default, sits above its last rung. Null when
	 * nothing is stranded, or the board does not exist yet.
	 */
	private String levelsStrandedBy(UUID orgUuid, String boardName, AgentBoardData.Ladder next) {
		if (null == orgUuid || null == boardName) return null;
		Optional<AgentBoard> row = repository.findByOrgAndName(orgUuid.toString(), boardName.trim());
		if (row.isEmpty()) return null;
		AgentBoardData bd = AgentBoardData.dataFromRecord(row.get());
		int rungs = null == next || next.isEmpty() ? 0 : next.levels().size();
		List<String> stranded = new ArrayList<>();
		for (AgentTask t : taskRepository.findByBoard(bd.getUuid().toString())) {
			AgentTaskData td = AgentTaskData.dataFromRecord(t);
			if (null != td.getWorkLevel() && td.getWorkLevel() >= rungs) stranded.add(td.label() + " (level " + td.getWorkLevel() + ")");
		}
		if (null != bd.getGroups()) {
			for (AgentBoardData.TaskGroup g : bd.getGroups()) {
				if (null != g.defaultWorkLevel() && g.defaultWorkLevel() >= rungs) stranded.add("group " + g.key() + " (default level " + g.defaultWorkLevel() + ")");
			}
		}
		if (stranded.isEmpty()) return null;
		return (0 == rungs ? "settings.ladder cannot be removed" : "settings.ladder cannot drop below " + rungs + " level(s)")
				+ " while " + stranded.size() + " task(s) or group(s) carry a level it does not have: "
				+ String.join(", ", stranded.subList(0, Math.min(10, stranded.size()))) + (stranded.size() > 10 ? ", ..." : "")
				+ "; clear the levels first";
	}

	/**
	 * What is wrong with a staleness block (task RD3-4): every threshold is a whole number of minutes,
	 * at least 1; null is off, and 0 is refused rather than read as off, so a typo cannot silently
	 * disable a rule.
	 */
	static List<String> stalenessProblems(AgentBoardData.Staleness st) {
		List<String> out = new ArrayList<>();
		if (null == st) return out;
		Map<String, Integer> knobs = new LinkedHashMap<>();
		knobs.put("roleUnstaffedMinutes", st.roleUnstaffedMinutes());
		knobs.put("hopNoProgressMinutes", st.hopNoProgressMinutes());
		knobs.put("deliveryStuckMinutes", st.deliveryStuckMinutes());
		knobs.put("seatSilentMinutes", st.seatSilentMinutes());
		knobs.put("repeatMinutes", st.repeatMinutes());
		knobs.forEach((k, v) -> {
			if (null != v && v < 1) out.add("settings.staleness." + k + " must be at least 1 minute; leave it out (null) to turn it off");
		});
		// Minutes past a deadline (task RD4-12): 0 alerts as soon as it passes.
		if (null != st.investigationOverdueMinutes() && st.investigationOverdueMinutes() < 0) {
			out.add("settings.staleness.investigationOverdueMinutes cannot be negative (0 alerts at the deadline);"
					+ " leave it out (null) to turn it off");
		}
		return out;
	}

	/**
	 * The board form's settings (task 40f270be): the input a board file's settings block takes, with
	 * the same presence semantics and the same checks. Null leaves every setting alone.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData updateSettingsFromInput(UUID boardUuid, Map<String, Object> settings, WhoUpdated wu)
			throws RelizaException {
		AgentBoard b = repository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		AgentBoardData bd = AgentBoardData.dataFromRecord(b);
		if (null == settings) return bd;
		SettingsInput in = settingsFromInput(bd.getOrg(), bd.getName(), settings);
		applySettings(bd, in.settings(), in.declared());
		return saveData(bd, wu);
	}

	/**
	 * The form's settings checked on their own, before the form's save writes anything else: a
	 * refused setting must not leave the description saved or the board created without its
	 * settings (T-2). Throws the messages a board file gets.
	 */
	public void checkSettingsInput(UUID orgUuid, String boardName, Map<String, Object> settings)
			throws RelizaException {
		if (null != settings) settingsFromInput(orgUuid, boardName, settings);
	}

	private record SettingsInput(BoardSettingsSpecDto settings, Declared declared) {}

	private SettingsInput settingsFromInput(UUID orgUuid, String boardName, Map<String, Object> settings)
			throws RelizaException {
		BoardSettingsSpecDto s = readSpec(settings, BoardSettingsSpecDto.class);
		Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("settings", settings);
		Declared d = new Declared(declaredKeys(raw, "settings"));
		List<Change> problems = new ArrayList<>();
		checkSettings(orgUuid, boardName, s, d, problems);
		if (!problems.isEmpty()) {
			throw new RelizaException(problems.stream().map(Change::getMessage).collect(Collectors.joining("; ")));
		}
		return new SettingsInput(s, d);
	}

	/** A role's or a preset's hop allowance removed: the form sent hopBudgetMicros as null. */
	public AgentTaskRoleConfigData clearHopBudget(AgentTaskRoleConfigData rc, WhoUpdated wu) {
		rc.setHopBudgetMicros(null);
		return saveRoleConfigData(rc, wu);
	}

	/** An active component or product of the org by exact name; none or several is a problem. */
	private UUID componentNamed(UUID orgUuid, String name, String what, String entity, List<Change> problems) {
		List<ComponentData> found = declarativeConfigService.activeComponentsNamed(orgUuid, name);
		if (found.size() == 1) return found.get(0).getUuid();
		problems.add(error(entity, what + " '" + name + "' " + (found.isEmpty()
				? "is not an active component of this organization"
				: "names " + found.size() + " components; rename one so the file can say which")));
		return null;
	}

	/**
	 * Tasks a deactivated role leaves waiting: QUEUED for it or ASSIGNED in it. Not a refusal --
	 * the file is the contract -- but said on the change and, when applied, on the board (D16).
	 */
	private void warnStranded(AgentBoardData bd, AgentTaskRoleConfigData rc, Change c, List<String> alerts) {
		List<String> waiting = new ArrayList<>();
		for (String status : List.of("QUEUED", "ASSIGNED")) {
			for (AgentTask t : taskRepository.findByBoardAndStatus(bd.getUuid().toString(), status)) {
				AgentTaskData td = AgentTaskData.dataFromRecord(t);
				boolean ofRole = (null != td.getRoleUuid() && td.getRoleUuid().equals(rc.getUuid()))
						|| (null != td.getRole() && td.getRole().equalsIgnoreCase(rc.getName()));
				if (!ofRole) continue;
				waiting.add(td.label() + " (" + status.toLowerCase(java.util.Locale.ROOT) + ")");
			}
		}
		if (waiting.isEmpty()) return;
		String text = "Role " + rc.getName() + " is deactivated while " + waiting.size() + " task(s) wait on it: "
				+ String.join(", ", waiting) + ". Nothing will serve them there; authorize them for an active role.";
		c.getWarnings().add(text);
		alerts.add(text);
	}

	private static String describeSource(String specHash, SourceDto source) {
		String hash = null == specHash ? "?" : specHash.substring(0, Math.min(12, specHash.length()));
		if (null == source || (StringUtils.isBlank(source.getRepo()) && StringUtils.isBlank(source.getPath()))) {
			return "a file (spec " + hash + ")";
		}
		return StringUtils.defaultString(source.getRepo())
				+ (StringUtils.isNotBlank(source.getCommit()) ? "@" + source.getCommit() : "")
				+ (StringUtils.isNotBlank(source.getPath()) ? " " + source.getPath() : "")
				+ " (spec " + hash + ")";
	}

	/** The board fields a file configures, as comparable values: missing, null and empty agree. */
	private static Map<String, Object> boardState(AgentBoardData bd) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("description", bd.getDescription());
		m.put("target", bd.getTarget());
		m.put("sources", bd.getSources());
		m.put("documentsRepo", bd.getDocumentsRepo());
		m.put("documentPaths", bd.getDocumentPaths());
		m.put("elementFamilies", bd.getDeclaredElementFamilies());
		m.put("elementChecks", bd.getElementCheckPolicy());
		m.put("delivery", bd.getDeliveryPolicy());
		m.put("documents.prefix", null == bd.getDocuments() ? null : bd.getDocuments().prefix());
		m.put("documents.shared", null == bd.getDocuments() ? null : bd.getDocuments().shared());
		m.put("documents.root", null == bd.getDocuments() ? null : bd.getDocuments().root());
		m.put("perspectives", bd.getPerspectives());
		m.put("groups", groupsState(bd.getGroups()));
		m.put("taskPrefix", bd.getTaskPrefix());
		m.put("priorityType", bd.getPriorityType());
		m.put("perAgentWipLimit", bd.getPerAgentWipLimit());
		m.put("defaultWorkLevel", bd.getDefaultWorkLevel());
		m.put("defaultInputResolution", bd.getDefaultInputResolution());
		m.put("coordinatorPrompt", bd.getCoordinatorPrompt());
		m.put("coordinatorCapabilities", bd.getCoordinatorCapabilities());
		m.put("settings.budgetMicros", bd.getBudgetMicros());
		m.put("settings.softAlertPercent", bd.getSoftAlertPercent());
		m.put("settings.cycleCap", bd.getCycleCap());
		m.put("settings.noProgressRepeatsToStop", bd.getNoProgressRepeatsToStop());
		m.put("settings.blockingPriority", bd.getBlockingPriority());
		m.put("settings.humanQueueAgeMinutes", bd.getHumanQueueAgeMinutes());
		m.put("settings.completionPriority", bd.getCompletionPriority());
		m.put("settings.coordinatorStopLift", bd.getCoordinatorStopLift());
		m.put("settings.eventRetentionDays", bd.getEventRetentionDays());
		m.put("settings.staleness", bd.getStaleness());
		m.put("settings.ladder", bd.getLadder());
		return m;
	}

	/** The groups as a file configures them, in display order, dependencies by key: the order counts, its numbers do not. */
	private static List<Map<String, Object>> groupsState(List<AgentBoardData.TaskGroup> groups) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (null == groups) return out;
		Map<UUID, String> keyOf = new java.util.HashMap<>();
		groups.forEach(g -> keyOf.put(g.uuid(), g.key()));
		for (AgentBoardData.TaskGroup g : AgentTaskGroupService.sortedByOrder(groups)) out.add(groupState(g, keyOf));
		return out;
	}

	private static Map<String, Object> groupState(AgentBoardData.TaskGroup g, Map<UUID, String> keyOf) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("key", g.key());
		m.put("name", g.name());
		m.put("description", g.description());
		m.put("dependsOn", g.dependsOn().stream().map(u -> keyOf.getOrDefault(u, u.toString())).toList());
		m.put("defaultWorkLevel", g.defaultWorkLevel());
		m.put("status", g.status());
		return m;
	}

	/**
	 * One change per group a file's list touches (task RD2-30): created, updated with the fields that
	 * changed, unchanged, or archived -- closed because the list leaves it out, or deleted by a cleared list.
	 */
	private static List<Change> groupChanges(List<AgentBoardData.TaskGroup> before, List<AgentBoardData.TaskGroup> after,
			List<BoardGroupSpecDto> entries) {
		java.util.Set<String> listed = new java.util.HashSet<>();
		if (null != entries) {
			entries.stream().filter(java.util.Objects::nonNull).map(BoardGroupSpecDto::getKey)
					.filter(java.util.Objects::nonNull)
					.forEach(k -> listed.add(k.strip().toLowerCase(java.util.Locale.ROOT)));
		}
		Map<UUID, String> keyOf = new java.util.HashMap<>();
		before.forEach(g -> keyOf.put(g.uuid(), g.key()));
		after.forEach(g -> keyOf.put(g.uuid(), g.key()));
		Map<UUID, AgentBoardData.TaskGroup> was = new java.util.HashMap<>();
		before.forEach(g -> was.put(g.uuid(), g));
		java.util.Set<UUID> kept = new java.util.HashSet<>();
		List<Change> out = new ArrayList<>();
		for (AgentBoardData.TaskGroup g : AgentTaskGroupService.sortedByOrder(after)) {
			kept.add(g.uuid());
			AgentBoardData.TaskGroup w = was.get(g.uuid());
			if (null == w) {
				out.add(Change.of(DeclarativeKind.BOARD, g.key(), Action.CREATE,
						changedFields(Map.of(), groupState(g, keyOf)), "group"));
				continue;
			}
			List<String> changed = changedFields(groupState(w, keyOf), groupState(g, keyOf));
			if (changed.isEmpty()) {
				out.add(Change.of(DeclarativeKind.BOARD, g.key(), Action.UNCHANGED, null, "group"));
			} else if (!listed.contains(g.key())) {
				out.add(Change.of(DeclarativeKind.BOARD, g.key(), Action.ARCHIVE, changed,
						"group absent from the board's file; closed, not deleted"));
			} else {
				out.add(Change.of(DeclarativeKind.BOARD, g.key(), Action.UPDATE, changed, "group"));
			}
		}
		for (AgentBoardData.TaskGroup g : AgentTaskGroupService.sortedByOrder(before)) {
			if (kept.contains(g.uuid())) continue;
			out.add(Change.of(DeclarativeKind.BOARD, g.key(), Action.ARCHIVE, null,
					"group deleted: the file clears the board's groups"));
		}
		return out;
	}

	/** The groups as a file writes them (task RD2-30): in display order, dependencies by key, empty members left out, no state. */
	static List<BoardGroupSpecDto> groupSpecs(List<AgentBoardData.TaskGroup> groups) {
		Map<UUID, String> keyOf = new java.util.HashMap<>();
		groups.forEach(g -> keyOf.put(g.uuid(), g.key()));
		List<BoardGroupSpecDto> out = new ArrayList<>();
		for (AgentBoardData.TaskGroup g : AgentTaskGroupService.sortedByOrder(groups)) {
			BoardGroupSpecDto e = new BoardGroupSpecDto();
			e.setKey(g.key());
			e.setName(StringUtils.isBlank(g.name()) ? null : g.name());
			e.setDescription(StringUtils.isBlank(g.description()) ? null : g.description());
			if (!g.dependsOn().isEmpty()) {
				e.setDependsOn(g.dependsOn().stream().map(u -> keyOf.getOrDefault(u, u.toString())).toList());
			}
			e.setDefaultWorkLevel(g.defaultWorkLevel());
			e.setStatus(g.status());
			out.add(e);
		}
		return out;
	}

	/**
	 * The board form's group list (AgentBoardInput.groups, task RD2-30), with a board file's rules:
	 * null or empty deletes every group, refused while one holds tasks; a listed group is created or
	 * updated -- by uuid when it carries one, so the form can rename it -- and one left out is closed.
	 * Refused whole, naming every problem.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public AgentBoardData setGroupsFromInput(UUID boardUuid, List<Map<String, Object>> input, WhoUpdated wu)
			throws RelizaException {
		List<BoardGroupSpecDto> entries = null;
		if (null != input) {
			entries = new ArrayList<>();
			for (Map<String, Object> m : input) {
				if (null == m) continue;
				Map<String, Object> fields = new LinkedHashMap<>(m);
				Object uuid = fields.remove("uuid");
				Object order = fields.remove("order");
				BoardGroupSpecDto e = readSpec(fields, BoardGroupSpecDto.class);
				e.setDeclared(declaredKeys(fields));
				if (null != uuid) e.setUuid(parseGroupUuid(String.valueOf(uuid)));
				if (order instanceof Number n) e.setOrder(n.intValue());
				entries.add(e);
			}
		}
		AgentBoardData bd = lockBoard(boardUuid);
		List<String> problems = new ArrayList<>();
		List<AgentBoardData.TaskGroup> after = AgentTaskGroupService.groupsAfter(bd.getGroups(), entries,
				() -> tasksPerGroup(boardUuid), bd.getLadder(), problems);
		if (!problems.isEmpty()) throw new RelizaException(String.join("; ", problems));
		bd.setGroups(new ArrayList<>(after));
		return saveData(bd, wu);
	}

	private static UUID parseGroupUuid(String raw) throws RelizaException {
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			throw new RelizaException("A group uuid is a uuid (got '" + raw + "')");
		}
	}

	/** Tasks per group of a board; none for a board not yet created. */
	private Map<UUID, Long> tasksPerGroup(UUID boardUuid) {
		Map<UUID, Long> out = new java.util.HashMap<>();
		if (null == boardUuid) return out;
		for (AgentTask t : taskRepository.findByBoard(boardUuid.toString())) {
			UUID g = AgentTaskData.dataFromRecord(t).getGroup();
			if (null != g) out.merge(g, 1L, Long::sum);
		}
		return out;
	}

	/** The role fields a file configures, as comparable values. */
	private static Map<String, Object> roleState(AgentTaskRoleConfigData rc) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("prompt", rc.getPrompt());
		m.put("orderIndex", rc.getOrderIndex());
		m.put("wipLimit", rc.getWipLimit());
		m.put("requireDistinctAgent", rc.isRequireDistinctAgent());
		m.put("active", rc.isActive());
		m.put("kind", rc.getKind());
		m.put("necessity", rc.getNecessity());
		m.put("humanGate", rc.getHumanGate());
		m.put("requiredCapabilities", rc.getRequiredCapabilities());
		m.put("requiredInputs", rc.getRequiredInputs());
		m.put("producesOutputs", rc.getProducesOutputs());
		m.put("hopBudgetMicros", rc.getHopBudgetMicros());
		m.put("blindReview", rc.isBlindReview());
		m.put("commissions", rc.getCommissions());
		m.put("strength.requiredStrength", rc.getRequiredStrength());
		m.put("strength.strengthHeadroom", rc.getStrengthHeadroom());
		m.put("strength.strengthCategory", rc.getStrengthCategory());
		m.put("strength.modelStrengths", rc.getModelStrengths());
		return m;
	}

	/** Keys whose values differ, treating a missing key, null, an empty list and an empty map as one. */
	private static List<String> changedFields(Map<String, Object> before, Map<String, Object> after) {
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, Object> e : after.entrySet()) {
			Object a = emptyToNull(e.getValue());
			Object b = emptyToNull(before.get(e.getKey()));
			if (!java.util.Objects.equals(a, b)) out.add(e.getKey());
		}
		return out;
	}

	private static Object emptyToNull(Object v) {
		if (v instanceof java.util.Collection<?> c && c.isEmpty()) return null;
		if (v instanceof Map<?, ?> m && m.isEmpty()) return null;
		if (v instanceof java.util.Collection<?> c) return new ArrayList<>(c);
		return v;
	}

	/** The board as a spec: configuration only, roles in declaration order. */
	public BoardSpecDto exportBoard(UUID boardUuid) throws RelizaException {
		AgentBoardData bd = getBoardData(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		BoardSpecDto spec = new BoardSpecDto();
		spec.setName(bd.getName());
		spec.setDescription(bd.getDescription());
		spec.setTarget(componentName(bd.getTarget()));
		spec.setDefaultWorkLevel(bd.getDefaultWorkLevel());
		spec.setDefaultInputResolution(bd.getDefaultInputResolution());
		spec.setPriorityType(bd.getPriorityType());
		spec.setPerAgentWipLimit(bd.getPerAgentWipLimit());
		if (bd.getSources() != null) spec.setSources(new LinkedList<>(bd.getSources()));
		if (null != bd.getDocumentsRepo()) {
			// Export the URI the row holds. A spec that carried the uuid would be unusable
			// anywhere but this org, and exportBoard already takes this trade for target.
			spec.setDocumentsRepo(vcsRepositoryService.getVcsRepositoryData(bd.getDocumentsRepo())
					.map(VcsRepositoryData::getUri).orElse(null));
		}
		if (bd.getDocumentPaths() != null) spec.setDocumentPaths(new LinkedHashMap<>(bd.getDocumentPaths()));
		if (bd.getDeclaredElementFamilies() != null) spec.setElementFamilies(bd.getDeclaredElementFamilies());
		if (bd.getElementCheckPolicy() != null) spec.setElementChecks(bd.getElementCheckPolicy());
		if (bd.getDeliveryPolicy() != null) spec.setDelivery(bd.getDeliveryPolicy());
		if (null != bd.getPerspectives() && !bd.getPerspectives().isEmpty()) {
			spec.setPerspectives(boardPerspectiveService.entries(bd));
		}
		spec.setTaskPrefix(bd.getTaskPrefix());
		if (null != bd.getDocuments() && !bd.getDocuments().isEmpty()) {
			// Only the members the board sets, so the file round-trips what was declared.
			DocumentsSpecDto docs = new DocumentsSpecDto();
			docs.setPrefix(bd.getDocuments().prefix());
			docs.setShared(bd.getDocuments().shared());
			docs.setRoot(bd.getDocuments().root());
			spec.setDocuments(docs);
		}
		spec.setCoordinatorPrompt(bd.getCoordinatorPrompt());
		if (null != bd.getCoordinatorCapabilities() && !bd.getCoordinatorCapabilities().isEmpty()) {
			spec.setCoordinatorCapabilities(new ArrayList<>(bd.getCoordinatorCapabilities()));
		}
		BoardSettingsSpecDto settings = new BoardSettingsSpecDto();
		settings.setBudgetMicros(bd.getBudgetMicros());
		settings.setSoftAlertPercent(bd.getSoftAlertPercent());
		settings.setCycleCap(bd.getCycleCap());
		settings.setNoProgressRepeatsToStop(bd.getNoProgressRepeatsToStop());
		settings.setBlockingPriority(bd.getBlockingPriority());
		settings.setCompletionPriority(bd.getCompletionPriority());
		settings.setHumanQueueAgeMinutes(bd.getHumanQueueAgeMinutes());
		settings.setCoordinatorStopLift(bd.getCoordinatorStopLift());
		settings.setEventRetentionDays(bd.getEventRetentionDays());
		settings.setStaleness(bd.getStaleness());
		settings.setLadder(bd.getLadder());
		spec.setSettings(settings);
		if (null != bd.getGroups() && !bd.getGroups().isEmpty()) spec.setGroups(groupSpecs(bd.getGroups()));
		for (AgentTaskRoleConfigData rc : listRoleConfigs(boardUuid)) {
			spec.getRoles().add(roleSpecOf(rc));
		}
		return spec;
	}

	/**
	 * A board of the organization as a board file, by name or uuid (declarative-boards §3.3). What
	 * the declarative export uses: a board of another organization is not found, the same as one
	 * that does not exist.
	 */
	public BoardSpecDto exportBoardOfOrg(UUID orgUuid, String board) throws RelizaException {
		return exportBoardOfOrg(orgUuid, board, BoardAccess.ALL);
	}

	/** As above for a caller that must read the board and its configuration (architecture d8e7bd7e §3.3). */
	public BoardSpecDto exportBoardOfOrg(UUID orgUuid, String board, BoardAccess access) throws RelizaException {
		return exportBoard(readableBoardOfOrg(orgUuid, board, access).getUuid());
	}

	/** A board of the organization by name or uuid that the caller may read as a board file. */
	private AgentBoardData readableBoardOfOrg(UUID orgUuid, String board, BoardAccess access) throws RelizaException {
		AgentBoardData bd = boardOfOrg(orgUuid, board);
		// A board the caller holds nothing on is not found, word for word, by name or uuid: the
		// refusal below names the board and would confirm it.
		if (!access.sees(bd.getUuid())) throw boardNotFound(board);
		if (!access.onBoard(bd.getUuid(), CallType.ESSENTIAL_READ, PermissionFunction.BOARD_READ,
				PermissionFunction.CONFIGURATION_READ)) {
			throw new RelizaException("Reading board " + bd.getName() + " as a file needs BOARD_READ and"
					+ " CONFIGURATION_READ on it");
		}
		return bd;
	}

	/**
	 * A board's perspectives as the server holds them, for the export's reader (task b9115d09, round
	 * 3): uuid, name and product, in the board's order. The export writes a shared name as its uuid,
	 * so a client keeping a configured form maps it through these, under the export's own gate.
	 */
	public List<BoardPerspectiveService.BoardPerspective> exportBoardPerspectivesOfOrg(UUID orgUuid, String board)
			throws RelizaException {
		return exportBoardPerspectivesOfOrg(orgUuid, board, BoardAccess.ALL);
	}

	/** As above for a caller that must read the board and its configuration. */
	public List<BoardPerspectiveService.BoardPerspective> exportBoardPerspectivesOfOrg(UUID orgUuid, String board,
			BoardAccess access) throws RelizaException {
		return boardPerspectiveService.held(readableBoardOfOrg(orgUuid, board, access));
	}

	/** A board of the organization by name, else by uuid; not found otherwise. */
	private AgentBoardData boardOfOrg(UUID orgUuid, String board) throws RelizaException {
		String ref = null == board ? "" : board.trim();
		Optional<AgentBoard> byName = repository.findByOrgAndName(orgUuid.toString(), ref);
		if (byName.isPresent()) return AgentBoardData.dataFromRecord(byName.get());
		try {
			UUID uuid = UUID.fromString(ref);
			Optional<AgentBoardData> bd = getBoardData(uuid);
			if (bd.isPresent() && orgUuid.equals(bd.get().getOrg())) return bd.get();
		} catch (IllegalArgumentException notAUuid) {
			// a name that matched nothing: not found below
		}
		throw boardNotFound(board);
	}

	/** The export's answer for a board that is not there, or that the caller may not know is. */
	private static RelizaException boardNotFound(String board) {
		return new RelizaException("Board " + (null == board ? "" : board.trim()) + " not found");
	}

	/** A presets file of the organization's presets, as they are (declarative-boards §3.3). */
	public RolePresetsSpecDto exportRolePresets(UUID orgUuid) {
		RolePresetsSpecDto spec = new RolePresetsSpecDto();
		spec.setAuthoritative(false);
		for (AgentTaskRoleConfigData rc : listPresets(orgUuid)) spec.getPresets().add(roleSpecOf(rc));
		return spec;
	}

	/** One role or preset in portable form: components and models by reference, not uuid. */
	private BoardRoleSpecDto roleSpecOf(AgentTaskRoleConfigData rc) {
		BoardRoleSpecDto r = new BoardRoleSpecDto();
		r.setName(rc.getName());
		r.setPrompt(rc.getPrompt());
		r.setOrderIndex(rc.getOrderIndex());
		r.setWipLimit(rc.getWipLimit());
		r.setRequireDistinctAgent(rc.isRequireDistinctAgent());
		r.setActive(rc.isActive());
		r.setKind(rc.getKind());
		r.setNecessity(rc.getNecessity());
		r.setHumanGate(rc.getHumanGate());
		if (rc.getRequiredCapabilities() != null) {
			r.setRequiredCapabilities(new LinkedList<>(rc.getRequiredCapabilities()));
		}
		if (rc.getRequiredInputs() != null) {
			for (RequiredInput ri : rc.getRequiredInputs()) {
				BoardRequiredInputSpecDto in = new BoardRequiredInputSpecDto();
				in.setKind(ri.kind());
				in.setSpecification(ri.specification());
				in.setScope(ri.scope());
				in.setComponent(componentName(ri.component()));
				in.setMinLifecycle(ri.minLifecycle());
				in.setResolution(ri.resolution());
				r.getRequiredInputs().add(in);
			}
		}
		if (rc.getProducesOutputs() != null) {
			for (ProducedOutput po : rc.getProducesOutputs()) {
				BoardProducedOutputSpecDto out = new BoardProducedOutputSpecDto();
				out.setSpecification(po.specification());
				out.setScope(po.scope());
				out.setRequired(po.required());
				r.getProducesOutputs().add(out);
			}
		}
		r.setHopBudgetMicros(rc.getHopBudgetMicros());
		r.setBlindReview(rc.isBlindReview());
		r.setCommissions(rc.getCommissions());
		BoardStrengthSpecDto st = new BoardStrengthSpecDto();
		st.setRequiredStrength(rc.getRequiredStrength());
		st.setStrengthHeadroom(rc.getStrengthHeadroom());
		st.setStrengthCategory(rc.getStrengthCategory());
		List<BoardModelStrengthSpecDto> overrides = new LinkedList<>();
		for (AgentTaskRoleConfigData.ModelStrength ms : null == rc.getModelStrengths()
				? List.<AgentTaskRoleConfigData.ModelStrength>of() : rc.getModelStrengths()) {
			BoardModelStrengthSpecDto o = new BoardModelStrengthSpecDto();
			o.setModel(modelOntologyService.getModelOntologyData(ms.model())
					.map(ModelOntologyService::referenceOf).orElse(String.valueOf(ms.model())));
			o.setStrength(ms.strength());
			overrides.add(o);
		}
		st.setModelStrengths(overrides);
		r.setStrength(st);
		return r;
	}

	private String componentName(UUID componentUuid) {
		if (componentUuid == null) return null;
		return getComponentService.getComponentData(componentUuid)
				.map(ComponentData::getName).orElse(null);
	}

}
