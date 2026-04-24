/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cyclonedx.model.Bom;
import org.cyclonedx.model.vulnerability.Vulnerability;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.VdrSnapshotType;

/**
 * Generates OpenVEX 0.2.0 documents for a release.
 *
 * <p>OpenVEX is a minimal, VEX-focused JSON format distinct from CycloneDX VEX. This service
 * reuses the enriched/filtered/stamped VEX {@link Bom} produced by
 * {@link ReleaseService#buildCdxVexBom} as its source of truth, then maps each
 * {@link Vulnerability} into an OpenVEX {@code statement} entry. This keeps snapshot semantics,
 * suppressed handling, and the explicit {@code IN_TRIAGE} stamping behavior in lock-step with
 * the CycloneDX VEX output.
 *
 * <p>Status mapping (plan-pinned):
 * <ul>
 *   <li>{@code EXPLOITABLE → affected} (carries {@code action_statement} derived from CDX
 *       {@code recommendation} / {@code analysis.responses[]} / {@code workaround})</li>
 *   <li>{@code NOT_AFFECTED → not_affected} (carries {@code justification} + {@code impact_statement})</li>
 *   <li>{@code FALSE_POSITIVE → not_affected} with {@code justification=vulnerable_code_not_present}</li>
 *   <li>{@code RESOLVED → fixed} (no free-text fields)</li>
 *   <li>{@code IN_TRIAGE → under_investigation} (no free-text fields)</li>
 * </ul>
 *
 * <p><strong>Strict per-status field contract.</strong> OpenVEX 0.2.0's reference validator
 * ({@code go-vex} / {@code vexctl}) rejects statements that place {@code justification} or
 * {@code impact_statement} on a status other than {@code not_affected}, or {@code action_statement}
 * on a status other than {@code affected}. This service therefore gates every free-text /
 * enum-justification field on status.
 *
 * <p>Products identify the release itself (one product per statement, no subcomponents) via
 * a stable {@code urn:uuid:<release>} identifier. This is a release-level VEX; consumers who
 * need component-level granularity should consume the paired CycloneDX VEX.
 */
@Service
public class OpenVexService {

	static final String OPENVEX_CONTEXT = "https://openvex.dev/ns/v0.2.0";
	static final String OPENVEX_AUTHOR = "Reliza ReARM";
	static final int OPENVEX_DOC_VERSION = 1;

	private final ReleaseService releaseService;
	private final ObjectMapper objectMapper;

	public OpenVexService(ReleaseService releaseService, ObjectMapper objectMapper) {
		this.releaseService = releaseService;
		this.objectMapper = objectMapper;
	}

	/**
	 * Generate an OpenVEX 0.2.0 document for a release. CE-compatible variant: supports DATE /
	 * LIFECYCLE snapshots. For SaaS approval snapshots use {@link #generateOpenVexWithSnapshot}.
	 */
	public String generateOpenVex(ReleaseData releaseData, Boolean includeSuppressed, Boolean includeInTriage,
			ZonedDateTime upToDate, ReleaseLifecycle targetLifecycle) throws Exception {
		ZonedDateTime cutOffDate = releaseService.computeLifecycleCutoffDate(releaseData, upToDate, targetLifecycle);
		VdrSnapshotType snapshotType = null;
		String snapshotValue = null;
		if (targetLifecycle != null) {
			snapshotType = VdrSnapshotType.LIFECYCLE;
			snapshotValue = targetLifecycle.name();
		} else if (upToDate != null) {
			snapshotType = VdrSnapshotType.DATE;
		}
		return generateOpenVexWithSnapshot(releaseData, includeSuppressed, includeInTriage,
				cutOffDate, snapshotType, snapshotValue);
	}

	/**
	 * Generate an OpenVEX document with pre-computed snapshot metadata. Used by the SaaS layer
	 * for approval snapshots; mirrors {@link ReleaseService#generateCdxVexWithSnapshot}.
	 */
	public String generateOpenVexWithSnapshot(ReleaseData releaseData, Boolean includeSuppressed, Boolean includeInTriage,
			ZonedDateTime cutOffDate, VdrSnapshotType snapshotType, String snapshotValue) throws Exception {
		Bom vexBom = releaseService.buildCdxVexBom(releaseData, includeSuppressed, includeInTriage,
				cutOffDate, snapshotType, snapshotValue);
		Map<String, Object> doc = buildOpenVexDocument(vexBom, releaseData.getUuid(), cutOffDate,
				snapshotType, snapshotValue, includeSuppressed, includeInTriage);
		return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
	}

	// ---- Pure mapping (no Spring / no I/O) — exposed package-private for unit tests. ----

