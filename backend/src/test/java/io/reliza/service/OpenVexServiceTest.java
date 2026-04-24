/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cyclonedx.model.Bom;
import org.cyclonedx.model.vulnerability.Vulnerability;
import org.junit.jupiter.api.Test;

import io.reliza.model.VdrSnapshotType;

/**
 * Pure-mapping tests for {@link OpenVexService}. Does not require a Spring context — verifies the
 * static transform contract: status mapping, justification mapping, @id determinism, distinctness
 * from CycloneDX VEX serials, and output document shape.
 */
public class OpenVexServiceTest {

	private static final UUID FIXED_RELEASE = UUID.fromString("00000000-0000-0000-0000-000000000042");
	private static final ZonedDateTime FIXED_CUTOFF = ZonedDateTime.parse("2026-01-01T00:00:00Z");

	// ---- Status mapping (plan-pinned contract) ----

	@Test
	void status_exploitable_maps_to_affected() {
		assertEquals("affected", OpenVexService.toOpenVexStatus(Vulnerability.Analysis.State.EXPLOITABLE));
	}

	@Test
	void status_notAffected_maps_to_not_affected() {
		assertEquals("not_affected", OpenVexService.toOpenVexStatus(Vulnerability.Analysis.State.NOT_AFFECTED));
	}

	@Test
	void status_falsePositive_collapses_to_not_affected() {
		// CISA guidance: no direct OpenVEX equivalent for FALSE_POSITIVE; map to not_affected
		// (justification is stamped separately in buildStatement).
		assertEquals("not_affected", OpenVexService.toOpenVexStatus(Vulnerability.Analysis.State.FALSE_POSITIVE));
	}

	@Test
	void status_resolved_maps_to_fixed() {
		assertEquals("fixed", OpenVexService.toOpenVexStatus(Vulnerability.Analysis.State.RESOLVED));
	}

	@Test
	void status_inTriage_maps_to_under_investigation() {
		assertEquals("under_investigation", OpenVexService.toOpenVexStatus(Vulnerability.Analysis.State.IN_TRIAGE));
	}

	// ---- Justification mapping ----

	@Test
	void justification_codeNotPresent_maps_to_vulnerable_code_not_present() {
		assertEquals("vulnerable_code_not_present",
				OpenVexService.toOpenVexJustification(Vulnerability.Analysis.Justification.CODE_NOT_PRESENT));
	}

	@Test
	void justification_codeNotReachable_maps_to_vulnerable_code_not_in_execute_path() {
		assertEquals("vulnerable_code_not_in_execute_path",
				OpenVexService.toOpenVexJustification(Vulnerability.Analysis.Justification.CODE_NOT_REACHABLE));
	}

	@Test
	void justification_requiresDependency_maps_to_component_not_present() {
		assertEquals("component_not_present",
				OpenVexService.toOpenVexJustification(Vulnerability.Analysis.Justification.REQUIRES_DEPENDENCY));
	}

	@Test
	void justification_protectedByMitigatingControl_maps_to_inline_mitigations_already_exist() {
		assertEquals("inline_mitigations_already_exist",
				OpenVexService.toOpenVexJustification(Vulnerability.Analysis.Justification.PROTECTED_BY_MITIGATING_CONTROL));
	}

	@Test
	void justification_null_returns_null() {
		assertNull(OpenVexService.toOpenVexJustification(null));
	}

	// ---- Document @id (deterministic seed) ----

	@Test
	void docId_is_deterministic_for_same_inputs() {
		String a = OpenVexService.buildOpenVexDocId(FIXED_RELEASE, null, null, null, Boolean.FALSE, Boolean.FALSE);
		String b = OpenVexService.buildOpenVexDocId(FIXED_RELEASE, null, null, null, Boolean.FALSE, Boolean.FALSE);
		assertEquals(a, b);
	}

	@Test
	void docId_matches_urn_uuid_format() {
		String id = OpenVexService.buildOpenVexDocId(FIXED_RELEASE, null, null, null, null, null);
		assertTrue(id.matches("^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"),
				"OpenVEX @id must be a valid urn:uuid, was " + id);
	}

	@Test
	void docId_differs_by_release() {
		String a = OpenVexService.buildOpenVexDocId(FIXED_RELEASE, null, null, null, null, null);
		String b = OpenVexService.buildOpenVexDocId(UUID.fromString("00000000-0000-0000-0000-000000000099"),
				null, null, null, null, null);
		assertNotEquals(a, b);
	}

