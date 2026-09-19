/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.Branch;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.SceDto;
import io.reliza.repositories.SourceCodeEntryRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Pins the transaction shape of the agent-session reverse-index write
 * ({@link AgentSessionService#recordCommit}) as driven from
 * {@link SourceCodeEntryService}, against real Postgres.
 *
 * <p>The write is deferred to {@code afterCommit} and runs REQUIRES_NEW; the
 * self-deadlock that motivates that shape is described on
 * {@code recordCommit}. These tests pin both halves: the mixed
 * merge-then-create sequence completes inside one outer transaction, and a
 * failing {@code recordCommit} runs in its own transaction and cannot roll
 * back the SCE create.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class SourceCodeEntryRecordCommitIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private SourceCodeEntryService sourceCodeEntryService;
	@Autowired private SourceCodeEntryRepository sourceCodeEntryRepository;
	@Autowired private AgentService agentService;
	@Autowired private PlatformTransactionManager transactionManager;

	@MockitoSpyBean private AgentSessionService agentSessionService;

	/** Everything a test needs to submit trailered commits for one session. */
	private record Fixture(UUID orgUuid, Branch branch, UUID vcsUuid, AgentData agent,
			AgentSessionData session, String clientSessionId) {}

	private Fixture fixture() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());
		UUID vcsUuid = vcsRepositoryService.provisionVcsRepository(org.getUuid(),
				"github.com/example/record-commit-" + UUID.randomUUID(), VcsType.GIT,
				WhoUpdated.getTestWhoUpdated());
		// Link the repo to the branch, as the first addrelease against a
		// component does, so populateSourceCodeEntryByVcsAndCommit resolves
		// the VCS off the branch instead of demanding uri + type.
		branchService.updateBranch(BranchDto.builder()
				.uuid(branch.getUuid()).vcs(vcsUuid).vcsBranch("main").build(),
				WhoUpdated.getTestWhoUpdated());
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"record-commit-agent-" + UUID.randomUUID(), null, null, null, WhoUpdated.getTestWhoUpdated());
		String clientSessionId = "repro-" + UUID.randomUUID();
		AgentSessionData session = agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				clientSessionId, "record-commit session", WhoUpdated.getTestWhoUpdated());
		return new Fixture(org.getUuid(), branch, vcsUuid, agent, session, clientSessionId);
	}

	/**
	 * Subject line exactly as the CI commit-list format ships it: {@code %s}
	 * followed by the trailers rendered space-separated on the same line
	 * ({@code rearm-actions/initialize} AGENTIC_TRAILER_FMT).
	 */
	private SceDto trailered(Fixture f, String commitSha, String subject) {
		return SceDto.builder()
				.branch(f.branch().getUuid())
				.vcs(f.vcsUuid())
				.commit(commitSha)
				.organizationUuid(f.orgUuid())
				.commitMessage(subject + " ReARM-Agent: " + f.agent().getUuid()
						+ " ReARM-Agentic-Session: " + f.clientSessionId())
				.build();
	}

	private List<UUID> sessionCommits(UUID sessionUuid) {
		AgentSessionData sd = agentSessionService.getSessionData(sessionUuid).orElseThrow();
		return sd.getCommits() == null ? List.of() : sd.getCommits();
	}

	/**
	 * The production trap: an already-registered agent commit (merge path,
	 * joins the outer tx) followed by a never-registered one (create path,
	 * REQUIRES_NEW) in one transaction. Before the fix the second call
	 * blocked on the session row lock the first call was holding in the
	 * outer tx; on the unthrottled test DB that hang is unbounded, so the
	 * timeout runs on a separate thread (a JDBC socket read ignores
	 * interrupt).
	 */
	@Test
	@Timeout(value = 60, threadMode = ThreadMode.SEPARATE_THREAD)
	public void mixedMergeThenCreateInOneTransaction_doesNotDeadlock() throws RelizaException {
		Fixture f = fixture();
		String sha1 = "1111111111111111111111111111111111111111";
		String sha2 = "2222222222222222222222222222222222222222";

		// Commit #1 registered up front, as a previous addrelease would have done.
		SourceCodeEntry sce1 = sourceCodeEntryService.createSourceCodeEntry(
				trailered(f, sha1, "first agent commit"), WhoUpdated.getTestWhoUpdated());
		assertEquals(f.session().getUuid(), SourceCodeEntryData.dataFromRecord(sce1).getAgentSession());
		assertEquals(List.of(sce1.getUuid()), sessionCommits(f.session().getUuid()));

		TransactionTemplate tt = new TransactionTemplate(transactionManager);
		List<Optional<SourceCodeEntryData>> results = tt.execute(status -> {
			try {
				Optional<SourceCodeEntryData> merged = sourceCodeEntryService.populateSourceCodeEntryByVcsAndCommit(
						trailered(f, sha1, "first agent commit"), true, WhoUpdated.getTestWhoUpdated());
				Optional<SourceCodeEntryData> created = sourceCodeEntryService.populateSourceCodeEntryByVcsAndCommit(
						trailered(f, sha2, "second agent commit"), true, WhoUpdated.getTestWhoUpdated());
				return List.of(merged, created);
			} catch (RelizaException e) {
				throw new IllegalStateException(e);
			}
		});

		assertNotNull(results);
		assertTrue(results.get(0).isPresent(), "merge path must return the existing SCE");
		assertTrue(results.get(1).isPresent(), "create path must return the new SCE");
		assertEquals(sce1.getUuid(), results.get(0).get().getUuid());
		UUID sce2Uuid = results.get(1).get().getUuid();
		assertEquals(f.session().getUuid(), results.get(1).get().getAgentSession());

		// Outer tx has committed: both afterCommit hooks have run and the
		// reverse index carries both SCEs exactly once.
		List<UUID> commits = sessionCommits(f.session().getUuid());
		assertEquals(2, commits.size(), "session commits: " + commits);
		assertTrue(commits.contains(sce1.getUuid()));
		assertTrue(commits.contains(sce2Uuid));
	}

	/**
	 * The reverse-index write is best-effort: when it blows up, the SCE
	 * create must still return and its row must be durable, with no
	 * {@code UnexpectedRollbackException} from a poisoned transaction.
	 */
	@Test
	public void recordCommitFailure_doesNotRollBackSceCreate() throws RelizaException {
		Fixture f = fixture();
		String sha = "3333333333333333333333333333333333333333";

		// Stub the unwrapped spy, not the CGLIB transaction proxy around it
		// (see FindingChangeEventEmitIntegrationTest for the pass-through hazard).
		// The proxy's interceptor still runs first, so the stub observes the
		// transaction recordCommit was given: pin that it is a NEW one. From
		// an afterCommit hook a join-caller propagation would silently attach
		// to the already-committed tx and the write would never land.
		AgentSessionService sessionSpy = AopTestUtils.getUltimateTargetObject(agentSessionService);
		AtomicReference<Boolean> ranInNewTx = new AtomicReference<>();
		doAnswer(inv -> {
			ranInNewTx.set(TransactionAspectSupport.currentTransactionStatus().isNewTransaction());
			throw new RuntimeException("simulated recordCommit failure");
		}).when(sessionSpy).recordCommit(any(), any(), any());

		SourceCodeEntry saved = assertDoesNotThrow(() -> sourceCodeEntryService.createSourceCodeEntry(
				trailered(f, sha, "agent commit with failing reverse index"), WhoUpdated.getTestWhoUpdated()));

		assertNotNull(saved);
		assertEquals(Boolean.TRUE, ranInNewTx.get(), "recordCommit must run in its own (REQUIRES_NEW) transaction");
		// Forward pointer landed and the row is durable.
		SourceCodeEntryData sced = SourceCodeEntryData.dataFromRecord(saved);
		assertEquals(f.session().getUuid(), sced.getAgentSession());
		Optional<SourceCodeEntry> reloaded = sourceCodeEntryRepository.findByCommitAndVcs(sha, f.vcsUuid().toString());
		assertTrue(reloaded.isPresent(), "SCE row must survive a failing recordCommit");
		assertEquals(saved.getUuid(), reloaded.get().getUuid());
		// Reverse index is the only thing that did not happen.
		assertFalse(sessionCommits(f.session().getUuid()).contains(saved.getUuid()));
	}
}
