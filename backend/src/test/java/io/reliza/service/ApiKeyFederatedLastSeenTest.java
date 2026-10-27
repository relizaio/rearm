/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.testsupport.StampAssertions.assertContentSave;
import static io.reliza.testsupport.StampAssertions.assertStampedOnly;
import static io.reliza.testsupport.StampAssertions.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.FederatedIdentity;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.ApiKeyRepository;
import io.reliza.testsupport.StampAssertions.RowSnapshot;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * A routine federated exchange refreshes the identity's last-seen triple (federation.lastRef, lastRunId,
 * lastActor) in place through ApiKeyRepository.stampFederatedLastSeen: no revision, no audit row, no version
 * bump. A revived tombstone, a changed provider field and the first repository id stay content saves
 * (TPF-71 / TPF-107, architecture round 2 section 5.1).
 */
@SpringBootTest(classes = {App.class})
public class ApiKeyFederatedLastSeenTest {

	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ApiKeyRepository apiKeyRepository;
	@Autowired private AuditService auditService;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private TestInitializer testInitializer;
	@Autowired private PlatformTransactionManager transactionManager;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final Set<String> TRIPLE = Set.of("lastRef", "lastRunId", "lastActor");

	/** ApiKeyOrderlessIdTest's fixture: GITHUB_ACTIONS, owner acme, repositoryUri github.com/repository. */
	private static FederatedIdentity identity(String repository) {
		FederatedIdentity fi = new FederatedIdentity();
		fi.setProvider(FederatedTrustRule.Provider.GITHUB_ACTIONS);
		fi.setOwner("acme");
		fi.setRepository(repository);
		fi.setRepositoryUri("github.com/" + repository);
		return fi;
	}

	private static FederatedIdentity identity(String repository, String ref, String runId, String actor) {
		FederatedIdentity fi = identity(repository);
		fi.setLastRef(ref);
		fi.setLastRunId(runId);
		fi.setLastActor(actor);
		return fi;
	}