	/**
	 * Build an OpenVEX document as an ordered {@link Map}. Pure function: takes an already-filtered
	 * CDX VEX {@link Bom} and emits the OpenVEX JSON structure. Key ordering follows the OpenVEX
	 * spec convention (@context, @id, author, timestamp, version, statements).
	 */
	static Map<String, Object> buildOpenVexDocument(Bom vexBom, UUID releaseUuid, ZonedDateTime cutOffDate,
			VdrSnapshotType snapshotType, String snapshotValue,
			Boolean includeSuppressed, Boolean includeInTriage) {
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("@context", OPENVEX_CONTEXT);
		doc.put("@id", buildOpenVexDocId(releaseUuid, snapshotType, snapshotValue, cutOffDate,
				includeSuppressed, includeInTriage));
		doc.put("author", OPENVEX_AUTHOR);
		doc.put("timestamp", (cutOffDate != null ? cutOffDate : ZonedDateTime.now()).toInstant().toString());
		doc.put("version", OPENVEX_DOC_VERSION);

		String productId = "urn:uuid:" + releaseUuid;
		List<Map<String, Object>> statements = new ArrayList<>();
		if (vexBom.getVulnerabilities() != null) {
			for (Vulnerability v : vexBom.getVulnerabilities()) {
				Map<String, Object> stmt = buildStatement(v, productId);
				if (stmt != null) {
					statements.add(stmt);
				}
			}
		}
		doc.put("statements", statements);
		return doc;
	}

	/**
	 * Map one CycloneDX VEX {@link Vulnerability} to an OpenVEX statement. Returns {@code null}
	 * for entries without an analysis state (defensive — upstream transform should have stamped
	 * them IN_TRIAGE, but we skip rather than emit an invalid OpenVEX statement).
	 */
	static Map<String, Object> buildStatement(Vulnerability v, String productId) {
		if (v.getAnalysis() == null || v.getAnalysis().getState() == null) {
			return null;
		}
		Map<String, Object> stmt = new LinkedHashMap<>();

		Map<String, Object> vuln = new LinkedHashMap<>();
		vuln.put("name", v.getId());
		stmt.put("vulnerability", vuln);

		Map<String, Object> product = new LinkedHashMap<>();
		product.put("@id", productId);
		stmt.put("products", List.of(product));

		Vulnerability.Analysis.State state = v.getAnalysis().getState();
		String status = toOpenVexStatus(state);
		stmt.put("status", status);

		// Strict per-status field contract. The go-vex / vexctl reference validator rejects
		// forbidden fields on the wrong status, so we gate emission explicitly:
		//   - not_affected: justification (9→5 map; stamp vulnerable_code_not_present for
		//     FALSE_POSITIVE absent) and/or impact_statement (from CDX detail).
		//   - affected: action_statement (derived from CDX recommendation / responses[] /
		//     workaround / detail / neutral fallback — MUST be present).
		//   - fixed / under_investigation: no free-text or enum-justification fields.
		if ("not_affected".equals(status)) {
			String justification = toOpenVexJustification(v.getAnalysis().getJustification());
			if (justification == null && state == Vulnerability.Analysis.State.FALSE_POSITIVE) {
				justification = "vulnerable_code_not_present";
			}
			if (justification != null) {
				stmt.put("justification", justification);
			}
			String detail = v.getAnalysis().getDetail();
			if (detail != null && !detail.isBlank()) {
				stmt.put("impact_statement", detail);
			}
		} else if ("affected".equals(status)) {
			stmt.put("action_statement", deriveActionStatement(v));
		}

		return stmt;
	}

