/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Audit;
import io.reliza.model.SbomSigningKeyData;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeyStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The SBOM signing key store against the test database (SCORE-32, T-2, T-10): the migration and its
 * indexes, the one-CURRENT-per-org index, the per-org CURRENT read, the audit rows each lifecycle
 * event writes, and a real rotation race.
 */
@SpringBootTest(classes = {App.class})
class SbomSigningKeyServiceIntegrationTest {

	@Autowired private SbomSigningKeyService service;
	@Autowired private AuditService auditService;
	@Autowired private EncryptionService encryptionService;
	@Autowired private TestInitializer testInitializer;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactionManager;

	private final ExecutorService pool = Executors.newFixedThreadPool(2);

	@AfterEach
	void stop() {
		pool.shutdownNow();
	}

	private static WhoUpdated actor() {
		return WhoUpdated.getWhoUpdated(ProgrammaticType.MANUAL, UUID.randomUUID(), "127.0.0.1");
	}

	private UUID org() {
		return testInitializer.obtainOrganization().getUuid();
	}

	private List<Audit> audits(UUID key) {
		return auditService.getAuditForEntity(key, TableName.SBOM_SIGNING_KEYS, 100, 0);
	}

	private long currentRows(UUID org) {
		return jdbc.queryForObject("SELECT count(*) FROM rearm.sbom_signing_keys WHERE record_data->>'org' = ?"
				+ " AND record_data->>'status' = 'CURRENT'", Long.class, org.toString());
	}

	private Map<String, Object> liveRecord(UUID key) {
		return jdbc.queryForMap("SELECT record_data::text AS r FROM rearm.sbom_signing_keys WHERE uuid = ?", key);
	}