	@Test
	void docId_differs_by_triage_flag() {
		String off = OpenVexService.buildOpenVexDocId(FIXED_RELEASE, null, null, null, Boolean.FALSE, Boolean.FALSE);
		String on = OpenVexService.buildOpenVexDocId(FIXED_RELEASE, null, null, null, Boolean.FALSE, Boolean.TRUE);
		assertNotEquals(off, on);
	}

	@Test
	void docId_distinct_from_cdx_vex_and_vdr_for_same_snapshot() {
		// Three identities per release: VDR, CDX VEX, OpenVEX — must never collide.
		String vdr = ReleaseService.buildVdrSerialNumber(FIXED_RELEASE,
				VdrSnapshotType.LIFECYCLE, "production", FIXED_CUTOFF, Boolean.FALSE);
		String cdxVex = ReleaseService.buildCdxVexSerialNumber(FIXED_RELEASE,
				VdrSnapshotType.LIFECYCLE, "production", FIXED_CUTOFF, Boolean.FALSE, Boolean.FALSE);
		String openVex = OpenVexService.buildOpenVexDocId(FIXED_RELEASE,
				VdrSnapshotType.LIFECYCLE, "production", FIXED_CUTOFF, Boolean.FALSE, Boolean.FALSE);
		assertNotEquals(vdr, cdxVex);
		assertNotEquals(vdr, openVex);
		assertNotEquals(cdxVex, openVex);
	}

	// ---- Statement building ----

	@Test
	void statement_analysisLess_returns_null() {
		// Defensive: upstream transform should have stamped IN_TRIAGE. If it didn't, we skip
		// rather than emit an invalid OpenVEX statement.
		Vulnerability v = new Vulnerability();
		v.setId("CVE-2024-0001");
		Map<String, Object> stmt = OpenVexService.buildStatement(v, "urn:uuid:" + FIXED_RELEASE);
		assertNull(stmt);
	}

	@Test
	void statement_exploitable_without_derivation_inputs_falls_back_to_detail() {
		// Strict contract: affected forbids impact_statement; CDX detail is the last-resort
		// input for action_statement when no recommendation / responses / workaround exist.
		Vulnerability v = vulnWith("CVE-2024-0001", Vulnerability.Analysis.State.EXPLOITABLE, null, "needs patch");
		Map<String, Object> stmt = OpenVexService.buildStatement(v, "urn:uuid:" + FIXED_RELEASE);
		assertNotNull(stmt);
		assertEquals("affected", stmt.get("status"));
		assertFalse(stmt.containsKey("justification"), "affected must not carry justification");
		assertFalse(stmt.containsKey("impact_statement"), "affected must not carry impact_statement");
		assertEquals("needs patch.", stmt.get("action_statement"));
	}

	@Test
	void statement_affected_drops_cdx_justification_and_impact_statement() {
		// Strict contract: the go-vex reference validator rejects statements that place
		// justification or impact_statement on status=affected. Even when the paired CDX VEX
		// carries those fields, OpenVEX must omit them — the detail is re-routed into
		// action_statement via the deriveActionStatement fallback.
		Vulnerability v = vulnWith("CVE-2024-0001", Vulnerability.Analysis.State.EXPLOITABLE,
				Vulnerability.Analysis.Justification.CODE_NOT_PRESENT, "smoke test");
		Map<String, Object> stmt = OpenVexService.buildStatement(v, "urn:uuid:" + FIXED_RELEASE);
		assertEquals("affected", stmt.get("status"));
		assertFalse(stmt.containsKey("justification"), "affected forbids justification per OpenVEX 0.2.0");
		assertFalse(stmt.containsKey("impact_statement"), "affected forbids impact_statement per OpenVEX 0.2.0");
		assertEquals("smoke test.", stmt.get("action_statement"));
	}

	@Test
	void statement_notAffected_carries_mapped_justification() {
		Vulnerability v = vulnWith("CVE-2024-0002", Vulnerability.Analysis.State.NOT_AFFECTED,
				Vulnerability.Analysis.Justification.CODE_NOT_PRESENT, "unreachable");
		Map<String, Object> stmt = OpenVexService.buildStatement(v, "urn:uuid:" + FIXED_RELEASE);
		assertEquals("not_affected", stmt.get("status"));
		assertEquals("vulnerable_code_not_present", stmt.get("justification"));
		assertEquals("unreachable", stmt.get("impact_statement"));
	}

