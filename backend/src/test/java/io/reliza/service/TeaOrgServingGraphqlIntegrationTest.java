/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.AdvisoryLockKey;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.TeaServing;
import io.reliza.model.OrganizationData.TeaWellKnownHosting;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;

/**
 * TPF-142, architecture section 4 rows 13, 14 and 15 (a class of their own beside TeaProfileGraphqlIntegrationTest,
 * which carries the case-25 extension): the organization administrator's setTeaOrgServing, its gate and invariants,
 * the discovery by mode, and the audited funnel with its carry-forward and lock order. TPF-149 rows 1-5 and 7-9: a
 * serving mode other than NONE on an organization without a TEA id mints one in the same request, after every
 * refusal, under the lock order system_info, then TEA_ID_MINT(org), then the organization row.
 */
public class TeaOrgServingGraphqlIntegrationTest extends TeaProfileGraphqlIntegrationTestBase {

	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private TeaHostAssignmentService teaHostAssignmentService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private PlatformTransactionManager transactionManager;

	private final ExecutorService pool = Executors.newFixedThreadPool(3);

	@BeforeEach
	void clearHostMap() {
		jdbcTemplate.update("UPDATE rearm.system_info SET data = data - 'teaHostAssignments' WHERE id = 1");
	}

	@AfterEach
	void clearHostMapAndPool() {
		pool.shutdownNow();
		jdbcTemplate.update("UPDATE rearm.system_info SET data = data - 'teaHostAssignments' WHERE id = 1");
	}

