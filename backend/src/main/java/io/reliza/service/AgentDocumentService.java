/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.exceptions.ActionRefusedException;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoard;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTask;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.ElementCheckReport;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ElementIndex;
import io.reliza.model.BoardReviewItemIndex;
import io.reliza.model.BoardReviewItemIndex.About;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItem;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemLocation;
import io.reliza.model.BoardReviewItemIndex.BoardReviewVerdict;
import io.reliza.model.OrganizationData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.DocumentRef;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.VersionAssignment;
import io.reliza.model.VcsRepository;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;
import io.reliza.repositories.AgentBoardRepository;
import io.reliza.repositories.AgentTaskRepository;
import io.reliza.service.ComponentLockService.LockedOperation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishing a document version: a release of a specification component that points at bytes in
 * the board's documents repository.
 *
 * <p>A document version IS a release, deliberately. That buys identity, versioning, lifecycle,
 * component locks, signed-commit attribution through the source code entry, and required-input
 * resolution, all unchanged. What this service adds is the pointer to the file, the round number,
 * and — for reviews and test runs — the review item index the next hop reads as data.
 *
 * <h2>Lock ordering</h2>
 *
 * <b>Board first, then task. Always.</b> This is the first path in the boards code to hold two row
 * locks at once, and no path may take the board lock while holding a task lock. Today the other
 * paths hold one or the other — the coordinator mutations lock the board, the worker mutations
 * lock the task, and the sign-off lock check reads component locks without touching the board row
 * — so nothing conflicts. The rule is written here and at both lock sites because the first path
 * that takes them the other way round produces a deadlock that appears only under concurrency,
 * which is the worst kind to find later.
 */
@Service
@Slf4j
public class AgentDocumentService {

	@Autowired private AgentBoardRepository boardRepository;
	@Autowired private AgentTaskRepository taskRepository;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentRoleHistoryService roleHistoryService;
	@Autowired private BoardDocumentComponentService boardDocumentComponentService;
	@Autowired private BoardPerspectiveService boardPerspectiveService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private BranchService branchService;
	@Autowired private SourceCodeEntryService sourceCodeEntryService;
	@Autowired private GetSourceCodeEntryService getSourceCodeEntryService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private io.reliza.service.oss.OssReleaseService ossReleaseService;
	@Autowired private ComponentLockService componentLockService;
	@Autowired private VersionAssignmentService versionAssignmentService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private AgentTaskInputService agentTaskInputService;
	@Autowired private ElementCheckCatalogueService elementCheckCatalogueService;

	/**
	 * Self-injection so the REQUIRES_NEW component creation below goes through Spring's proxy.
	 * A direct this.* call bypasses AOP and joins the caller's transaction.
	 */
	@Autowired
	@Lazy
	private AgentDocumentService self;

	@PersistenceContext
	private EntityManager entityManager;

	/** Everything the caller states about the document it has already committed. */
	/**
	 * @param elements the element index as the exact JSON text the CLI parsed and digested; null for
	 *        index types and for a prose document published without one
	 * @param elementsDigest sha256 (hex) of those bytes
	 */
	public record PublishRequest(UUID taskUuid, RearmSpecificationType specification, UUID component,
			String path, String digest, String mediaType, String indexPath, String indexDigest,
			Map<String, Object> index, String commit, String vcsUri, String commitMessage,
			ZonedDateTime commitDate, ReleaseLifecycle lifecycle, String elements, String elementsDigest,
			boolean advisory) {

		/** Not advisory: a round from the session that holds the task, or a component-scoped one. */
		public PublishRequest(UUID taskUuid, RearmSpecificationType specification, UUID component,
				String path, String digest, String mediaType, String indexPath, String indexDigest,
				Map<String, Object> index, String commit, String vcsUri, String commitMessage,
				ZonedDateTime commitDate, ReleaseLifecycle lifecycle, String elements, String elementsDigest) {
			this(taskUuid, specification, component, path, digest, mediaType, indexPath, indexDigest, index,
					commit, vcsUri, commitMessage, commitDate, lifecycle, elements, elementsDigest, false);
		}

		/** Without elements: every caller that predates them. */
		public PublishRequest(UUID taskUuid, RearmSpecificationType specification, UUID component,
				String path, String digest, String mediaType, String indexPath, String indexDigest,
				Map<String, Object> index, String commit, String vcsUri, String commitMessage,
				ZonedDateTime commitDate, ReleaseLifecycle lifecycle) {
			this(taskUuid, specification, component, path, digest, mediaType, indexPath, indexDigest, index,
					commit, vcsUri, commitMessage, commitDate, lifecycle, null, null, false);
		}
	}

	/**
	 * Publish a document version.
	 *
	 * <p>Steps run in the order the design fixes: board lock, task lock, idempotency under both,
	 * component lock check, index validation, round, create. The idempotency lookup is under the
	 * locks and not before them — two retries that both looked before locking would both miss, and
	 * the second would mint a round the repository has no file for.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public ReleaseData publish(AgentSessionData session, PublishRequest req, WhoUpdated wu)
			throws RelizaException {
		if (null == req.specification()) throw new RelizaException("A document needs a specification type");
		if (RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT == req.specification()) {
			throw new RelizaException("The board cuts BOARD_ELEMENT_CHECK_REPORT rounds: publish the document, and the board checks"
					+ " its elements; `rearm agent doc element-check` re-runs the element checks");
		}
		// The producer no longer certifies its own output (operator-actions D12): a document is
		// created as a draft, and the board promotes it to ASSEMBLED when the hop signs off.
		if (null != req.lifecycle() && ReleaseLifecycle.DRAFT != req.lifecycle()) {
			throw new RelizaException("The board sets a document's lifecycle: publish creates a DRAFT, and the"
					+ " board promotes it to ASSEMBLED when the hop signs off. Leave the lifecycle out.");
		}
		// Index-only: the items ARE the document. A questions round rarely has prose worth
		// committing, and forcing a file would put an empty markdown in the repository to satisfy
		// a check. Recognised by the absence of a path rather than a flag, so a caller cannot
		// claim one shape and send the other.
		boolean indexOnly = StringUtils.isBlank(req.path()) && null != req.index();
		if (indexOnly) {
			if (!needsIndex(req.specification())) {
				throw new RelizaException(req.specification() + " is prose with a pointer, so it"
						+ " needs a file: only " + INDEXED_TYPES + " can be published as an index alone");
			}
			if (null == req.taskUuid()) {
				throw new RelizaException("An index-only document belongs to a task");
			}
		} else {
			if (StringUtils.isBlank(req.path())) throw new RelizaException("A document needs a path");
			if (StringUtils.isBlank(req.digest())) throw new RelizaException("A document needs a digest");
			if (StringUtils.isBlank(req.commit())) throw new RelizaException("A document needs the commit it was read at");
		}

		boolean taskScoped = null != req.taskUuid();
		AgentTaskData td = null;
		// An advisory round (task e97fde56): a role's own type on a task another session holds.
		boolean advisory = false;
		String author = null;
		UUID boardUuid;
		if (taskScoped) {
			td = agentTaskService.getTaskData(req.taskUuid())
					.orElseThrow(() -> new RelizaException("Task not found: " + req.taskUuid()));
			if (!td.getOrg().equals(session.getOrg())) {
				throw new RelizaException("Task does not belong to this session's organization");
			}
			advisoryRole(td, session, req);
			boardUuid = td.getBoard();
		} else {
			boardUuid = resolveBoardForComponentScope(session, req);
		}
		// A report is an investigation's deliverable (task RD4-12): published on the investigation, nowhere else.
		if (RearmSpecificationType.BOARD_INVESTIGATION_REPORT == req.specification() && (null == td || !td.isInvestigation())) {
			throw new RelizaException("An " + RearmSpecificationType.BOARD_INVESTIGATION_REPORT + " is the report of an"
					+ " investigation task" + (null == td ? "; name the investigation with --task"
							: ", and " + td.label() + " is a work task"));
		}

		// ---- 1. Board lock. LOCK ORDER: board before task, always. ----
		AgentBoard boardRow = boardRepository.findByIdWriteLocked(boardUuid)
				.orElseThrow(() -> new RelizaException("Board not found: " + boardUuid));
		entityManager.refresh(boardRow);
		AgentBoardData board = AgentBoardData.dataFromRecord(boardRow);

		// An index-only round writes no file, so it needs no repository and cannot mismatch one.
		// The board still has to be read: the document COMPONENT hangs off its target either way.
		if (!indexOnly && null == board.getDocumentsRepo()) {
			throw new RelizaException("Board " + board.getName()
					+ " has no documentsRepo set; nothing can be published until it names one of its sources");
		}
		if (!indexOnly) {
		// Identity by ROW, not by string. The CLI sends whatever its remote is configured as --
		// https, ssh, with or without .git -- and looking that up lands on the same row when the
		// two mean one repository, which is what removed the requirement that the CLI mirror a
		// canonicaliser it cannot see.
		//
		// LOOKUP, never create. Provisioning here would mint a repository row for whatever the CLI
		// sent, including a mistyped --repo, and leave it in the org after refusing the publish.
		// Nothing legitimate needs a create: the board's row was made when an operator configured
		// it, so a uri that resolves to nothing is by definition not this board's repository, and
		// absent reads as a mismatch below.
		UUID sentRepo = vcsRepositoryService.getVcsRepositoryByUri(session.getOrg(), req.vcsUri(),
						null, VcsType.GIT, false, WhoUpdated.getAutoWhoUpdated())
				.map(VcsRepository::getUuid).orElse(null);
		if (!board.getDocumentsRepo().equals(sentRepo)) {
			throw new RelizaException("Documents for this board are published to "
					+ repoUri(board.getDocumentsRepo()) + ", not " + req.vcsUri());
		}
		}

		UUID componentUuid = resolveOrCreateComponent(board, req, true, wu);

		// ---- 2. Task lock, under the board lock. LOCK ORDER: board before task, always. ----
		if (taskScoped) {
			AgentTask taskRow = taskRepository.findByIdWriteLocked(req.taskUuid())
					.orElseThrow(() -> new RelizaException("Task not found: " + req.taskUuid()));
			entityManager.refresh(taskRow);
			td = AgentTaskData.dataFromRecord(taskRow);
			// Re-asserted against the locked read. The first check ran on a pre-lock copy, and the
			// assignment can be released in between -- by a return, a session close, or an
			// operator -- which would let a session that no longer holds the task publish a round
			// onto it.
			author = advisoryRole(td, session, req);
			advisory = null != author;
			if (!advisory && null != td.getAssignment()) author = td.getAssignment().role();
		}
		// The version this publish replaces (task RD4-7): the same path published earlier in this very
		// hop. Found before the index is read, because the round before a new version is the round
		// before the one it replaces, not the replaced version itself.
		ReleaseData replaced = taskScoped && !advisory ? sameHopVersion(td, session, req).orElse(null) : null;
		UUID skip = null == replaced ? null : replaced.getUuid();

		// ---- 3. Index in, converted, BEFORE the identity check. ----
		// Deserialised at the boundary, so the rest of the system sees a type rather than a map.
		// An unknown status or verdict, or a priority that is not an integer, fails HERE with the
		// document named -- rather than being carried as a string that every later consumer would
		// have to re-check and that nothing would ever reject.
		//
		// The carry-forward conversion runs here and not after the lookup below, and the order is
		// load-bearing: the lookup compares the digest of the index being published against the
		// digest of each stored round's items. Converting afterwards would store items that no
		// repeat of the same publish could reproduce -- the raw side would digest the legacy text,
		// the stored side the converted form -- and the same publish retried would cut a second
		// round instead of returning the first.
		BoardReviewItemIndex index = null;
		if (needsIndex(req.specification())) {
			try {
				BoardReviewItemIndex raw = Utils.OM.convertValue(req.index(), BoardReviewItemIndex.class);
				index = new BoardReviewItemIndex(raw.kind(), raw.round(), raw.verdict(), raw.counts(),
						BoardReviewItemIndex.carryForward(raw.reviewItems()), raw.about(), raw.tested());
			} catch (IllegalArgumentException e) {
				throw new RelizaException("The review item index is not a valid " + req.specification()
						+ " index: " + rootCause(e));
			}
			// Attribution before the retry check, for the same reason as the conversion above: the
			// stored round carries it, so an incoming round must too for a retry to match.
			index = attributeAgentRound(index,
					taskScoped ? previousRoundIndex(td, req.specification(), skip) : null,
					AgentActor.ofSession(session.getUuid()));
			// Elements named by review items resolve here, with the path and line filled in, for the same
			// reason: the stored round carries them, so a retry must too (elements.md §8).
			if (taskScoped) {
				index = BoardReviewItemIndexValidator.stampElements(index, previousRoundIndex(td, req.specification(), skip),
						elementPositions(td));
			}
		}

		// ---- 3b. Element index (gaps §2.A): checked, never refused for what it says. ----
		ElementIndex elements = elementIndexOf(req, board, td);

		// ---- 4. Idempotency, under the locks. ----
		Optional<ReleaseData> existing = findEquivalent(td, componentUuid, req, index);
		if (existing.isPresent()) {
			// The file did not change, so a parse of it cannot have: a different index is a
			// different parser (or a tampered one), and silently keeping either would be wrong.
			ElementIndex had = existing.get().getDocument().elements();
			String hadDigest = null == had ? null : had.digest();
			String nowDigest = null == elements ? null : elements.digest();
			if (!java.util.Objects.equals(hadDigest, nowDigest)) {
				throw new RelizaException("The element index differs from the earlier publish of " + req.path()
						+ " at this commit; re-parse with the same CLI version (release "
						+ existing.get().getUuid() + " has " + (null == hadDigest ? "no index" : "index " + hadDigest) + ")");
			}
			log.info("Document publish for {} at commit {} already landed as release {}; returning it",
					req.specification(), req.commit(), existing.get().getUuid());
			return existing.get();
		}

		// ---- 5. Component lock. A locked document component takes no new release. ----
		Optional<Branch> base = branchService.getBaseBranchOfComponent(componentUuid);
		UUID baseBranch = base.map(Branch::getUuid).orElse(null);
		componentLockService.assertUnlocked(componentUuid, baseBranch, LockedOperation.RELEASE_CREATION);

		// ---- 6. Index validation, against the round before it. ----
		if (null != index) {
			BoardReviewItemIndex previous = taskScoped
					? previousRoundIndex(td, req.specification(), skip) : null;
			BoardReviewItemIndexValidator.validate(index, req.specification(),
					priorityLevels(session.getOrg()), previous);
		}

		// ---- 7. Round, still under the task lock. ----
		// A new version of a round keeps its number (task RD4-7); anything else is the next round.
		Integer round = null != replaced ? replaced.getDocument().round()
				: taskScoped ? countRounds(td, req.specification()) + 1 : null;

		// ---- 8. Create the source code entry and the release. ----
		// No file, no commit, so no source code entry: an index-only release points at nothing in
		// a repository and the recognised-commit predicate has nothing to say about it.
		UUID sceUuid = indexOnly ? null : createSce(session, board, req, componentUuid, baseBranch, wu);
		DocumentRef doc = new DocumentRef(req.specification(), req.path(), req.digest(),
				indexOnly ? null : StringUtils.defaultIfBlank(req.mediaType(), "text/markdown"),
				req.indexPath(), req.indexDigest(), taskScoped ? td.getUuid() : null,
				session.getUuid(), round, index, elements, null, advisory, author);

		// Mint the version off the document component's own base feature set. The schema is plain
		// integers, so this is round N of the component -- which for a task-scoped type is not the
		// same as round N of the TASK, since every task's rounds share the component. The task's
		// round is what the document field carries and what the path template uses.
		String version = versionAssignmentService
				.getSetNewVersionWrapper(baseBranch, null, null, null)
				.map(VersionAssignment::getVersion)
				.orElseThrow(() -> new RelizaException("Could not mint a version for the document component"));

		ReleaseDto dto = ReleaseDto.builder()
				.version(version)
				.org(session.getOrg())
				.component(componentUuid)
				.branch(baseBranch)
				.sourceCodeEntry(sceUuid)
				.lifecycle(ReleaseLifecycle.DRAFT)
				.document(doc)
				.build();
		Release created = ossReleaseService.createRelease(dto, wu);
		ReleaseData rd = ReleaseData.dataFromRecord(created);
		if (advisory) {
			// No sign-off will hand an advisory round over: the author role vouches for it by
			// publishing, as its own PASSED sign-off would have. ASSEMBLED through the same call the
			// sign-off uses, so lifecycle triggers and guards see it the same way; that makes it the
			// holder's latest input at its floor.
			promoteOutputs(List.of(rd.getUuid()), wu);
			rd = sharedReleaseService.getReleaseData(rd.getUuid()).orElse(rd);
		}
		// Name this round on the items it decided, now that it exists (operator-actions D7). A
		// draft may still be written; the retry check leaves decidedIn out of every digest.
		if (null != index && index.reviewItems().stream().anyMatch(AgentDocumentService::decidedHere)) {
			rd.setDocument(doc.withReviewItems(stampSelfPointer(index, null, rd.getUuid())));
			rd = ReleaseData.dataFromRecord(ossReleaseService.saveRelease(created, rd, wu, false));
		}

		// ---- 8. Link to the task, still under its lock, so TASK-scope resolution can find it. ----
		if (taskScoped) {
			agentTaskService.linkRelease(td.getUuid(), rd.getUuid(), wu);
		}
		// ---- 8b. The replaced version points at this one (task RD4-7). ----
		// Still readable by its uuid and in the task's documents; every newest-round read skips it.
		if (null != replaced) markSuperseded(replaced.getUuid(), rd.getUuid(), wu);

		// ---- 9. Check the elements (elements.md §7), still under both locks. ----
		// Here rather than at hand-over: the author learns at publish, and a refusal inside the
		// sign-off would roll back the very report it is refusing on. The sign-off only reads it.
		// A document that defines nothing -- a note that only names review items (grammar 1.2) -- has nothing to check.
		if (taskScoped && null != elements && !elements.elements().isEmpty()) {
			ReleaseData report = cutElementCheckReport(td, elementCheckCatalogueService.run(checkScope(board, td, rd)), wu);
			agentTaskService.linkRelease(td.getUuid(), report.getUuid(), wu);
		}
		if (advisory) {
			// The holder's cue, and the feed's: routing does not move, so nothing else says it.
			String holder = null != td.getAssignment() ? td.getAssignment().role() + " holds it"
					: null != td.getRole() ? "queued for " + td.getRole() : "no role holds it";
			agentBoardService.postEvent(board.getUuid(), AgentBoardData.BoardEventKind.INFO,
					author + " published " + req.specification() + " round " + round + " on " + td.label()
							+ " (advisory; " + holder + ")",
					AgentActor.ofSession(session.getUuid()), wu);
		}
		log.info("Published {} round {} for task {} as release {}{}{}", req.specification(), round,
				taskScoped ? td.getUuid() : "(component-scoped)", rd.getUuid(), advisory ? " (advisory, " + author + ")" : "",
				null == replaced ? "" : ", replacing " + replaced.getUuid());
		return rd;
	}

	/**
	 * The release this hop already published at the same path, if there is one (task RD4-7): same task,
	 * same session, published since the current assignment began, same specification and path (both
	 * absent for an index-only round), settled and not itself replaced. A second publish of it is the
	 * correction the hop means, so it becomes a new version of that round rather than the next round.
	 *
	 * <p>Only the holder's hop: the session must hold the task's assignment. An advisory round is not
	 * a hop's, and a publish from another assignment is a new round as it always was.
	 */
	private Optional<ReleaseData> sameHopVersion(AgentTaskData td, AgentSessionData session, PublishRequest req) {
		AgentTaskData.TaskAssignment a = td.getAssignment();
		if (null == a || null == a.assignedAt() || !session.getUuid().equals(a.session()) || null == td.getReleases()) {
			return Optional.empty();
		}
		for (int i = td.getReleases().size() - 1; i >= 0; i--) {
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(td.getReleases().get(i));
			if (ord.isEmpty() || !isSettledRound(ord.get().getLifecycle())) continue;
			ReleaseData rd = ord.get();
			DocumentRef doc = rd.getDocument();
			if (null == doc || doc.superseded() || doc.advisoryRound() || doc.specification() != req.specification()
					|| !td.getUuid().equals(doc.task()) || !session.getUuid().equals(doc.session())) continue;
			if (null == rd.getCreatedDate() || rd.getCreatedDate().isBefore(a.assignedAt())) continue;
			if (!java.util.Objects.equals(StringUtils.trimToNull(doc.path()), StringUtils.trimToNull(req.path()))) continue;
			return ord;
		}
		return Optional.empty();
	}

