/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.reliza.model.SupportExportState;

/**
 * Pins the export-state seam that {@code SbomComponentSupportCoverage.supportExportState}
 * reports, and the claim it makes about the running system.
 *
 * <p>The field exists so the coverage gauge and the export state travel in ONE response: a
 * client fetching them separately could render full coverage beside an export that silently
 * carries nothing. The value is a constant today, and a constant carrying a comment that
 * promises it will change is a constant that stays -- so this test asserts the state of the
 * system the constant describes, and fails when that state changes.
 *
 * <p>The scan is over ALL of {@code src/main/java}, NOT over the one class that happens to
 * call the injector today. An earlier revision named {@code SharedArtifactService}, and
 * review pointed out the obvious defeat: the natural home for an export gate is
 * {@code SupportInjectionService.injectCurrentSupport}, which already receives the org and
 * already short-circuits, and a gate there would have left this test green while its premise
 * was false. {@code CyclonedxTaxonomyDocSyncTest} carries the same lesson in its own javadoc,
 * having been burned by it once already. Scan, do not list.
 */
class SupportExportStateTest {

	private static final Path MAIN_SOURCES = Path.of("src/main/java");
	/**
	 * The only classes allowed to mention the seam, and why: one owns it, the other REPORTS
	 * it on the coverage query. Reporting is the point of the field; gating on it is what
	 * has not happened yet. Anything else mentioning it is a gate, wherever it lives.
	 */
	private static final List<Path> REPORTERS = List.of(
			Path.of("src/main/java/io/reliza/service/SbomComponentService.java"),
			Path.of("src/main/java/io/reliza/ws/SbomComponentDataFetcher.java"));

	@Test
	void reportsPartialBecauseOnlySomeExportPathsInject() {
		// Not ENABLED, deliberately. Injection is un-gated, but of a narrower surface than
		// "this org's exports": one call site (the CycloneDX artifact download), while the
		// SPDX-augmented form and the release-level SBOM export carry nothing. ENABLED here
		// would let a 100%-coverage gauge sit beside an export with no disclosure in it.
		SbomComponentService svc = new SbomComponentService(null, null, null, null, null, null);
		assertEquals(SupportExportState.PARTIAL, svc.supportExportState(UUID.randomUUID()),
				"supportExportState must describe the running system, not the intended one");
	}

	/**
	 * The load-bearing half: nothing outside the owner consults the seam, so injection is
	 * still ungated wherever it happens.
	 *
	 * <p>When an export control lands ANYWHERE outside those two -- the injector, the release
	 * export path, a settings service -- this fails, and its author has to come here, make
	 * {@code supportExportState} read the real setting, and re-point this test at the gate
	 * rather than its absence.
	 */
	@Test
	void nothingGatesInjectionOnTheExportSeamYet() throws IOException {
		assertTrue(Files.isDirectory(MAIN_SOURCES),
				"main sources not found at " + MAIN_SOURCES.toAbsolutePath());
		List<Path> consumers;
		try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
			consumers = files.filter(f -> f.toString().endsWith(".java"))
					.filter(f -> !REPORTERS.contains(f))
					.filter(f -> {
						try {
							return Files.readString(f).contains("supportExportState");
						} catch (IOException e) {
							throw new IllegalStateException("could not read " + f, e);
						}
					})
					.toList();
		}
		assertEquals(List.of(), consumers,
				"production code now consults supportExportState, so an export control has"
						+ " landed and injection is no longer unconditional. Make"
						+ " SbomComponentService.supportExportState read the real setting"
						+ " instead of returning a constant, and re-point this test at the"
						+ " gate rather than its absence. Consumers found: " + consumers);
	}

	/**
	 * The injection surface this state describes, pinned so it cannot widen or narrow
	 * silently.
	 *
	 * <p>PARTIAL is only the right answer while exactly one path injects. If a second call
	 * site appears the answer may become ENABLED, and if the only one goes away it becomes
	 * DISABLED -- either way somebody has to revisit the constant, and this is what tells
	 * them.
	 *
	 * <p>The gap itself is board task {@code t20260826-172851-10180}, open since 2026-08-26:
	 * support disclosure rides only the native-CycloneDX artifact egress. Closing that task is
	 * what lets this method return ENABLED or DISABLED, and this test is what proves it: when
	 * every egress is gated on one setting, the call-site count changes and this fails.
	 *
	 * <p>The second defect that used to be described here -- the release export and the
	 * SPDX-augmented download bypassing the forged-provenance strip -- was pulled out of that
	 * task and FIXED separately, because the strip must run on every egress unconditionally
	 * while injection is what an operator opts into. Those two are now stripped and marked but
	 * still carry no facts, which is why this is PARTIAL and not DISABLED. See
	 * {@code ReleaseServiceForgedSupportStripTest} and
	 * {@code SharedArtifactServiceForgedSupportStripTest}.
	 */
	@Test
	void exactlyOneProductionCallSiteInjectsSupport() throws IOException {
		List<Path> callers;
		try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
			callers = files.filter(f -> f.toString().endsWith(".java"))
					.filter(f -> !f.toString().endsWith("SupportInjectionService.java"))
					.filter(f -> {
						try {
							return Files.readString(f).contains(".injectCurrentSupport(");
						} catch (IOException e) {
							throw new IllegalStateException("could not read " + f, e);
						}
					})
					.toList();
		}
		assertEquals(1, callers.size(),
				"the number of paths that inject support properties changed, so PARTIAL may no"
						+ " longer be the right answer -- revisit"
						+ " SbomComponentService.supportExportState. Callers: " + callers);
	}
}
