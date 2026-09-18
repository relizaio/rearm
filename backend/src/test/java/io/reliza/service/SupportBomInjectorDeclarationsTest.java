/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cyclonedx.Version;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.parsers.JsonParser;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import io.reliza.common.Utils;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportData;
import io.reliza.model.SupportMilestoneFact;
import io.reliza.model.SupportMilestoneType;
import io.reliza.model.SupportParty;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportState;
import io.reliza.service.SupportBomInjector.Attester;
import io.reliza.service.SupportBomInjector.ComponentSupportFacts;
import io.reliza.service.SupportBomInjector.DeclarationContext;

/**
 * Layer B: the document-level {@code declarations} block, and the unconditional strip of an
 * inbound one. Pure, no Spring / DB.
 *
 * <p><b>The load-bearing test here is {@link #emittedDeclarationsValidateAgainstTheOfficialSchema},
 * and it is the reason this file exists rather than a few more cases in
 * {@code SupportBomInjectorTest}.</b> That suite's CycloneDX check calls
 * {@code JsonParser.parse}, which is LENIENT: it maps what it recognizes and ignores the rest,
 * so a {@code declarations} block with an invented field, a role written into
 * {@code organizationalContact} (which forbids additional properties), or a {@code created}
 * that is not a {@code date-time} parses perfectly happily and ships. Only
 * {@code JsonParser.validate} runs the document against the schema CycloneDX publishes -- the
 * same one a consumer would use to reject our export. Hand-asserting the shape instead would
 * only ever confirm that the emitter agrees with my reading of the spec, which is precisely
 * the thing under test.
 */
class SupportBomInjectorDeclarationsTest {