	/** Write {@code by} onto the replaced version's document; its lifecycle stays where it is. */
	private void markSuperseded(UUID replaced, UUID by, WhoUpdated wu) throws RelizaException {
		Release row = sharedReleaseService.getRelease(replaced)
				.orElseThrow(() -> new RelizaException("Release not found: " + replaced));
		ReleaseData rd = ReleaseData.dataFromRecord(row);
		rd.setDocument(rd.getDocument().withSupersededBy(by));
		// Triggers off: nothing about the replaced version's lifecycle or content changed.
		ossReleaseService.saveRelease(row, rd, wu, false);
	}

	/**
	 * The newest version of a document release (task RD4-7): the release itself unless a later version of
	 * its round replaced it, then that one, followed to the end. Bounded, so a malformed chain cannot loop.
	 */
	public UUID newestVersionOf(UUID release) {
		UUID cur = release;
		for (int hops = 0; hops < 64; hops++) {
			UUID next = sharedReleaseService.getReleaseData(cur).map(ReleaseData::getDocument)
					.map(DocumentRef::supersededBy).orElse(null);
			if (null == next || next.equals(cur)) return cur;
			cur = next;
		}
		return cur;
	}

	/**
	 * The newest round of a document's series that the same hop published (task RD4-18): a series is a
	 * task's rounds of one specification type. An output a later round of its series replaced within the
	 * hop is taken as that round, so a hop that fixed a round by publishing the next one hands over the fix,
	 * and the round it replaced is not an output: not checked at hand-over, not promoted, left a draft,
	 * still in the task's documents and its report in the task's checks. Not marked superseded, since that
	 * flag means a newer version of the same round and every round count rests on it.
	 *
	 * <p>Only rounds of this hop count: the task's releases this session published since the assignment
	 * began, settled, not replaced by a newer version, not advisory. A release outside that window is
	 * returned as it is, for the output checks to refuse or accept as before.
	 *
	 * @param since the assignment's start; null leaves the release as it is
	 */
	public UUID newestRoundOfHop(AgentTaskData td, UUID session, ZonedDateTime since, UUID release) {
		if (null == td || null == session || null == since || null == td.getReleases()) return release;
		ReleaseData offered = sharedReleaseService.getReleaseData(release).orElse(null);
		if (!ofHop(offered, td, session, since) || null == offered.getDocument().round()) return release;
		ReleaseData newest = offered;
		for (UUID r : td.getReleases()) {
			ReleaseData rd = sharedReleaseService.getReleaseData(r).orElse(null);
			if (!ofHop(rd, td, session, since) || null == rd.getDocument().round()
					|| rd.getDocument().specification() != offered.getDocument().specification()) continue;
			if (rd.getDocument().round() > newest.getDocument().round()) newest = rd;
		}
		return newest.getUuid();
	}

	/** A round this hop published: this task, this session, since the assignment, settled, current, not advisory. */
	private static boolean ofHop(ReleaseData rd, AgentTaskData td, UUID session, ZonedDateTime since) {
		if (null == rd || !isSettledRound(rd.getLifecycle()) || null == rd.getCreatedDate()
				|| rd.getCreatedDate().isBefore(since)) return false;
		DocumentRef doc = rd.getDocument();
		return null != doc && !doc.superseded() && !doc.advisoryRound() && td.getUuid().equals(doc.task())
				&& session.equals(doc.session());
	}

	/** Whether a release is a document version a later version of its round replaced. */
	private static boolean isSuperseded(ReleaseData rd) {
		return null != rd.getDocument() && rd.getDocument().superseded();
	}

	/** One element of a task's documents, with the release and specification it came from. */
	public record TaskElement(String id, String family, String title, String parent, Integer level,
			List<ElementIndex.Link> traces, List<String> assumes, List<String> speculative,
			String contentDigest, Integer line, UUID release, String specification, List<String> terms) {

		/** Without terms: grammar 1. */
		public TaskElement(String id, String family, String title, String parent, Integer level,
				List<ElementIndex.Link> traces, List<String> assumes, List<String> speculative,
				String contentDigest, Integer line, UUID release, String specification) {
			this(id, family, title, parent, level, traces, assumes, speculative, contentDigest, line, release,
					specification, List.of());
		}
	}

	/**
	 * The elements of a task's documents (gaps §2.A): the union over the task's latest release per
	 * specification, each element carrying where it came from. Per read, so a round published a
	 * moment ago is included without a cache.
	 */
	public List<TaskElement> taskElements(AgentTaskData td) {
		java.util.Map<RearmSpecificationType, ReleaseData> latest = new java.util.LinkedHashMap<>();
		for (UUID r : null == td.getReleases() ? List.<UUID>of() : td.getReleases()) {
			sharedReleaseService.getReleaseData(r)
					.filter(rd -> null != rd.getDocument() && null != rd.getDocument().specification() && !isSuperseded(rd))
					.ifPresent(rd -> latest.put(rd.getDocument().specification(), rd));
		}
		List<TaskElement> out = new ArrayList<>();
		for (ReleaseData rd : latest.values()) {
			ElementIndex ei = rd.getDocument().elements();
			if (null == ei) continue;
			for (ElementIndex.Element e : ei.elements()) {
				out.add(new TaskElement(e.id(), e.family(), e.title(), e.parent(), e.level(), e.traces(),
						e.assumes(), e.speculative(), e.contentDigest(), e.line(), rd.getUuid(),
						rd.getDocument().specification().name(), e.terms()));
			}
		}
		return out;
	}

	/**
	 * The elements a review item may name, and where each is: the task's documents first (their latest
	 * release per specification), then the releases bound to the current assignment (elements.md §8).
	 */
	Map<String, BoardReviewItemIndexValidator.ElementPosition> elementPositions(AgentTaskData td) {
		Map<String, BoardReviewItemIndexValidator.ElementPosition> out = new LinkedHashMap<>();
		if (null == td) return out;
		for (ReleaseData rd : scopeReleases(td, false)) {
			DocumentRef doc = rd.getDocument();
			for (ElementIndex.Element e : doc.elements().elements()) {
				if (null != e.id()) out.putIfAbsent(e.id(),
						new BoardReviewItemIndexValidator.ElementPosition(doc.path(), e.line(), rd.getUuid()));
			}
		}
		return out;
	}

	/**
	 * Element-bearing releases a task's element questions are answered over, in the order they win
	 * for an id defined twice: the task's latest release per specification, then the releases bound
	 * to the current assignment, then -- with {@code withBoard} -- the latest release of each document
	 * series under the board's target.
	 */
	List<ReleaseData> scopeReleases(AgentTaskData td, boolean withBoard) {
		java.util.LinkedHashMap<UUID, ReleaseData> out = new java.util.LinkedHashMap<>();
		java.util.Map<RearmSpecificationType, ReleaseData> latest = new java.util.LinkedHashMap<>();
		for (UUID r : null == td.getReleases() ? List.<UUID>of() : td.getReleases()) {
			sharedReleaseService.getReleaseData(r)
					.filter(rd -> null != rd.getDocument() && null != rd.getDocument().specification()
							&& td.getUuid().equals(rd.getDocument().task()) && isSettledRound(rd.getLifecycle())
							&& !isSuperseded(rd))
					.ifPresent(rd -> latest.put(rd.getDocument().specification(), rd));
		}
		latest.values().stream().filter(rd -> null != rd.getDocument().elements())
				.forEach(rd -> out.putIfAbsent(rd.getUuid(), rd));
		if (null != td.getAssignment() && null != td.getAssignment().resolvedInputs()) {
			td.getAssignment().resolvedInputs().stream()
					.map(io.reliza.model.AgentTaskInput.ResolvedInput::release)
					.filter(java.util.Objects::nonNull)
					.forEach(r -> sharedReleaseService.getReleaseData(r)
							.filter(rd -> null != rd.getDocument() && null != rd.getDocument().elements())
							.ifPresent(rd -> out.putIfAbsent(rd.getUuid(), rd)));
		}
		if (withBoard) {
			agentBoardService.getBoardData(td.getBoard()).ifPresent(board -> {
				for (RearmSpecificationType spec : RearmSpecificationType.values()) {
					// Index types carry review items, never elements; anything else without an index drops out below.
					if (needsIndex(spec)) continue;
					boardDocumentComponentService.componentOf(board, spec)
							.flatMap(branchService::getBaseBranchOfComponent)
							.flatMap(b -> sharedReleaseService.getLatestNonCancelledOrRejectedReleaseDataOfBranch(b.getUuid()))
							.filter(rd -> null != rd.getDocument() && null != rd.getDocument().elements())
							.ifPresent(rd -> out.putIfAbsent(rd.getUuid(), rd));
				}
			});
		}
		return new ArrayList<>(out.values());
	}

	/** An edge from a dependent to what it depends on: parent, assumes, or the trace verb. */
	public record ElementEdge(String from, String to, String kind) {}

	/** One element of the closure, with the edges that pulled it in and how far it is. */
	public record ElementDependent(TaskElement element, List<ElementEdge> via, int distance) {}

	/**
	 * @param found whether any release in scope defines the element
	 * @param count the size of the closure
	 * @param truncated whether the depth bound stopped the walk
	 */
	public record ElementDependents(String element, boolean found, int count, boolean truncated,
			List<ElementDependent> dependents) {}

	public static final int DEFAULT_DEPENDENTS_DEPTH = 8;

	/**
	 * What depends on an element, transitively (elements.md §8): breadth-first over incoming edges,
	 * nearest first, cycle-guarded, bounded by {@code depth}. Every edge counts whatever its verb --
	 * the verb rides on the edge so a reader can discount {@code invalidates} or keep only
	 * {@code derives_from}; the server does not rank them.
	 */
	public ElementDependents dependentsOf(AgentTaskData td, String element, Integer depth) {
		int bound = null == depth || depth < 1 ? DEFAULT_DEPENDENTS_DEPTH : depth;
		Map<String, TaskElement> byId = new LinkedHashMap<>();
		for (ReleaseData rd : scopeReleases(td, true)) {
			for (ElementIndex.Element e : rd.getDocument().elements().elements()) {
				if (null != e.id()) byId.putIfAbsent(e.id(), taskElement(e, rd));
			}
		}
		// incoming[x] = the edges from elements that depend on x
		Map<String, List<ElementEdge>> incoming = new LinkedHashMap<>();
		for (TaskElement e : byId.values()) {
			if (null != e.parent()) incoming.computeIfAbsent(e.parent(), k -> new ArrayList<>())
					.add(new ElementEdge(e.id(), e.parent(), "parent"));
			for (ElementIndex.Link l : e.traces()) {
				if (null != l.target()) incoming.computeIfAbsent(l.target(), k -> new ArrayList<>())
						.add(new ElementEdge(e.id(), l.target(), null == l.verb() ? "traces" : l.verb()));
			}
			for (String a : e.assumes()) {
				incoming.computeIfAbsent(a, k -> new ArrayList<>()).add(new ElementEdge(e.id(), a, "assumes"));
			}
		}
		boolean found = byId.containsKey(element);
		List<ElementDependent> out = new ArrayList<>();
		Set<String> seen = new java.util.HashSet<>(Set.of(element));
		List<String> frontier = List.of(element);
		boolean truncated = false;
		for (int distance = 1; !frontier.isEmpty(); distance++) {
			Map<String, List<ElementEdge>> next = new LinkedHashMap<>();
			for (String x : frontier) {
				for (ElementEdge edge : incoming.getOrDefault(x, List.of())) {
					if (seen.contains(edge.from()) && !next.containsKey(edge.from())) continue;
					next.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
				}
			}
			if (next.isEmpty()) break;
			if (distance > bound) {
				truncated = true;
				break;
			}
			for (Map.Entry<String, List<ElementEdge>> n : next.entrySet()) {
				seen.add(n.getKey());
				TaskElement te = byId.get(n.getKey());
				if (null != te) out.add(new ElementDependent(te, n.getValue(), distance));
			}
			frontier = new ArrayList<>(next.keySet());
		}
		return new ElementDependents(element, found, out.size(), truncated, out);
	}

