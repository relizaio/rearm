/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisResponse;
import io.reliza.model.AnalysisState;
import io.reliza.model.VulnAnalysis;
import io.reliza.model.VulnAnalysisData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.VulnAnalysisRepository;

/**
 * Unit tests for the CISA VEX validation rules in {@link VulnAnalysisService}.
 *
 * Rules tested:
 *   - NOT_AFFECTED requires either a justification OR a non-blank details/impact statement.
 *   - EXPLOITABLE requires at least one response OR a non-blank recommendation.
 *   - Other states (IN_TRIAGE, FALSE_POSITIVE, FIXED) have no additional CISA requirement.
 *
 * Also round-trips VulnAnalysisData with the new responses/recommendation/workaround
 * fields through the {@code addAnalysisHistoryEntry} and history-getter path.
 */
@ExtendWith(MockitoExtension.class)
public class VulnAnalysisServiceCisaValidationTest {

	@Mock
	private VulnAnalysisRepository vulnAnalysisRepository;

	@Mock
	private AuditService auditService;

	@Mock
	private VulnAnalysisUpdateService vulnAnalysisUpdateService;

	@InjectMocks
	private VulnAnalysisService service;

	private Method validateCisaConstraints;
	private Method selectHigherPriorityAnalysisState;

	@BeforeEach
	void setUp() throws NoSuchMethodException {
		selectHigherPriorityAnalysisState = VulnAnalysisService.class.getDeclaredMethod(
				"selectHigherPriorityAnalysisState", AnalysisState.class, AnalysisState.class);
		selectHigherPriorityAnalysisState.setAccessible(true);
		// Look up the private validation helper via reflection so we can unit-test
		// it in isolation without needing mocked collaborators.
		validateCisaConstraints = VulnAnalysisService.class.getDeclaredMethod(
				"validateCisaConstraints",
				AnalysisState.class,
				AnalysisJustification.class,
				String.class,
				List.class,
				String.class);
		validateCisaConstraints.setAccessible(true);
	}

	private void invoke(AnalysisState state, AnalysisJustification justification, String details,
			List<AnalysisResponse> responses, String recommendation) throws Throwable {
		try {
			validateCisaConstraints.invoke(service, state, justification, details, responses, recommendation);
		} catch (InvocationTargetException ite) {
			throw ite.getCause();
		}
	}

	// ---- NOT_AFFECTED branch ----

	@Test
	void notAffected_withoutJustificationOrDetails_throws() {
		RelizaException ex = assertThrows(RelizaException.class,
				() -> invoke(AnalysisState.NOT_AFFECTED, null, null, null, null));
		assertTrue(ex.getMessage().contains("NOT_AFFECTED"));
	}

	@Test
	void notAffected_withBlankDetailsOnly_throws() {
		assertThrows(RelizaException.class,
				() -> invoke(AnalysisState.NOT_AFFECTED, null, "   ", null, null));
	}

	@Test
	void notAffected_withJustification_passes() {
		assertDoesNotThrow(() -> invoke(AnalysisState.NOT_AFFECTED,
				AnalysisJustification.CODE_NOT_PRESENT, null, null, null));
	}

	@Test
	void notAffected_withDetailsOnly_passes() {
		assertDoesNotThrow(() -> invoke(AnalysisState.NOT_AFFECTED,
				null, "This CVE applies only to the Windows port which we do not ship.", null, null));
	}

	// ---- EXPLOITABLE branch ----

	@Test
	void exploitable_withoutResponsesOrRecommendation_throws() {
		RelizaException ex = assertThrows(RelizaException.class,
				() -> invoke(AnalysisState.EXPLOITABLE, null, null, null, null));
		assertTrue(ex.getMessage().contains("EXPLOITABLE"));
	}

	@Test
	void exploitable_withEmptyResponseListAndBlankRecommendation_throws() {
		assertThrows(RelizaException.class,
				() -> invoke(AnalysisState.EXPLOITABLE, null, null, List.of(), "   "));
	}

	@Test
	void exploitable_withSingleResponse_passes() {
		assertDoesNotThrow(() -> invoke(AnalysisState.EXPLOITABLE,
				null, null, List.of(AnalysisResponse.WILL_NOT_FIX), null));
	}

