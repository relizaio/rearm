/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The startup sweep itself, not the fold it calls. sweepOnStartup ran foldDuplicates and
 * markCardSpecVersions on this rather than through the proxy, so neither ran in a transaction and
 * the fold's repoint (propagation MANDATORY) threw on every installation with duplicates to fold;
 * the sweep logged the error, handed its claim back and failed again on the next start. The fold
 * tests call foldDuplicates through the proxy, which is why they never saw it.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ModelCatalogueStartupSweepTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Autowired private TestInitializer testInitializer;
	@Autowired private ModelOntologyService modelOntologyService;
	@Autowired private AgentService agentService;
	@Autowired private ModelCatalogueSweepService sweepService;
	@Autowired private SystemInfoService systemInfoService;
	@Autowired private JdbcTemplate jdbcTemplate;

	@Test
	public void theStartupSweepFoldsDuplicatesAndKeepsItsClaim() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ModelOntologyData a = modelOntologyService.findOrRegisterModel(
				org.getUuid(), "claude-opus", "4.7", "Anthropic", WU);
		ModelOntologyData b = modelOntologyService.findOrRegisterModel(
				org.getUuid(), "claude-opus-4-7", ModelOntologyData.UNKNOWN_VERSION, "Anthropic", WU);
		assertEquals(2, List.of(a, b).stream().map(ModelOntologyData::getUuid).distinct().count(),
				"two spellings of one model start as two rows");
		// Something must name the row the fold removes, or the repoint has nothing to do.
		var agent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"startup-sweep-agent-" + UUID.randomUUID(), b.getUuid(), null, null, WU);

		// The context's own startup already ran the sweep and took the claim; hand it back so this
		// call runs the sweep again.
		systemInfoService.releaseModelCatalogueSweep();
		sweepService.sweepOnStartup();

		List<ModelOntologyData> remaining = modelOntologyService.listByOrg(org.getUuid()).stream()
				.filter(m -> ModelIdNormalizer.normalize(m.getName(), m.getVersion()).base()
						.equals("claudeopus47"))
				.toList();
		assertEquals(1, remaining.size(), "the sweep folded the two spellings into one row");
		assertEquals(remaining.get(0).getUuid(),
				agentService.getAgentData(agent.getUuid()).orElseThrow().getModel(),
				"the agent was repointed at the survivor");
		// A failed sweep hands the claim back; a completed one keeps it.
		assertFalse(systemInfoService.claimModelCatalogueSweep(), "the completed sweep kept its claim");
	}

	/**
	 * The startup sweep marks an org's cards in one transaction, as the daily path does: a failure
	 * part way leaves none of that org's cards marked. Without the proxy each saveData committed on
	 * its own, so the cards before the failure stayed marked. A trigger refuses the update of the
	 * older row; listByOrg returns newest first, so the newer row is marked before the refusal and
	 * must be rolled back with it.
	 */
	@Test
	public void theStartupSweepMarksAnOrgsCardsAtomically() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		ModelOntologyData older = modelOntologyService.findOrRegisterModel(
				org.getUuid(), "atomic-older-" + suffix, "1.0", "Anthropic", WU);
		ModelOntologyData newer = modelOntologyService.findOrRegisterModel(
				org.getUuid(), "atomic-newer-" + suffix, "1.0", "Anthropic", WU);
		// Unmarked, the shape a card that predates the converter has.
		jdbcTemplate.update("UPDATE rearm.model_ontologies SET record_data = record_data - 'modelCardSpecVersion'"
				+ " WHERE uuid IN (?, ?)", older.getUuid(), newer.getUuid());
		String fn = "rearm.refuse_mark_" + suffix;
		String trigger = "refuse_mark_" + suffix;
		jdbcTemplate.execute("CREATE FUNCTION " + fn + "() RETURNS trigger AS $$ BEGIN"
				+ " IF NEW.uuid = '" + older.getUuid() + "'::uuid THEN RAISE EXCEPTION 'refused by the test'; END IF;"
				+ " RETURN NEW; END $$ LANGUAGE plpgsql");
		jdbcTemplate.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON rearm.model_ontologies"
				+ " FOR EACH ROW EXECUTE FUNCTION " + fn + "()");
		try {
			systemInfoService.releaseModelCatalogueSweep();
			sweepService.sweepOnStartup();
		} finally {
			jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON rearm.model_ontologies");
			jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + fn + "()");
		}
		ModelOntologyData newerAfter = modelOntologyService.listByOrg(org.getUuid()).stream()
				.filter(m -> m.getUuid().equals(newer.getUuid())).findFirst().orElseThrow();
		assertNull(newerAfter.getModelCardSpecVersion(),
				"the newer card's marking was rolled back with the refused one");
		// The failed sweep handed its claim back; take it and return it so the next test starts clean.
		assertTrue(systemInfoService.claimModelCatalogueSweep(), "the failed sweep handed its claim back");
		systemInfoService.releaseModelCatalogueSweep();
	}
}
