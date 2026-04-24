/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cyclonedx.model.Bom;
import org.cyclonedx.model.vulnerability.Vulnerability;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import io.reliza.model.VdrSnapshotType;

/**
 * Structural guardrail that OpenVEX documents emitted by
 * {@link OpenVexService#buildOpenVexDocument} validate against the bundled OpenVEX 0.2.0 JSON
 * schema at {@code src/test/resources/schema/openvex-0.2.0.schema.json}.
 *
 * <p>Covers each distinct document path the producer actually emits: minimal (live / no snapshot),
 * lifecycle snapshot, date snapshot, every status combination, and mixed statement lists. The
 * negative case proves the validator isn't silently passing everything.
 */
public class OpenVexServiceSchemaTest {

	private static final UUID FIXED_RELEASE = UUID.fromString("00000000-0000-0000-0000-000000000042");
	private static final ZonedDateTime FIXED_CUTOFF = ZonedDateTime.parse("2026-01-01T00:00:00Z");
	private static final String SCHEMA_PATH = "/schema/openvex-0.2.0.schema.json";

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final JsonSchema SCHEMA = loadSchema();

	private static JsonSchema loadSchema() {
		JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
		try (InputStream in = OpenVexServiceSchemaTest.class.getResourceAsStream(SCHEMA_PATH)) {
			if (in == null) {
				throw new IllegalStateException("OpenVEX schema fixture not found at " + SCHEMA_PATH);
			}
			return factory.getSchema(in);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to load OpenVEX schema fixture", e);
		}
	}

	private static void assertSchemaValid(Map<String, Object> doc) throws Exception {
		String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
		JsonNode node = MAPPER.readTree(json);
		Set<ValidationMessage> errors = SCHEMA.validate(node);
		assertTrue(errors.isEmpty(),
				() -> "OpenVEX 0.2.0 schema validation failed:\n" + errors + "\n---\n" + json);
	}

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

	// ---- Positive fixtures ----

	@Test
	void minimalLiveDocument_isSchemaValid() throws Exception {
		Bom bom = new Bom();
		bom.setVulnerabilities(new ArrayList<>());
		Map<String, Object> doc = OpenVexService.buildOpenVexDocument(
				bom, FIXED_RELEASE, FIXED_CUTOFF, null, null, Boolean.FALSE, Boolean.FALSE);
		assertSchemaValid(doc);
	}

	@Test
	void lifecycleSnapshotDocument_isSchemaValid() throws Exception {
		Bom bom = new Bom();
		bom.setVulnerabilities(new ArrayList<>(List.of(
				vulnWith("CVE-2024-0001", Vulnerability.Analysis.State.EXPLOITABLE, null, "upstream patch pending"))));
		Map<String, Object> doc = OpenVexService.buildOpenVexDocument(
				bom, FIXED_RELEASE, FIXED_CUTOFF, VdrSnapshotType.LIFECYCLE, "production",
				Boolean.FALSE, Boolean.FALSE);
		assertSchemaValid(doc);
	}

	@Test
	void dateSnapshotDocument_isSchemaValid() throws Exception {
		Bom bom = new Bom();
		bom.setVulnerabilities(new ArrayList<>(List.of(
				vulnWith("CVE-2024-0002", Vulnerability.Analysis.State.RESOLVED, null, null))));
		Map<String, Object> doc = OpenVexService.buildOpenVexDocument(
				bom, FIXED_RELEASE, FIXED_CUTOFF, VdrSnapshotType.DATE, null, Boolean.FALSE, Boolean.FALSE);
		assertSchemaValid(doc);
	}

	@Test
	void notAffectedStatement_withMappedJustification_isSchemaValid() throws Exception {
		Bom bom = new Bom();
		bom.setVulnerabilities(new ArrayList<>(List.of(
				vulnWith("CVE-2024-0003", Vulnerability.Analysis.State.NOT_AFFECTED,
						Vulnerability.Analysis.Justification.CODE_NOT_REACHABLE,
						"Vulnerable method never invoked."))));
		Map<String, Object> doc = OpenVexService.buildOpenVexDocument(
				bom, FIXED_RELEASE, FIXED_CUTOFF, null, null, Boolean.FALSE, Boolean.FALSE);
		assertSchemaValid(doc);
	}