	/**
	 * @param changed whether the element's content digest differs from the round before; false on the
	 *        first round it appears in
	 */
	public record ElementVersion(UUID release, Integer round, String specification, String contentDigest,
			Integer line, boolean changed) {}

	/**
	 * An element across the task's rounds of the document that defines it, oldest first: which rounds
	 * changed it. Empty when no document of the task defines it (an input's element has no history
	 * here).
	 */
	public List<ElementVersion> elementHistory(AgentTaskData td, String element) {
		RearmSpecificationType spec = taskElements(td).stream().filter(e -> element.equals(e.id())).findFirst()
				.map(e -> RearmSpecificationType.valueOf(e.specification())).orElse(null);
		List<ElementVersion> out = new ArrayList<>();
		if (null == spec || null == td.getReleases()) return out;
		String previous = null;
		for (UUID r : td.getReleases()) {
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(r);
			if (ord.isEmpty() || !isSettledRound(ord.get().getLifecycle()) || isSuperseded(ord.get())) continue;
			DocumentRef doc = ord.get().getDocument();
			if (null == doc || spec != doc.specification() || !td.getUuid().equals(doc.task()) || null == doc.elements()) continue;
			Optional<ElementIndex.Element> e = doc.elements().elements().stream()
					.filter(x -> element.equals(x.id())).findFirst();
			if (e.isEmpty()) continue;
			String digest = e.get().contentDigest();
			out.add(new ElementVersion(r, doc.round(), spec.name(), digest, e.get().line(),
					!out.isEmpty() && !java.util.Objects.equals(previous, digest)));
			previous = digest;
		}
		return out;
	}

	/** An element of a release as the task-element view shows it. */
	static TaskElement taskElement(ElementIndex.Element e, ReleaseData rd) {
		return new TaskElement(e.id(), e.family(), e.title(), e.parent(), e.level(), e.traces(), e.assumes(),
				e.speculative(), e.contentDigest(), e.line(), rd.getUuid(), rd.getDocument().specification().name(),
				e.terms());
	}

	/** An element as a release's index has it; empty when the release carries no index or not the id. */
	public Optional<TaskElement> elementOf(UUID release, String element) {
		if (null == release || null == element) return Optional.empty();
		return sharedReleaseService.getReleaseData(release)
				.filter(rd -> null != rd.getDocument() && null != rd.getDocument().elements())
				.flatMap(rd -> rd.getDocument().elements().elements().stream()
						.filter(e -> element.equals(e.id())).findFirst().map(e -> taskElement(e, rd)));
	}

	/**
	 * The element index a prose publish carries, verified against its digest and checked against the
	 * board and the task's inputs; null when none was sent.
	 *
	 * <p>The index travels as the exact JSON text the CLI digested. A GraphQL object would be
	 * re-serialised on each side -- key order, escapes -- and a digest recomputed from it would drift
	 * between the Go client and this server; the text arrives byte for byte.
	 */
	private ElementIndex elementIndexOf(PublishRequest req, AgentBoardData board, AgentTaskData td)
			throws RelizaException {
		if (StringUtils.isBlank(req.elements())) return null;
		if (StringUtils.isBlank(req.elementsDigest())) {
			throw new RelizaException("An element index needs its elementsDigest: the sha256 of the JSON sent");
		}
		return elementIndexOf(req.elements(), req.elementsDigest(), req.specification(), board, td);
	}

	/**
	 * An element index as sent, digest-checked when a digest came with it, parsed and checked against the board and
	 * the task's inputs: what a publish stores and what a check preview runs over.
	 *
	 * <p>An index type (BOARD_REVIEW_ITEMS, BOARD_TEST_REPORT, BOARD_QUESTIONS) carries elements only under grammar 1.2, and only
	 * when a family is defined in it (task RD4-6): a test report defines test ids on a board without a test plan.
	 */
	private ElementIndex elementIndexOf(String json, String sentDigest, RearmSpecificationType spec, AgentBoardData board,
			AgentTaskData td) throws RelizaException {
		byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		String actual;
		try {
			actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		if (StringUtils.isNotBlank(sentDigest)) {
			String sent = StringUtils.removeStartIgnoreCase(sentDigest.strip(), "sha256:");
			if (!actual.equalsIgnoreCase(sent)) {
				throw new RelizaException("The element index does not match its elementsDigest (sent " + sent
						+ ", computed " + actual + "); send the index exactly as it was digested");
			}
		}
		ElementIndex parsed;
		try {
			parsed = Utils.OM.readValue(json, ElementIndex.class);
		} catch (Exception e) {
			throw new RelizaException("The element index is not valid JSON of the element grammar: " + rootCause(e));
		}
		if (null != parsed.grammarVersion() && !ElementIndex.GRAMMAR_VERSIONS.contains(parsed.grammarVersion())) {
			throw new RelizaException("The element index is grammar " + parsed.grammarVersion() + "; this server reads "
					+ String.join(", ", ElementIndex.GRAMMAR_VERSIONS));
		}
		Map<String, List<RearmSpecificationType>> definedIn = board.getEffectiveElementFamilyDefinedIn();
		if (needsIndex(spec) && !(io.reliza.model.ElementFamilies.typed(parsed)
				&& io.reliza.model.ElementFamilies.definesAnything(definedIn, spec))) {
			throw new RelizaException(spec + " carries a review item index, not elements" + (io.reliza.model.ElementFamilies.typed(parsed)
					? ": this board defines no element family in it" : ", below grammar 1.2")
					+ "; leave the element index out");
		}
		ElementIndex withDigest = new ElementIndex(parsed.grammarVersion(), parsed.elements(), parsed.warnings(), actual,
				parsed.references());
		return ElementIndexValidator.validate(withDigest, board.getEffectiveElementFamilies(), definedIn, spec,
				resolvableElementIds(td), reservedTaskPrefixes(board));
	}

	/** The release a check preview names as the checked document: none, since nothing is created. */
	public static final UUID PREVIEW_RELEASE = new UUID(0L, 0L);

	/**
	 * The checks a publish of this element index would run (task RD4-6; {@code rearm agent doc publish --check}): the
	 * index validated as a publish validates it, then the catalogue over the task's current scope with the index as
	 * the next round of its series, as a DRAFT. Creates no release and no report round; the report's checked release
	 * is {@link #PREVIEW_RELEASE}.
	 */
	public ElementCheckReport previewElementChecks(AgentTaskData td, RearmSpecificationType spec, String elements, String elementsDigest)
			throws RelizaException {
		if (StringUtils.isBlank(elements)) {
			throw new RelizaException("elements is required: the index doc publish would send");
		}
		if (RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT == spec) {
			throw new RelizaException("the board cuts BOARD_ELEMENT_CHECK_REPORT; there is nothing to preview");
		}
		AgentBoardData board = agentBoardService.getBoardData(td.getBoard())
				.orElseThrow(() -> new RelizaException("Board not found: " + td.getBoard()));
		ElementIndex index = elementIndexOf(elements, elementsDigest, spec, board, td);
		ElementCheckCatalogueService.ScopedDocument preview = new ElementCheckCatalogueService.ScopedDocument(PREVIEW_RELEASE, spec,
				ReleaseLifecycle.DRAFT, index);
		return elementCheckCatalogueService.run(checkScope(board, td, preview, spec + "/" + td.getUuid()));
	}

	/** The board's task prefixes, current and held before: their tokens are task keys, not ids (task RD2-28). */
	static java.util.Set<String> reservedTaskPrefixes(AgentBoardData board) {
		java.util.Set<String> out = new java.util.HashSet<>();
		if (StringUtils.isNotBlank(board.getTaskPrefix())) out.add(board.getTaskPrefix());
		if (null != board.getTaskPrefixHistory()) {
			board.getTaskPrefixHistory().stream().filter(StringUtils::isNotBlank).forEach(out::add);
		}
		return out;
	}

	/**
	 * Ids a new element may point at outside its own document: the elements of the releases bound to
	 * the current assignment, and of the task's latest release of each specification.
	 */
	private java.util.Set<String> resolvableElementIds(AgentTaskData td) {
		java.util.Set<String> ids = new java.util.HashSet<>();
		if (null == td) return ids;
		List<UUID> releases = new ArrayList<>();
		if (null != td.getAssignment() && null != td.getAssignment().resolvedInputs()) {
			td.getAssignment().resolvedInputs().stream()
					.map(io.reliza.model.AgentTaskInput.ResolvedInput::release)
					.filter(java.util.Objects::nonNull).forEach(releases::add);
		}
		java.util.Map<RearmSpecificationType, UUID> latest = new java.util.LinkedHashMap<>();
		for (UUID r : null == td.getReleases() ? List.<UUID>of() : td.getReleases()) {
			sharedReleaseService.getReleaseData(r).map(ReleaseData::getDocument)
					.filter(d -> null != d && null != d.specification() && !d.superseded())
					.ifPresent(d -> latest.put(d.specification(), r));
		}
		releases.addAll(latest.values());
		for (UUID r : releases) {
			sharedReleaseService.getReleaseData(r).map(ReleaseData::getDocument).map(DocumentRef::elements)
					.ifPresent(ei -> ei.elements().forEach(e -> { if (null != e.id()) ids.add(e.id()); }));
		}
		return ids;
	}

	/** Task states an advisory round may go on: the task is still being worked (§3.1 item 1). */
	private static final java.util.Set<AgentTaskData.TaskStatus> ADVISORY_STATES = java.util.EnumSet.of(
			AgentTaskData.TaskStatus.QUEUED, AgentTaskData.TaskStatus.ASSIGNED,
			AgentTaskData.TaskStatus.AWAITING_COORDINATOR, AgentTaskData.TaskStatus.ON_HOLD,
			AgentTaskData.TaskStatus.DELIVERING);

	/**
	 * Whether this publish is an advisory round (task e97fde56), and as which role: null for the
	 * session that holds the task, which gets a normal round whatever it sent. Any other session
	 * gets the holder refusal word for word unless it asked for an advisory round, and then the
	 * round must be prose, on an active task, of a type a role its agent has signed off on this
	 * board produces.
	 */
	private String advisoryRole(AgentTaskData td, AgentSessionData session, PublishRequest req)
			throws RelizaException {
		if (holds(td, session)) return null;
		if (!req.advisory()) assertSessionHoldsTask(td, session);
		if (needsIndex(req.specification())) {
			throw new RelizaException("An advisory round cannot be an index type: " + req.specification()
					+ " drives verdicts and routing, so only the session holding task " + td.label() + " publishes it");
		}
		if (!ADVISORY_STATES.contains(td.getStatus())) {
			throw new RelizaException("Advisory rounds go on active tasks; task " + td.label() + " is " + td.getStatus());
		}
		java.util.Set<UUID> held = roleHistoryService.rolesFor(td.getBoard(), session.getAgent());
		return agentBoardService.listRoleConfigs(td.getBoard()).stream()
				.filter(rc -> held.contains(rc.getUuid()) && null != rc.getProducesOutputs()
						&& rc.getProducesOutputs().stream().anyMatch(o -> req.specification() == o.specification()))
				.map(AgentTaskRoleConfigData::getName)
				.sorted()
				.findFirst()
				.orElseThrow(() -> new RelizaException("No role you have held on this board produces "
						+ req.specification() + ", so you cannot publish an advisory round of it on task " + td.label()));
	}

	private static boolean holds(AgentTaskData td, AgentSessionData session) {
		return null != td.getAssignment() && session.getUuid().equals(td.getAssignment().session());
	}

	/**
	 * The session must hold the task's current assignment.
	 *
	 * <p>Not merely "has touched it": publishing is a write on behalf of the hop in progress, and
	 * a session that signed off already had its outputs frozen onto that sign-off.
	 */
	private void assertSessionHoldsTask(AgentTaskData td, AgentSessionData session) throws RelizaException {
		// task RD3-4: a session whose assignment a person released is told so, not that it never held the task
		java.util.Optional<AgentTaskData.Unassignment> released = td.unassignedFor(session.getUuid());
		if (released.isPresent()) throw new RelizaException(released.get().refusal(td.keyOrUuid()));
		if (null == td.getAssignment() || !session.getUuid().equals(td.getAssignment().session())) {
			throw new RelizaException("Session " + session.getUuid()
					+ " does not hold the current assignment of task " + td.getUuid()
					+ "; only the working session may publish its documents");
		}
	}

	/**
	 * Which board a component-scoped publish belongs to.
	 *
	 * <p>A component-scoped document has no task to read the board from, so the session's board is
	 * used: the coordinator seat, else the single board its open assignment belongs to.
	 */
	/** The board a component-scoped publish from this session goes to, for the caller's permission check. */
	public UUID componentScopeBoard(AgentSessionData session, UUID component) throws RelizaException {
		return resolveBoardForComponentScope(session, new PublishRequest(null, null, component, null, null, null,
				null, null, null, null, null, null, null, null));
	}

	private UUID resolveBoardForComponentScope(AgentSessionData session, PublishRequest req)
			throws RelizaException {
		if (null == req.component()) {
			throw new RelizaException("A component-scoped document needs the component it belongs to");
		}
		List<AgentBoard> seats = boardRepository.findBySeatSession(session.getUuid().toString());
		if (seats.size() == 1) return AgentBoardData.dataFromRecord(seats.get(0)).getUuid();
		if (seats.size() > 1) {
			throw new RelizaException("Session holds the coordinator seat on several boards; "
					+ "publish from a session bound to one board");
		}
		List<AgentTask> assigned = taskRepository.findByAssignedSession(session.getUuid().toString());
		if (assigned.size() == 1) return AgentTaskData.dataFromRecord(assigned.get(0)).getBoard();
		throw new RelizaException("Cannot tell which board this document belongs to: the session "
				+ "holds no coordinator seat and " + assigned.size() + " assignments");
	}

	/**
	 * The document component for this publish, created on first use.
	 *
	 * <p>Runs under the board lock, which is what makes get-or-create safe: component names carry
	 * no unique index, so two tasks publishing their first round at the same moment would
	 * otherwise both create the shared component and the board would end up with two components
	 * claiming one specification.
	 *
	 * @param boardLocked whether the caller holds the board lock, which recording in the board's
	 *        map needs (see {@link #documentComponentOf})
	 */
	private UUID resolveOrCreateComponent(AgentBoardData board, PublishRequest req, boolean boardLocked,
			WhoUpdated wu) throws RelizaException {
		if (null != req.component()) {
			ComponentData cd = getComponentService.getComponentData(req.component())
					.orElseThrow(() -> new RelizaException("Component not found: " + req.component()));
			if (!carriesSpecification(cd, req.specification())) {
				throw new RelizaException("Component " + cd.getName() + " does not carry the "
						+ req.specification() + " specification identifier");
			}
			return cd.getUuid();
		}
		if (null == board.getTarget()) {
			throw new RelizaException("Board " + board.getName() + " has no target node to hang documents off");
		}
		return documentComponentOf(board, req.specification(), boardLocked, wu);
	}

	/**
	 * The board's document component for a specification (board-documents.md §2.2): the one its map
	 * records; else, for a board from before the map, the one its target already carries, adopted;
	 * else a new one, created and recorded.
	 *
	 * <p>Recording writes the board, so it happens only when the caller holds the board lock. A path
	 * holding just the task lock (the answer and decision rounds) resolves the same component without
	 * recording it, and the next publish -- which takes the board first -- adopts it. That keeps the
	 * lock order, board before task, that every publishing path shares.
	 */
	private UUID documentComponentOf(AgentBoardData board, RearmSpecificationType spec, boolean boardLocked,
			WhoUpdated wu) throws RelizaException {
		Optional<UUID> recorded = boardDocumentComponentService.recordedOf(board, spec);
		if (recorded.isPresent()) return recorded.get();
		Optional<UUID> legacy = boardDocumentComponentService.legacyComponentOf(board, spec);
		if (legacy.isPresent()) {
			if (boardLocked) {
				log.info("Board {} adopted component {} for specification {}", board.getUuid(), legacy.get(), spec);
				// An adopted component joins the board's real perspectives, as a created one does (§3.3).
				boardPerspectiveService.joinDocumentComponent(board, legacy.get(), wu);
				return record(board, spec, legacy.get(), wu);
			}
			return legacy.get();
		}
		// Through the proxy, so the REQUIRES_NEW above actually applies; a direct this.* call
		// would join this transaction and defeat the whole point.
		UUID created = self.createDocumentComponentIsolated(board, spec, wu);
		return boardLocked ? record(board, spec, created, wu) : created;
	}

	/** Record a board's document component, on the row and on the caller's copy of the board. */
	private UUID record(AgentBoardData board, RearmSpecificationType spec, UUID component, WhoUpdated wu)
			throws RelizaException {
		UUID kept = agentBoardService.recordDocumentComponent(board.getUuid(), spec, component, wu);
		if (null == board.getDocumentComponents()) board.setDocumentComponents(new java.util.LinkedHashMap<>());
		board.getDocumentComponents().put(spec, kept);
		return kept;
	}

	private boolean carriesSpecification(ComponentData cd, RearmSpecificationType spec) {
		List<RearmIdentifier> ids = cd.getIdentifiers();
		if (null == ids) return false;
		return ids.stream().anyMatch(i -> null != i
				&& RearmIdentifierType.SPECIFICATION == i.getIdType()
				&& spec.name().equals(i.getIdValue()));
	}

	/**
	 * Create the shared task-scoped component and compose it into the target's base feature set.
	 *
	 * <p>The version schema is a plain integer so round N of the component is version N: rounds are
	 * counted, not semver'd, and a document version that reads {@code 3} beside "round 3" is one
	 * less thing to reconcile. The name is deterministic so a stray duplicate is recognisable.
	 */
	/**
	 * Create the component in its OWN transaction, committed before this one continues.
	 *
	 * <p>Not a style choice. Version assignment runs {@code REQUIRES_NEW}, so it cannot see a
	 * branch created in this still-open transaction: minting would return empty and every first
	 * publish on a board would fail. The same reasoning already governs
	 * {@code provisionVcsRepository}, whose javadoc says so explicitly.
	 *
	 * <p>Serialisation is unaffected — the caller holds the board row lock across this call, so a
	 * concurrent publisher is still blocked before it reaches creation, which is what D8 needs.
	 *
	 * <p>The cost is that a component survives a rollback of the outer transaction. That is
	 * benign and self-correcting: the next publish finds it by its specification identifier and
	 * reuses it, which is exactly what get-or-create does on any later round.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = RelizaException.class)
	public UUID createDocumentComponentIsolated(AgentBoardData board, RearmSpecificationType spec,
			WhoUpdated wu) throws RelizaException {
		return createDocumentComponent(board, spec, wu);
	}

	private UUID createDocumentComponent(AgentBoardData board, RearmSpecificationType spec, WhoUpdated wu)
			throws RelizaException {
		// Named after the board, not its target (board-documents.md D2), so two boards on one target
		// keep two series. Component names carry no unique index, so a clash is refused here rather
		// than left as two components nobody can tell apart.
		String name = board.documentComponentName(spec);
		boolean taken = componentService.listComponentDataByOrganization(board.getOrg(), ComponentType.COMPONENT,
						ComponentType.PRODUCT).stream()
				.anyMatch(c -> StatusEnum.ARCHIVED != c.getStatus() && null != c.getName()
						&& c.getName().trim().equalsIgnoreCase(name));
		if (taken) {
			throw new RelizaException("A component named " + name + " exists; set documents.prefix on board "
					+ board.getName() + " to name its documents differently");
		}

		CreateComponentDto dto = CreateComponentDto.builder()
				.name(name)
				.organization(board.getOrg())
				.type(ComponentType.COMPONENT)
				.kind(ComponentData.ComponentKind.BOARD_DOCUMENT)
				// Plain integers: round N of the component is version N. Rounds are counted, not
				// semver'd, and a version reading "3" beside "round 3" is one less thing to
				// reconcile when reading a task's history.
				.versionSchema("Micro")
				// Required by component creation even though a document component never branches:
				// rounds are cut on the base feature set only. Left unset, the first publish on a
				// board fails at component creation.
				.featureBranchVersioning("Branch.Micro")
				.vcs(resolveDocumentsVcs(board))
				.identifiers(List.of(new RearmIdentifier(RearmIdentifierType.SPECIFICATION, spec.name())))
				.build();
		Component created = componentService.createBoardDocumentComponent(dto, wu);
		composeIntoTarget(board, created.getUuid(), wu);
		// Members of the board's real perspectives (board-permissions.md D10); products take none.
		boardPerspectiveService.joinDocumentComponent(board, created.getUuid(), wu);
		log.info("Created document component {} for specification {} on board {}", name, spec, board.getUuid());
		return created.getUuid();
	}

	/**
	 * The VCS repository row for the board's documents repo, created on first use.
	 *
	 * <p>Provisioned rather than merely looked up: a board names its documents repository as a
	 * string, and requiring an operator to separately register the same repository as a VCS row
	 * before any agent could publish would be a step nobody would discover until the first publish
	 * failed. {@code provisionVcsRepository} commits in its own transaction, which the source code
	 * entry below depends on — it validates its vcs against a committed row.
	 */
	/**
	 * The board's documents repository row.
	 *
	 * <p>Just the stored uuid now. It was resolved once when the board was configured, so every
	 * document's source code entry hangs off the same row the agent's own pushes and CI register
	 * against -- which is what makes commit recognition and signature verification apply to a
	 * document exactly as they do to code.
	 */
	private UUID resolveDocumentsVcs(AgentBoardData board) {
		return board.getDocumentsRepo();
	}

	/** A repository's uri, for a message. Falls back to the uuid when the row has gone. */
	private String repoUri(UUID vcsUuid) {
		return vcsRepositoryService.getVcsRepository(vcsUuid)
				.map(v -> VcsRepositoryData.dataFromRecord(v).getUri())
				.orElseGet(() -> String.valueOf(vcsUuid));
	}

	/** Compose a new document component into the board target's base feature set. */
	private void composeIntoTarget(AgentBoardData board, UUID componentUuid, WhoUpdated wu)
			throws RelizaException {
		Optional<Branch> base = branchService.getBaseBranchOfComponent(board.getTarget());
		if (base.isEmpty()) {
			throw new RelizaException("Board target has no base feature set to compose documents into");
		}
		BranchData bd = BranchData.branchDataFromDbRecord(base.get());
		List<ChildComponent> deps = null != bd.getDependencies()
				? new LinkedList<>(bd.getDependencies()) : new LinkedList<>();
		Optional<Branch> docBase = branchService.getBaseBranchOfComponent(componentUuid);
		deps.add(ChildComponent.builder()
				.uuid(componentUuid)
				.branch(docBase.map(Branch::getUuid).orElse(null))
				// IGNORED, not REQUIRED: the component is composed into the feature set so
				// document resolution can find it, but it must take no part in product-release
				// matching. A review item round is not a thing the product is built from, and making
				// one REQUIRED would let an unpublished review round hold up a release.
				.status(StatusEnum.IGNORED)
				.build());
		BranchDto branchDto = BranchDto.builder()
				.uuid(bd.getUuid())
				.dependencies(deps)
				.build();
		branchService.updateBranch(branchDto, wu);
	}

	/**
	 * An already-published release for the same content, if there is one.
	 *
	 * <p>The key is the task (or component), the specification, the commit and the digest. A CLI
	 * retry after a timeout re-sends all four, so it gets its original release back rather than
	 * minting round N+1 — which would also record a path the repository has no file at, because
	 * the path template contains the round.
	 */
	private Optional<ReleaseData> findEquivalent(AgentTaskData td, UUID componentUuid,
			PublishRequest req, BoardReviewItemIndex converted) {
		List<ReleaseData> candidates;
		if (null != td) {
			candidates = new ArrayList<>();
			if (null == td.getReleases()) return Optional.empty();
			for (UUID r : td.getReleases()) {
				sharedReleaseService.getReleaseData(r).ifPresent(candidates::add);
			}
		} else {
			Optional<Branch> docBase = branchService.getBaseBranchOfComponent(componentUuid);
			if (docBase.isEmpty()) return Optional.empty();
			candidates = sharedReleaseService.listReleaseDataOfBranch(docBase.get().getUuid());
		}
		// An index-only round has no commit and no file digest, so its identity is the index
		// itself: the same items published twice are the same round. Canonical JSON rather than
		// the request map, so key order cannot make one round look like two.
		// The CONVERTED index, matching what a publish of the same content would store.
		String indexDigest = null == converted ? null : canonicalIndexDigest(converted);
		for (int i = candidates.size() - 1; i >= 0; i--) {
			ReleaseData rd = candidates.get(i);
			DocumentRef doc = rd.getDocument();
			// A replaced version is not returned for a retry: its round has a newer version (task RD4-7).
			if (null == doc || doc.specification() != req.specification() || doc.superseded()) continue;
			if (StringUtils.isBlank(req.path())) {
				if (null != doc.path() || null == doc.reviewItems()) continue;
				if (indexDigest.equals(canonicalIndexDigest(doc.reviewItems()))) return Optional.of(rd);
				continue;
			}
			if (!req.digest().equals(doc.digest())) continue;
			Optional<String> commit = commitOf(rd);
			if (commit.isPresent() && commit.get().equals(req.commit())) return Optional.of(rd);
		}
		return Optional.empty();
	}

	private Optional<String> commitOf(ReleaseData rd) {
		if (null == rd.getSourceCodeEntry()) return Optional.empty();
		return getSourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry())
				.map(sce -> sce.getCommit());
	}