	@Test
	void exploitable_withRecommendationOnly_passes() {
		assertDoesNotThrow(() -> invoke(AnalysisState.EXPLOITABLE,
				null, null, null, "Upgrade to 4.2.1 or apply patch ABC-123."));
	}

	// ---- States with no CISA requirement ----

	@Test
	void inTriage_noExtraFieldsRequired() {
		assertDoesNotThrow(() -> invoke(AnalysisState.IN_TRIAGE, null, null, null, null));
	}

	@Test
	void falsePositive_noExtraFieldsRequired() {
		assertDoesNotThrow(() -> invoke(AnalysisState.FALSE_POSITIVE, null, null, null, null));
	}

	@Test
	void fixed_noExtraFieldsRequired() {
		assertDoesNotThrow(() -> invoke(AnalysisState.FIXED, null, null, null, null));
	}

	@Test
	void nullState_isNoOp_allowingPartialUpdates() {
		// Regression: previously threw "Analysis state is required", breaking partial updates
		// that only touch severity/findingAliases/details/etc.
		assertDoesNotThrow(() -> invoke(null, null, null, null, null));
	}

	// ---- Partial-update preservation of top-level CISA fields ----

	@Test
	void addAnalysisHistoryEntry_partialUpdateDoesNotWipeResponsesRecommendationWorkaround() {
		VulnAnalysisData vad = VulnAnalysisData.createVulnAnalysisData(
				UUID.randomUUID(), "pkg:npm/demo@1.0.0", "pkg:npm/demo@1.0.0",
				io.reliza.model.LocationType.PURL,
				"CVE-2099-0002", List.of(),
				io.reliza.model.FindingType.VULNERABILITY,
				io.reliza.model.AnalysisScope.ORG, UUID.randomUUID(),
				null, null, null,
				AnalysisState.IN_TRIAGE, null, null,
				(WhoUpdated) null);

		// First: set EXPLOITABLE with full CISA action statement
		vad.addAnalysisHistoryEntry(
				AnalysisState.EXPLOITABLE, null, null, null,
				List.of(AnalysisResponse.UPDATE),
				"Upgrade to 4.2.1",
				"Disable feature-x",
				(WhoUpdated) null);

		// Sanity check baseline
		assertEquals(List.of(AnalysisResponse.UPDATE), vad.getResponses());
		assertEquals("Upgrade to 4.2.1", vad.getRecommendation());
		assertEquals("Disable feature-x", vad.getWorkaround());

		// Partial update: only touch details, leave CISA fields null.
		// Previously this wiped responses/recommendation/workaround — regression for #2.
		vad.addAnalysisHistoryEntry(
				AnalysisState.EXPLOITABLE, null, "Triaging ongoing", null,
				null, null, null,
				(WhoUpdated) null);

		assertEquals(List.of(AnalysisResponse.UPDATE), vad.getResponses(),
				"responses must survive a partial update with null arg");
		assertEquals("Upgrade to 4.2.1", vad.getRecommendation(),
				"recommendation must survive a partial update with null arg");
		assertEquals("Disable feature-x", vad.getWorkaround(),
				"workaround must survive a partial update with null arg");
	}

	@Test
	void addAnalysisHistoryEntry_legacy5ArgOverloadPreservesCisaFields() {
		VulnAnalysisData vad = VulnAnalysisData.createVulnAnalysisData(
				UUID.randomUUID(), "pkg:npm/demo@1.0.0", "pkg:npm/demo@1.0.0",
				io.reliza.model.LocationType.PURL,
				"CVE-2099-0003", List.of(),
				io.reliza.model.FindingType.VULNERABILITY,
				io.reliza.model.AnalysisScope.ORG, UUID.randomUUID(),
				null, null, null,
				AnalysisState.IN_TRIAGE, null, null,
				(WhoUpdated) null);

		vad.addAnalysisHistoryEntry(
				AnalysisState.EXPLOITABLE, null, null, null,
				List.of(AnalysisResponse.ROLLBACK), null, null,
				(WhoUpdated) null);

		// Legacy 5-arg overload — previously wiped CISA fields via null delegation.
		vad.addAnalysisHistoryEntry(AnalysisState.EXPLOITABLE, null, "Revisited", null, (WhoUpdated) null);

		assertEquals(List.of(AnalysisResponse.ROLLBACK), vad.getResponses());
	}