	@Test
	void falsePositiveStatement_withStampedJustification_isSchemaValid() throws Exception {
		// Confirms the hardcoded vulnerable_code_not_present stamping passes schema validation.
		Bom bom = new Bom();
		bom.setVulnerabilities(new ArrayList<>(List.of(
				vulnWith("CVE-2024-0004", Vulnerability.Analysis.State.FALSE_POSITIVE, null,
						"Scanner matched on unrelated namespace."))));
		Map<String, Object> doc = OpenVexService.buildOpenVexDocument(
				bom, FIXED_RELEASE, FIXED_CUTOFF, null, null, Boolean.FALSE, Boolean.FALSE);
		assertSchemaValid(doc);
	}

	@Test
	void inTriageStatement_isSchemaValid() throws Exception {
		Bom bom = new Bom();
		bom.setVulnerabilities(new ArrayList<>(List.of(
				vulnWith("CVE-2024-0005", Vulnerability.Analysis.State.IN_TRIAGE, null, null))));
		Map<String, Object> doc = OpenVexService.buildOpenVexDocument(
				bom, FIXED_RELEASE, FIXED_CUTOFF, null, null, Boolean.FALSE, Boolean.TRUE);
		assertSchemaValid(doc);
	}

	@Test
	void mixedStatementsDocument_isSchemaValid() throws Exception {
		// Covers all five status outputs in a single document to exercise the array branch.
		Bom bom = new Bom();
		bom.setVulnerabilities(new ArrayList<>(List.of(
				vulnWith("CVE-A", Vulnerability.Analysis.State.EXPLOITABLE, null, null),
				vulnWith("CVE-B", Vulnerability.Analysis.State.NOT_AFFECTED,
						Vulnerability.Analysis.Justification.PROTECTED_BY_MITIGATING_CONTROL, null),
				vulnWith("CVE-C", Vulnerability.Analysis.State.FALSE_POSITIVE, null, null),
				vulnWith("CVE-D", Vulnerability.Analysis.State.RESOLVED, null, null),
				vulnWith("CVE-E", Vulnerability.Analysis.State.IN_TRIAGE, null, null))));
		Map<String, Object> doc = OpenVexService.buildOpenVexDocument(
				bom, FIXED_RELEASE, FIXED_CUTOFF, VdrSnapshotType.LIFECYCLE, "production",
				Boolean.TRUE, Boolean.TRUE);
		assertSchemaValid(doc);
	}

	// ---- Negative fixture: prove the validator is real ----

	@Test
	void malformedDocument_validatorReportsErrors() throws Exception {
		// Tamper with a valid document to carry an invalid status value. The schema pins status
		// to the 4-value OpenVEX enum, so validation MUST reject this.
		Bom bom = new Bom();
		bom.setVulnerabilities(new ArrayList<>(List.of(
				vulnWith("CVE-X", Vulnerability.Analysis.State.EXPLOITABLE, null, null))));
		Map<String, Object> doc = OpenVexService.buildOpenVexDocument(
				bom, FIXED_RELEASE, FIXED_CUTOFF, null, null, Boolean.FALSE, Boolean.FALSE);
		String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
		String broken = json.replaceFirst(
				"\"status\"\\s*:\\s*\"affected\"",
				"\"status\" : \"not-a-real-status\"");
		assertFalse(broken.equals(json), "sanity: status replacement did not apply");

		JsonNode node = MAPPER.readTree(broken);
		Set<ValidationMessage> errors = SCHEMA.validate(node);
		assertFalse(errors.isEmpty(),
				"Expected schema errors for invalid status; got none (validator is a no-op)");
	}
}