	/** How many rounds of this type the task already has. Counted under the task lock. */
	private int countRounds(AgentTaskData td, RearmSpecificationType spec) {
		if (null == td || null == td.getReleases()) return 0;
		int n = 0;
		for (UUID r : td.getReleases()) {
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(r);
			if (ord.isEmpty()) continue;
			// Settled only, like every other reader of this list: a reservation mid-cut and an
			// abandoned round the sweeper cancelled are not rounds, and numbering against them
			// would leave a gap named after a document nothing can read.
			if (!isSettledRound(ord.get().getLifecycle())) continue;
			DocumentRef doc = ord.get().getDocument();
			// A replaced version is its round's earlier copy, not a round of its own (task RD4-7).
			if (null != doc && doc.specification() == spec && td.getUuid().equals(doc.task()) && !doc.superseded()) n++;
		}
		return n;
	}

	/**
	 * The repository path a new document of this type would take on this board, every placeholder
	 * filled (gaps §1.18): {@code {key}} the task's key (board-documents.md D11), {@code {round}} the
	 * task's next round of the type -- the counter publish itself uses -- {@code {type}} lower case,
	 * and {@code {component}} the component's name slugged; then the board's documents root in
	 * front (D6). What the CLI publishes to when it is not given a file; the release records the
	 * path actually used.
	 *
	 * <p>Refuses when the template needs a task or a component the caller did not name, and when
	 * either does not belong to this board's organization -- a component's name is not the
	 * caller's to learn otherwise.
	 */
	public String resolveDocumentPath(AgentBoardData board, RearmSpecificationType spec, UUID taskUuid,
			UUID componentUuid) throws RelizaException {
		if (RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT == spec) {
			throw new RelizaException("BOARD_ELEMENT_CHECK_REPORT has no path: the board cuts it, and it is never a file");
		}
		String template = agentBoardService.effectiveDocumentPaths(board).get(spec);
		if (template.contains("{task}")) {
			// Unreachable: templates are normalised to {key} when a board is loaded and when they come in.
			throw new RelizaException("The " + spec + " path on board " + board.getName() + " (" + template
					+ ") uses {task}, which is gone; use {key}");
		}
		String path = template.replace("{type}", spec.name().toLowerCase(java.util.Locale.ROOT));
		if (path.contains("{key}") || path.contains("{round}")) {
			if (null == taskUuid) {
				throw new RelizaException("The " + spec + " path on board " + board.getName() + " (" + template
						+ ") is per task; name the task");
			}
			AgentTaskData td = agentTaskService.getTaskData(taskUuid)
					.filter(t -> board.getUuid().equals(t.getBoard()))
					.orElseThrow(() -> new RelizaException("Task " + taskUuid + " is not on board " + board.getName()));
			if (null == td.getKey()) {
				// A task from before keys gets its key on its first publish: number its board, read it again.
				agentTaskService.ensureNumbered(board.getUuid(), WhoUpdated.getAutoWhoUpdated());
				td = agentTaskService.getTaskData(taskUuid).orElse(td);
			}
			// {round} counts by task and type, not by path: a task numbered after its first round
			// continues at round 2 under its key while round 1 stays where it was written.
			path = path.replace("{key}", String.valueOf(td.getKey()))
					.replace("{round}", String.valueOf(countRounds(td, spec) + 1));
		}
		if (path.contains("{component}")) {
			if (null == componentUuid) {
				throw new RelizaException("The " + spec + " path on board " + board.getName() + " (" + template
						+ ") is per component; name the component");
			}
			ComponentData cd = getComponentService.getComponentData(componentUuid)
					.filter(c -> board.getOrg().equals(c.getOrg()))
					.orElseThrow(() -> new RelizaException("Component " + componentUuid + " not found"));
			String slug = io.reliza.common.Utils.slug(cd.getName());
			path = path.replace("{component}", slug.isEmpty() ? cd.getUuid().toString().substring(0, 8) : slug);
		}
		// The board's root, once, after the template (board-documents.md D6): boards/<slug>/ on a shared
		// repository, whatever the operator set, or nothing.
		return board.documentsRoot() + path;
	}

	/** The previous round's index for this task and type, for the carry-forward check. */
	private BoardReviewItemIndex previousRoundIndex(AgentTaskData td, RearmSpecificationType spec) {
		return previousRoundIndex(td, spec, null);
	}

	/**
	 * As {@link #previousRoundIndex(AgentTaskData, RearmSpecificationType)}, leaving out {@code skip}: the
	 * version a new version of its round replaces (task RD4-7), whose predecessor is the round before it.
	 */
	private BoardReviewItemIndex previousRoundIndex(AgentTaskData td, RearmSpecificationType spec, UUID skip) {
		return latestRoundRelease(td, spec, skip).map(rd -> rd.getDocument().reviewItems()).orElse(null);
	}

	/** The release of the newest settled round of {@code spec} on this task, if there is one. */
	public Optional<ReleaseData> latestRoundRelease(AgentTaskData td, RearmSpecificationType spec) {
		return latestRoundRelease(td, spec, null);
	}

	private Optional<ReleaseData> latestRoundRelease(AgentTaskData td, RearmSpecificationType spec, UUID skip) {
		if (null == td || null == td.getReleases()) return Optional.empty();
		for (int i = td.getReleases().size() - 1; i >= 0; i--) {
			if (td.getReleases().get(i).equals(skip)) continue;
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(td.getReleases().get(i));
			// A replaced version is never the newest of its round (task RD4-7).
			if (ord.isEmpty() || isSuperseded(ord.get())) continue;
			// A cancelled or half-cut round must not become "the latest": the next round would be
			// computed against items nothing else can read, and its carry-forward would resurrect
			// them. Skipping it makes the sweeper's CANCELLED a usable stale signal -- the chain
			// falls back to the last round that settled.
			if (!isSettledRound(ord.get().getLifecycle())) continue;
			DocumentRef doc = ord.get().getDocument();
			if (null != doc && doc.specification() == spec && td.getUuid().equals(doc.task())
					&& null != doc.reviewItems() && indexIsOfItsKind(doc)) {
				return ord;
			}
		}
		return Optional.empty();
	}