	private static String repo() {
		return "acme/last-seen-" + UUID.randomUUID();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> federationOf(RowSnapshot s) {
		return (Map<String, Object>) s.recordData().get("federation");
	}

	private RowSnapshot snap(UUID uuid) {
		return snapshot(jdbc, TableName.API_KEYS, uuid);
	}

	private static int versionOf(RowSnapshot s) {
		return ((Number) s.recordData().get("version")).intValue();
	}

	/** Inside federation, every key other than the triple is unchanged. */
	private static void assertOnlyTripleMoved(RowSnapshot before, RowSnapshot after) {
		Map<String, Object> b = new LinkedHashMap<>(federationOf(before));
		Map<String, Object> a = new LinkedHashMap<>(federationOf(after));
		b.keySet().removeAll(TRIPLE);
		a.keySet().removeAll(TRIPLE);
		assertEquals(b, a, "the stamp changed federation outside the last-seen triple");
	}

	private String jsonTypeInFederation(UUID uuid, String key) {
		return jdbc.queryForObject("select jsonb_typeof(record_data->'federation'->?) from rearm.api_keys where uuid = ?",
				String.class, key, uuid);
	}

	private boolean federationHasKey(UUID uuid, String key) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"select (record_data->'federation') ?? ? from rearm.api_keys where uuid = ?", Boolean.class, key, uuid));
	}

	private Object lastAccessDate(UUID uuid) {
		return jdbc.queryForObject("select last_access_date from rearm.api_keys where uuid = ?", Object.class, uuid);
	}

	private ApiKeyData auditCopy(UUID uuid) {
		return Utils.OM.convertValue(auditService.getAuditForEntity(uuid, TableName.API_KEYS, 1000, 0).get(0)
				.getRevisionRecordData(), ApiKeyData.class);
	}

	// row 1
	@Test
	public void aRoutineExchangeStampsTheTripleWithoutARevisionOrAnAuditRow() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String repository = repo();
		UUID key = apiKeyService.ensureFederatedIdentity(org, identity(repository), WU).getUuid();
		RowSnapshot before = snap(key);
		Object accessBefore = lastAccessDate(key);

		ApiKey returned = apiKeyService.ensureFederatedIdentity(org,
				identity(repository, "refs/heads/main", "1001", "alice"), WU);

		RowSnapshot after = snap(key);
		assertStampedOnly(before, after, Set.of("federation"));
		assertOnlyTripleMoved(before, after);
		assertEquals("refs/heads/main", federationOf(after).get("lastRef"));
		assertEquals("1001", federationOf(after).get("lastRunId"));
		assertEquals("alice", federationOf(after).get("lastActor"));
		assertEquals(versionOf(before), versionOf(after), "no version bump on the stamp path");
		assertEquals("refs/heads/main", ApiKeyData.dataFromRecord(returned).getFederation().getLastRef(),
				"the returned entity is refreshed");
		assertEquals(accessBefore, lastAccessDate(key), "last_access_date is not the stamp's");
	}

	// row 2
	@Test
	public void aTripleAddedToARowThatHadNoneMatchesAWholeSave() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String repoA = repo();
		String repoB = repo();
		UUID a = apiKeyService.ensureFederatedIdentity(org, identity(repoA), WU).getUuid();
		UUID b = apiKeyService.ensureFederatedIdentity(org, identity(repoB), WU).getUuid();
		RowSnapshot aBefore = snap(a);
		RowSnapshot bBefore = snap(b);
		assertTrue(Collections.disjoint(federationOf(aBefore).keySet(), TRIPLE), "no triple stored before");

		apiKeyService.ensureFederatedIdentity(org, identity(repoA, "refs/heads/main", "2002", "bob"), WU);
		FederatedIdentity moved = identity(repoB, "refs/heads/main", "2002", "bob");
		moved.setRepositoryUri("github.com/" + repoB + "-moved");
		apiKeyService.ensureFederatedIdentity(org, moved, WU);

		RowSnapshot aAfter = snap(a);
		RowSnapshot bAfter = snap(b);
		assertStampedOnly(aBefore, aAfter, Set.of("federation"));
		assertContentSave(bBefore, bAfter);
		Map<String, Object> fa = new LinkedHashMap<>(federationOf(aAfter));
		Map<String, Object> fb = new LinkedHashMap<>(federationOf(bAfter));
		// repository differs by construction (it is the key order); repositoryUri is what forced B's save
		for (Map<String, Object> f : List.of(fa, fb)) {
			f.remove("repository");
			f.remove("repositoryUri");
		}
		assertEquals(fb, fa, "the stamp stores the shape a whole save stores");
		assertTrue(fa.keySet().containsAll(TRIPLE));
		assertFalse(fa.containsValue(null));
		assertFalse(fb.containsValue(null));
		assertEquals("string", jsonTypeInFederation(a, "lastActor"));
		assertEquals("string", jsonTypeInFederation(b, "lastActor"));
	}

	// row 3
	@Test
	public void aNullActorRemovesTheKeyAsAWholeSaveOmitsIt() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String repository = repo();
		UUID key = apiKeyService.ensureFederatedIdentity(org, identity(repository, "refs/heads/a", "1", "carol"), WU)
				.getUuid();
		assertTrue(federationHasKey(key, "lastActor"));
		RowSnapshot before = snap(key);

		apiKeyService.ensureFederatedIdentity(org, identity(repository, "refs/heads/b", "2", null), WU);

		RowSnapshot after = snap(key);
		assertFalse(federationHasKey(key, "lastActor"), "a null actor removes the key");
		assertEquals("refs/heads/b", federationOf(after).get("lastRef"));
		assertEquals("2", federationOf(after).get("lastRunId"));
		assertStampedOnly(before, after, Set.of("federation"));
		assertOnlyTripleMoved(before, after);

		String sibling = repo();
		UUID s = apiKeyService.ensureFederatedIdentity(org, identity(sibling, "refs/heads/a", "1", "carol"), WU).getUuid();
		RowSnapshot sBefore = snap(s);
		FederatedIdentity moved = identity(sibling, "refs/heads/b", "2", null);
		moved.setRepositoryUri("github.com/" + sibling + "-moved");
		apiKeyService.ensureFederatedIdentity(org, moved, WU);
		assertContentSave(sBefore, snap(s));
		assertFalse(federationHasKey(s, "lastActor"), "the whole save omits a null actor too");

		RowSnapshot beforeAllNull = snap(key);
		apiKeyService.ensureFederatedIdentity(org, identity(repository, null, null, null), WU);
		RowSnapshot afterAllNull = snap(key);
		for (String k : TRIPLE) assertFalse(federationHasKey(key, k), k + " removed");
		assertStampedOnly(beforeAllNull, afterAllNull, Set.of("federation"));
		assertOnlyTripleMoved(beforeAllNull, afterAllNull);
	}

	// row 4
	@Test
	public void aRevivedTombstoneIsASave() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String repository = repo();
		UUID key = apiKeyService.ensureFederatedIdentity(org, identity(repository), WU).getUuid();
		apiKeyService.deleteApiKey(key, WU);
		RowSnapshot before = snap(key);
		assertEquals("REVOKED", before.recordData().get("status"));

		apiKeyService.ensureFederatedIdentity(org, identity(repository, "refs/heads/main", "4004", "dave"), WU);

		RowSnapshot after = snap(key);
		// The content path, exactly as today: the status comes back and version moves, which the stamp never does.
		assertEquals("ACTIVE", after.recordData().get("status"));
		assertEquals(versionOf(before) + 1, versionOf(after), "the revive is the whole save, version + 1");
		assertEquals("4004", federationOf(after).get("lastRunId"));
		// saveApiKey audits only a row the table reads as live (ApiKeyService.saveApiKey, live.orElse(...)): a
		// tombstone's revive is written whole without a revision or an audit row, before and after TPF-107.
		assertEquals(before.revision(), after.revision(), "the funnel does not audit a revived tombstone (base behaviour)");
		assertEquals(before.auditRows(), after.auditRows(), "the funnel does not audit a revived tombstone (base behaviour)");
	}

	// row 5
	@Test
	public void aRenamedRepositoryIsASave() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String repository = repo();
		UUID key = apiKeyService.ensureFederatedIdentity(org, identity(repository), WU).getUuid();
		RowSnapshot before = snap(key);

		FederatedIdentity moved = identity(repository, "refs/heads/main", "5005", "erin");
		moved.setRepositoryUri("github.com/acme/renamed");
		apiKeyService.ensureFederatedIdentity(org, moved, WU);

		RowSnapshot afterUri = snap(key);
		assertContentSave(before, afterUri);
		assertEquals("github.com/acme/renamed", federationOf(afterUri).get("repositoryUri"));
		assertEquals(versionOf(before) + 1, versionOf(afterUri));

		FederatedIdentity reissued = identity(repository, "refs/heads/main", "5006", "erin");
		reissued.setRepositoryUri("github.com/acme/renamed");
		reissued.setIssuer("https://issuer.example");
		apiKeyService.ensureFederatedIdentity(org, reissued, WU);
		RowSnapshot afterIssuer = snap(key);
		assertContentSave(afterUri, afterIssuer);
		assertEquals("https://issuer.example", federationOf(afterIssuer).get("issuer"));
		assertEquals(versionOf(afterUri) + 1, versionOf(afterIssuer));
	}

	@Test
	public void aDifferentOwnerOrSpellingIsASaveThatStoresTheNewValue() {
		UUID org = testInitializer.obtainOrganization().getUuid();

		// the owner: same repository, another owner claim
		String repository = repo();
		UUID key = apiKeyService.ensureFederatedIdentity(org, identity(repository), WU).getUuid();
		RowSnapshot before = snap(key);
		FederatedIdentity otherOwner = identity(repository, "refs/heads/main", "6001", "frank");
		otherOwner.setOwner("another-owner");
		apiKeyService.ensureFederatedIdentity(org, otherOwner, WU);
		RowSnapshot afterOwner = snap(key);
		assertContentSave(before, afterOwner);
		assertEquals("another-owner", federationOf(afterOwner).get("owner"));
		assertEquals(versionOf(before) + 1, versionOf(afterOwner));

		// the spelling of the repository: the row is found by the lower-cased name, the stored name follows the latest spelling
		String mixed = "Acme/Last-Seen-" + UUID.randomUUID();
		FederatedIdentity first = identity(mixed);
		UUID spelled = apiKeyService.ensureFederatedIdentity(org, first, WU).getUuid();
		RowSnapshot spellBefore = snap(spelled);
		FederatedIdentity lower = identity(mixed.toLowerCase(), "refs/heads/main", "6002", "frank");
		lower.setRepositoryUri(first.getRepositoryUri());          // only the spelling of the repository differs
		assertEquals(ApiKeyService.federatedKeyOrder(first), ApiKeyService.federatedKeyOrder(lower));
		apiKeyService.ensureFederatedIdentity(org, lower, WU);
		RowSnapshot spellAfter = snap(spelled);
		assertContentSave(spellBefore, spellAfter);
		assertEquals(mixed.toLowerCase(), federationOf(spellAfter).get("repository"));
	}

	@Test
	public void aRowWithoutAFederationObjectIsRepairedByASave() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String repository = repo();
		UUID key = apiKeyService.ensureFederatedIdentity(org, identity(repository), WU).getUuid();
		jdbc.update("update rearm.api_keys set record_data = record_data - 'federation' where uuid = ?", key);
		RowSnapshot broken = snap(key);
		assertTrue(!broken.recordData().containsKey("federation"));

		apiKeyService.ensureFederatedIdentity(org, identity(repository, "refs/heads/main", "6004", "gina"), WU);
		RowSnapshot repaired = snap(key);
		assertContentSave(broken, repaired);
		assertEquals("6004", federationOf(repaired).get("lastRunId"));
		assertEquals("acme", federationOf(repaired).get("owner"));
	}

	private String pinnedDateText(UUID uuid) {
		return jdbc.queryForObject("select record_data->'federation'->>'pinnedDate' from rearm.api_keys where uuid = ?",
				String.class, uuid);
	}

	// row 6
	@Test
	public void theFirstRepositoryIdPinsAndIsASave() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String repository = repo();
		UUID key = apiKeyService.ensureFederatedIdentity(org, identity(repository), WU).getUuid();
		RowSnapshot before = snap(key);

		FederatedIdentity pinned = identity(repository, "refs/heads/main", "6006", "frank");
		pinned.setRepositoryId("R_1");
		pinned.setOwnerId("O_1");
		apiKeyService.ensureFederatedIdentity(org, pinned, WU);

		RowSnapshot afterPin = snap(key);
		assertContentSave(before, afterPin);
		assertEquals("R_1", federationOf(afterPin).get("repositoryId"));
		assertEquals("O_1", federationOf(afterPin).get("ownerId"));
		assertNotNull(federationOf(afterPin).get("pinnedDate"));
		assertEquals(versionOf(before) + 1, versionOf(afterPin));
		String pinnedDate = pinnedDateText(key);

		FederatedIdentity next = identity(repository, "refs/heads/next", "6007", "grace");
		next.setRepositoryId("R_1");
		next.setOwnerId("O_1");
		ApiKey returned = apiKeyService.ensureFederatedIdentity(org, next, WU);

		RowSnapshot afterStamp = snap(key);
		assertStampedOnly(afterPin, afterStamp, Set.of("federation"));
		assertOnlyTripleMoved(afterPin, afterStamp);
		assertEquals("6007", federationOf(afterStamp).get("lastRunId"));
		assertEquals(versionOf(afterPin), versionOf(afterStamp), "no version bump on the stamp path");
		assertEquals("refs/heads/next", ApiKeyData.dataFromRecord(returned).getFederation().getLastRef());
		assertEquals(pinnedDate, pinnedDateText(key), "the pin date is untouched to the digit");
	}

	// row 7
	@Test
	public void aKillSwitchSetDuringTheExchangeIsReadBeforeTheSplitDecides() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String repository = repo();
		FederatedIdentity fi = identity(repository);
		UUID key = apiKeyService.ensureFederatedIdentity(org, fi, WU).getUuid();
		RowSnapshot[] afterAdmin = new RowSnapshot[1];
		ExecutorService other = Executors.newSingleThreadExecutor();
		try {
			ApiKeyStatus seen = new TransactionTemplate(transactionManager).execute(status -> {
				apiKeyService.getFederatedIdentity(org, fi).orElseThrow();
				try {
					afterAdmin[0] = other.submit(() -> {
						apiKeyService.setApiKeyStatus(key, ApiKeyStatus.INACTIVE, true, WU);
						return snap(key);
					}).get(30, TimeUnit.SECONDS);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
				return ApiKeyData.dataFromRecord(apiKeyService.ensureFederatedIdentity(org,
						identity(repository, "refs/heads/main", "7007", "heidi"), WU)).getStatus();
			});
			assertEquals(ApiKeyStatus.INACTIVE, seen, "the returned entity reads the kill switch");
		} finally {
			other.shutdownNow();
		}
		RowSnapshot afterExchange = snap(key);
		assertEquals("INACTIVE", afterAdmin[0].recordData().get("status"));
		assertStampedOnly(afterAdmin[0], afterExchange, Set.of("federation"));
		assertOnlyTripleMoved(afterAdmin[0], afterExchange);
		assertEquals("7007", federationOf(afterExchange).get("lastRunId"));
	}

	// row 8
	@Test
	public void aContentSaveBetweenTwoStampsIsTheOneRevision() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String repository = repo();
		UUID key = apiKeyService.ensureFederatedIdentity(org, identity(repository), WU).getUuid();
		RowSnapshot before = snap(key);

		apiKeyService.ensureFederatedIdentity(org, identity(repository, "refs/heads/first", "8001", "ivan"), WU);
		apiKeyService.setNotesOnApiKey(key, "n", WU);
		apiKeyService.ensureFederatedIdentity(org, identity(repository, "refs/heads/second", "8002", "ivan"), WU);

		RowSnapshot after = snap(key);
		assertEquals(before.revision() + 1, after.revision(), "the notes are the one revision");
		assertEquals(before.auditRows() + 1, after.auditRows(), "the notes are the one audit row");
		assertEquals("refs/heads/first", auditCopy(key).getFederation().getLastRef(),
				"the stamped value rides into the trail with the next content change");
		assertEquals("refs/heads/second", federationOf(after).get("lastRef"));
		assertEquals("n", after.recordData().get("notes"));
	}

	// row 9
	@Test
	public void stampFederatedLastSeenRefusesARowWithoutAFederationObject() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		ApiKey freeform = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org, UUID.randomUUID().toString(),
				"TPF-107 no federation", WU);
		RowSnapshot before = snap(freeform.getUuid());
		assertNull(before.recordData().get("federation"), "a FREEFORM key carries no federation object");
		TransactionTemplate tt = new TransactionTemplate(transactionManager);
		assertEquals(Integer.valueOf(0), tt.<Integer>execute(s -> apiKeyRepository.stampFederatedLastSeen(freeform.getUuid(), "r", "1", "a")));
		assertStampedOnly(before, snap(freeform.getUuid()), Set.of());
		assertEquals(Integer.valueOf(0), tt.<Integer>execute(s -> apiKeyRepository.stampFederatedLastSeen(UUID.randomUUID(), "r", "1", "a")));
	}

	// row 10
	@Test
	public void twoExchangesOfOneIdentityAtOnceBothLand() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String repository = repo();
		UUID key = apiKeyService.ensureFederatedIdentity(org, identity(repository), WU).getUuid();
		RowSnapshot before = snap(key);
		CyclicBarrier gate = new CyclicBarrier(2);
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (String runId : List.of("10001", "10002")) {
				futures.add(pool.submit(() -> {
					try {
						gate.await(10, TimeUnit.SECONDS);
						new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
							jdbc.execute("SET LOCAL lock_timeout = '10s'");
							apiKeyService.ensureFederatedIdentity(org, identity(repository, "refs/heads/main", runId, "judy"), WU);
						});
					} catch (Throwable th) {
						failures.add(th);
					}
					return null;
				}));
			}
			for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		assertTrue(failures.isEmpty(), "both exchanges land: " + failures);
		RowSnapshot after = snap(key);
		assertStampedOnly(before, after, Set.of("federation"));
		assertOnlyTripleMoved(before, after);
		assertTrue(Set.of("10001", "10002").contains(federationOf(after).get("lastRunId")),
				"one of the two run ids: " + federationOf(after).get("lastRunId"));
		assertEquals(federationOf(before).size() + 3, federationOf(after).size(), "the triple added, nothing else");
	}
}
