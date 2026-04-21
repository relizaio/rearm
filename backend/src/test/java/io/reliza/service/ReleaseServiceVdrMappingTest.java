/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.cyclonedx.model.vulnerability.Vulnerability;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.reliza.model.AnalysisResponse;
import io.reliza.model.AnalysisState;

/**
 * Unit tests for the private VDR mapping helpers in {@link ReleaseService}:
 *   - {@code mapVdrAnalysisState}  : internal AnalysisState -> CDX Analysis.State
 *                                    (FIXED must map to RESOLVED per CISA VEX)
 *   - {@code mapVdrAnalysisResponse}: internal AnalysisResponse -> CDX Analysis.Response
 */
public class ReleaseServiceVdrMappingTest {

	private static Method mapVdrAnalysisState;
	private static Method mapVdrAnalysisResponse;

	@BeforeAll
	static void lookupPrivateMethods() throws NoSuchMethodException {
		mapVdrAnalysisState = ReleaseService.class.getDeclaredMethod("mapVdrAnalysisState", AnalysisState.class);
		mapVdrAnalysisState.setAccessible(true);

		mapVdrAnalysisResponse = ReleaseService.class.getDeclaredMethod("mapVdrAnalysisResponse", AnalysisResponse.class);
		mapVdrAnalysisResponse.setAccessible(true);
	}

	private static Vulnerability.Analysis.State mapState(AnalysisState state) throws InvocationTargetException, IllegalAccessException {
		// Static method — receiver is null.
		return (Vulnerability.Analysis.State) mapVdrAnalysisState.invoke(null, state);
	}

	private static Vulnerability.Analysis.Response mapResponse(AnalysisResponse response) throws InvocationTargetException, IllegalAccessException {
		// Static method — receiver is null.
		return (Vulnerability.Analysis.Response) mapVdrAnalysisResponse.invoke(null, response);
	}

	@Test
	void mapState_fixedMapsToResolved() throws Exception {
		assertEquals(Vulnerability.Analysis.State.RESOLVED, mapState(AnalysisState.FIXED));
	}

	@Test
	void mapState_exploitable() throws Exception {
		assertEquals(Vulnerability.Analysis.State.EXPLOITABLE, mapState(AnalysisState.EXPLOITABLE));
	}

	@Test
	void mapState_inTriage() throws Exception {
		assertEquals(Vulnerability.Analysis.State.IN_TRIAGE, mapState(AnalysisState.IN_TRIAGE));
	}

	@Test
	void mapState_falsePositive() throws Exception {
		assertEquals(Vulnerability.Analysis.State.FALSE_POSITIVE, mapState(AnalysisState.FALSE_POSITIVE));
	}

	@Test
	void mapState_notAffected() throws Exception {
		assertEquals(Vulnerability.Analysis.State.NOT_AFFECTED, mapState(AnalysisState.NOT_AFFECTED));
	}

	@Test
	void mapResponse_allValuesPresentAndDistinct() throws Exception {
		// Verify every internal value has a non-null CDX mapping.
		for (AnalysisResponse r : AnalysisResponse.values()) {
			Vulnerability.Analysis.Response mapped = mapResponse(r);
			assertNotNull(mapped, "No CDX mapping for " + r);
			// Names must match 1:1 with the CDX enum.
			assertEquals(r.name(), mapped.name());
		}
	}
}