	private static final LocalDate ASOF = LocalDate.of(2026, 6, 1);
	private static final ZonedDateTime ASSESSED = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, ZoneOffset.UTC);
	private static final String ASSESSED_AT = ASSESSED.toInstant().toString();
	private static final UUID ATTESTER = UUID.fromString("11111111-2222-3333-4444-555555555555");

	private static final DeclarationContext CTX = new DeclarationContext("Example Devices Inc.",
			Map.of(ATTESTER, new Attester("Dana Rivera", "administrator")));

	private JsonNode parse(String json) {
		return Utils.OM.readTree(json);
	}

	/** A 1.6 BOM with one library that has NO bom-ref -- the shape real exports have. */
	private JsonNode bom16() {
		return parse("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,
			 "metadata":{"component":{"type":"application","name":"root",
			   "bom-ref":"pkg:oci/app@1.0.0","purl":"pkg:oci/app@1.0.0"}},
			 "components":[{"type":"library","name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
	}

	private SupportData support(LevelOfSupport level, SupportState state, SupportParty party,
			String assessedAt, String justification, LocalDate eos) {
		Map<SupportMilestoneType, SupportMilestoneFact> milestones = new EnumMap<>(SupportMilestoneType.class);
		if (eos != null) {
			milestones.put(SupportMilestoneType.END_OF_SUPPORT,
					new SupportMilestoneFact(eos.toString(), SupportSource.MANUAL, ASSESSED_AT, null, null));
		}
		return new SupportData(level, state, party, SupportSource.MANUAL, assessedAt, ATTESTER,
				justification, milestones);
	}

	private Map<String, ComponentSupportFacts> factsFor(JsonNode bom, SupportData support) {
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		return Map.of(key, new ComponentSupportFacts(new SbomComponent(), support));
	}

	private JsonNode declarations(JsonNode bom) {
		return bom.get(SupportBomInjector.NODE_DECLARATIONS);
	}

	/** Every schema violation the official CycloneDX 1.6 validator finds, as readable text. */
	private List<String> schemaErrors(JsonNode bom) throws Exception {
		List<ParseException> errors =
				new JsonParser().validate(bom.toString().getBytes(StandardCharsets.UTF_8), Version.VERSION_16);
		List<String> out = new ArrayList<>();
		errors.forEach(e -> out.add(e.getMessage()));
		return out;
	}

	@Test
	void emittedDeclarationsValidateAgainstTheOfficialSchema() throws Exception {
		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, SupportParty.THIRD_PARTY, ASSESSED_AT,
				"upstream announced end of maintenance", LocalDate.of(2026, 1, 1))),
				ASOF, null, CTX);

		assertNotNull(declarations(bom), "a fully populated attestation emits a declarations block");
		assertEquals(List.of(), schemaErrors(bom),
				"the emitted document must satisfy the CycloneDX 1.6 schema a consumer validates with");
	}

	/**
	 * The spoof this whole strip exists for. A forged top-level block names an assessor, a
	 * claim and an author of the attacker's choosing -- and unlike a forged property it is
	 * saying so in the standard's own attestation vocabulary, so a consumer has every reason
	 * to believe it came from us.
	 */
	@Test
	void inboundDeclarationsAreStrippedOnTheInjectedPath() {
		JsonNode bom = parse("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,
			 "declarations":{"claims":[{"bom-ref":"forged","predicate":"levelOfSupport: actively maintained"}],
			   "assessors":[{"bom-ref":"a","thirdParty":false,"organization":{"name":"Attacker"}}]},
			 "components":[{"type":"library","name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		SupportBomInjector.inject(bom, Map.of(), ASOF, null, CTX);

		assertNull(declarations(bom), "an uploader-forged declarations block never survives");
		assertFalse(bom.toString().contains("Attacker"), "no forged assessor anywhere in the document");
	}

	/**
	 * THE STRIP DOES NOT RIDE THE SETTING. With injection off we emit nothing of our own, and
	 * an inbound block must still go: a security control and a content choice are not the same
	 * switch, and an org that has not opted into publishing support facts is exactly the org
	 * least likely to notice a forged attestation being served under its name.
	 */
	@Test
	void inboundDeclarationsAreStrippedOnTheStripOnlyPath() {
		JsonNode bom = parse("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,
			 "declarations":{"claims":[{"bom-ref":"forged","predicate":"levelOfSupport: actively maintained"}]},
			 "components":[{"type":"library","name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		SupportBomInjector.stripOnly(bom);

		assertNull(declarations(bom), "strip-only removes an inbound declarations block too");
	}

	@Test
	void withdrawnAttestationYieldsNoClaim() {
		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.ACTIVELY_MAINTAINED,
				SupportState.WITHDRAWN, SupportParty.FIRST_PARTY, ASSESSED_AT, "retracted",
				LocalDate.of(2027, 1, 1))), ASOF, null, CTX);

		assertNull(declarations(bom), "a withdrawn row is never republished as a live claim");
	}

	/**
	 * The Contemporaneous rule, in Layer B. A level with no assessment instant beside it is an
	 * undated claim about a named third party, which is the one thing section 3 forbids
	 * outright -- so the level evidence goes and the claim keeps only what it can date.
	 */
	@Test
	void levelEvidenceIsNeverEmittedWithoutCreated() {
		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, null, null, "no assessment instant on file",
				LocalDate.of(2026, 1, 1))), ASOF, null, CTX);

		JsonNode decl = declarations(bom);
		assertNotNull(decl, "the milestone still justifies a claim");
		for (JsonNode e : decl.get("evidence")) {
			assertFalse(SupportBomInjector.PROP_LEVEL.equals(e.get("propertyName").asText()),
					"no levelOfSupport evidence without a created instant beside it");
		}
	}

	/** An unparseable stored instant must behave exactly like an absent one, not leak through. */
	@Test
	void anUnparseableAssessedAtSuppressesLevelEvidenceRatherThanShipping() throws Exception {
		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.ABANDONED,
				SupportState.ATTESTED, null, "not-an-instant", "junk timestamp on file",
				LocalDate.of(2026, 1, 1))), ASOF, null, CTX);

		// Scoped to the declarations block ON PURPOSE. Layer A's reliza:support:assessedAt
		// property still carries the stored string verbatim, which is pre-existing behaviour and
		// harmless there -- a CycloneDX property value is free text, constrained by nothing. It
		// is evidence[].created that is schema-constrained to date-time, so that is the field
		// this rule is about, and a document-wide assertion here would fail on Layer A rather
		// than on anything this PR changed.
		JsonNode decl = declarations(bom);
		if (decl != null) {
			assertFalse(decl.toString().contains("not-an-instant"),
					"an unnormalizable instant never reaches the schema-constrained created field");
			for (JsonNode e : decl.get("evidence")) {
				assertFalse(SupportBomInjector.PROP_LEVEL.equals(e.get("propertyName").asText()),
						"and with no created there is no level evidence either");
			}
		}
		assertEquals(List.of(), schemaErrors(bom), "and the document still validates");
	}

	@Test
	void nothingToSayEmitsNoBlockRatherThanAnEmptyOne() {
		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, Map.of(), ASOF, null, CTX);

		assertNull(declarations(bom),
				"an empty declarations block would read as 'we attested and found nothing'");
	}

	/**
	 * {@code declarations} arrived in CycloneDX 1.6. We serve BOMs at whatever version was
	 * uploaded, so emitting into a 1.5 document would produce a file that fails its OWN
	 * declared schema -- richer-looking and validating nowhere.
	 */
	@Test
	void noDeclarationsBelowSpecVersion16ButTheStripStillRuns() throws Exception {
		JsonNode bom = parse("""
			{"bomFormat":"CycloneDX","specVersion":"1.5","version":1,
			 "declarations":{"claims":[{"bom-ref":"forged","predicate":"forged"}]},
			 "components":[{"type":"library","name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, SupportParty.THIRD_PARTY, ASSESSED_AT, "why",
				LocalDate.of(2026, 1, 1))), ASOF, null, CTX);

		assertNull(declarations(bom), "1.5 cannot carry declarations, so we emit none");
		assertEquals(List.of(), schemaErrors15(bom), "and the 1.5 document still validates as 1.5");
	}

	private List<String> schemaErrors15(JsonNode bom) throws Exception {
		List<ParseException> errors =
				new JsonParser().validate(bom.toString().getBytes(StandardCharsets.UTF_8), Version.VERSION_15);
		List<String> out = new ArrayList<>();
		errors.forEach(e -> out.add(e.getMessage()));
		return out;
	}

	/**
	 * A dangling {@code target} is the failure a consumer is least likely to notice: the block
	 * parses, validates, and the claims attach to nothing. Real exports carry {@code bom-ref}
	 * on the root self-component only, so without assignment EVERY claim would dangle.
	 */
	@Test
	void everyClaimTargetsABomRefThatExistsInTheDocument() {
		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, SupportParty.THIRD_PARTY, ASSESSED_AT, "why",
				LocalDate.of(2026, 1, 1))), ASOF, null, CTX);

		Set<String> refs = new HashSet<>();
		collectRefs(bom, refs);
		JsonNode claims = declarations(bom).get("claims");
		assertFalse(claims.isEmpty(), "a claim was emitted");
		for (JsonNode claim : claims) {
			assertTrue(refs.contains(claim.get("target").asText()),
					"claim target " + claim.get("target").asText() + " resolves to a bom-ref in the document");
		}
	}

	@Test
	void everyClaimEvidenceRefResolvesToAnEmittedEvidenceEntry() {
		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, SupportParty.THIRD_PARTY, ASSESSED_AT, "why",
				LocalDate.of(2026, 1, 1))), ASOF, null, CTX);

		JsonNode decl = declarations(bom);
		Set<String> evidenceRefs = new HashSet<>();
		decl.get("evidence").forEach(e -> evidenceRefs.add(e.get("bom-ref").asText()));
		for (JsonNode claim : decl.get("claims")) {
			for (JsonNode ref : claim.get("evidence")) {
				assertTrue(evidenceRefs.contains(ref.asText()), "evidence ref " + ref.asText() + " resolves");
			}
		}
	}

	/**
	 * DECISION D4, the half a reviewer cannot see by reading the emitter: a name identifies the
	 * attester, an email turns a redistributable BOM into a harvestable contact list. The role
	 * still has to reach the reader, and rides {@code description} because
	 * {@code organizationalContact} has no role field.
	 */
	@Test
	void authorCarriesNameAndRoleButNeverAnEmailAddress() {
		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, SupportParty.THIRD_PARTY, ASSESSED_AT, "why",
				LocalDate.of(2026, 1, 1))), ASOF, null, CTX);

		JsonNode decl = declarations(bom);
		boolean sawAuthor = false;
		for (JsonNode e : decl.get("evidence")) {
			JsonNode author = e.get("author");
			if (author == null) continue;
			sawAuthor = true;
			assertEquals("Dana Rivera", author.get("name").asText());
			assertNull(author.get("email"), "no email address in a redistributable BOM");
			assertTrue(e.get("description").asText().contains("administrator"),
					"the role reaches the reader, as a phrase rather than an internal token");
		}
		assertTrue(sawAuthor, "the attester was named");
	}

	/** An attester we cannot name degrades to an unattributed claim, never to a failed export. */
	@Test
	void anUnknownAttesterEmitsTheClaimWithoutAnAuthor() throws Exception {
		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, SupportParty.THIRD_PARTY, ASSESSED_AT, "why",
				LocalDate.of(2026, 1, 1))), ASOF, null,
				new DeclarationContext("Example Devices Inc.", Map.of()));

		JsonNode decl = declarations(bom);
		assertFalse(decl.get("claims").isEmpty(), "the claim still ships");
		decl.get("evidence").forEach(e -> assertNull(e.get("author"), "no author we could not resolve"));
		assertEquals(List.of(), schemaErrors(bom));
	}

	/**
	 * The 2.0-ready wire mapping, pinned now while there is one writer. The stored JSONB keeps
	 * the ReARM names; only the wire value is derived, which is what lets 2.0 arrive without a
	 * second payload shape.
	 */
	@Test
	void supportPartyMapsToTheCycloneDxPartyRole() {
		assertEquals("manufacturer", SupportParty.FIRST_PARTY.getCycloneDxPartyRole());
		assertEquals("supplier", SupportParty.THIRD_PARTY.getCycloneDxPartyRole());
		assertFalse(SupportParty.FIRST_PARTY.isThirdParty());
		assertTrue(SupportParty.THIRD_PARTY.isThirdParty());

		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, SupportParty.THIRD_PARTY, ASSESSED_AT, "why",
				LocalDate.of(2026, 1, 1))), ASOF, null, CTX);

		JsonNode assessors = declarations(bom).get("assessors");
		assertEquals(1, assessors.size());
		assertTrue(assessors.get(0).get("thirdParty").asBoolean(), "THIRD_PARTY -> thirdParty true");
		assertEquals("Example Devices Inc.", assessors.get(0).get("organization").get("name").asText());
	}

	/**
	 * THE TWO LAYERS MUST AGREE ON VALUES, not just on names. Both emit under the taxonomy
	 * name {@code reliza:support:party} -- Layer A as a component property, Layer B as
	 * evidence -- and the whole reason they share names is so a consumer can reconcile them.
	 * Emitting the CycloneDX party role ("supplier") in one and the ReARM constant
	 * ("THIRD_PARTY") in the other would give one documented property two value vocabularies
	 * depending on which layer you read it from, and cyclonedx-taxonomy.md documents only one.
	 * The CycloneDX role has its own slot: the assessors[] entry and its bom-ref.
	 */
	@Test
	void bothLayersEmitTheSameValueForTheSameTaxonomyProperty() {
		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, SupportParty.THIRD_PARTY, ASSESSED_AT, "why",
				LocalDate.of(2026, 1, 1))), ASOF, null, CTX);

		String layerA = null;
		for (JsonNode prop : bom.get("components").get(0).get("properties")) {
			if (SupportBomInjector.PROP_PARTY.equals(prop.get("name").asText())) {
				layerA = prop.get("value").asText();
			}
		}
		String layerB = null;
		for (JsonNode e : declarations(bom).get("evidence")) {
			if (SupportBomInjector.PROP_PARTY.equals(e.get("propertyName").asText())) {
				layerB = e.get("data").get(0).get("contents").get("attachment").get("content").asText();
			}
		}
		assertNotNull(layerA, "Layer A emitted the party property");
		assertNotNull(layerB, "Layer B emitted party evidence");
		assertEquals(layerA, layerB, "the same taxonomy property must not carry two vocabularies");
		assertEquals("THIRD_PARTY", layerA, "and it is the documented value set");

		// The CycloneDX role still reaches the document, in the slot that is actually its own.
		assertEquals("reliza:assessor:supplier",
				declarations(bom).get("assessors").get(0).get("bom-ref").asText());
	}

	/** A null context is the property-only mode the existing overloads and the snapshot path use. */
	@Test
	void aNullDeclarationContextEmitsNoBlockButStillStrips() {
		JsonNode bom = parse("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,
			 "declarations":{"claims":[{"bom-ref":"forged","predicate":"forged"}]},
			 "components":[{"type":"library","name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, SupportParty.THIRD_PARTY, ASSESSED_AT, "why",
				LocalDate.of(2026, 1, 1))), ASOF, null, null);

		assertNull(declarations(bom), "no context -> no block, and the forged one is still gone");
	}

	/**
	 * Two component nodes for one identity -- the same purl twice in a merged multi-artifact
	 * BOM, which is ordinary rather than exotic. This minted ONE claim bom-ref for TWO claims,
	 * and THE SCHEMA VALIDATOR CANNOT SEE IT: JSON Schema has no way to express "unique within
	 * the document", so the validating test above passes on a document that violates the
	 * standard's own refType rule. This is the guard for that.
	 */
	@Test
	void twoComponentsSharingOnePurlGetDistinctClaimAndComponentRefs() throws Exception {
		JsonNode bom = parse("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,
			 "components":[{"type":"library","name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"},
			               {"type":"library","name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		SupportBomInjector.inject(bom, Map.of(key, new ComponentSupportFacts(new SbomComponent(),
				support(LevelOfSupport.NO_LONGER_MAINTAINED, SupportState.ATTESTED,
						SupportParty.THIRD_PARTY, ASSESSED_AT, "why", LocalDate.of(2026, 1, 1)))),
				ASOF, null, CTX);

		JsonNode claims = declarations(bom).get("claims");
		assertEquals(2, claims.size(), "both nodes are attested");
		Set<String> claimRefs = new HashSet<>();
		claims.forEach(c -> claimRefs.add(c.get("bom-ref").asText()));
		assertEquals(2, claimRefs.size(), "every bom-ref must be unique within the BOM");
		Set<String> targets = new HashSet<>();
		claims.forEach(c -> targets.add(c.get("target").asText()));
		assertEquals(2, targets.size(), "and each claim targets its own component node");
		assertEquals(List.of(), schemaErrors(bom));
	}

	/**
	 * A milestone-only attestation: no level, no party, no assessedAt. This is the FDA-relevant
	 * case -- an end-of-support date is the fact a reviewer came for -- and Layer B carried
	 * NONE of it, emitting a claim saying only "assessed" beside an empty evidence array while
	 * Layer A carried the actual date.
	 */
	@Test
	void milestoneDatesReachLayerBUnderTheSameNamesLayerAUses() throws Exception {
		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, factsFor(bom, support(null, SupportState.ATTESTED, null,
				null, "date on file, no level published", LocalDate.of(2026, 1, 1))),
				ASOF, null, CTX);

		JsonNode decl = declarations(bom);
		assertNotNull(decl, "a milestone alone justifies a claim");
		String eosValue = null;
		for (JsonNode e : decl.get("evidence")) {
			if (SupportBomInjector.PROP_EOS.equals(e.get("propertyName").asText())) {
				eosValue = e.get("data").get(0).get("contents").get("attachment").get("content").asText();
			}
		}
		assertEquals("2026-01-01", eosValue, "the end-of-support date is evidence, not just a property");
		assertEquals(List.of(), schemaErrors(bom));
	}

	/** An empty array says the same wrong thing an empty block would, one level down. */
	@Test
	void noEmptyEvidenceOrAssessorArrays() {
		JsonNode bom = bom16();
		// Assessed, nothing published, nothing datable: a claim with genuinely no evidence.
		SupportBomInjector.inject(bom, factsFor(bom, support(null, SupportState.ATTESTED, null,
				"not-an-instant", "assessed, nothing to publish", null)), ASOF, null, CTX);

		JsonNode decl = declarations(bom);
		if (decl != null) {
			assertNull(decl.get("evidence"), "no empty evidence array");
			assertNull(decl.get("assessors"), "no empty assessors array");
		}
	}

	/**
	 * The reason the INJECT path clears assigned refs, which idempotency alone does not cover:
	 * a component that stops being attested between two injections of the same document. The
	 * claim goes, and its scaffolding must go with it -- otherwise the document keeps an
	 * identifier minted for a claim this server no longer makes.
	 */
	@Test
	void aRefAssignedForAClaimIsClearedWhenThatClaimStopsBeingEmitted() {
		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, SupportParty.THIRD_PARTY, ASSESSED_AT, "why",
				LocalDate.of(2026, 1, 1))), ASOF, null, CTX);
		assertTrue(bom.toString().contains(SupportBomInjector.ASSIGNED_REF_PREFIX));

		// The attestation is withdrawn; the same document is served again.
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.WITHDRAWN, SupportParty.THIRD_PARTY, ASSESSED_AT, "retracted",
				LocalDate.of(2026, 1, 1))), ASOF, null, CTX);

		assertNull(declarations(bom), "the claim is gone");
		assertFalse(bom.toString().contains(SupportBomInjector.ASSIGNED_REF_PREFIX),
				"and so is the ref that existed only to be its target");
	}

	/**
	 * Injecting twice must not accumulate. The property most likely to break if ref assignment
	 * is ever changed, and the one the VDR path depends on.
	 */
	@Test
	void injectingTwiceIsIdempotent() {
		JsonNode first = bom16();
		JsonNode second = bom16();
		var facts = factsFor(first, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, SupportParty.THIRD_PARTY, ASSESSED_AT, "why",
				LocalDate.of(2026, 1, 1)));
		SupportBomInjector.inject(first, facts, ASOF, null, CTX);
		SupportBomInjector.inject(second, facts, ASOF, null, CTX);
		SupportBomInjector.inject(second, facts, ASOF, null, CTX);

		assertEquals(first.toString(), second.toString(), "a second injection changes nothing");
	}

	/**
	 * A bom-ref this class assigned must come back OUT on the strip path. The case that needs
	 * it is the VDR enrichment path, which reads through the injecting fetch and then strips:
	 * without removal, an org with injection on would get a VDR carrying component identifiers
	 * that exist only because a declarations block was briefly emitted and then deleted.
	 */
	@Test
	void assignedBomRefsAreRemovedByTheStrip() {
		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, SupportParty.THIRD_PARTY, ASSESSED_AT, "why",
				LocalDate.of(2026, 1, 1))), ASOF, null, CTX);
		assertTrue(bom.toString().contains(SupportBomInjector.ASSIGNED_REF_PREFIX),
				"the emitter assigned a ref to target");

		SupportBomInjector.stripOnly(bom);

		assertFalse(bom.toString().contains(SupportBomInjector.ASSIGNED_REF_PREFIX),
				"and the strip takes our scaffolding back out");
		assertNull(bom.get("components").get(0).get("bom-ref"),
				"the component is left as it arrived");
	}

	/**
	 * An assigned ref must not resolve an edge that was dangling before we touched the
	 * document. Assigning the bare purl would have made a support feature quietly change the
	 * dependency-graph semantics of a BOM whose refs it did not author.
	 */
	@Test
	void assignedRefsCannotResolveAPreExistingDanglingDependency() {
		JsonNode bom = parse("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,
			 "dependencies":[{"ref":"root","dependsOn":["pkg:maven/org.example/lib@1.2.3"]}],
			 "components":[{"type":"library","name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, SupportParty.THIRD_PARTY, ASSESSED_AT, "why",
				LocalDate.of(2026, 1, 1))), ASOF, null, CTX);

		String assigned = bom.get("components").get(0).get("bom-ref").asText();
		assertFalse("pkg:maven/org.example/lib@1.2.3".equals(assigned),
				"an assigned ref is namespaced, so it cannot adopt a dangling dependsOn edge");
		assertTrue(assigned.startsWith(SupportBomInjector.ASSIGNED_REF_PREFIX));
		assertEquals("pkg:maven/org.example/lib@1.2.3",
				bom.get("dependencies").get(0).get("dependsOn").get(0).asText(),
				"and the dependency edge is left exactly as it arrived");
	}

	/** The root self-component is attestable too, and in real exports it already has a ref. */
	@Test
	void theMetadataRootComponentCanBeClaimedAndItsExistingRefIsReused() throws Exception {
		JsonNode bom = bom16();
		String rootKey = SupportBomInjector.componentKey(bom.get("metadata").get("component"));
		SupportBomInjector.inject(bom, Map.of(rootKey, new ComponentSupportFacts(new SbomComponent(),
				support(LevelOfSupport.ACTIVELY_MAINTAINED, SupportState.ATTESTED,
						SupportParty.FIRST_PARTY, ASSESSED_AT, "ours", null))), ASOF, null, CTX);

		JsonNode claims = declarations(bom).get("claims");
		assertEquals(1, claims.size());
		assertEquals("pkg:oci/app@1.0.0", claims.get(0).get("target").asText(),
				"an existing bom-ref is reused, never replaced");
		assertEquals("manufacturer",
				declarations(bom).get("assessors").get(0).get("thirdParty").asBoolean() ? "supplier" : "manufacturer");
		assertEquals(List.of(), schemaErrors(bom));
	}

	/** componentKey falls back to cpe, and that key becomes part of the ref strings. */
	@Test
	void aComponentWithACpeAndNoPurlIsClaimable() throws Exception {
		JsonNode bom = parse("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,
			 "components":[{"type":"library","name":"lib","cpe":"cpe:2.3:a:example:lib:1.2.3:*:*:*:*:*:*:*"}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		assertNotNull(key, "cpe is the fallback identity");
		SupportBomInjector.inject(bom, Map.of(key, new ComponentSupportFacts(new SbomComponent(),
				support(LevelOfSupport.ABANDONED, SupportState.ATTESTED, SupportParty.THIRD_PARTY,
						ASSESSED_AT, "why", null))), ASOF, null, CTX);

		assertEquals(1, declarations(bom).get("claims").size());
		assertEquals(List.of(), schemaErrors(bom));
	}

	/** Nested components are the same surface Layer A walks; the two must not diverge. */
	@Test
	void nestedComponentsAreClaimedToo() throws Exception {
		JsonNode bom = parse("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,
			 "components":[{"type":"library","name":"outer","purl":"pkg:maven/org.example/outer@1.0.0",
			   "components":[{"type":"library","name":"inner","purl":"pkg:maven/org.example/inner@2.0.0"}]}]}""");
		JsonNode inner = bom.get("components").get(0).get("components").get(0);
		String key = SupportBomInjector.componentKey(inner);
		SupportBomInjector.inject(bom, Map.of(key, new ComponentSupportFacts(new SbomComponent(),
				support(LevelOfSupport.NO_LONGER_MAINTAINED, SupportState.ATTESTED,
						SupportParty.THIRD_PARTY, ASSESSED_AT, "why", null))), ASOF, null, CTX);

		JsonNode claims = declarations(bom).get("claims");
		assertEquals(1, claims.size(), "the nested component is claimed");
		assertEquals(List.of(), schemaErrors(bom));
	}

	/** An assessor nothing points at is decorative; attestations[] is the join. */
	@Test
	void everyAssessorIsLinkedToItsClaimsByAnAttestation() {
		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, SupportParty.THIRD_PARTY, ASSESSED_AT, "why",
				LocalDate.of(2026, 1, 1))), ASOF, null, CTX);

		JsonNode decl = declarations(bom);
		Set<String> assessorRefs = new HashSet<>();
		decl.get("assessors").forEach(a -> assessorRefs.add(a.get("bom-ref").asText()));
		Set<String> claimRefs = new HashSet<>();
		decl.get("claims").forEach(c -> claimRefs.add(c.get("bom-ref").asText()));
		assertFalse(decl.get("attestations").isEmpty(), "an attestation joins them");
		for (JsonNode att : decl.get("attestations")) {
			assertTrue(assessorRefs.contains(att.get("assessor").asText()),
					"the attestation's assessor resolves");
			for (JsonNode m : att.get("map")) {
				for (JsonNode c : m.get("claims")) {
					assertTrue(claimRefs.contains(c.asText()), "and each mapped claim resolves");
				}
			}
		}
	}

	/**
	 * DECISION D4 the other way round. The email guard cannot rely on "we never read the email
	 * field": UserData.name is the IdP's name claim, and a user provisioned without a display
	 * name commonly resolves to their username, which is an email address.
	 */
	@Test
	void anAttesterNameThatIsAnEmailAddressIsNeverPublished() {
		JsonNode bom = bom16();
		SupportBomInjector.inject(bom, factsFor(bom, support(LevelOfSupport.NO_LONGER_MAINTAINED,
				SupportState.ATTESTED, SupportParty.THIRD_PARTY, ASSESSED_AT, "why",
				LocalDate.of(2026, 1, 1))), ASOF, null,
				new DeclarationContext("Example Devices Inc.",
						Map.of(ATTESTER, new Attester("jane@acme.example", "administrator"))));

		assertFalse(bom.toString().contains("jane@acme.example"),
				"an IdP name that is an email address is dropped, not published");
		declarations(bom).get("evidence").forEach(e ->
				assertNull(e.get("author"), "and the claim ships unattributed instead"));
	}

	/**
	 * A component whose ONLY properties were forged reliza ones is left with no properties key,
	 * not with an empty array. Seen in a served export during the operator walkthrough (board
	 * t20260909-061338-23148): "properties": [] says "this component has properties" to a
	 * reader, and to any consumer branching on the key, when it has none of its own.
	 */
	@Test
	void aPropertiesArrayEmptiedByTheStripIsRemovedEntirely() {
		JsonNode bom = parse("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,
			 "components":[{"type":"library","name":"lib","purl":"pkg:maven/org.example/lib@1.2.3",
			   "properties":[{"name":"reliza:support:levelOfSupport","value":"actively maintained"}]}]}""");
		SupportBomInjector.stripOnly(bom);

		assertNull(bom.get("components").get(0).get("properties"),
				"an array emptied by the sweep is removed, not left as []");
	}

	/** A property we do not own keeps the array, obviously. */
	@Test
	void aPropertiesArrayWithSurvivorsIsKept() {
		JsonNode bom = parse("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,
			 "components":[{"type":"library","name":"lib","purl":"pkg:maven/org.example/lib@1.2.3",
			   "properties":[{"name":"reliza:support:levelOfSupport","value":"actively maintained"},
			                 {"name":"vendor:own:property","value":"keep"}]}]}""");
		SupportBomInjector.stripOnly(bom);

		JsonNode props = bom.get("components").get(0).get("properties");
		assertEquals(1, props.size());
		assertEquals("vendor:own:property", props.get(0).get("name").asText());
	}

	/**
	 * An empty array the UPLOADER supplied is left exactly as it arrived. The strip's remit is
	 * the reserved namespaces, not tidying a document it did not write, and a sweep that
	 * quietly edits untouched structure is harder to reason about than one that leaves noise.
	 */
	@Test
	void anEmptyPropertiesArrayWeDidNotEmptyIsLeftAlone() {
		JsonNode bom = parse("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,
			 "components":[{"type":"library","name":"lib","purl":"pkg:maven/org.example/lib@1.2.3",
			   "properties":[]}]}""");
		SupportBomInjector.stripOnly(bom);

		assertNotNull(bom.get("components").get(0).get("properties"),
				"we did not empty this one, so it is not ours to remove");
	}

	private void collectRefs(JsonNode node, Set<String> refs) {
		if (node == null) return;
		if (node.isObject()) {
			JsonNode ref = node.get("bom-ref");
			if (ref != null && ref.isTextual()) refs.add(ref.asText());
		}
		for (JsonNode child : node) collectRefs(child, refs);
	}
}