	/**
	 * Whether a round's index is of the kind it is filed under. Before task bc7fc25a the board
	 * unwound a tester's or reviewer's review item frame by cutting a round filed under BOARD_QUESTIONS with
	 * the BOARD_TEST_REPORT or BOARD_REVIEW_ITEMS index inside; those rounds stay as the record of what
	 * happened, but they are not questions, so no reader of the latest round -- open questions, the
	 * questions carry-forward -- takes them for one. An index without a kind predates the field and
	 * counts as what it is filed under.
	 */
	static boolean indexIsOfItsKind(DocumentRef doc) {
		RearmSpecificationType kind = null == doc.reviewItems() ? null : doc.reviewItems().kind();
		return null == kind || kind == doc.specification();
	}

	/**
	 * Whether a round is a person's decision round (operator-actions D23): cut without a session,
	 * and recording a person as the decider of at least one item in this very round. Only a
	 * person's decision does that -- the board's policy round decides nothing in a person's name,
	 * and every agent round carries its session -- so a round this does not recognise fails safe:
	 * the role it would have passed stays unpassed.
	 */
	public static boolean isPersonDecisionRound(ReleaseData rd) {
		if (null == rd || null == rd.getDocument() || null != rd.getDocument().session()
				|| null == rd.getDocument().reviewItems()) {
			return false;
		}
		return rd.getDocument().reviewItems().reviewItems().stream()
				.anyMatch(f -> f.decidedByPerson() && rd.getUuid().equals(f.decidedIn()));
	}