	@Test
	void statement_falsePositive_stamps_hardcoded_justification() {
		// FALSE_POSITIVE → not_affected + vulnerable_code_not_present regardless of what the
		// CDX Analysis.Justification field says (it's meaningless for this state in OpenVEX).
		Vulnerability v = vulnWith("CVE-2024-0003", Vulnerability.Analysis.State.FALSE_POSITIVE, null, null);
		Map<String, Object> stmt = OpenVexService.buildStatement(v, "urn:uuid:" + FIXED_RELEASE);
		assertEquals("not_affected", stmt.get("status"));
		assertEquals("vulnerable_code_not_present", stmt.get("justification"));
	}

	@Test
	void statement_resolved_omits_all_freetext_fields() {
		// Strict contract: fixed carries no justification, impact_statement, or action_statement.
		// Even if CDX had populated them, OpenVEX must drop them.
		Vulnerability v = vulnWith("CVE-2024-0004", Vulnerability.Analysis.State.RESOLVED,
				Vulnerability.Analysis.Justification.CODE_NOT_PRESENT, "was resolved");
		Map<String, Object> stmt = OpenVexService.buildStatement(v, "urn:uuid:" + FIXED_RELEASE);
		assertEquals("fixed", stmt.get("status"));
		assertFalse(stmt.containsKey("justification"));
		assertFalse(stmt.containsKey("impact_statement"));
		assertFalse(stmt.containsKey("action_statement"));
	}

	@Test
	void statement_inTriage_omits_all_freetext_fields() {
		// Strict contract: under_investigation carries no justification, impact_statement,
		// or action_statement.
		Vulnerability v = vulnWith("CVE-2024-0005", Vulnerability.Analysis.State.IN_TRIAGE,
				Vulnerability.Analysis.Justification.CODE_NOT_PRESENT, "still analysing");
		Map<String, Object> stmt = OpenVexService.buildStatement(v, "urn:uuid:" + FIXED_RELEASE);
		assertEquals("under_investigation", stmt.get("status"));
		assertFalse(stmt.containsKey("justification"));
		assertFalse(stmt.containsKey("impact_statement"));
		assertFalse(stmt.containsKey("action_statement"));
	}

	// ---- action_statement derivation (status=affected) ----

	@Test
	void actionStatement_from_recommendation_only() {
		Vulnerability v = affectedVuln("CVE-2024-0010", "Upgrade log4j-core to 2.24.1", null, null, null);
		Map<String, Object> stmt = OpenVexService.buildStatement(v, "urn:uuid:" + FIXED_RELEASE);
		assertEquals("affected", stmt.get("status"));
		assertEquals("Upgrade log4j-core to 2.24.1.", stmt.get("action_statement"));
	}

	@Test
	void actionStatement_from_responses_only() {
		Vulnerability v = affectedVuln("CVE-2024-0011", null, null,
				List.of(Vulnerability.Analysis.Response.UPDATE,
						Vulnerability.Analysis.Response.WORKAROUND_AVAILABLE),
				null);
		Map<String, Object> stmt = OpenVexService.buildStatement(v, "urn:uuid:" + FIXED_RELEASE);
		assertEquals("Update to a different revision or release. A workaround is available.",
				stmt.get("action_statement"));
	}

	@Test
	void actionStatement_from_workaround_only() {
		Vulnerability v = affectedVuln("CVE-2024-0012", null, "Disable JNDI lookups via system property", null, null);
		Map<String, Object> stmt = OpenVexService.buildStatement(v, "urn:uuid:" + FIXED_RELEASE);
		assertEquals("Workaround: Disable JNDI lookups via system property.", stmt.get("action_statement"));
	}

	@Test
	void actionStatement_composes_recommendation_responses_and_workaround() {
		Vulnerability v = affectedVuln("CVE-2024-0013", "Upgrade to 2.24.1", "Disable JNDI",
				List.of(Vulnerability.Analysis.Response.UPDATE), null);
		Map<String, Object> stmt = OpenVexService.buildStatement(v, "urn:uuid:" + FIXED_RELEASE);
		assertEquals(
				"Upgrade to 2.24.1. Update to a different revision or release. Workaround: Disable JNDI.",
				stmt.get("action_statement"));
	}