	/**
	 * Derive the OpenVEX {@code action_statement} for an {@code affected} statement from CDX
	 * vulnerability fields. OpenVEX has a single free-text {@code action_statement}; CDX splits
	 * the same information across {@code recommendation}, {@code analysis.responses[]},
	 * {@code workaround}, and (as last resort) {@code analysis.detail}.
	 *
	 * <p>Derivation order: {@code recommendation} → prose-rendered {@code responses[]} →
	 * {@code "Workaround: " + workaround} → {@code analysis.detail} (only when nothing else
	 * is present; {@code detail} isn't action-oriented but OpenVEX forbids {@code impact_statement}
	 * under {@code affected}). If everything is empty, return a neutral literal so the statement
	 * satisfies OpenVEX / CISA's MUST-be-present requirement.
	 */
	static String deriveActionStatement(Vulnerability v) {
		List<String> parts = new ArrayList<>();
		String recommendation = v.getRecommendation();
		if (recommendation != null && !recommendation.isBlank()) {
			parts.add(recommendation.trim());
		}
		List<Vulnerability.Analysis.Response> responses = v.getAnalysis() != null
				? v.getAnalysis().getResponses() : null;
		if (responses != null) {
			for (Vulnerability.Analysis.Response r : responses) {
				String prose = responseProse(r);
				if (prose != null) {
					parts.add(prose);
				}
			}
		}
		String workaround = v.getWorkaround();
		if (workaround != null && !workaround.isBlank()) {
			parts.add("Workaround: " + workaround.trim());
		}
		if (parts.isEmpty()) {
			String detail = v.getAnalysis() != null ? v.getAnalysis().getDetail() : null;
			if (detail != null && !detail.isBlank()) {
				parts.add(detail.trim());
			}
		}
		if (parts.isEmpty()) {
			return "Contact vendor for remediation guidance.";
		}
		StringBuilder sb = new StringBuilder();
		for (String p : parts) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(p);
			if (!p.endsWith(".")) {
				sb.append('.');
			}
		}
		return sb.toString();
	}

	/**
	 * Render a CycloneDX {@link Vulnerability.Analysis.Response} enum to the prose phrasing
	 * used by the CDX 1.6 schema {@code meta:enum} descriptors. Composed into the OpenVEX
	 * {@code action_statement} via {@link #deriveActionStatement}.
	 */
	static String responseProse(Vulnerability.Analysis.Response r) {
		if (r == null) {
			return null;
		}
		return switch (r) {
			case CAN_NOT_FIX -> "Cannot be fixed";
			case WILL_NOT_FIX -> "Will not be fixed";
			case UPDATE -> "Update to a different revision or release";
			case ROLLBACK -> "Revert to a previous revision or release";
			case WORKAROUND_AVAILABLE -> "A workaround is available";
		};
	}

	/**
	 * Translate a CycloneDX {@link Vulnerability.Analysis.State} to an OpenVEX 0.2.0 status value.
	 * {@code RESOLVED_WITH_PEDIGREE} is normalised to {@code fixed} since OpenVEX has no pedigree concept.
	 */
	static String toOpenVexStatus(Vulnerability.Analysis.State state) {
		return switch (state) {
			case EXPLOITABLE -> "affected";
			case NOT_AFFECTED -> "not_affected";
			case FALSE_POSITIVE -> "not_affected";
			case RESOLVED -> "fixed";
			case RESOLVED_WITH_PEDIGREE -> "fixed";
			case IN_TRIAGE -> "under_investigation";
		};
	}

	/**
	 * Translate a CycloneDX {@link Vulnerability.Analysis.Justification} to an OpenVEX 0.2.0
	 * justification value. OpenVEX has 5 justification values; several CDX justifications map to
	 * the same OpenVEX value (e.g. all PROTECTED_* map to inline_mitigations_already_exist).
	 * Returns {@code null} if {@code j} is null.
	 */
	static String toOpenVexJustification(Vulnerability.Analysis.Justification j) {
		if (j == null) {
			return null;
		}
		return switch (j) {
			case CODE_NOT_PRESENT -> "vulnerable_code_not_present";
			case CODE_NOT_REACHABLE -> "vulnerable_code_not_in_execute_path";
			case REQUIRES_CONFIGURATION -> "vulnerable_code_cannot_be_controlled_by_adversary";
			case REQUIRES_DEPENDENCY -> "component_not_present";
			case REQUIRES_ENVIRONMENT -> "vulnerable_code_cannot_be_controlled_by_adversary";
			case PROTECTED_BY_COMPILER -> "inline_mitigations_already_exist";
			case PROTECTED_AT_RUNTIME -> "inline_mitigations_already_exist";
			case PROTECTED_AT_PERIMETER -> "inline_mitigations_already_exist";
			case PROTECTED_BY_MITIGATING_CONTROL -> "inline_mitigations_already_exist";
		};
	}

	/**
	 * Deterministic {@code @id} generator for OpenVEX documents. Mirrors
	 * {@link ReleaseService#buildCdxVexSerialNumber} but uses an {@code "OpenVEX|"} seed prefix so
	 * the same release's CycloneDX VEX and OpenVEX documents receive distinct {@code urn:uuid}
	 * identifiers (three identities per release: VDR, CDX VEX, OpenVEX).
	 */
	static String buildOpenVexDocId(UUID releaseUuid, VdrSnapshotType snapshotType, String snapshotValue,
			ZonedDateTime cutOffDate, Boolean includeSuppressed, Boolean includeInTriage) {
		String seed = String.join("|",
				"OpenVEX",
				String.valueOf(releaseUuid),
				snapshotType != null ? snapshotType.name() : "LIVE",
				snapshotValue != null ? snapshotValue : "",
				cutOffDate != null ? cutOffDate.toInstant().toString() : "",
				Boolean.TRUE.equals(includeSuppressed) ? "withSuppressed" : "noSuppressed",
				Boolean.TRUE.equals(includeInTriage) ? "withTriage" : "noTriage");
		UUID id = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
		return "urn:uuid:" + id;
	}
}
