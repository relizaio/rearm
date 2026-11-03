/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.TeaServing;
import io.reliza.model.OrganizationData.TeaWellKnownHosting;

/**
 * TPF-142 mutation round: what the store classes leave open. The in-place statements of the host map (a second host
 * survives an unassign, a JSON null map is replaced, only the one system_info row is touched, an absent row is created),
 * the two locked reads as against a copy the caller's transaction already holds (setTeaServing sees an assign committed
 * after the copy was read, unassign sees a DOMAIN written after it), the setTeaServing refusals, and the carry-forward
 * of an asymmetric stored pair through both whole-record funnels.
 */
public class TeaHostAssignmentStoreEdgesIntegrationTest extends TeaHostRoutingIntegrationTestBase {

	@Autowired private SystemInfoService systemInfoService;
	@Autowired private PlatformTransactionManager transactionManager;

	private final ExecutorService pool = Executors.newSingleThreadExecutor();

	@AfterEach
	void cleanUp() {
		pool.shutdownNow();
		jdbcTemplate.update("DELETE FROM rearm.system_info WHERE id = 2");
	}

	private UUID freshOrg() {
		return testInitializer.obtainOrganization().getUuid();
	}

	/** Runs the work in another thread and transaction, committed when this returns. */
	private void elsewhere(Callable<?> work) {
		try {
			pool.submit(work).get(60, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private String storedKey(UUID org, String key) {
		return jdbcTemplate.queryForObject("SELECT record_data->>'" + key + "' FROM rearm.organizations WHERE uuid = ?", String.class, org);
	}

	private String rowTwo(String host) {
		return jdbcTemplate.queryForObject("SELECT data->'teaHostAssignments'->>? FROM rearm.system_info WHERE id = 2", String.class, host);
	}

	@Test
	void unassigningOneHostKeepsTheOthers() throws Exception {
		UUID o1 = freshOrg();
		UUID o2 = freshOrg();
		assign(A_HOST, o1);
		assign(B_HOST, o2);

		assertTrue(teaHostAssignmentService.unassign(A_HOST, null));

		assertEquals(Map.of(B_HOST, o2.toString()), systemInfoService.getTeaHostAssignments());
	}

	@Test
	void aJsonNullMapIsReplacedByTheFirstAssignment() throws Exception {
		jdbcTemplate.update("UPDATE rearm.system_info SET data = jsonb_set(data, '{teaHostAssignments}', cast('null' as jsonb)) WHERE id = 1");
		UUID o1 = freshOrg();

		assign(A_HOST, o1);

		assertEquals(Map.of(A_HOST, o1.toString()), systemInfoService.getTeaHostAssignments());
	}

	@Test
	void aHostWhoseStoredValueIsJsonNullIsFreeToAssign() throws Exception {
		jdbcTemplate.update("UPDATE rearm.system_info SET data = jsonb_set(data, '{teaHostAssignments}',"
				+ " cast('{\"" + A_HOST + "\": null}' as jsonb)) WHERE id = 1");
		UUID o1 = freshOrg();

		assertEquals(o1, teaHostAssignmentService.assign(A_HOST, o1, null).org(), "the host held by no one is assigned");

		assertEquals(Map.of(A_HOST, o1.toString()), systemInfoService.getTeaHostAssignments());
	}

	@Test
	void assignAndUnassignWriteTheOneSystemInfoRowOnly() throws Exception {
		UUID other = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO rearm.system_info (id, data) VALUES (2, CAST(? AS jsonb))",
				"{\"teaHostAssignments\": {\"" + A_HOST + "\": \"" + other + "\"}}");
		UUID o1 = freshOrg();

		assign(A_HOST, o1);
		assertEquals(other.toString(), rowTwo(A_HOST), "assign leaves the other row's value");
		assertTrue(teaHostAssignmentService.unassign(A_HOST, null));
		assertEquals(other.toString(), rowTwo(A_HOST), "unassign leaves the other row's key");
	}

	@Test
	void assignAndUnassignCreateTheRowWhenItIsAbsent() throws Exception {
		String snapshot = jdbcTemplate.queryForObject("SELECT data::text FROM rearm.system_info WHERE id = 1", String.class);
		UUID o1 = freshOrg();
		try {
			jdbcTemplate.update("DELETE FROM rearm.system_info WHERE id = 1");
			systemInfoService.assignTeaHost(A_HOST, o1);
			assertEquals(Map.of(A_HOST, o1.toString()), systemInfoService.getTeaHostAssignments(), "assign writes once more after creating the row");

			jdbcTemplate.update("DELETE FROM rearm.system_info WHERE id = 1");
			systemInfoService.unassignTeaHost(A_HOST);
			assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.system_info WHERE id = 1", Long.class),
					"unassign creates the row it found absent");
		} finally {
			jdbcTemplate.update("DELETE FROM rearm.system_info WHERE id = 1");
			jdbcTemplate.update("INSERT INTO rearm.system_info (id, data) VALUES (1, CAST(? AS jsonb))", snapshot);
		}
	}

	@Test
	void setTeaServingSeesAnAssignCommittedAfterItsTransactionReadTheRow() throws Exception {
		UUID org = freshOrg();
		new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
			systemInfoService.getTeaHostAssignments(); // the managed row, no assignment yet
			elsewhere(() -> teaHostAssignmentService.assign(A_HOST, org, null));
			try {
				organizationService.setTeaServing(org, TeaServing.DOMAIN, TeaWellKnownHosting.NONE, WU);
			} catch (RelizaException e) {
				throw new IllegalStateException(e);
			}
		});
		assertEquals("DOMAIN", storedKey(org, OrganizationData.TEA_SERVING_FIELD));
	}

	@Test
	void unassignReadsTheHoldersModeFromTheStoredRowNotFromACopy() throws Exception {
		UUID org = freshOrg();
		assign(A_HOST, org);
		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
					getOrganizationService.getOrganization(org).orElseThrow(); // the managed copy, serving NONE
					elsewhere(() -> organizationService.setTeaServing(org, TeaServing.DOMAIN, TeaWellKnownHosting.NONE, WU));
					try {
						teaHostAssignmentService.unassign(A_HOST, null);
					} catch (RelizaException e) {
						throw new IllegalStateException(e);
					}
				}));
		assertTrue(refused.getCause() instanceof RelizaException && refused.getCause().getMessage().contains("serves TEA on"),
				String.valueOf(refused));
		assertEquals(Map.of(A_HOST, org.toString()), systemInfoService.getTeaHostAssignments(), "the host is still assigned");
	}

	@Test
	void aSettingNeedsAllThreeArguments() {
		UUID org = freshOrg();
		for (RelizaException e : new RelizaException[] {
				assertThrows(RelizaException.class, () -> organizationService.setTeaServing(null, TeaServing.NONE, TeaWellKnownHosting.NONE, WU)),
				assertThrows(RelizaException.class, () -> organizationService.setTeaServing(org, null, TeaWellKnownHosting.NONE, WU)),
				assertThrows(RelizaException.class, () -> organizationService.setTeaServing(org, TeaServing.NONE, null, WU)) }) {
			assertEquals("org, teaServing and teaWellKnownHosting are required", e.getMessage());
		}
		assertNull(storedKey(org, OrganizationData.TEA_SERVING_FIELD), "nothing was written");
	}

}