	/**
	 * Whether a person has decided over a role's rejection (operator-actions D23, with the
	 * architect's two conditions): the rejection published a review item index with something
	 * blocking at the blocking priority, and the newest round of that index is a person's decision
	 * round reading PASSED. A rejection with nothing blocking stated no reason, so nothing was
	 * decided over it. Shared by a person's complete and by routing, so the two cannot disagree
	 * about whether a role has passed.
	 */
	boolean rejectionDecidedOver(AgentTaskData td, Integer blockingPriority, AgentTaskData.SignOff rejected) {
		if (null == rejected || null == rejected.outputs()) return false;
		for (UUID r : rejected.outputs()) {
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(r);
			if (ord.isEmpty() || null == ord.get().getDocument() || null == ord.get().getDocument().reviewItems()) continue;
			DocumentRef rejectedRound = ord.get().getDocument();
			if (rejectedRound.reviewItems().blockingReviewItems(blockingPriority).isEmpty()) continue;
			Optional<ReleaseData> newest = latestRoundRelease(td, rejectedRound.specification());
			if (newest.isPresent() && isPersonDecisionRound(newest.get())
					&& BoardReviewVerdict.PASSED == newest.get().getDocument().reviewItems().verdict()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the latest round of any review or test index on the task still has an item blocking
	 * at the blocking priority: what decides that a person's decisions have cleared the way
	 * (operator-actions D9 as amended).
	 */
	boolean anyLatestRoundBlocks(AgentTaskData td, Integer blockingPriority) {
		for (RearmSpecificationType spec : List.of(RearmSpecificationType.BOARD_REVIEW_ITEMS,
				RearmSpecificationType.BOARD_TEST_REPORT)) {
			Optional<ReleaseData> latest = latestRoundRelease(td, spec);
			if (latest.isPresent()
					&& !latest.get().getDocument().reviewItems().blockingReviewItems(blockingPriority).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Cut a policy round: the board closing items nobody is going to answer.
	 *
	 * <p>Only reachable when a loop has stopped -- a cycle cap, a no-progress stop or a budget --
	 * and the board is completing the task under its completion priority. What it may do is
	 * deliberately narrow: move OPEN items to {@link BoardReviewItemStatus#POLICY_ACCEPTED}, and nothing
	 * else. It cannot add an item, change a title, touch a file or alter a verdict, because a
	 * board that could edit review items would be a board that could make a review say something the
	 * reviewer did not.
	 *
	 * <p>A new round rather than a rewrite. An index's digest is part of the release's identity and
	 * its idempotency key, and a review items release may be pinned as a STRICT_LATEST input on
	 * another task; rewriting one under a recorded digest is exactly what the handoff design
	 * exists to prevent. So the policy decision is visible as a round, authored by the board.
	 *
	 * <p>No session: the hop that would have owned it has closed. The holds-task check is skipped
	 * for the same reason -- there is no assignment to hold -- which is why this is a separate
	 * entry point rather than a flag on {@link #publish}.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public ReleaseData publishPolicyRound(AgentTaskData td, RearmSpecificationType spec,
			BoardReviewItemIndex previous, String reason, WhoUpdated wu) throws RelizaException {
		if (null == previous || previous.openReviewItems().isEmpty()) return null;
		// The stop reason is words, so it goes in resolution, not in resolvedBy where it used to
		// live. The pointer is written after this round has a uuid: nothing else answered these
		// items, so what closed them is this round, and cutBoardRound stamps it (D13).
		List<BoardReviewItem> closed = BoardReviewItemIndex.carryForward(previous.reviewItems()).stream()
				.map(f -> BoardReviewItemStatus.OPEN == f.status()
						? f.closedBy(BoardReviewItemStatus.POLICY_ACCEPTED, null, reason)
						: f)
				.toList();
		BoardReviewItemIndex policy = new BoardReviewItemIndex(previous.kind(), null, previous.verdict(),
				previous.counts(), closed, previous.about());
		Set<String> stopped = previous.openReviewItems().stream().map(BoardReviewItem::id)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		ReleaseData rd = cutBoardRound(td, spec, policy, stopped, wu);
		log.info("Board cut a policy round of {} for task {} as release {}: {} item(s) accepted by policy",
				spec, td.getUuid(), rd.getUuid(), previous.openReviewItems().size());
		return rd;
	}

	/**
	 * Close the ids a human answered, as a BOARD_QUESTIONS round.
	 *
	 * <p>The answer is a round rather than a note because a note is prose the asking agent has to
	 * go and find and cannot pin as an input. As a round it arrives in the asker's bindings, and
	 * the no-progress rule sees the open set change rather than the same ids twice.
	 *
	 * <p>Takes no lock and saves no task: the caller owns the one copy written in its transaction
	 * and adds the returned release to it (D14).
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public ReleaseData publishAnswerRound(AgentTaskData td, BoardReviewItemIndex previous,
			List<Answer> answers, WhoUpdated wu) throws RelizaException {
		if (null == previous) {
			throw new RelizaException("Task " + td.getUuid() + " has no questions round to answer");
		}
		if (null == answers || answers.isEmpty()) {
			throw new RelizaException("An answer round needs at least one answered id");
		}
		Set<String> open = previous.openReviewItems().stream().map(BoardReviewItem::id)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, Answer> byId = new LinkedHashMap<>();
		for (Answer a : answers) {
			if (!open.contains(a.id())) {
				throw new RelizaException("Review item " + a.id() + " is not open on the latest"
						+ " questions round of task " + td.getUuid());
			}
			if (BoardReviewItemStatus.RESOLVED != a.status() && BoardReviewItemStatus.WITHDRAWN != a.status()) {
				throw new RelizaException("An answer sets RESOLVED or WITHDRAWN, not " + a.status());
			}
			if (StringUtils.isBlank(a.resolution())) {
				throw new RelizaException("Review item " + a.id() + " needs an answer");
			}
			byId.put(a.id(), a);
		}
		List<BoardReviewItem> next = BoardReviewItemIndex.carryForward(previous.reviewItems()).stream()
				.map(f -> {
					Answer a = byId.get(f.id());
					return null == a ? f : f.closedBy(a.status(), null, a.resolution());
				})
				.toList();
		BoardReviewItemIndex answered = new BoardReviewItemIndex(previous.kind(), null, previous.verdict(),
				previous.counts(), next, previous.about());
		ReleaseData rd = cutBoardRound(td, RearmSpecificationType.BOARD_QUESTIONS, answered,
				byId.keySet(), wu);
		log.info("Answer round for task {} as release {}: {} id(s) closed", td.getUuid(),
				rd.getUuid(), byId.size());
		return rd;
	}

	/** One answered id: what it becomes, and the words that closed it. */
	public record Answer(String id, BoardReviewItemStatus status, String resolution) {}

	/**
	 * Close the ids a frame was waiting on, because the role that produces the questioned input
	 * passed (D11).
	 *
	 * <p>An agent answers by republishing its document, which carries no index and so cannot close
	 * an id by itself. Without this round the asked ids would stay OPEN for the life of the task
	 * even though they were answered, and a human answer and an agent answer would leave different
	 * records of the same event. {@code resolvedBy} points at the release that answered, which is
	 * the one case where the pointer names a document other than the round carrying the item.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public ReleaseData publishUnwindRound(AgentTaskData td, BoardReviewItemIndex previous,
			UUID answeringRelease, WhoUpdated wu) throws RelizaException {
		if (null == previous || previous.openReviewItems().isEmpty()) return null;
		String words = "answered by release " + answeringRelease;
		List<BoardReviewItem> closed = BoardReviewItemIndex.carryForward(previous.reviewItems()).stream()
				.map(f -> BoardReviewItemStatus.OPEN == f.status()
						? f.closedBy(BoardReviewItemStatus.RESOLVED, answeringRelease, words)
						: f)
				.toList();
		BoardReviewItemIndex unwound = new BoardReviewItemIndex(previous.kind(), null, previous.verdict(),
				previous.counts(), closed, previous.about());
		// No self-stamp: these items already point at the release that answered them, which is a
		// different document and the more useful pointer.
		ReleaseData rd = cutBoardRound(td, RearmSpecificationType.BOARD_QUESTIONS, unwound, Set.of(), wu);
		log.info("Board closed {} question(s) on task {} as release {}, answered by {}",
				previous.openReviewItems().size(), td.getUuid(), rd.getUuid(), answeringRelease);
		return rd;
	}

	/** What a person does to one review item (operator-actions D6). */
	public enum BoardReviewItemDecisionAction { ACCEPT, DISMISS, SET_PRIORITY, FILE }

	/**
	 * One decision in a person's submission.
	 *
	 * @param reviewItemId the review item decided; absent on FILE, where the board numbers it
	 * @param priority the new priority on SET_PRIORITY, the priority of the filed review item on FILE
	 * @param title the filed review item's title, FILE only
	 * @param location where the filed review item points, FILE only and optional
	 * @param resolution the person's words, required on ACCEPT and DISMISS
	 */
	public record BoardReviewItemDecision(BoardReviewItemDecisionAction action, String reviewItemId, Integer priority,
			String title, BoardReviewItemLocation location, String resolution) {}

	/**
	 * A person's decision round before it is cut, or the round an identical earlier submission cut.
	 *
	 * @param landed the round a retried submission already cut; null when there is a round to cut
	 * @param index the round's items, attribution set and {@code decidedIn} not yet stamped
	 * @param closedIds the ids this round accepts or dismisses, stamped with the round as resolvedBy
	 * @param addsBlocking whether it leaves an item blocking at the board's blocking priority that
	 *        the previous round did not, which is what routes it (D9) and what a hold refuses (D8)
	 */
	public record DecisionPlan(RearmSpecificationType spec, ReleaseData landed, BoardReviewItemIndex index,
			Set<String> closedIds, boolean addsBlocking) {}

	private static final java.util.regex.Pattern FILED_ID = java.util.regex.Pattern.compile("P-(\\d+)");

	/**
	 * Work out a person's decision round against the task's latest index of {@code spec}, without
	 * cutting it (operator-actions §3.1).
	 *
	 * <p>Separate from the cut so the caller can apply D8 -- which depends on whether the round adds
	 * a blocking item -- before anything is written.
	 *
	 * <p><b>A retried submission finds the round the first one cut.</b> The first cut changed the
	 * latest index, so the same decisions applied to it again are refused or say something else
	 * (an accepted item is no longer open, a filed review item would take the next number). So when the
	 * latest round is a person's decision round, the decisions are first applied to the round
	 * before it, and if that reproduces the latest round's identity this is the same submission
	 * again. The identity carries the decider, so it only ever matches the same person's.
	 *
	 * @param filedAsCorrections whether what the person files is a correction (task cac71351): true
	 *        when they are accepting, so the work they file travels with the task without holding
	 *        the acceptance back; false everywhere else, where a filed review item blocks like any other
	 */
	public DecisionPlan planDecisionRound(AgentTaskData td, RearmSpecificationType spec,
			List<BoardReviewItemDecision> decisions, About about, AgentActor by, Integer blockingPriority,
			boolean filedAsCorrections) throws RelizaException {
		if (RearmSpecificationType.BOARD_REVIEW_ITEMS != spec && RearmSpecificationType.BOARD_TEST_REPORT != spec) {
			throw new RelizaException("People decide BOARD_REVIEW_ITEMS and BOARD_TEST_REPORT review items, not " + spec
					+ "; questions are answered, not decided");
		}
		if (null == decisions || decisions.isEmpty()) {
			throw new RelizaException("A review item decision needs at least one decision");
		}
		if (null == by || AgentActor.ActorKind.USER != by.kind()) {
			throw new RelizaException("Review item decisions are a person's; agents decide through their own rounds");
		}
		int levels = priorityLevels(td.getOrg());
		Optional<ReleaseData> latest = latestRoundRelease(td, spec);
		if (latest.isPresent() && isPersonDecisionRound(latest.get())
				&& null != latest.get().getDocument().indexDigest()) {
			BoardReviewItemIndex before = roundBefore(td, spec, latest.get().getUuid())
					.map(rd -> rd.getDocument().reviewItems()).orElse(null);
			Applied again = applyDecisions(spec, before, decisions, about, by, blockingPriority, levels,
					filedAsCorrections);
			if (again.problems().isEmpty() && latest.get().getDocument().indexDigest()
					.equals(canonicalIndexDigest(BoardReviewItemIndexValidator.stampElements(again.index(), before,
							elementPositions(td))))) {
				return new DecisionPlan(spec, latest.get(), latest.get().getDocument().reviewItems(),
						again.closedIds(), false);
			}
		}
		BoardReviewItemIndex previous = latest.map(rd -> rd.getDocument().reviewItems()).orElse(null);
		Applied applied = applyDecisions(spec, previous, decisions, about, by, blockingPriority, levels,
				filedAsCorrections);
		if (!applied.problems().isEmpty()) {
			throw new RelizaException(String.join("; ", applied.problems()));
		}
		// A review item a person files may name an element, resolved and positioned like an agent's.
		BoardReviewItemIndex stamped = BoardReviewItemIndexValidator.stampElements(applied.index(), previous, elementPositions(td));
		Set<String> blockingBefore = null == previous ? Set.of()
				: ids(previous.blockingReviewItems(blockingPriority));
		boolean adds = !blockingBefore.containsAll(ids(stamped.blockingReviewItems(blockingPriority)));
		return new DecisionPlan(spec, null, stamped, applied.closedIds(), adds);
	}

	/**
	 * Cut the round a plan describes, through the board's session-less path.
	 *
	 * <p>Takes no lock and saves no task, like the other board-cut rounds: the caller owns the one
	 * copy of the task written in its transaction and adds the release to it (D14 of the
	 * human-answers brief).
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public ReleaseData cutDecisionRound(AgentTaskData td, DecisionPlan plan, WhoUpdated wu)
			throws RelizaException {
		if (null != plan.landed()) return plan.landed();
		ReleaseData rd = cutBoardRound(td, plan.spec(), plan.index(), plan.closedIds(), wu);
		log.info("Decision round of {} for task {} as release {}", plan.spec(), td.getUuid(), rd.getUuid());
		return rd;
	}

	// ---------- Element checks (gaps §2.A, task 2e0fffa6; elements.md §7) ----------

	/**
	 * What the checks of one document can see: the document, the task's latest release of each
	 * specification, and the releases bound to the current assignment -- the universe
	 * {@code ElementIndexValidator} resolves against (elements.md §6). One release per document
	 * series, the checked one winning, so an older round of the same document does not read as a
	 * second definition of every id in it.
	 */
	ElementCheckCatalogueService.RunScope checkScope(AgentBoardData board, AgentTaskData td, ReleaseData checked) {
		DocumentRef doc = checked.getDocument();
		return checkScope(board, td, new ElementCheckCatalogueService.ScopedDocument(checked.getUuid(), doc.specification(),
				checked.getLifecycle(), doc.elements()), seriesOf(checked));
	}

	/** As above for a document given as its scoped form and its series (a preview has no release). */
	private ElementCheckCatalogueService.RunScope checkScope(AgentBoardData board, AgentTaskData td,
			ElementCheckCatalogueService.ScopedDocument checked, String checkedSeries) {
		List<ElementCheckCatalogueService.ScopedDocument> docs = new ArrayList<>();
		Set<String> series = new java.util.HashSet<>();
		docs.add(checked);
		series.add(checkedSeries);
		java.util.Map<RearmSpecificationType, ReleaseData> latest = new java.util.LinkedHashMap<>();
		for (UUID r : null == td.getReleases() ? List.<UUID>of() : td.getReleases()) {
			sharedReleaseService.getReleaseData(r)
					.filter(rd -> null != rd.getDocument() && null != rd.getDocument().elements() && !isSuperseded(rd))
					.ifPresent(rd -> latest.put(rd.getDocument().specification(), rd));
		}
		latest.values().forEach(rd -> addScoped(docs, series, rd));
		Map<RearmSpecificationType, ReleaseLifecycle> floors = null;
		if (null != td.getAssignment()) {
			if (null != td.getAssignment().resolvedInputs()) {
				td.getAssignment().resolvedInputs().stream()
						.map(io.reliza.model.AgentTaskInput.ResolvedInput::release)
						.filter(java.util.Objects::nonNull)
						.forEach(r -> sharedReleaseService.getReleaseData(r).ifPresent(rd -> addScoped(docs, series, rd)));
			}
			floors = new java.util.EnumMap<>(RearmSpecificationType.class);
			AgentTaskRoleConfigData rc = agentBoardService.getRoleConfig(board.getUuid(), td.getAssignment().role())
					.orElse(null);
			for (io.reliza.model.AgentTaskInput.RequiredInput ri : agentTaskInputService.effectiveRequirements(rc, td)) {
				if (io.reliza.model.AgentTaskInput.InputKind.DOCUMENT != ri.kind() || null == ri.specification()
						|| null == ri.minLifecycle()) continue;
				floors.merge(ri.specification(), ri.minLifecycle(), (a, b) ->
						AgentTaskInputService.maturity(b) > AgentTaskInputService.maturity(a) ? b : a);
			}
		}
		return new ElementCheckCatalogueService.RunScope(td.getUuid(), checked.release(), docs,
				board.getEffectiveElementFamilies(), board.getEffectiveElementCheckPolicy(), floors,
				board.getEffectiveElementFamilyDefinedIn());
	}

	/** A document's series: its type on its task, or its component for a component-scoped document. */
	private static String seriesOf(ReleaseData rd) {
		DocumentRef doc = rd.getDocument();
		return null != doc.task() ? doc.specification() + "/" + doc.task() : "component/" + rd.getComponent();
	}

	private static void addScoped(List<ElementCheckCatalogueService.ScopedDocument> docs, Set<String> series, ReleaseData rd) {
		DocumentRef doc = rd.getDocument();
		if (null == doc || null == doc.elements()) return;
		if (!series.add(seriesOf(rd))) return;
		docs.add(new ElementCheckCatalogueService.ScopedDocument(rd.getUuid(), doc.specification(), rd.getLifecycle(),
				doc.elements()));
	}

	/**
	 * Cut a BOARD_ELEMENT_CHECK_REPORT round on a task: index-only, PENDING to ASSEMBLED, numbered among the task's
	 * reports. The sibling of {@link #cutBoardRound}, under the same rules: <b>no lock taken and no
	 * task saved</b> -- the caller holds the locks and links the returned release to the copy it owns.
	 *
	 * <p>Idempotent: when the newest report on the same document is this very report (same digest),
	 * that one is returned. Only the newest -- a report equal to one two runs ago is a change back,
	 * and returning the old round would leave a newer, different one reading as current.
	 */
	private ReleaseData cutElementCheckReport(AgentTaskData td, ElementCheckReport report, WhoUpdated wu) throws RelizaException {
		Optional<ReleaseData> latest = latestElementCheckReport(td, report.scope().checked());
		if (latest.isPresent() && report.digest().equals(latest.get().getDocument().elementChecks().digest())) {
			log.info("Check report for release {} on task {} unchanged; returning round {}", report.scope().checked(),
					td.getUuid(), latest.get().getDocument().round());
			return latest.get();
		}
		AgentBoardData board = agentBoardService.getBoardData(td.getBoard())
				.orElseThrow(() -> new RelizaException("Board not found: " + td.getBoard()));
		// Both callers -- publish and runElementChecks -- hold the board lock, so the component may be recorded.
		UUID componentUuid = resolveOrCreateComponent(board,
				new PublishRequest(td.getUuid(), RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT, null, null, null, null, null,
						null, null, null, null, null, null, null),
				true, wu);
		Optional<Branch> base = branchService.getBaseBranchOfComponent(componentUuid);
		UUID baseBranch = base.map(Branch::getUuid).orElse(null);
		String version = versionAssignmentService
				.getSetNewVersionWrapper(baseBranch, null, null, null)
				.map(VersionAssignment::getVersion)
				.orElseThrow(() -> new RelizaException("Could not mint a version for the check report"));
		int round = countRounds(td, RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT) + 1;
		DocumentRef doc = new DocumentRef(RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT, null, null, null, null,
				report.digest(), td.getUuid(), null, round, null, null, report);
		Release row = ossReleaseService.createRelease(ReleaseDto.builder()
				.version(version)
				.org(td.getOrg())
				.component(componentUuid)
				.branch(baseBranch)
				.lifecycle(ReleaseLifecycle.PENDING)
				.document(doc)
				.build(), wu);
		ReleaseData rd = ReleaseData.dataFromRecord(
				ossReleaseService.updateReleaseLifecycle(row.getUuid(), ReleaseLifecycle.ASSEMBLED, wu));
		log.info("Check report round {} for release {} on task {} as release {}", round, report.scope().checked(),
				td.getUuid(), rd.getUuid());
		return rd;
	}

	/** The newest settled BOARD_ELEMENT_CHECK_REPORT round on this task about this release. */
	public Optional<ReleaseData> latestElementCheckReport(AgentTaskData td, UUID checkedRelease) {
		if (null == td || null == td.getReleases() || null == checkedRelease) return Optional.empty();
		for (int i = td.getReleases().size() - 1; i >= 0; i--) {
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(td.getReleases().get(i));
			if (ord.isEmpty() || !isSettledRound(ord.get().getLifecycle())) continue;
			DocumentRef doc = ord.get().getDocument();
			if (null != doc && RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT == doc.specification() && null != doc.elementChecks()
					&& null != doc.elementChecks().scope() && checkedRelease.equals(doc.elementChecks().scope().checked())) {
				return ord;
			}
		}
		return Optional.empty();
	}

	/** The newest report about a release, found through the task the release belongs to. */
	public Optional<ReleaseData> latestElementCheckReport(UUID checkedRelease) {
		return sharedReleaseService.getReleaseData(checkedRelease)
				.map(ReleaseData::getDocument)
				.map(DocumentRef::task)
				.flatMap(agentTaskService::getTaskData)
				.flatMap(td -> latestElementCheckReport(td, checkedRelease));
	}

	/** The newest report for each document of this task that has one, newest document first. */
	public List<ElementCheckReport> elementChecksOfTask(AgentTaskData td) {
		List<ElementCheckReport> out = new ArrayList<>();
		Set<UUID> seen = new java.util.HashSet<>();
		if (null == td || null == td.getReleases()) return out;
		for (int i = td.getReleases().size() - 1; i >= 0; i--) {
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(td.getReleases().get(i));
			if (ord.isEmpty() || !isSettledRound(ord.get().getLifecycle())) continue;
			DocumentRef doc = ord.get().getDocument();
			if (null == doc || RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT != doc.specification() || null == doc.elementChecks()
					|| null == doc.elementChecks().scope()) continue;
			// A report about a replaced version is history: the new version's own report is current (RD4-7).
			if (sharedReleaseService.getReleaseData(doc.elementChecks().scope().checked()).map(AgentDocumentService::isSuperseded)
					.orElse(false)) continue;
			if (seen.add(doc.elementChecks().scope().checked())) out.add(doc.elementChecks());
		}
		return out;
	}

	/**
	 * Refuse a hand-over while a check the board blocks on failed on one of its documents
	 * (elements.md §7). Reads the newest report on each DRAFT element-bearing output; writes nothing,
	 * so the refusal rolls nothing back and the hop can fix, republish and sign off again. An output
	 * with no report -- published before the checks existed, or carrying no elements -- passes.
	 */
	public void assertBlockingElementChecksPass(AgentTaskData td, List<UUID> outputs) throws RelizaException {
		if (null == outputs) return;
		List<String> problems = new ArrayList<>();
		for (UUID r : outputs) {
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(r);
			if (ord.isEmpty() || ReleaseLifecycle.DRAFT != ord.get().getLifecycle()) continue;
			DocumentRef doc = ord.get().getDocument();
			if (null == doc || null == doc.elements()) continue;
			Optional<ReleaseData> report = latestElementCheckReport(td, r);
			if (report.isEmpty()) continue;
			List<ElementCheckReport.ElementCheckResult> failing = report.get().getDocument().elementChecks().results().stream()
					.filter(c -> ElementCheckReport.Result.FAIL == c.result() && c.blocking()).toList();
			if (failing.isEmpty()) continue;
			problems.add(doc.specification() + " round " + doc.round() + " (check report round "
					+ report.get().getDocument().round() + "): " + failing.stream()
							.map(AgentDocumentService::describeFailure)
							.collect(java.util.stream.Collectors.joining(", ")));
		}
		if (!problems.isEmpty()) {
			throw new RelizaException("hand-over refused: blocking check(s) failed on " + String.join("; ", problems)
					+ " -- fix and republish, or run `rearm agent doc element-check` after the inputs change");
		}
	}

	private static String describeFailure(ElementCheckReport.ElementCheckResult c) {
		// Each offence names its element: most checks' messages already do, but a coverage gate's is
		// its description and a glossary offence names the term, and the author has to know which
		// element to fix (723e0178, round 2, T-1).
		List<String> shown = c.offences().stream().limit(3).map(AgentDocumentService::describeOffence).toList();
		int more = c.offences().size() - shown.size();
		return c.check() + " (" + String.join("; ", shown) + (more > 0 ? "; and " + more + " more" : "") + ")";
	}

	private static String describeOffence(ElementCheckReport.Offence o) {
		String message = null == o.message() ? "" : o.message();
		if (null == o.elementId() || message.contains(o.elementId())) return message;
		return o.elementId() + ": " + message;
	}

	/**
	 * Re-run the checks of one document in its current scope, and cut a new report when anything
	 * changed (or return the newest, when nothing did). The inputs of a document move -- an upstream
	 * round lands, a draft is baselined -- and a report is only as current as its scope.
	 *
	 * <p>Takes the board lock and then the task lock, like publish, and owns the task copy it links
	 * the report to.
	 *
	 * @param session the calling session, which must hold the task; null for a person
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public ReleaseData runElementChecks(UUID releaseUuid, AgentSessionData session, WhoUpdated wu) throws RelizaException {
		ReleaseData checked = sharedReleaseService.getReleaseData(releaseUuid)
				.orElseThrow(() -> new RelizaException("Release not found: " + releaseUuid));
		DocumentRef doc = checked.getDocument();
		if (null == doc || null == doc.elements()) {
			throw new RelizaException("Release " + releaseUuid + " carries no element index: there is nothing to check");
		}
		if (null == doc.task()) {
			throw new RelizaException("Release " + releaseUuid + " is a component-scoped document; its elements are"
					+ " checked in a task's scope, and component-scoped documents are not checked yet");
		}
		AgentTaskData pre = agentTaskService.getTaskData(doc.task())
				.orElseThrow(() -> new RelizaException("Task not found: " + doc.task()));
		AgentBoard boardRow = boardRepository.findByIdWriteLocked(pre.getBoard())
				.orElseThrow(() -> new RelizaException("Board not found: " + pre.getBoard()));
		entityManager.refresh(boardRow);
		AgentBoardData board = AgentBoardData.dataFromRecord(boardRow);
		AgentTask taskRow = taskRepository.findByIdWriteLocked(doc.task())
				.orElseThrow(() -> new RelizaException("Task not found: " + doc.task()));
		entityManager.refresh(taskRow);
		AgentTaskData td = AgentTaskData.dataFromRecord(taskRow);
		if (null != session) assertSessionHoldsTask(td, session);
		ReleaseData current = sharedReleaseService.getReleaseData(releaseUuid).orElse(checked);
		ReleaseData report = cutElementCheckReport(td, elementCheckCatalogueService.run(checkScope(board, td, current)), wu);
		if (!td.getReleases().contains(report.getUuid())) {
			td.addRelease(report.getUuid());
			agentTaskService.saveData(td, wu);
		}
		return report;
	}

	/**
	 * Hand a hop's outputs to the next role: every one still at DRAFT becomes ASSEMBLED
	 * (operator-actions D13).
	 *
	 * <p>The producer no longer certifies its own document (D12); signing off is what hands it
	 * over, so this runs at sign-off, before routing binds the next role's inputs at their floors.
	 * Through the release lifecycle call the board rounds use, so lifecycle triggers fire once and
	 * an org guard is consulted. A guard that refuses the hand-over refuses the sign-off, cleanly:
	 * the refusal becomes this method's RelizaException, naming the round and the guard, and the
	 * sign-off's transaction rolls back whatever was written before it (task a8b519a0). The hop
	 * cannot hand over what the organization will not let reach ASSEMBLED.
	 *
	 * <p>Only DRAFT moves. An output published before this change may already be ASSEMBLED or
	 * later. Past ASSEMBLED the board moves a document only when it was reviewed
	 * ({@link #promoteReviewed}, D15).
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public void promoteOutputs(List<UUID> outputs, WhoUpdated wu) throws RelizaException {
		if (null == outputs) return;
		for (UUID r : outputs) {
			Optional<ReleaseData> rd = sharedReleaseService.getReleaseData(r);
			if (rd.isEmpty() || ReleaseLifecycle.DRAFT != rd.get().getLifecycle()) continue;
			try {
				// The overload whose transaction does not roll back on a guard refusal: the refusal
				// is thrown before anything is written, and it reaches the caller as a RelizaException
				// with its reason rather than as an UnexpectedRollbackException at commit.
				ossReleaseService.updateReleaseLifecycle(r, ReleaseLifecycle.ASSEMBLED, wu, true, null, null);
			} catch (ActionRefusedException e) {
				throw new RelizaException("Hand-over refused: " + roundLabel(rd.get()) + " cannot reach ASSEMBLED — "
						+ e.getMessage());
			}
		}
	}

	/** A document release as a reader names it: "BOARD_TEST_REPORT round 2". */
	private static String roundLabel(ReleaseData rd) {
		DocumentRef doc = rd.getDocument();
		if (null == doc) return "release " + rd.getUuid();
		return doc.specification() + (null == doc.round() ? "" : " round " + doc.round());
	}

	/** A document a review would have promoted to READY_TO_SHIP and a guard would not let reach it. */
	public record RefusedPromotion(UUID release, String label, String reason) {}

	/**
	 * A review passed: the documents it reviewed become READY_TO_SHIP (operator-actions D14/D15,
	 * task 0192a587), which on a board means "reviewed" -- by a reviewer role's pass over the
	 * documents it had pinned, or by a person accepting at a gate.
	 *
	 * <p>Only ASSEMBLED moves, and only a board document (a release carrying a DocumentRef). A
	 * DRAFT was never handed over, so a review of it is not a review of the handed-over work; a
	 * release already past READY_TO_SHIP was moved on by people and is theirs. Through the release
	 * lifecycle call, so an organization guard is consulted and the lifecycle event is recorded.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public List<RefusedPromotion> promoteReviewed(List<UUID> releases, WhoUpdated wu) throws RelizaException {
		return promoteReviewedRecording(releases, wu).refused();
	}

	/**
	 * What a review's promotions did (task fda2c9f1): {@code made} for the sign-off to record, one
	 * entry per document that moved or that a guard kept where it was; {@code refused} as
	 * {@link #promoteReviewed} returns it, for the feed and for routing.
	 */
	public record Promotions(List<AgentTaskData.Promotion> made, List<RefusedPromotion> refused) {}

	/** As {@link #promoteReviewed}, also saying what moved, so the sign-off can record it. */
	@Transactional(rollbackFor = RelizaException.class)
	public Promotions promoteReviewedRecording(List<UUID> releases, WhoUpdated wu) throws RelizaException {
		List<AgentTaskData.Promotion> made = new ArrayList<>();
		List<RefusedPromotion> refused = new ArrayList<>();
		if (null == releases) return new Promotions(made, refused);
		for (UUID r : releases) {
			Optional<ReleaseData> rd = sharedReleaseService.getReleaseData(r);
			if (rd.isEmpty() || null == rd.get().getDocument()
					|| ReleaseLifecycle.ASSEMBLED != rd.get().getLifecycle()) continue;
			try {
				// Best effort (task a8b519a0): the promotion is a consequence of the review, not the
				// review. A guard that keeps READY_TO_SHIP for people -- 3204c981's first example --
				// refuses an agent's review promotion, and that must not fail the review itself. The
				// overload that does not roll back on the refusal, which it throws before writing.
				ossReleaseService.updateReleaseLifecycle(r, ReleaseLifecycle.READY_TO_SHIP, wu, true, null, null);
				made.add(new AgentTaskData.Promotion(r, ReleaseLifecycle.READY_TO_SHIP, null));
			} catch (ActionRefusedException e) {
				// Not logged: a guard refusing is the guard doing its job, and the caller puts it
				// on the board's feed, where the operator reads it, and on the sign-off.
				refused.add(new RefusedPromotion(r, roundLabel(rd.get()), e.getMessage()));
				made.add(new AgentTaskData.Promotion(r, null, e.getMessage()));
			}
		}
		return new Promotions(made, refused);
	}

	private record Applied(BoardReviewItemIndex index, Set<String> closedIds, List<String> problems) {}

	/**
	 * A person's decisions applied to the previous round: carry every item forward with its
	 * attribution (D21), then apply each decision in the person's name. Collects every problem
	 * rather than stopping at the first, so one refusal lists everything wrong with the submission.
	 * Filed at an acceptance, a review item is a correction (task cac71351).
	 */
	private static Applied applyDecisions(RearmSpecificationType spec, BoardReviewItemIndex previous,
			List<BoardReviewItemDecision> decisions, About about, AgentActor by, Integer blockingPriority,
			int levels, boolean filedAsCorrections) {
		List<String> problems = new ArrayList<>();
		List<BoardReviewItem> items = new ArrayList<>(
				null == previous ? List.of() : BoardReviewItemIndex.carryForward(previous.reviewItems()));
		Map<String, Integer> at = new LinkedHashMap<>();
		for (int i = 0; i < items.size(); i++) at.put(items.get(i).id(), i);
		int nextFiled = items.stream()
				.map(f -> FILED_ID.matcher(null == f.id() ? "" : f.id()))
				.filter(java.util.regex.Matcher::matches)
				.mapToInt(m -> Integer.parseInt(m.group(1)))
				.max().orElse(0) + 1;
		Set<String> decided = new LinkedHashSet<>();
		Set<String> closed = new LinkedHashSet<>();
		for (BoardReviewItemDecision d : decisions) {
			if (null == d || null == d.action()) {
				problems.add("every decision needs an action");
				continue;
			}
			if (BoardReviewItemDecisionAction.FILE == d.action()) {
				if (StringUtils.isNotBlank(d.reviewItemId())) {
					problems.add("a filed review item is numbered by the board; leave reviewItemId out");
					continue;
				}
				if (StringUtils.isBlank(d.title())) {
					problems.add("a filed review item needs a title");
					continue;
				}
				if (!priorityInRange(d.priority(), levels)) {
					problems.add("a filed review item needs a priority in 1.." + levels);
					continue;
				}
				String id = "P-" + nextFiled++;
				BoardReviewItem filed = new BoardReviewItem(id, d.priority(), BoardReviewItemStatus.OPEN, d.title().trim(), d.location(),
						null, null, by, null);
				items.add(filedAsCorrections ? filed.asCorrection() : filed);
				continue;
			}
			Integer i = null == d.reviewItemId() ? null : at.get(d.reviewItemId());
			if (null == i) {
				problems.add("review item " + d.reviewItemId() + " is not on the latest " + spec + " round");
				continue;
			}
			if (!decided.add(d.reviewItemId())) {
				problems.add("review item " + d.reviewItemId() + " is decided more than once in one submission");
				continue;
			}
			BoardReviewItem f = items.get(i);
			switch (d.action()) {
				case ACCEPT, DISMISS -> {
					if (BoardReviewItemStatus.OPEN != f.status()) {
						problems.add("review item " + f.id() + " is " + f.status() + ", not OPEN");
					} else if (StringUtils.isBlank(d.resolution())) {
						problems.add("review item " + f.id() + " needs "
								+ (BoardReviewItemDecisionAction.ACCEPT == d.action() ? "a resolution" : "a reason"));
					} else {
						BoardReviewItemStatus to = BoardReviewItemDecisionAction.ACCEPT == d.action()
								? BoardReviewItemStatus.ACCEPTED : BoardReviewItemStatus.WITHDRAWN;
						items.set(i, f.closedBy(to, null, d.resolution().trim()).decidedBy(by));
						closed.add(f.id());
					}
				}
				case SET_PRIORITY -> {
					if (!priorityInRange(d.priority(), levels)) {
						problems.add("review item " + f.id() + " needs a priority in 1.." + levels);
					} else if (d.priority().equals(f.priority())) {
						problems.add("review item " + f.id() + " is already priority " + f.priority());
					} else {
						items.set(i, f.withPriority(d.priority()).decidedBy(by));
					}
				}
				default -> problems.add("unknown decision " + d.action());
			}
		}
		About previousAbout = null == previous ? null : previous.about();
		About kept = previousAbout;
		if (null != about) {
			if (null == previousAbout) {
				kept = about;
			} else if (previousAbout.specification() != about.specification()
					|| (null != previousAbout.release() && null != about.release()
							&& !previousAbout.release().equals(about.release()))) {
				problems.add("the round is already about " + previousAbout.specification()
						+ (null != previousAbout.release() ? " release " + previousAbout.release() : "")
						+ "; a decision cannot change what the review items are about");
			}
		}
		BoardReviewItemIndex draft = new BoardReviewItemIndex(null == previous ? spec : previous.kind(), null, null,
				null == previous ? null : previous.counts(), items, kept);
		// The verdict follows the content, at the blocking priority: REJECTED exactly when something
		// a role could send the work back over is still open (§3.1, D23).
		BoardReviewVerdict verdict = draft.blockingReviewItems(blockingPriority).isEmpty()
				? BoardReviewVerdict.PASSED : BoardReviewVerdict.REJECTED;
		BoardReviewItemIndex index = new BoardReviewItemIndex(draft.kind(), null, verdict, draft.counts(), items, kept);
		return new Applied(index, closed, problems);
	}

	private static boolean priorityInRange(Integer p, int levels) {
		return null != p && p >= 1 && p <= levels;
	}

	private static Set<String> ids(List<BoardReviewItem> items) {
		Set<String> out = new LinkedHashSet<>();
		items.forEach(f -> out.add(f.id()));
		return out;
	}

	/** The settled round of {@code spec} on this task just before {@code round}, if there is one. */
	private Optional<ReleaseData> roundBefore(AgentTaskData td, RearmSpecificationType spec, UUID round) {
		boolean seen = false;
		for (int i = td.getReleases().size() - 1; i >= 0; i--) {
			UUID r = td.getReleases().get(i);
			if (!seen) {
				seen = r.equals(round);
				continue;
			}
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(r);
			if (ord.isEmpty() || !isSettledRound(ord.get().getLifecycle()) || isSuperseded(ord.get())) continue;
			DocumentRef doc = ord.get().getDocument();
			if (null != doc && doc.specification() == spec && td.getUuid().equals(doc.task())
					&& null != doc.reviewItems() && indexIsOfItsKind(doc)) {
				return ord;
			}
		}
		return Optional.empty();
	}

	/**
	 * Cut an index-only round on a task's document component.
	 *
	 * <p>Shared by the policy round, the human answer round and the unwind round; only the item
	 * transformation differs between them.
	 *
	 * <p><b>Takes no lock and saves no task.</b> It runs under the board lock from the effects
	 * applier and under the task lock from the answer path, so taking either here would invert the
	 * board-before-task order on one of them. It returns the release and does not link it: linking
	 * loads and saves a second copy of the task, which the caller's own save would then overwrite.
	 * Every caller adds the returned release to the single copy it owns and saves once (D14).
	 *
	 * <p>Idempotent through {@link #findEquivalentRound}: an index-only round's identity is its
	 * items, so a retried effect returns the round the first attempt cut rather than cutting a
	 * second one.
	 */
	private ReleaseData cutBoardRound(AgentTaskData td, RearmSpecificationType spec,
			BoardReviewItemIndex index, Set<String> selfClosedIds, WhoUpdated wu) throws RelizaException {
		AgentBoardData board = agentBoardService.getBoardData(td.getBoard())
				.orElseThrow(() -> new RelizaException("Board not found: " + td.getBoard()));
		// Resolves without recording: the answer and decision paths hold only the task lock, and
		// taking the board's here would invert the order (see above). The next publish records it.
		UUID componentUuid = resolveOrCreateComponent(board,
				new PublishRequest(td.getUuid(), spec, null, null, null, null, null, null, null,
						null, null, null, null, null),
				false, wu);
		// The identity of this round, fixed BEFORE it exists. Recorded on the release rather than
		// recomputed from its stored items, because the items are about to gain a pointer to the
		// release itself -- a value no later attempt could predict, which would make every round
		// unique by construction and stop the dedupe below from ever matching again.
		String identity = canonicalIndexDigest(index);
		Optional<ReleaseData> existing = findEquivalentRound(td, spec, identity);
		if (existing.isPresent()) {
			log.info("Round of {} for task {} already landed as release {}; returning it", spec,
					td.getUuid(), existing.get().getUuid());
			return existing.get();
		}
		Optional<Branch> base = branchService.getBaseBranchOfComponent(componentUuid);
		UUID baseBranch = base.map(Branch::getUuid).orElse(null);
		String version = versionAssignmentService
				.getSetNewVersionWrapper(baseBranch, null, null, null)
				.map(VersionAssignment::getVersion)
				.orElseThrow(() -> new RelizaException("Could not mint a version for the round"));
		int roundNumber = countRounds(td, spec) + 1;

		// ---- Phase 1: reserve the release, PENDING, so the round can name itself. ----
		//
		// An item closed by this round should point AT this round, and the uuid is minted inside
		// createRelease. So the row is created first at PENDING -- not a round yet, just a
		// reservation -- and the pointer is written in phase 2 once the uuid is known.
		//
		// Nothing can observe the intermediate state: both phases are in the caller's
		// transaction, so the row does not exist outside it until it is ASSEMBLED. And if this
		// process dies between the phases, the reservation was never committed; a PENDING row
		// that somehow outlives its transaction is swept to CANCELLED by
		// ReleaseService.rejectPendingReleases after two hours, and CANCELLED sits below every
		// lifecycle floor, so a half-built round can never satisfy an input requirement.
		DocumentRef reserved = new DocumentRef(spec, null, null, null, null, identity,
				td.getUuid(), null, roundNumber, index);
		Release row = ossReleaseService.createRelease(ReleaseDto.builder()
				.version(version)
				.org(td.getOrg())
				.component(componentUuid)
				.branch(baseBranch)
				.lifecycle(ReleaseLifecycle.PENDING)
				.document(reserved)
				.build(), wu);

		// ---- Phase 2: name the round in its own items, then let it settle. ----
		//
		// Written here rather than through updateRelease, which does not carry a document at all:
		// a document is immutable once its round is ASSEMBLED, and teaching the shared update
		// path to overwrite one would grant that everywhere to buy it here. Triggers are
		// suppressed on this write and fire once on the promotion below, so nothing observes the
		// unstamped index.
		BoardReviewItemIndex named = stampSelfPointer(index, selfClosedIds, row.getUuid());
		ReleaseData rd = ReleaseData.dataFromRecord(row);
		rd.setDocument(new DocumentRef(spec, null, null, null, null, identity, td.getUuid(), null,
				roundNumber, named));
		ossReleaseService.saveRelease(row, rd, wu, false);
		return ReleaseData.dataFromRecord(
				ossReleaseService.updateReleaseLifecycle(row.getUuid(), ReleaseLifecycle.ASSEMBLED, wu));
	}

	/**
	 * Point the items this round closed at the round that closed them.
	 *
	 * <p>Only the ids the caller says it closed, and only where no other release already answers
	 * for them: the unwind round points at the document that answered, which is a different
	 * release and the more useful pointer. Carried items are left exactly as they are.
	 */
	private static BoardReviewItemIndex stampSelfPointer(BoardReviewItemIndex index, Set<String> selfClosedIds,
			UUID round) {
		Set<String> closed = null == selfClosedIds ? Set.of() : selfClosedIds;
		List<BoardReviewItem> named = index.reviewItems().stream()
				.map(f -> closed.contains(f.id()) && null == f.resolvedBy()
						? f.withResolvedBy(round.toString())
						: f)
				.map(f -> decidedHere(f) ? f.withDecidedIn(round) : f)
				.toList();
		return new BoardReviewItemIndex(index.kind(), index.round(), index.verdict(), index.counts(),
				named, index.about(), index.tested());
	}

	/**
	 * An item this round decided: it names a decider and no round yet. Carried items keep the
	 * round that decided them, so only this round's decisions match (operator-actions D7).
	 */
	private static boolean decidedHere(BoardReviewItem f) {
		return null != f.decidedBy() && null == f.decidedIn();
	}

	/**
	 * Write the attribution of an agent's round (operator-actions D22). Agents never write
	 * {@code decidedBy} or {@code decidedIn}: each item carries the previous round's, by id, except
	 * where this round changes its priority or its status other than OPEN to RESOLVED, where it is
	 * the publishing session, with the round stamped once it exists. An agent may leave the fields
	 * out or repeat the previous round's; anything else is refused, so an agent can neither claim a
	 * person's decision nor another agent's. The correction flag is carried the same way.
	 */
	static BoardReviewItemIndex attributeAgentRound(BoardReviewItemIndex index, BoardReviewItemIndex previous, AgentActor session)
			throws RelizaException {
		Map<String, BoardReviewItem> before = new LinkedHashMap<>();
		if (null != previous) previous.reviewItems().forEach(f -> before.put(f.id(), f));
		List<BoardReviewItem> out = new ArrayList<>();
		List<String> claimed = new ArrayList<>();
		List<String> correctionClaimed = new ArrayList<>();
		for (BoardReviewItem f : index.reviewItems()) {
			BoardReviewItem prev = before.get(f.id());
			AgentActor carriedBy = null == prev ? null : prev.decidedBy();
			UUID carriedIn = null == prev ? null : prev.decidedIn();
			if ((null != f.decidedBy() && !f.decidedBy().equals(carriedBy))
					|| (null != f.decidedIn() && !f.decidedIn().equals(carriedIn))) {
				claimed.add(f.id());
				continue;
			}
			boolean decides = null != prev && (!java.util.Objects.equals(prev.priority(), f.priority())
					|| (prev.status() != f.status()
							&& !(BoardReviewItemStatus.OPEN == prev.status() && BoardReviewItemStatus.RESOLVED == f.status())));
			// The correction flag is a person's (task cac71351): carried from the previous round
			// whatever the agent wrote there, so an agent can neither drop it -- promoting a
			// correction into a blocker -- nor claim it for an item of its own.
			if (f.isCorrection() && (null == prev || !prev.isCorrection())) {
				correctionClaimed.add(f.id());
				continue;
			}
			out.add(decides ? f.decidedBy(session).withCorrectionOf(prev) : f.withAttributionOf(prev));
		}
		if (!claimed.isEmpty()) {
			throw new RelizaException("decidedBy and decidedIn are written by the server; leave them out or"
					+ " repeat the previous round's. Review item(s) " + String.join(", ", claimed)
					+ " name a different decider");
		}
		if (!correctionClaimed.isEmpty()) {
			throw new RelizaException("correction is set by a person accepting with review items, never by an"
					+ " agent; leave it out. Review item(s) " + String.join(", ", correctionClaimed)
					+ " were not corrections in the previous round");
		}
		return new BoardReviewItemIndex(index.kind(), index.round(), index.verdict(), index.counts(), out,
				index.about(), index.tested());
	}

	/**
	 * An index-only round of this type on this task whose items are identical.
	 *
	 * <p>Same rule as {@link #findEquivalent}'s index-only branch, against a typed index rather
	 * than a request map, so a board-cut round can be retried without cutting a duplicate.
	 */
	private Optional<ReleaseData> findEquivalentRound(AgentTaskData td, RearmSpecificationType spec,
			String identity) {
		if (null == td.getReleases()) return Optional.empty();
		for (int i = td.getReleases().size() - 1; i >= 0; i--) {
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(td.getReleases().get(i));
			if (ord.isEmpty()) continue;
			ReleaseData rd = ord.get();
			// A round that has not settled is not a round. PENDING is a reservation mid-cut, and
			// CANCELLED is what the sweeper leaves behind when one was abandoned -- returning
			// either as "already landed" would hand the caller a round nothing else can read.
			if (!isSettledRound(rd.getLifecycle())) continue;
			DocumentRef doc = rd.getDocument();
			if (null == doc || doc.specification() != spec || null != doc.path()
					|| null == doc.reviewItems()) continue;
			// The recorded identity where there is one. Rounds cut before it was recorded fall
			// back to the digest of their items, which is what they were matched on at the time.
			String stored = null != doc.indexDigest() ? doc.indexDigest()
					: canonicalIndexDigest(doc.reviewItems());
			if (identity.equals(stored)) return Optional.of(rd);
		}
		return Optional.empty();
	}

	/** Lifecycles in which a round is readable by anything other than the cut that made it. */
	/** A board's document series as its page lists it (board-documents.md §5, task 36d0549e). */
	public record DocumentSeries(RearmSpecificationType specification, SeriesComponent component,
			SeriesRound latestRound, int roundsCount, Integer openReviewItems, String elementCheckVerdict,
			ElementCheckCounts elementCheckCounts) {}

	/**
	 * A check report's results counted as the task page counts them (task RD2-24): the verdict is read
	 * from these, by the rule the page applies, so the board's Documents tab and the task page agree.
	 */
	public record ElementCheckCounts(int pass, int fail, int skip, int blockingFailed) {}

	public record SeriesComponent(UUID uuid, String name) {}

	public record SeriesRound(Integer round, String version, ReleaseLifecycle lifecycle, String path, UUID task,
			UUID release) {}

	/**
	 * One entry per specification in the board's document component map: its newest settled round,
	 * how many settled rounds the component holds (every task's), the OPEN items across each task's
	 * latest round for an index type (null for prose), and the verdict of the newest check report on
	 * the latest round -- FAIL when a blocking check failed, WARN when only a non-blocking one did,
	 * PASS otherwise, null when it was never checked. The same reads the task's documents make, per
	 * component instead of per task.
	 */
	public List<DocumentSeries> documentSeries(AgentBoardData board) {
		if (null == board.getDocumentComponents() || board.getDocumentComponents().isEmpty()) return List.of();
		List<DocumentSeries> out = new ArrayList<>();
		for (Map.Entry<RearmSpecificationType, UUID> e : board.getDocumentComponents().entrySet()) {
			UUID component = e.getValue();
			String name = getComponentService.getComponentData(component).map(ComponentData::getName).orElse(null);
			List<ReleaseData> rounds = branchService.getBaseBranchOfComponent(component)
					.map(b -> sharedReleaseService.listReleaseDataOfBranch(b.getUuid(), false))
					.orElse(List.of()).stream()
					.filter(rd -> null != rd.getDocument() && isSettledRound(rd.getLifecycle()) && !isSuperseded(rd))
					.sorted(java.util.Comparator.comparing(ReleaseData::getCreatedDate,
							java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())).reversed())
					.toList();
			SeriesRound latest = null;
			String verdict = null;
			ElementCheckCounts counts = null;
			if (!rounds.isEmpty()) {
				ReleaseData rd = rounds.get(0);
				latest = new SeriesRound(rd.getDocument().round(), rd.getVersion(), rd.getLifecycle(),
						rd.getDocument().path(), rd.getDocument().task(), rd.getUuid());
				counts = latestElementCheckReport(rd.getUuid()).map(ReleaseData::getDocument).map(DocumentRef::elementChecks)
						.map(AgentDocumentService::countsOf).orElse(null);
				verdict = null == counts ? null : verdictOf(counts);
			}
			Integer open = null;
			if (needsIndex(e.getKey())) {
				// Each task's latest round carries its open items forward, so count those only.
				Set<UUID> seen = new java.util.HashSet<>();
				int n = 0;
				for (ReleaseData rd : rounds) {
					if (!seen.add(rd.getDocument().task()) || null == rd.getDocument().reviewItems()) continue;
					n += rd.getDocument().reviewItems().openReviewItems().size();
				}
				open = n;
			}
			out.add(new DocumentSeries(e.getKey(), new SeriesComponent(component, name), latest, rounds.size(), open, verdict,
					counts));
		}
		return out;
	}

	static ElementCheckCounts countsOf(ElementCheckReport report) {
		int pass = 0;
		int fail = 0;
		int skip = 0;
		int blocking = 0;
		for (ElementCheckReport.ElementCheckResult r : report.results()) {
			switch (r.result()) {
				case PASS -> pass++;
				case SKIP -> skip++;
				case FAIL -> {
					fail++;
					if (r.blocking()) blocking++;
				}
			}
		}
		return new ElementCheckCounts(pass, fail, skip, blocking);
	}

	/** FAIL when a blocking check failed, WARN when only a non-blocking one did, PASS otherwise (RD2-24). */
	static String verdictOf(ElementCheckCounts c) {
		if (c.blockingFailed() > 0) return "FAIL";
		return c.fail() > 0 ? "WARN" : "PASS";
	}

	private static boolean isSettledRound(ReleaseLifecycle lc) {
		return null != lc && ReleaseLifecycle.PENDING != lc && ReleaseLifecycle.CANCELLED != lc
				&& ReleaseLifecycle.REJECTED != lc;
	}

	/**
	 * Cut a policy round for every index on a task that still has open items.
	 *
	 * <p>Called after the task transaction commits, so it takes the board lock in the normal
	 * order. Failure is logged and swallowed for the same reason the rest of the board effects
	 * are: the loop has already ended, and refusing to record why would leave the task parked
	 * with nobody coming.
	 *
	 * <p><b>This method owns the task copy and is the only writer of it in this transaction</b>
	 * (D14). It locks the row, adds every round it cuts to that copy as it goes, and saves once at
	 * the end. Adding as it goes matters within the loop: the second type's round is numbered by
	 * {@code countRounds} and de-duplicated by {@code findEquivalentRound} against a task that
	 * already knows about the first. An unlinked round is invisible to both, so round numbers
	 * would collide and the closure would never be seen by the next read of the index.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public void cutPolicyRound(UUID taskUuid, String reason, WhoUpdated wu) {
		Optional<AgentTask> row = taskRepository.findByIdWriteLocked(taskUuid);
		if (row.isEmpty()) return;
		AgentTaskData td = AgentTaskData.dataFromRecord(row.get());
		boolean cut = false;
		for (Map.Entry<RearmSpecificationType, BoardReviewItemIndex> e : latestIndexes(td).entrySet()) {
			try {
				ReleaseData rd = publishPolicyRound(td, e.getKey(), e.getValue(), reason, wu);
				if (null != rd) {
					td.addRelease(rd.getUuid());
					cut = true;
				}
			} catch (Exception ex) {
				log.error("Could not record policy acceptance of {} items on task {}", e.getKey(),
						taskUuid, ex);
			}
		}
		if (cut) agentTaskService.saveData(td, wu);
	}

	/**
	 * Close the questions a frame was waiting on, after the answering role passed (D11).
	 *
	 * <p>Same ownership rule as {@link #cutPolicyRound}: one locked copy, the round added to it,
	 * one save. Runs from the effects applier after the task transaction commits, so the board
	 * lock is taken first and this row lock second.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public void cutUnwindRound(UUID taskUuid, UUID questionsRelease, UUID answeringRelease,
			WhoUpdated wu) {
		Optional<AgentTask> row = taskRepository.findByIdWriteLocked(taskUuid);
		if (row.isEmpty()) return;
		AgentTaskData td = AgentTaskData.dataFromRecord(row.get());
		try {
			BoardReviewItemIndex questions = sharedReleaseService.getReleaseData(questionsRelease)
					.map(ReleaseData::getDocument)
					.map(DocumentRef::reviewItems)
					.orElse(null);
			ReleaseData rd = publishUnwindRound(td, questions, answeringRelease, wu);
			if (null != rd) {
				td.addRelease(rd.getUuid());
				agentTaskService.saveData(td, wu);
			}
		} catch (Exception e) {
			log.error("Could not close the questions of release {} on task {}", questionsRelease,
					taskUuid, e);
		}
	}

	/** The newest index of each kind on this task, for the policy round and for reporting. */
	public Map<RearmSpecificationType, BoardReviewItemIndex> latestIndexes(AgentTaskData td) {
		Map<RearmSpecificationType, BoardReviewItemIndex> out = new LinkedHashMap<>();
		for (RearmSpecificationType spec : INDEXED_TYPES) {
			BoardReviewItemIndex idx = previousRoundIndex(td, spec);
			if (null != idx) out.put(spec, idx);
		}
		return out;
	}

	/**
	 * sha256 of an index in canonical form, which is what makes an index-only round idempotent.
	 *
	 * <p>Through the record rather than the caller's map: two clients that send the same items in
	 * a different key order, or with a field spelled null instead of omitted, mean one round.
	 */
	static String canonicalIndexDigest(Object index) {
		try {
			BoardReviewItemIndex typed = index instanceof BoardReviewItemIndex fi ? fi
					: Utils.OM.convertValue(index, BoardReviewItemIndex.class);
			// decidedIn points at the round that made a decision, which cannot be known before
			// that round exists; left in, a retry could never match what the first attempt cut
			// (operator-actions D7). Absent fields are omitted, so rounds recorded before the
			// attribution fields existed digest exactly as they did.
			typed = new BoardReviewItemIndex(typed.kind(), typed.round(), typed.verdict(), typed.counts(),
					typed.reviewItems().stream().map(f -> f.withDecidedIn(null)).toList(), typed.about(), typed.tested());
			byte[] json = Utils.OM.writeValueAsBytes(typed);
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
		} catch (RuntimeException | NoSuchAlgorithmException e) {
			// An index that cannot be read has no identity; validation refuses it a step later
			// with a message that names the problem, so this must not throw over it first.
			return "";
		}
	}

	/** Reviews, test runs and questions carry an index; everything else is prose with a pointer. */
	static boolean needsIndex(RearmSpecificationType spec) {
		return INDEXED_TYPES.contains(spec);
	}

	/**
	 * The task-scoped types whose index drives routing.
	 *
	 * <p>Listed once. Every reader of "which documents carry review items" -- the open-items scan, the
	 * router, the carry-forward rule -- reads this, so adding a type is one edit rather than a
	 * search for the places that enumerated two.
	 */
	static final List<RearmSpecificationType> INDEXED_TYPES = List.of(
			RearmSpecificationType.BOARD_REVIEW_ITEMS,
			RearmSpecificationType.BOARD_TEST_REPORT,
			RearmSpecificationType.BOARD_QUESTIONS);

	private int priorityLevels(UUID orgUuid) {
		return getOrganizationService.getOrganizationData(orgUuid)
				.map(OrganizationData::getSettings)
				.map(OrganizationData.Settings::getReviewItemPriorityLevels)
				.orElse(OrganizationData.DEFAULT_REVIEW_ITEM_PRIORITY_LEVELS);
	}

	/**
	 * The source code entry for the commit a document was read at, reused when it already exists.
	 *
	 * <p>One commit has one entry: {@code source_code_entries_vcs_commit_unique} says so, and the
	 * documents repository is one repository shared by every board that writes to it. So two tasks
	 * citing the same design at the same commit is ordinary -- a component-scoped document is
	 * written once and read by several tasks -- and creating a second entry for it fails the
	 * constraint. The caller saw "Request violates data constraints" and nothing about which
	 * commit, which is a poor thing to hand an agent that did nothing wrong.
	 */
	private UUID createSce(AgentSessionData session, AgentBoardData board, PublishRequest req,
			UUID componentUuid, UUID baseBranch, WhoUpdated wu) throws RelizaException {
		UUID documentsVcs = resolveDocumentsVcs(board);
		Optional<UUID> reused = existingSce(documentsVcs, req.commit());
		if (reused.isPresent()) return reused.get();
		SceDto sceDto = SceDto.builder()
				.branch(baseBranch)
				.vcs(documentsVcs)
				.commit(req.commit())
				.commitMessage(req.commitMessage())
				.date(req.commitDate())
				.organizationUuid(session.getOrg())
				.build();
		try {
			return sourceCodeEntryService.createSourceCodeEntry(sceDto, wu).getUuid();
		} catch (DataIntegrityViolationException dive) {
			// Lost the race: two hops publishing at the same commit both missed the lookup above
			// and both tried to create. The persist runs REQUIRES_NEW, so only the loser's attempt
			// rolled back and this publish can still finish on the winner's row. Without this the
			// loser gets the same constraint error the lookup exists to prevent, just more rarely
			// -- which is the worse failure, because it only appears under concurrency.
			return existingSce(documentsVcs, req.commit()).orElseThrow(() -> dive);
		}
	}

	/** The entry a commit already has on the documents repository, if any. */
	private Optional<UUID> existingSce(UUID documentsVcs, String commit) {
		if (null == commit) return Optional.empty();
		List<SourceCodeEntry> found = getSourceCodeEntryService
				.getSourceCodeEntriesByVcsAndCommits(documentsVcs, List.of(commit));
		return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0).getUuid());
	}

	/** Document releases of a task, newest first. */
	public List<ReleaseData> documentsOfTask(AgentTaskData td) {
		List<ReleaseData> out = new ArrayList<>();
		if (null == td || null == td.getReleases()) return out;
		for (int i = td.getReleases().size() - 1; i >= 0; i--) {
			sharedReleaseService.getReleaseData(td.getReleases().get(i))
					.filter(rd -> null != rd.getDocument())
					.ifPresent(out::add);
		}
		return out;
	}

	/**
	 * Open review items of a task: the newest round of each indexed type, entries still OPEN.
	 *
	 * <p>Newest round only, by design — each round carries forward everything the previous one
	 * left open, so the newest IS the current state and older rounds are history. Reading them all
	 * would double-count a review item that has been carried across three rounds.
	 */
	public List<BoardReviewItem> openReviewItemsOfTask(AgentTaskData td) {
		List<BoardReviewItem> out = new ArrayList<>();
		for (RearmSpecificationType spec : INDEXED_TYPES) {
			BoardReviewItemIndex newest = previousRoundIndex(td, spec);
			if (null != newest) out.addAll(newest.openReviewItems());
		}
		out.sort((a, b) -> Integer.compare(priorityOf(a), priorityOf(b)));
		return out;
	}

	/**
	 * A review item's priority for ordering. Absent sorts last rather than first: an entry we cannot
	 * read is not urgent, it is unclassified, and putting it at the top would bury real priority-1
	 * review items under it.
	 */
	private int priorityOf(BoardReviewItem reviewItem) {
		return null != reviewItem.priority() ? reviewItem.priority() : Integer.MAX_VALUE;
	}

	/** The innermost message of a deserialisation failure, which is the part that names the field. */
	private static String rootCause(Throwable t) {
		Throwable cur = t;
		while (null != cur.getCause()) cur = cur.getCause();
		return cur.getMessage();
	}
}