	@Test
	void actionStatement_falls_back_to_detail_when_no_action_inputs() {
		Vulnerability v = affectedVuln("CVE-2024-0014", null, null, null,
				"Exploitable on unsanitized user input.");
		Map<String, Object> stmt = OpenVexService.buildStatement(v, "urn:uuid:" + FIXED_RELEASE);
		assertEquals("Exploitable on unsanitized user input.", stmt.get("action_statement"));
	}

	@Test
	void actionStatement_uses_neutral_fallback_when_all_inputs_empty() {
		// Strict contract requires action_statement on affected; when CDX carries nothing
		// action-oriented, OpenVEX emits a neutral literal rather than omitting the field.
		Vulnerability v = affectedVuln("CVE-2024-0015", null, null, null, null);
		Map<String, Object> stmt = OpenVexService.buildStatement(v, "urn:uuid:" + FIXED_RELEASE);
		assertEquals("Contact vendor for remediation guidance.", stmt.get("action_statement"));
	}

	@Test
	void actionStatement_responseProse_covers_all_enum_values() {
		// Guardrail: if the CDX library adds a new Response enum value, this test breaks and
		// forces us to extend responseProse rather than silently dropping the new value.
		for (Vulnerability.Analysis.Response r : Vulnerability.Analysis.Response.values()) {
			assertNotNull(OpenVexService.responseProse(r),
					"responseProse returned null for " + r + " — extend the switch");
		}
	}

	// ---- CISA / OpenVEX invariants across a mixed document ----

	@Test
	void document_satisfies_cisa_and_openvex_per_status_invariants() {
		// Sweep: for every emitted statement, assert the Strict contract holds.
		Bom bom = new Bom();
		bom.setVulnerabilities(new ArrayList<>(List.of(
				affectedVuln("CVE-AFF", "Upgrade", null, null, null),
				vulnWith("CVE-NA", Vulnerability.Analysis.State.NOT_AFFECTED,
						Vulnerability.Analysis.Justification.CODE_NOT_REACHABLE, "not invoked"),
				vulnWith("CVE-FP", Vulnerability.Analysis.State.FALSE_POSITIVE, null, null),
				vulnWith("CVE-FIX", Vulnerability.Analysis.State.RESOLVED, null, null),
				vulnWith("CVE-IT", Vulnerability.Analysis.State.IN_TRIAGE, null, null))));
		Map<String, Object> doc = OpenVexService.buildOpenVexDocument(bom, FIXED_RELEASE, FIXED_CUTOFF,
				null, null, Boolean.TRUE, Boolean.TRUE);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> statements = (List<Map<String, Object>>) doc.get("statements");
		assertEquals(5, statements.size());
		for (Map<String, Object> s : statements) {
			String status = (String) s.get("status");
			switch (status) {
				case "not_affected" -> {
					assertTrue(s.containsKey("justification") || s.containsKey("impact_statement"),
							"not_affected MUST carry justification or impact_statement (CISA)");
					assertFalse(s.containsKey("action_statement"),
							"not_affected MUST NOT carry action_statement (OpenVEX strict)");
				}
				case "affected" -> {
					assertTrue(s.containsKey("action_statement"),
							"affected MUST carry action_statement (CISA + OpenVEX strict)");
					assertFalse(s.containsKey("justification"),
							"affected MUST NOT carry justification (OpenVEX strict)");
					assertFalse(s.containsKey("impact_statement"),
							"affected MUST NOT carry impact_statement (OpenVEX strict)");
				}
				case "fixed", "under_investigation" -> {
					assertFalse(s.containsKey("justification"),
							status + " MUST NOT carry justification (OpenVEX strict)");
					assertFalse(s.containsKey("impact_statement"),
							status + " MUST NOT carry impact_statement (OpenVEX strict)");
					assertFalse(s.containsKey("action_statement"),
							status + " MUST NOT carry action_statement (OpenVEX strict)");
				}
				default -> throw new AssertionError("unexpected status: " + status);
			}
		}
	}

	@Test
	void statement_carries_product_id() {
		Vulnerability v = vulnWith("CVE-2024-0006", Vulnerability.Analysis.State.EXPLOITABLE, null, null);
		String productId = "urn:uuid:" + FIXED_RELEASE;
		Map<String, Object> stmt = OpenVexService.buildStatement(v, productId);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> products = (List<Map<String, Object>>) stmt.get("products");
		assertNotNull(products);
		assertEquals(1, products.size());
		assertEquals(productId, products.get(0).get("@id"));
	}

