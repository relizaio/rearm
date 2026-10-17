/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Agent;
import io.reliza.model.AgentData;
import io.reliza.model.AgentData.AgentStatus;
import io.reliza.model.AgentData.AgentType;
import io.reliza.model.AgentIdentity;
import io.reliza.model.AgentIdentityCredential;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentIdentityData;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentIdentityCredentialRepository;
import io.reliza.repositories.AgentIdentityRepository;
import io.reliza.repositories.AgentRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;
import jakarta.persistence.EntityManager;

/**
 * Two first registrations of the same agent or credential race on a unique index; the loser must
 * return the winner, not fail (gaps §1.11, design §3.2 point 3).
 *
 * <p>A real race, not a stub: the winner's row is inserted and flushed in a transaction that is
 * held open, so the loser's lookup cannot see it and the loser's insert waits on the index. Only
 * then does the winner commit, and the loser's insert fails exactly as it would in production.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentConcurrentRegistrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentService agentService;
	@Autowired private AgentIdentityService agentIdentityService;
	@Autowired private AgentRepository agentRepository;
	@Autowired private AgentIdentityRepository identityRepository;
	@Autowired private AgentIdentityCredentialRepository credentialRepository;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private EntityManager entityManager;
	@Autowired private JdbcTemplate jdbc;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private final ExecutorService pool = Executors.newFixedThreadPool(2);

	@AfterEach
	void stop() {
		pool.shutdownNow();
	}

	/** Runs {@code insert} in a transaction held open until {@code release}, after it has flushed. */
	private Future<UUID> holdOpen(java.util.function.Supplier<UUID> insert, CountDownLatch flushed,
			CountDownLatch release) {
		return pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
			UUID id = insert.get();
			entityManager.flush();
			flushed.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return id;
		}));
	}

	@Test
	public void aRootAgentRegistrationRaceReturnsTheWinner() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID identity = UUID.randomUUID();
		String name = "racer-" + UUID.randomUUID();
		CountDownLatch flushed = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		Future<UUID> winner = holdOpen(() -> {
			AgentData seed = new AgentData();
			seed.setOrg(org.getUuid());
			seed.setAgentIdentity(identity);
			seed.setName(name);
			seed.setStatus(AgentStatus.ACTIVE);
			seed.setAgentType(AgentType.ROOT);
			Agent a = new Agent();
			a.setRecordData(Utils.dataToRecord(seed));
			return agentRepository.save(a).getUuid();
		}, flushed, release);
		flushed.await(30, TimeUnit.SECONDS);

		// The loser, inside a transaction of its caller's, as session initialize calls it.
		Future<UUID> loser = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
			try {
				return agentService.findOrRegisterRootAgent(org.getUuid(), identity, name, null, null, null, WU)
						.getUuid();
			} catch (RelizaException e) {
				throw new IllegalStateException(e);
			}
		}));
		Thread.sleep(1_000);   // the loser has looked, missed, and is waiting on the index
		release.countDown();

		UUID won = winner.get(30, TimeUnit.SECONDS);
		assertEquals(won, loser.get(30, TimeUnit.SECONDS), "the loser returns the winner and its caller commits");
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM rearm.agents WHERE record_data->>'agentIdentity' = ?",
				Integer.class, identity.toString()));
	}

	@Test
	public void aCredentialRegistrationRaceReturnsTheWinnerAndLeavesNoOrphan() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String value = "race-key-" + UUID.randomUUID();
		String identityName = "Identity for " + IdentityType.REARM_API_KEY.name() + " " + value;
		CountDownLatch flushed = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		Future<UUID> winner = holdOpen(() -> {
			AgentIdentityData seed = new AgentIdentityData();
			seed.setOrg(org.getUuid());
			seed.setName(identityName);
			seed.setCreatedType(ProgrammaticType.AUTO);
			AgentIdentity row = new AgentIdentity();
			row.setRecordData(Utils.dataToRecord(seed));
			AgentIdentity saved = identityRepository.save(row);
			AgentIdentityCredential cred = new AgentIdentityCredential();
			cred.setAgentIdentityUuid(saved.getUuid());
			cred.setIdentityType(IdentityType.REARM_API_KEY.name());
			cred.setIdentityValue(value);
			credentialRepository.save(cred);
			return saved.getUuid();
		}, flushed, release);
		flushed.await(30, TimeUnit.SECONDS);

		Future<UUID> loser = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
			try {
				return agentIdentityService.findOrRegisterByCredential(org.getUuid(), IdentityType.REARM_API_KEY,
						value, WU).getUuid();
			} catch (RelizaException e) {
				throw new IllegalStateException(e);
			}
		}));
		Thread.sleep(1_000);
		release.countDown();

		UUID won = winner.get(30, TimeUnit.SECONDS);
		assertEquals(won, loser.get(30, TimeUnit.SECONDS));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM rearm.agent_identities WHERE record_data->>'name' = ?",
				Integer.class, identityName), "no speculative identity left behind");
	}
}