	// ---- Priority tie-break between FIXED and NOT_AFFECTED ----

	private AnalysisState higher(AnalysisState current, AnalysisState candidate) throws Exception {
		return (AnalysisState) selectHigherPriorityAnalysisState.invoke(service, current, candidate);
	}

	@Test
	void priority_fixedBeatsNotAffected_regardlessOfArgOrder() throws Exception {
		// Deterministic: FIXED is strictly stronger than NOT_AFFECTED (remediated vs non-exposed).
		assertEquals(AnalysisState.FIXED, higher(AnalysisState.NOT_AFFECTED, AnalysisState.FIXED));
		assertEquals(AnalysisState.FIXED, higher(AnalysisState.FIXED, AnalysisState.NOT_AFFECTED));
	}

	@Test
	void priority_exploitableWinsOverAllOthers() throws Exception {
		for (AnalysisState other : new AnalysisState[] {
				AnalysisState.IN_TRIAGE, AnalysisState.NOT_AFFECTED,
				AnalysisState.FALSE_POSITIVE, AnalysisState.FIXED }) {
			assertEquals(AnalysisState.EXPLOITABLE, higher(other, AnalysisState.EXPLOITABLE));
			assertEquals(AnalysisState.EXPLOITABLE, higher(AnalysisState.EXPLOITABLE, other));
		}
	}

	@Test
	void priority_nullCandidateKeepsCurrent_nullCurrentTakesCandidate() throws Exception {
		// Short-circuit semantics of selectHigherPriorityAnalysisState:
		//   - candidate null → return current (regardless of priority)
		//   - current null, candidate non-null → return candidate
		assertEquals(AnalysisState.FIXED, higher(null, AnalysisState.FIXED));
		assertEquals(AnalysisState.FIXED, higher(AnalysisState.FIXED, null));
		assertEquals(AnalysisState.IN_TRIAGE, higher(null, AnalysisState.IN_TRIAGE));
	}

	// ---- Original round-trip test ----

	@Test
	void addAnalysisHistoryEntry_mirrorsNewFieldsOnTopLevel() {
		VulnAnalysisData vad = VulnAnalysisData.createVulnAnalysisData(
				UUID.randomUUID(), "pkg:npm/demo@1.0.0", "pkg:npm/demo@1.0.0",
				io.reliza.model.LocationType.PURL,
				"CVE-2099-0001", List.of(),
				io.reliza.model.FindingType.VULNERABILITY,
				io.reliza.model.AnalysisScope.ORG, UUID.randomUUID(),
				null, null, null,
				AnalysisState.IN_TRIAGE, null, null,
				(WhoUpdated) null);

		vad.addAnalysisHistoryEntry(
				AnalysisState.EXPLOITABLE, null, null, null,
				List.of(AnalysisResponse.UPDATE, AnalysisResponse.WORKAROUND_AVAILABLE),
				"Upgrade to 4.2.1", "Disable feature-x until patched",
				(WhoUpdated) null);

		assertEquals(AnalysisState.EXPLOITABLE, vad.getAnalysisState());
		assertEquals(List.of(AnalysisResponse.UPDATE, AnalysisResponse.WORKAROUND_AVAILABLE),
				vad.getResponses());
		assertEquals("Upgrade to 4.2.1", vad.getRecommendation());
		assertEquals("Disable feature-x until patched", vad.getWorkaround());

		// Latest history entry should also have the new fields.
		var history = vad.getAnalysisHistory();
		assertEquals(2, history.size());
		var latest = history.get(history.size() - 1);
		assertNotNull(latest.getResponses());
		assertEquals(2, latest.getResponses().size());
		assertEquals("Upgrade to 4.2.1", latest.getRecommendation());
		assertEquals("Disable feature-x until patched", latest.getWorkaround());
	}

	// ---- Regression: updateAnalysisState must validate CISA against merged (existing + incoming) fields ----