	// ---- End-to-end document shape ----

	@Test
	void document_has_required_openvex_fields_in_spec_order() {
		Bom bom = new Bom();
		bom.setVulnerabilities(new ArrayList<>(List.of(
				vulnWith("CVE-A", Vulnerability.Analysis.State.EXPLOITABLE, null, null),
				vulnWith("CVE-B", Vulnerability.Analysis.State.NOT_AFFECTED,
						Vulnerability.Analysis.Justification.CODE_NOT_PRESENT, null))));

		Map<String, Object> doc = OpenVexService.buildOpenVexDocument(bom, FIXED_RELEASE, FIXED_CUTOFF,
				VdrSnapshotType.DATE, null, Boolean.FALSE, Boolean.FALSE);

		// Required top-level keys
		assertEquals("https://openvex.dev/ns/v0.2.0", doc.get("@context"));
		assertNotNull(doc.get("@id"));
		assertEquals("Reliza ReARM", doc.get("author"));
		assertEquals("2026-01-01T00:00:00Z", doc.get("timestamp"));
		assertEquals(1, doc.get("version"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> statements = (List<Map<String, Object>>) doc.get("statements");
		assertEquals(2, statements.size());
		assertEquals("affected", statements.get(0).get("status"));
		assertEquals("not_affected", statements.get(1).get("status"));

		// Spec-ordered keys (LinkedHashMap): @context first, statements last
		List<String> keys = new ArrayList<>(doc.keySet());
		assertEquals("@context", keys.get(0));
		assertEquals("statements", keys.get(keys.size() - 1));
	}

	@Test
	void document_skips_analysisless_vulnerabilities() {
		// Defensive: upstream transform filters/stamps these, but if anything leaks through we
		// must not emit broken statements.
		Bom bom = new Bom();
		Vulnerability bare = new Vulnerability();
		bare.setId("CVE-BARE");
		bom.setVulnerabilities(new ArrayList<>(List.of(
				bare,
				vulnWith("CVE-OK", Vulnerability.Analysis.State.EXPLOITABLE, null, null))));

		Map<String, Object> doc = OpenVexService.buildOpenVexDocument(bom, FIXED_RELEASE, null,
				null, null, null, null);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> statements = (List<Map<String, Object>>) doc.get("statements");
		assertEquals(1, statements.size());
		assertEquals("CVE-OK", ((Map<?, ?>) statements.get(0).get("vulnerability")).get("name"));
	}

	@Test
	void document_empty_vulnerabilities_emits_empty_statements_array() {
		Bom bom = new Bom();
		bom.setVulnerabilities(null);
		Map<String, Object> doc = OpenVexService.buildOpenVexDocument(bom, FIXED_RELEASE, null,
				null, null, null, null);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> statements = (List<Map<String, Object>>) doc.get("statements");
		assertNotNull(statements, "statements[] must be present even when empty (OpenVEX requires the key)");
		assertTrue(statements.isEmpty());
	}

	// ---- Helpers ----

	private static Vulnerability vulnWith(String id, Vulnerability.Analysis.State state,
			Vulnerability.Analysis.Justification justification, String detail) {
		Vulnerability v = new Vulnerability();
		v.setId(id);
		Vulnerability.Analysis analysis = new Vulnerability.Analysis();
		analysis.setState(state);
		if (justification != null) {
			analysis.setJustification(justification);
		}
		if (detail != null) {
			analysis.setDetail(detail);
		}
		v.setAnalysis(analysis);
		return v;
	}

	private static Vulnerability affectedVuln(String id, String recommendation, String workaround,
			List<Vulnerability.Analysis.Response> responses, String detail) {
		Vulnerability v = new Vulnerability();
		v.setId(id);
		if (recommendation != null) {
			v.setRecommendation(recommendation);
		}
		if (workaround != null) {
			v.setWorkaround(workaround);
		}
		Vulnerability.Analysis analysis = new Vulnerability.Analysis();
		analysis.setState(Vulnerability.Analysis.State.EXPLOITABLE);
		if (detail != null) {
			analysis.setDetail(detail);
		}
		if (responses != null) {
			analysis.setResponses(responses);
		}
		v.setAnalysis(analysis);
		return v;
	}
}
