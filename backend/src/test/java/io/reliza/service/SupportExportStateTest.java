/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;


import io.reliza.model.OrganizationData;
import io.reliza.model.SupportInjectionSetting;


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
			Path.of("src/main/java/io/reliza/ws/SbomComponentDataFetcher.java"));

	/**
	 * The ONE class allowed to decide whether an export carries support facts.
	 *
	 * <p>The gate exists now, so the old "nobody consults the seam" assertion could not
	 * survive unchanged -- but its shape had to: what it was really protecting is that the
	 * decision lives in exactly one place. Three egresses each choosing for themselves is how
	 * they drifted apart in the first place, one injecting while two stripped.
	 *
	 * <p>It is also the only class that READS the setting. The gauge and the egress take their
	 * answer from the same method, so a gauge reporting "injection ON" beside an export
	 * carrying nothing is not expressible: there is one predicate, not two that could drift.
	 */
	private static final Path DECIDER =
			Path.of("src/main/java/io/reliza/service/SupportInjectionService.java");

	/**
	 * Classes that legitimately touch the setting without deciding anything with it.
	 *
	 * <p>Listed by ROLE, because "mentions the setting" and "decides export content" are not
	 * the same thing and conflating them makes the assertion fire on the class that merely
	 * declares the accessor:
	 * <ul>
	 *   <li>{@code OrganizationData} DECLARES {@code getSupportInjectionOrDefault} -- it is
	 *       the field's home;</li>
	 *   <li>{@code OrganizationService} WRITES it, applying the settings patch.</li>
	 * </ul>
	 * Neither reads it to choose what a served BOM carries. A class that does, and is not the
	 * decider, is the thing this test is looking for.
	 */
	private static final List<Path> SETTING_OWNERS = List.of(
			Path.of("src/main/java/io/reliza/model/OrganizationData.java"),
			Path.of("src/main/java/io/reliza/service/OrganizationService.java"));

	/**
	 * The three answers, from the setting rather than from a constant.
	 *
	 * <p>UNKNOWN is not a synonym for DISABLED and must never be rendered as "off": DISABLED
	 * says this org chose not to export attestations, UNKNOWN says we could not find out. A
	 * gauge reporting "export injection OFF" on the strength of a failed lookup is a confident
	 * sentence about a fact nobody established.
	 */
	@Test
	void reportsTheSettingWhenItIsEnabled() {
		assertEquals(SupportExportState.ENABLED, stateFor(SupportInjectionSetting.ENABLED),
				"an org with injection ENABLED must report ENABLED");
	}

	@Test
	void reportsDisabledWhenTheSettingIsOff() {
		assertEquals(SupportExportState.DISABLED, stateFor(SupportInjectionSetting.DISABLED));
	}

	/** Null is DISABLED by decision D3 -- unset and explicitly-off are the same answer. */
	@Test
	void reportsDisabledWhenTheSettingWasNeverSet() {
		assertEquals(SupportExportState.DISABLED, stateFor(null),
				"an unset org must read as DISABLED, not as UNKNOWN: D3 makes off the default");
	}

	/**
	 * An org that has never saved settings is DISABLED, not UNKNOWN.
	 *
	 * <p>createOrganization writes record_data with only the name, so settings is null until
	 * someone calls updateOrganizationSettings. Reading it through the raw getter NPEs, the
	 * broad catch turns that into UNKNOWN, and the gauge then says "could not determine" about
	 * something perfectly determinate -- while logging a warn on every BOM download that org
	 * makes. Invisible in every other test here, because they all construct a real Settings.
	 */
	@Test
	void reportsDisabledForAnOrgThatNeverSavedSettings() {
		OrganizationData od = mock(OrganizationData.class);
		when(od.getSettingsWithDefaults()).thenReturn(new OrganizationData.Settings());
		assertEquals(SupportExportState.DISABLED,
				serviceWithOrg(Optional.of(od)).supportExportState(UUID.randomUUID()));
	}

	@Test
	void reportsUnknownWhenTheOrgCannotBeRead() {
		SupportInjectionService svc = serviceWithOrg(Optional.empty());
		assertEquals(SupportExportState.UNKNOWN, svc.supportExportState(UUID.randomUUID()),
				"a missing org row is 'could not find out', never 'this org chose off'");
	}

	@Test
	void reportsUnknownWhenTheLookupThrows() {
		GetOrganizationService boom = mock(GetOrganizationService.class);
		when(boom.getOrganizationData(any())).thenThrow(new RuntimeException("db down"));
		assertEquals(SupportExportState.UNKNOWN, serviceWith(boom).supportExportState(UUID.randomUUID()),
				"a failed lookup must not be reported as a deliberate choice");
	}

	private SupportExportState stateFor(SupportInjectionSetting setting) {
		OrganizationData.Settings settings = new OrganizationData.Settings();
		settings.setSupportInjection(setting);
		OrganizationData od = mock(OrganizationData.class);
		when(od.getSettingsWithDefaults()).thenReturn(settings);
		return serviceWithOrg(Optional.of(od)).supportExportState(UUID.randomUUID());
	}

	private SupportInjectionService serviceWithOrg(Optional<OrganizationData> od) {
		GetOrganizationService gos = mock(GetOrganizationService.class);
		when(gos.getOrganizationData(any())).thenReturn(od);
		return serviceWith(gos);
	}

	/** Repositories are null: this predicate reads the org row and nothing else. */
	private SupportInjectionService serviceWith(GetOrganizationService gos) {
		return new SupportInjectionService(null, null, gos, null);
	}

	/**
	 * The decision lives in ONE place.
	 *
	 * <p>This is the old "nothing gates injection yet" assertion, re-pointed at the gate
	 * rather than at its absence. What it protected was never the absence -- it was that
	 * whoever added a gate had to come here and say where it lives. Now it holds the answer:
	 * only SupportInjectionService decides, and only its datafetcher REPORTS the result.
	 * SbomComponentService no longer mentions the seam at all -- its copy of the predicate was
	 * deleted rather than left delegating, so there is one answer and not two that could
	 * drift.
	 */
	@Test
	void onlyOneClassDecidesWhetherExportsCarrySupport() throws IOException {
		assertTrue(Files.isDirectory(MAIN_SOURCES),
				"main sources not found at " + MAIN_SOURCES.toAbsolutePath());
		List<Path> deciders;
		try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
			deciders = files.filter(f -> f.toString().endsWith(".java"))
					.filter(f -> !REPORTERS.contains(f))
					.filter(f -> !DECIDER.equals(f))
					.filter(f -> !SETTING_OWNERS.contains(f))
					.filter(f -> {
						try {
							String src = Files.readString(f);
							return src.contains("supportExportState")
									|| src.contains("getSupportInjectionOrDefault");
						} catch (IOException e) {
							throw new IllegalStateException("could not read " + f, e);
						}
					})
					.toList();
		}
		assertEquals(List.of(), deciders,
				"a second class now decides whether exports carry support facts. That decision"
						+ " belongs in SupportInjectionService.injectIfEnabledElseStrip, which"
						+ " every egress calls, so the disclosure marker cannot disagree with"
						+ " what was actually done. Found: " + deciders);
	}

	/**
	 * EVERY egress goes through the seam, and none injects behind its back.
	 *
	 * <p>Replaces "exactly one call site injects". One was the symptom this whole task
	 * existed to fix: the native artifact download injected while the SPDX-augmented download
	 * and the merged release export stripped and carried nothing, so an org at full coverage
	 * exported a release SBOM with no disclosure in it.
	 *
	 * <p>Now the count that matters is direct callers of the raw injector, which must be
	 * ZERO outside the seam: a path that calls injectCurrentSupport itself is one that ignores
	 * the setting.
	 */
	@Test
	void noProductionPathInjectsBehindTheSeam() throws IOException {
		List<Path> callers;
		try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
			callers = files.filter(f -> f.toString().endsWith(".java"))
					.filter(f -> !DECIDER.equals(f))
					.filter(f -> {
						try {
							return Files.readString(f).contains(".injectCurrentSupport(");
						} catch (IOException e) {
							throw new IllegalStateException("could not read " + f, e);
						}
					})
					.toList();
		}
		assertEquals(List.of(), callers,
				"an egress calls injectCurrentSupport directly, so it injects regardless of the"
						+ " org's export setting. Call injectIfEnabledElseStrip instead."
						+ " Callers: " + callers);
	}

	/** The three egresses the setting governs, pinned so a fourth cannot appear unnoticed. */
	@Test
	void exactlyThreeEgressesAreGatedOnTheSetting() throws IOException {
		int sites = 0;
		try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
			for (Path f : files.filter(x -> x.toString().endsWith(".java"))
					.filter(x -> !DECIDER.equals(x)).toList()) {
				String src = Files.readString(f);
				int from = 0;
				// supportInjectionService.<call>, not any mention: a javadoc {@link} written
				// with a leading dot would otherwise inflate the count and the pin would drift
				// without anyone adding an egress.
				while ((from = src.indexOf("supportInjectionService.injectIfEnabledElseStrip(", from)) >= 0) {
					sites++; from++;
				}
			}
		}
		assertEquals(3, sites,
				"the number of gated egresses changed: native artifact download, SPDX-augmented"
						+ " download, merged release export. A new one is fine -- update this"
						+ " count and make sure the live ingest probe covers it. Found: " + sites);
	}
}