	/**
	 * Builds a persisted VulnAnalysis entity whose VulnAnalysisData already satisfies CISA for
	 * {@code initialState} via the provided action/impact fields. Repository and audit collaborators
	 * are wired to behave like a real save round-trip so {@code updateAnalysisState} can run end-to-end.
	 */
	private VulnAnalysis primeExistingRecord(AnalysisState initialState, AnalysisJustification justification,
			String initialDetails, List<AnalysisResponse> responses, String recommendation, String workaround) {
		VulnAnalysisData vad = VulnAnalysisData.createVulnAnalysisData(
				UUID.randomUUID(), "pkg:npm/demo@1.0.0", "pkg:npm/demo@1.0.0",
				io.reliza.model.LocationType.PURL,
				"CVE-2099-9999", List.of(),
				io.reliza.model.FindingType.VULNERABILITY,
				io.reliza.model.AnalysisScope.ORG, UUID.randomUUID(),
				null, null, null,
				initialState, justification, initialDetails,
				responses, recommendation, workaround,
				(WhoUpdated) null);

		VulnAnalysis va = new VulnAnalysis();
		va.setUuid(vad.getUuid());
		va.setRecordData(Utils.OM.convertValue(vad, new TypeReference<Map<String, Object>>() {}));

		// findById is called twice: once in updateAnalysisState (outer) and once in saveVulnAnalysis
		// (revision bump path). Both must return the same entity.
		lenient().when(vulnAnalysisRepository.findById(vad.getUuid())).thenReturn(Optional.of(va));
		// save: echo back the entity after re-reading recordData (dataFromRecord relies on it).
		lenient().when(vulnAnalysisRepository.save(any(VulnAnalysis.class)))
				.thenAnswer(inv -> inv.getArgument(0));
		return va;
	}

	@Test
	void updateAnalysisState_severityOnlyUpdate_onExploitableRecord_doesNotThrow() throws Exception {
		// Existing EXPLOITABLE record already has a recommendation → CISA-compliant.
		// Caller only updates severity. Previously the validator saw null response + blank
		// recommendation and threw "EXPLOITABLE analysis requires an action statement...".
		VulnAnalysis va = primeExistingRecord(
				AnalysisState.EXPLOITABLE, null, null,
				null, "Upgrade to 4.2.1", null);

		assertDoesNotThrow(() -> service.updateAnalysisState(
				va.getUuid(),
				null,   // state unchanged
				null,   // justification unchanged
				null,   // details unchanged
				null,   // findingAliases unchanged
				io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity.HIGH,
				null,   // responses unchanged — was the bug trigger
				null,   // recommendation unchanged
				null,   // workaround unchanged
				WhoUpdated.getTestWhoUpdated()));
	}

	@Test
	void updateAnalysisState_detailsOnlyUpdate_onNotAffectedRecord_doesNotThrow() throws Exception {
		// Existing NOT_AFFECTED record has a stored justification → CISA-compliant.
		// Caller adds details only; justification arg is null.
		VulnAnalysis va = primeExistingRecord(
				AnalysisState.NOT_AFFECTED, AnalysisJustification.CODE_NOT_PRESENT, null,
				null, null, null);

		assertDoesNotThrow(() -> service.updateAnalysisState(
				va.getUuid(),
				null,
				null,   // justification unchanged — merge must see existing
				"Additional context added later",
				null,
				null,
				null, null, null,
				WhoUpdated.getTestWhoUpdated()));
	}

	@Test
	void updateAnalysisState_clearingRecommendation_onExploitableRecordWithoutResponses_throws() {
		// Existing record had a recommendation only (no responses). Caller explicitly clears
		// recommendation to a blank string without supplying responses. After merge the record
		// would no longer satisfy CISA — validation must reject.
		VulnAnalysis va = primeExistingRecord(
				AnalysisState.EXPLOITABLE, null, null,
				null, "Upgrade to 4.2.1", null);

		RelizaException ex = assertThrows(RelizaException.class, () -> service.updateAnalysisState(
				va.getUuid(),
				null,
				null,
				null,
				null,
				null,
				List.of(),   // explicit empty responses
				"   ",        // explicit blank recommendation
				null,
				(WhoUpdated) null));
		assertTrue(ex.getMessage().contains("EXPLOITABLE"));
	}
}