	@Test
	void migration_appliesAndIndexesExist() {
		// by description, not version: a renumber at merge touches the file name only
		List<Map<String, Object>> applied = jdbc.queryForList("SELECT version, success FROM public.flyway_schema_history"
				+ " WHERE description = 'sbom signing keys'");
		assertEquals(1, applied.size(), applied.toString());
		assertEquals(Boolean.TRUE, applied.get(0).get("success"), applied.toString());
		List<String> indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'rearm'"
				+ " AND tablename = 'sbom_signing_keys'", String.class);
		assertTrue(indexes.containsAll(List.of("sbom_signing_keys_org", "sbom_signing_keys_org_current")), indexes.toString());
		String def = jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname = 'rearm'"
				+ " AND indexname = 'sbom_signing_keys_org_current'", String.class);
		assertTrue(def.startsWith("CREATE UNIQUE INDEX"), def);
		assertTrue(def.contains("'CURRENT'"), def);
	}

	@Test
	void uniqueIndex_refusesTwoCurrentRowsForOneOrg() {
		String org = UUID.randomUUID().toString();
		String insert = "INSERT INTO rearm.sbom_signing_keys (record_data) VALUES (jsonb_build_object('org', ?::text,"
				+ " 'status', ?::text))";
		jdbc.update(insert, org, "CURRENT");
		jdbc.update(insert, org, "RETIRED");
		jdbc.update(insert, org, "RETIRED");
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, org, "CURRENT"));
		// another org keeps its own CURRENT key
		jdbc.update(insert, UUID.randomUUID().toString(), "CURRENT");
		jdbc.update("DELETE FROM rearm.sbom_signing_keys WHERE record_data->>'org' = ?", org);
	}

	/**
	 * T-10: the read SCORE-31b signs every export with runs the real query and returns only the
	 * org's own CURRENT key. B's key is made before A's, so a query blind to the org returns A's key
	 * for B and C (newest first) or one key for both A and B (no order).
	 */
	@Test
	void currentKey_isOrgScopedAgainstTheDatabase() throws Exception {
		UUID orgA = org();
		UUID orgB = org();
		UUID orgC = org();
		UUID keyB = service.generate(orgB, actor()).getUuid();
		UUID keyA = service.generate(orgA, actor()).getUuid();

		assertEquals(keyA, currentUuid(orgA), "org A signs with A's key");
		assertEquals(keyB, currentUuid(orgB), "org B signs with B's key");
		assertEquals(Optional.empty(), service.currentKey(orgC), "an org without a key has none");

		String pem = SbomSigningTestKeys.pem("PRIVATE KEY",
				KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded());
		UUID importedB = service.importKey(orgB, pem, actor()).getUuid();
		assertEquals(SbomSigningKeyStatus.RETIRED, service.getKey(keyB).orElseThrow().getStatus());
		assertEquals(keyA, currentUuid(orgA), "B's rotation leaves A's key CURRENT for A");
		assertEquals(importedB, currentUuid(orgB), "org B signs with its imported key");

		service.revoke(keyA, actor());
		assertEquals(Optional.empty(), service.currentKey(orgA), "A has no CURRENT key once it is revoked");
		assertEquals(importedB, currentUuid(orgB), "A's revoke leaves B's key CURRENT for B");
		assertEquals(Optional.empty(), service.currentKey(orgC), "an org without a key still has none");

		// the public read verifiers use lists each org's own keys only
		assertEquals(List.of(keyA.toString()), kids(orgA));
		assertEquals(List.of(importedB.toString(), keyB.toString()), kids(orgB));
		assertEquals(List.of(), kids(orgC));
	}

	private UUID currentUuid(UUID org) {
		return service.currentKey(org).orElseThrow().getUuid();
	}

	private List<String> kids(UUID org) {
		return service.publicKeys(org).stream().map(e -> String.valueOf(e.get(SbomSigningKeyData.JWK_KID))).toList();
	}

	@Test
	void audit_rowsPerLifecycleEvent() throws Exception {
		UUID org = org();
		WhoUpdated creator = actor();
		WhoUpdated rotator = actor();
		WhoUpdated revoker = actor();

		SbomSigningKeyData first = service.generate(org, creator);
		assertEquals(List.of(), audits(first.getUuid()), "a first key writes no audit row");

		SbomSigningKeyData second = service.generate(org, rotator);
		List<Audit> firstAudits = audits(first.getUuid());
		assertEquals(1, firstAudits.size());
		Audit a = firstAudits.get(0);
		assertEquals(0, a.getRevision());
		assertEquals("CURRENT", a.getRevisionRecordData().get("status"));
		assertEquals(creator.getLastUpdatedBy().toString(),
				String.valueOf(a.getRevisionRecordData().get(CommonVariables.LAST_UPDATED_BY_FIELD)));
		assertEquals(List.of(), audits(second.getUuid()), "the new key writes none");

		SbomSigningKeyData revoked = service.revoke(second.getUuid(), revoker);
		List<Audit> secondAudits = audits(second.getUuid());
		assertEquals(1, secondAudits.size());
		assertEquals("CURRENT", secondAudits.get(0).getRevisionRecordData().get("status"));
		assertEquals(rotator.getLastUpdatedBy().toString(),
				String.valueOf(secondAudits.get(0).getRevisionRecordData().get(CommonVariables.LAST_UPDATED_BY_FIELD)));

		// the live rows carry the latest actor
		assertEquals(revoker.getLastUpdatedBy(), revoked.getLastUpdatedBy());
		assertEquals(rotator.getLastUpdatedBy(), service.getKey(first.getUuid()).orElseThrow().getLastUpdatedBy());
		assertEquals(SbomSigningKeyStatus.RETIRED, service.getKey(first.getUuid()).orElseThrow().getStatus());
		assertEquals(0, currentRows(org));

		// an audit row holds the record as stored: ciphertext only, never the plaintext key
		for (SbomSigningKeyData k : List.of(first, second)) {
			byte[] pkcs8 = Base64.getDecoder().decode(encryptionService.decrypt(k.getEncryptedPrivateKey()));
			for (Audit row : audits(k.getUuid())) {
				LocalKeySignerTest.assertNoKeyMaterial(String.valueOf(row.getRevisionRecordData()), pkcs8, null);
			}
			LocalKeySignerTest.assertNoKeyMaterial(String.valueOf(liveRecord(k.getUuid())), pkcs8, null);
		}
	}

	@Test
	void importKey_isAuditedLikeGenerate() throws Exception {
		UUID org = org();
		WhoUpdated importer = actor();
		String pem = SbomSigningTestKeys.pem("PRIVATE KEY",
				KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded());
		SbomSigningKeyData generated = service.generate(org, actor());
		SbomSigningKeyData imported = service.importKey(org, pem, importer);
		assertEquals(List.of(), audits(imported.getUuid()));
		assertEquals(1, audits(generated.getUuid()).size());
		assertEquals(importer.getLastUpdatedBy(), service.getKey(generated.getUuid()).orElseThrow().getLastUpdatedBy());
		assertEquals(imported.getUuid(), service.currentKey(org).orElseThrow().getUuid());
	}

	/**
	 * A revoke whose request already read the key (the GraphQL revoke reads it to authorize) while a
	 * rotation retired it: the revoke works from the row as committed once it holds the lock, so the
	 * rotation's retiredAt and revision survive and the audit has one row per revision.
	 */
	@Test
	void revoke_afterAConcurrentRotationWorksFromTheCommittedRow() throws Exception {
		UUID org = org();
		SbomSigningKeyData first = service.generate(org, actor());
		SbomSigningKeyData revoked = new TransactionTemplate(transactionManager).execute(status -> {
			try {
				// the request's read, before the lock: the row enters this persistence context as CURRENT
				assertEquals(SbomSigningKeyStatus.CURRENT, service.getKey(first.getUuid()).orElseThrow().getStatus());
				pool.submit(() -> service.generate(org, actor())).get(30, TimeUnit.SECONDS);
				return service.revoke(first.getUuid(), actor());
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		assertEquals(SbomSigningKeyStatus.REVOKED, revoked.getStatus());
		SbomSigningKeyData stored = service.getKey(first.getUuid()).orElseThrow();
		assertTrue(null != stored.getRetiredAt(), "the rotation's retiredAt is kept");
		assertTrue(null != stored.getRevokedAt());
		assertEquals(2, jdbc.queryForObject("SELECT revision FROM rearm.sbom_signing_keys WHERE uuid = ?",
				Integer.class, first.getUuid()));
		assertEquals(List.of(1, 0), audits(first.getUuid()).stream().map(Audit::getRevision).toList());
		assertEquals("RETIRED", audits(first.getUuid()).get(0).getRevisionRecordData().get("status"));
	}

	@Test
	void concurrentRotation_isReportedAndLeavesOneCurrent() throws Exception {
		UUID org = org();
		SbomSigningKeyData first = service.generate(org, actor());
		raceTwoRotations(org);
		assertEquals(SbomSigningKeyStatus.RETIRED, service.getKey(first.getUuid()).orElseThrow().getStatus());
	}

	@Test
	void concurrentFirstKeys_areReportedAndLeaveOneCurrent() throws Exception {
		raceTwoRotations(org());
	}

	/**
	 * A real race, not a stub: the winner rotates in a transaction held open after its rows are
	 * flushed; the loser then rotates on its own, waits on the winner's lock or index entry, and once
	 * the winner commits is refused with the race message, its transaction rolled back.
	 */
	private void raceTwoRotations(UUID org) throws Exception {
		CountDownLatch flushed = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<UUID> winner = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
			try {
				UUID id = service.generate(org, actor()).getUuid();
				flushed.countDown();
				release.await(30, TimeUnit.SECONDS);
				return id;
			} catch (RelizaException | InterruptedException e) {
				throw new IllegalStateException(e);
			}
		}));
		assertTrue(flushed.await(30, TimeUnit.SECONDS));
		Future<SbomSigningKeyData> loser = pool.submit(() -> service.generate(org, actor()));
		Thread.sleep(1_000);
		assertFalse(loser.isDone(), "the loser waits for the winner");
		release.countDown();

		UUID won = winner.get(30, TimeUnit.SECONDS);
		ExecutionException e = assertThrows(ExecutionException.class, () -> loser.get(30, TimeUnit.SECONDS));
		assertInstanceOf(RelizaException.class, e.getCause());
		assertEquals(SbomSigningKeyService.ROTATION_RACE_MESSAGE, e.getCause().getMessage());
		assertEquals(1, currentRows(org));
		assertEquals(won, service.currentKey(org).orElseThrow().getUuid());
	}
}