	private UserData member(Organization org, PermissionType type) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Srv " + tag, "tpf142-s-" + tag + "@tea.io", true, List.of(org.getUuid()),
				"tpf142-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), type, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private Map<String, Object> discovery(UserData u, Organization org) {
		return ok(exec(u, DISCOVERY, "o", org.getUuid().toString()), "teaOrgDiscovery");
	}

	private graphql.ExecutionResult serving(UserData u, Organization org, String s, String h) {
		return exec(u, SET_SERVING, "o", org.getUuid().toString(), "s", s, "h", h);
	}

	private OrganizationData stored(UUID org) {
		return OrganizationData.orgDataFromDbRecord(organizationRepository.findById(org).orElseThrow());
	}

	private long auditRows(UUID org) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.audit WHERE entity_uuid = ? AND entity_name = ?", Long.class, org,
				TableName.ORGANIZATIONS.toString());
	}

	private long revision(UUID org) {
		return jdbcTemplate.queryForObject("SELECT revision FROM rearm.organizations WHERE uuid = ?", Long.class, org);
	}

	private String storedKey(UUID org, String key) {
		return jdbcTemplate.queryForObject("SELECT record_data->>'" + key + "' FROM rearm.organizations WHERE uuid = ?", String.class, org);
	}

	private int lockWaiters() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid "
				+ "WHERE NOT l.granted AND a.datname = current_database()", Integer.class);
	}

	private void awaitWaiters(int n) throws InterruptedException {
		long until = System.currentTimeMillis() + 30_000;
		while (lockWaiters() < n) {
			if (System.currentTimeMillis() > until) throw new IllegalStateException("fewer than " + n + " lock waiters");
			Thread.sleep(50);
		}
	}

	private static Throwable cause(Future<?> f) throws InterruptedException {
		try {
			f.get(60, TimeUnit.SECONDS);
			return null;
		} catch (ExecutionException e) {
			return e.getCause();
		} catch (TimeoutException e) {
			throw new IllegalStateException("the thread did not finish", e);
		}
	}

	// row 13
	@Test
	void theServingWriteIsOrganizationAdminOnlyAndKeepsItsInvariants() throws Exception {
		Organization a = testInitializer.obtainOrganization();
		Organization other = testInitializer.obtainOrganization();
		UserData admin = admin(a);
		for (UserData refused : List.of(member(a, PermissionType.READ_WRITE), member(a, PermissionType.READ_ONLY),
				member(other, PermissionType.READ_ONLY), admin(other))) {
			failsWith(serving(refused, a, "LEGACY_UUID", "NONE"), "Not authorized");
		}
		assertEquals(TeaServing.NONE, stored(a.getUuid()).effectiveTeaServing(), "nothing written by a refused caller");
		assertNull(storedKey(a.getUuid(), "teaUuid"), "a refused caller mints nothing");
		Map<String, Object> legacy = ok(serving(admin, a, "LEGACY_UUID", "NONE"), "setTeaOrgServing");
		assertEquals("LEGACY_UUID", legacy.get("teaServing"));
		// TPF-149 row 2: LEGACY_UUID on an organization without an id mints one instead of refusing
		assertNotNull(legacy.get("teaUuid"));
		assertEquals(storedKey(a.getUuid(), "teaUuid"), legacy.get("teaUuid"));
		assertEquals(StringUtils.stripEnd(relizaConfigProps.getBaseuri(), "/") + "/tea/" + legacy.get("teaUuid"), legacy.get("apiBase"));
		assertNull(legacy.get("wellKnownUrl"));
		failsWith(serving(admin, a, "DOMAIN", "NONE"), OrganizationService.NO_TEA_DOMAIN_ASSIGNED);
		failsWith(serving(admin, a, "NONE", "DOMAIN"), OrganizationService.WELL_KNOWN_NEEDS_DOMAIN);
		failsWith(serving(admin, a, "LEGACY_UUID", "DOMAIN"), OrganizationService.WELL_KNOWN_NEEDS_DOMAIN);

		teaHostAssignmentService.assign("tea-a.test", a.getUuid(), null);
		Map<String, Object> domain = ok(serving(admin, a, "DOMAIN", "DOMAIN"), "setTeaOrgServing");
		assertEquals("tea-a.test", domain.get("assignedHost"));
		assertEquals("DOMAIN", domain.get("teaServing"));
		assertEquals("DOMAIN", domain.get("teaWellKnownHosting"));
		assertEquals("https://tea-a.test/tea", domain.get("apiBase"));
		assertEquals("https://tea-a.test/.well-known/tea", domain.get("wellKnownUrl"));
		assertEquals(Utils.OM.readTree("{\"schemaVersion\":1,\"endpoints\":[{\"url\":\"https://tea-a.test/tea\",\"versions\":[\"1.0.0\"],\"priority\":1}]}"),
				Utils.OM.readTree((String) domain.get("wellKnownDocument")));
		assertEquals(domain, discovery(member(a, PermissionType.READ_ONLY), a), "teaOrgDiscovery as a READ member");
		ok(serving(admin, a, "NONE", "NONE"), "setTeaOrgServing");
	}

	// row 14
	@Test
	void theDiscoveryFollowsTheMode() throws Exception {
		Organization a = testInitializer.obtainOrganization();
		UserData admin = admin(a);
		ok(save(admin, a, "ORGANIZATION", null, profile("publishing", "ENABLED")), "saveTeaProfile");
		teaHostAssignmentService.assign("tea-a.test", a.getUuid(), null);
		Map<String, Object> dark = discovery(admin, a);
		assertEquals("NONE", dark.get("teaServing"));
		assertNull(dark.get("apiBase"));
		assertNull(dark.get("wellKnownDocument"));
		assertNull(dark.get("wellKnownUrl"));
		assertEquals("tea-a.test", dark.get("assignedHost"), "the assigned host is reported whatever the mode");

		ok(serving(admin, a, "LEGACY_UUID", "NONE"), "setTeaOrgServing");
		Map<String, Object> legacy = discovery(admin, a);
		String uuidBase = StringUtils.stripEnd(relizaConfigProps.getBaseuri(), "/") + "/tea/" + legacy.get("teaUuid");
		assertEquals(uuidBase, legacy.get("apiBase"));
		assertNull(legacy.get("wellKnownUrl"));
		assertEquals("tea-a.test", legacy.get("assignedHost"));

		ok(serving(admin, a, "DOMAIN", "NONE"), "setTeaOrgServing");
		Map<String, Object> domain = discovery(admin, a);
		assertEquals("https://tea-a.test/tea", domain.get("apiBase"));
		assertNull(domain.get("wellKnownUrl"), "hosting NONE: the instance does not host the document");
		ok(serving(admin, a, "NONE", "NONE"), "setTeaOrgServing");
	}

	// row 15: one revision and one audit row; a concurrent mint survives; the mutation never writes teaUuid
	@Test
	void theWriteGoesThroughTheAuditedFunnelAndKeepsAConcurrentMint() throws Exception {
		Organization a = testInitializer.obtainOrganization();
		long rev = revision(a.getUuid());
		long audits = auditRows(a.getUuid());
		organizationService.setTeaServing(a.getUuid(), TeaServing.NONE, TeaWellKnownHosting.NONE, WU);
		assertEquals(rev + 1, revision(a.getUuid()));
		assertEquals(audits + 1, auditRows(a.getUuid()));
		assertNull(storedKey(a.getUuid(), "teaUuid"));

		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		UUID[] minted = new UUID[1];
		tx.executeWithoutResult(s -> {
			// the outer transaction holds a copy without a TEA id
			assertNull(OrganizationData.orgDataFromDbRecord(organizationRepository.findById(a.getUuid()).orElseThrow()).getTeaUuid());
			try {
				minted[0] = pool.submit(() -> organizationService.ensureTeaUuid(a.getUuid(), WU)).get(60, TimeUnit.SECONDS);
				organizationService.setTeaServing(a.getUuid(), TeaServing.NONE, TeaWellKnownHosting.NONE, WU);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		assertNotNull(minted[0]);
		assertEquals(minted[0].toString(), storedKey(a.getUuid(), "teaUuid"), "the mint is not erased by the stale save");
	}

	// ---------------------------------------------------------------- TPF-149

	private static final String DOMAIN_DOCUMENT =
			"{\"schemaVersion\":1,\"endpoints\":[{\"url\":\"https://tea-a.test/tea\",\"versions\":[\"1.0.0\"],\"priority\":1}]}";

	// TPF-149 row 1
	@Test
	void aDomainChoiceOnAnOrganizationWithoutATeaIdMintsOneAndIsServed() throws Exception {
		Organization a = testInitializer.obtainOrganization();
		UserData admin = admin(a);
		teaHostAssignmentService.assign("tea-a.test", a.getUuid(), null);
		assertNull(storedKey(a.getUuid(), "teaUuid"));
		long rev = revision(a.getUuid());
		long audits = auditRows(a.getUuid());

		Map<String, Object> answer = ok(serving(admin, a, "DOMAIN", "DOMAIN"), "setTeaOrgServing");

		assertNotNull(answer.get("teaUuid"));
		assertEquals(storedKey(a.getUuid(), "teaUuid"), answer.get("teaUuid"));
		assertEquals("DOMAIN", answer.get("teaServing"));
		assertEquals("https://tea-a.test/tea", answer.get("apiBase"));
		assertEquals("https://tea-a.test/.well-known/tea", answer.get("wellKnownUrl"));
		assertEquals(Utils.OM.readTree(DOMAIN_DOCUMENT), Utils.OM.readTree((String) answer.get("wellKnownDocument")));
		assertEquals(rev + 1, revision(a.getUuid()), "the mint writes no revision, the serving write one");
		assertEquals(audits + 1, auditRows(a.getUuid()), "the mint writes no audit row, the serving write one");
		assertEquals(answer, discovery(admin, a));
	}

	// TPF-149 row 3
	@Test
	void noneNeverMints() throws Exception {
		Organization a = testInitializer.obtainOrganization();
		UserData admin = admin(a);
		teaHostAssignmentService.assign("tea-a.test", a.getUuid(), null);

		Map<String, Object> answer = ok(serving(admin, a, "NONE", "NONE"), "setTeaOrgServing");

		assertNull(answer.get("teaUuid"));
		assertNull(answer.get("apiBase"));
		assertNull(answer.get("wellKnownDocument"));
		assertEquals("tea-a.test", answer.get("assignedHost"));
		assertNull(storedKey(a.getUuid(), "teaUuid"));
	}

	// TPF-149 row 5
	@Test
	void anOrganizationWithATeaIdKeepsItOnEveryMode() throws Exception {
		Organization a = testInitializer.obtainOrganization();
		UserData admin = admin(a);
		UUID x = organizationService.ensureTeaUuid(a.getUuid(), WU);
		teaHostAssignmentService.assign("tea-a.test", a.getUuid(), null);
		Map<String, Object> none = null;
		for (String mode : List.of("LEGACY_UUID", "DOMAIN", "NONE")) {
			Map<String, Object> answer = ok(serving(admin, a, mode, "NONE"), "setTeaOrgServing");
			assertEquals(x.toString(), answer.get("teaUuid"), mode);
			none = answer;
		}
		assertEquals(x.toString(), storedKey(a.getUuid(), "teaUuid"), "NONE never clears the id");
		assertNull(none.get("apiBase"));
	}

	// TPF-149 row 7
	@Test
	void twoConcurrentServingWritesMintOneId() throws Exception {
		Organization a = testInitializer.obtainOrganization();
		teaHostAssignmentService.assign("tea-a.test", a.getUuid(), null);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<OrganizationData>> writes = List.of(
				pool.submit(() -> {
					start.await();
					return organizationService.setTeaServing(a.getUuid(), TeaServing.DOMAIN, TeaWellKnownHosting.NONE, WU);
				}),
				pool.submit(() -> {
					start.await();
					return organizationService.setTeaServing(a.getUuid(), TeaServing.DOMAIN, TeaWellKnownHosting.NONE, WU);
				}));
		start.countDown();
		for (Future<OrganizationData> f : writes) assertNull(cause(f));
		UUID first = writes.get(0).get().getTeaUuid();
		assertNotNull(first);
		assertEquals(first, writes.get(1).get().getTeaUuid());
		assertEquals(first.toString(), storedKey(a.getUuid(), "teaUuid"));
	}

	/** A transaction that holds the TEA_ID_MINT lock of the organization until released, then mints {@code id}. */
	private Future<?> holdTheMintLock(UUID org, UUID id, CountDownLatch held, CountDownLatch release) {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		return pool.submit(() -> tx.executeWithoutResult(s -> {
			organizationRepository.lockTeaIdMint(AdvisoryLockKey.TEA_ID_MINT.getQueryVal(), org.toString());
			held.countDown();
			try {
				assertTrue(release.await(60, TimeUnit.SECONDS));
			} catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
			organizationRepository.mintTeaUuid(org, id.toString());
		}));
	}

	// TPF-149 row 8
	@Test
	void aServingWriteAgainstAConcurrentMintAnswersThatMintsId() throws Exception {
		Organization a = testInitializer.obtainOrganization();
		UUID x = UUID.randomUUID();
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<?> t1 = holdTheMintLock(a.getUuid(), x, held, release);
		assertTrue(held.await(60, TimeUnit.SECONDS));
		int before = lockWaiters();
		Future<OrganizationData> t2 = pool.submit(
				() -> organizationService.setTeaServing(a.getUuid(), TeaServing.LEGACY_UUID, TeaWellKnownHosting.NONE, WU));
		awaitWaiters(before + 1);
		release.countDown();
		assertNull(cause(t1));
		assertNull(cause(t2));
		assertEquals(x.toString(), storedKey(a.getUuid(), "teaUuid"));
		assertEquals(x, t2.get().getTeaUuid(), "the serving write minted no second id");
		assertEquals("LEGACY_UUID", storedKey(a.getUuid(), "teaServing"));
	}

	// TPF-149 row 9: system_info before the mint lock
	@Test
	void theServingWriteHoldsSystemInfoWhileItWaitsOnTheMintLock() throws Exception {
		Organization a = testInitializer.obtainOrganization();
		Organization b = testInitializer.obtainOrganization();
		teaHostAssignmentService.assign("tea-a.test", a.getUuid(), null);
		UUID x = UUID.randomUUID();
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<?> t1 = holdTheMintLock(a.getUuid(), x, held, release);
		assertTrue(held.await(60, TimeUnit.SECONDS));
		int before = lockWaiters();
		Future<OrganizationData> t2 = pool.submit(
				() -> organizationService.setTeaServing(a.getUuid(), TeaServing.DOMAIN, TeaWellKnownHosting.NONE, WU));
		awaitWaiters(before + 1);
		Future<?> t3 = pool.submit(() -> teaHostAssignmentService.assign("tea-b.test", b.getUuid(), null));
		awaitWaiters(before + 2);
		assertFalse(t3.isDone(), "assign waits on the system_info row the serving write holds");
		release.countDown();
		assertNull(cause(t1));
		assertNull(cause(t2));
		assertNull(cause(t3));
		assertEquals(x.toString(), storedKey(a.getUuid(), "teaUuid"));
		assertEquals("DOMAIN", storedKey(a.getUuid(), "teaServing"));
		assertEquals(Optional.of(b.getUuid()), teaHostAssignmentService.orgOfHost("tea-b.test"));
	}
}
