/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.ws.App;
import jakarta.persistence.EntityManager;

/**
 * TPF-70 (architecture round 2, test 4.5 row 2): the server honours the three settings of
 * {@code OrganizationService.inTotalsReadTransaction} inside it and drops them at its end. The
 * shared {@link EntityManager} proxy joins the transaction the method opens, so a read through it
 * sees the transaction-local values. That {@code getNumericAnalytics} calls the method is pinned
 * by {@code OrganizationServiceTotalsTransactionTest}.
 */
@SpringBootTest(classes = {App.class})
class OrganizationServiceTotalsTransactionIntegrationTest {

	@Autowired private OrganizationService organizationService;
	@Autowired private EntityManager entityManager;
	@Autowired private JdbcTemplate jdbc;

	private String show(String setting) {
		return (String) entityManager.createNativeQuery("SHOW " + setting).getSingleResult();
	}

	@Test
	void theSettingsHoldInsideTheWrapperAndRevertAfterIt() {
		List<String> inside = organizationService.inTotalsReadTransaction(
				() -> List.of(show("enable_seqscan"), show("jit"), show("statement_timeout")));
		assertEquals(List.of("off", "off", "30s"), inside);

		List<String> after = List.of(jdbc.queryForObject("SHOW enable_seqscan", String.class),
				jdbc.queryForObject("SHOW jit", String.class),
				jdbc.queryForObject("SHOW statement_timeout", String.class));
		assertEquals(List.of("on", "on", "0"), after);
	}
}
