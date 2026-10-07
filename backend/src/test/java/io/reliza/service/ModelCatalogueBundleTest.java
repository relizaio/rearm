/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.ModelFacts.HostingKind;
import io.reliza.model.ModelFacts.Modality;
import io.reliza.model.ModelOntologyData.ModelTier;
import io.reliza.ws.App;

/**
 * The bundled catalogue must actually parse.
 *
 * <p>Worth its own test because the loader CATCHES its failures by design -- a malformed bundle
 * must not stop the application. The consequence is that a bad edit degrades silently: every model
 * resolves as unresolved and prices nothing, with only a log line to say so. Nothing else in the
 * suite would notice, and in production nobody reads a startup log until costs are missing.
 *
 * <p>This is not hypothetical. Typing the facts turned {@code modalities: [text, image]} in the
 * yaml into an enum parse, Jackson matches enum names case-sensitively, and the whole catalogue
 * went empty.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ModelCatalogueBundleTest {

	@Autowired private ModelCatalogueBundle bundle;

	@Test
	public void theShippedCatalogueLoads() {
		assertFalse(bundle.models().isEmpty(),
				"the bundled catalogue is empty, which means it failed to parse: every model "
						+ "would resolve as UNRESOLVED and price nothing");
	}

	@Test
	public void everyBundledModelCarriesTheFactsTheCatalogueExistsToProvide() {
		// A row with no facts is no better than the unresolved row it replaces.
		for (var m : bundle.models()) {
			assertNotNull(m.canonicalId(), "a bundled model needs a canonical id");
			assertNotNull(m.facts(), m.canonicalId() + " has no facts");
			assertNotNull(m.facts().contextWindow(), m.canonicalId() + " has no context window");
			assertTrue(m.facts().contextWindow() > 0, m.canonicalId() + " has a nonsense context window");
		}
	}

	@Test
	public void theVocabulariesInTheYamlMatchTheEnums() {
		// The specific breakage: a value the yaml spells differently from the enum parses to
		// nothing, and the catch swallows it.
		for (var m : bundle.models()) {
			if (null != m.tier()) {
				// Tier is provenance now, not capability. Every bundled row is a closed model from
				// a frontier lab; a bundled row that said otherwise would be a claim about someone
				// else's weights that nobody here can make.
				assertEquals(ModelTier.FRONTIER, m.tier(),
						m.canonicalId() + " is bundled, so its provenance is FRONTIER");
			}
			assertNotNull(m.strength(),
					m.canonicalId() + " needs a strength: a bundled row with none is eligible for"
							+ " no role that asks for a floor, which is not what the bundle is for");
			for (Modality mod : m.facts().modalities()) {
				assertNotNull(mod, m.canonicalId() + " has an unparsed modality");
			}
			if (null != m.facts().hostingKind()) {
				assertNotNull(HostingKind.valueOf(m.facts().hostingKind().name()));
			}
		}
	}

	@Test
	public void aMisCasedValueStillParses() {
		// Belt and braces for the hand-edited file: the enums accept either case, so one
		// lowercase letter cannot cost the whole catalogue again.
		assertEquals(Modality.TEXT, Modality.fromValue("text"));
		assertEquals(Modality.IMAGE, Modality.fromValue(" Image "));
		assertEquals(HostingKind.API, HostingKind.fromValue("api"));
		assertEquals(HostingKind.SELF_HOSTED, HostingKind.fromValue("self_hosted"));
	}

	@Test
	public void anUnquotedDateWouldNotSilentlyKillTheCatalogue() {
		// snakeyaml parses an unquoted 2026-01-31 as a java.util.Date, which does not convert to
		// the LocalDate ModelFacts declares -- and the loader catches, so the whole catalogue goes
		// empty without anything failing. No date is in the bundle yet; this asserts the loaded
		// state so that adding one unquoted turns a silent outage into a red test.
		assertFalse(bundle.models().isEmpty(), "the catalogue must load");
		for (var m : bundle.models()) {
			// Reaching the facts at all proves the record bound; a date that failed to convert
			// would have thrown during load and left the list empty.
			assertNotNull(m.facts(), m.canonicalId() + " has no facts");
		}
	}
}
