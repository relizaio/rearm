/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.ComponentIdentity;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentFlowControl;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * pkg:generic canonical components are unmatchable by construction (no
 * upstream registry -> no vulnerability source indexes them, nothing for BEAR
 * to resolve), so they are marked enrichment-terminal at mint (and by the V79
 * backfill) and leave the matchable universe: candidate window, bucket
 * population, coverage gate. A CPE identity rescues a row -- NVD matching
 * works regardless of purl type.
 *
 * <p>Motivating incident: a filesystem-scan SBOM with thousands of per-file
 * pkg:generic entries monopolized the org's oldest-first enrichment window,
 * starving every other BOM's pull (org-wide SYNTHETIC-STALL) while never
 * being able to yield a single finding.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class GenericPurlEnrichmentTerminalTest {

	@Autowired private SbomComponentRepository sbomComponentRepository;
	@Autowired private TestInitializer testInitializer;

	// ---- decision logic ------------------------------------------------

	@Test
	public void genericPurlWithoutCpeIsUnmatchable() {
		assertTrue(SbomComponentService.isUnmatchablePurlType(
				"pkg:generic/example-header.h?path=%2Fusr%2Finclude%2Fexample-header.h", null));
		assertTrue(SbomComponentService.isUnmatchablePurlType(
				"pkg:generic/thing@1.0", List.of(new ComponentIdentity("purl", "pkg:generic/thing@1.0"))));
	}

	@Test
	public void cpeIdentityRescuesAGenericPurl() {
		assertFalse(SbomComponentService.isUnmatchablePurlType(
				"pkg:generic/vendor-tool@2.1",
				List.of(new ComponentIdentity("purl", "pkg:generic/vendor-tool@2.1"),
						new ComponentIdentity("cpe", "cpe:2.3:a:vendor:tool:2.1:*:*:*:*:*:*:*"))),
				"NVD matches CPEs regardless of purl type -- the row must stay matchable");
	}

	@Test
	public void resolvablePurlTypesAreNeverTerminal() {
		assertFalse(SbomComponentService.isUnmatchablePurlType("pkg:npm/left-pad@1.3.0", null));
		assertFalse(SbomComponentService.isUnmatchablePurlType("pkg:maven/org.example/lib@2.0", null));
		assertFalse(SbomComponentService.isUnmatchablePurlType("cpe:2.3:a:vendor:tool:1:*:*:*:*:*:*:*", null));
		assertFalse(SbomComponentService.isUnmatchablePurlType(null, null));
	}

	// ---- late-CPE rescue -------------------------------------------------

	@Test
	public void lateCpeAssertionRescuesAnUnmatchableTerminalRow() {
		SbomComponent sc = new SbomComponent();
		sc.setCanonicalPurl("pkg:generic/tool@1.0");
		sc.setIdentities(List.of(new ComponentIdentity("purl", "pkg:generic/tool@1.0")));
		SbomComponentService.stampTerminalIfUnmatchablePurlType(sc);
		assertTrue(sc.isEnrichmentTerminal(), "precondition: minted terminal without a CPE");

		// The reconcile identity-merge is the single place a late CPE lands.
		List<ComponentIdentity> merged = List.of(
				new ComponentIdentity("purl", "pkg:generic/tool@1.0"),
				new ComponentIdentity("cpe", "cpe:2.3:a:vendor:tool:1.0:*:*:*:*:*:*:*"));
		assertTrue(SbomComponentService.rescuesUnmatchableTerminal(sc, merged),
				"a row that is NVD-matchable now must not stay excluded forever");

		// Without a CPE the union changes nothing.
		assertFalse(SbomComponentService.rescuesUnmatchableTerminal(sc, List.of(
				new ComponentIdentity("purl", "pkg:generic/tool@1.0"),
				new ComponentIdentity("swid", "swid:example"))));
	}

	@Test
	public void rescueIsScopedToTheUnmatchableReasonOnly() {
		// V75 terminal reasons describe unrecoverable dead ends; a new
		// identity must never resurrect them.
		SbomComponent sc = new SbomComponent();
		sc.setCanonicalPurl("pkg:generic/tool@1.0");
		sc.setFlowControl(new SbomComponentFlowControl(
				ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
				"OWN_BOM_PULLED_UNMATCHED"));
		List<ComponentIdentity> merged = List.of(
				new ComponentIdentity("cpe", "cpe:2.3:a:vendor:tool:1.0:*:*:*:*:*:*:*"));
		assertFalse(SbomComponentService.rescuesUnmatchableTerminal(sc, merged));
	}

	@Test
	public void mintStampMatchesTheSqlTimestampShape() {
		// Both writers of enrichmentTerminalAt (Java mint, SQL
		// markEnrichmentTerminal/V79 to_char) emit second precision with a
		// minimal offset, e.g. 2026-08-12T21:20:43+00 -- no fractional
		// seconds, no +00:00.
		SbomComponent sc = new SbomComponent();
		sc.setCanonicalPurl("pkg:generic/x.h");
		SbomComponentService.stampTerminalIfUnmatchablePurlType(sc);
		String at = sc.getFlowControl().enrichmentTerminalAt();
		assertTrue(at.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}(\\d{2})?"),
				"stamp must match the SQL to_char shape, got " + at);
	}

	// ---- the terminal flag actually removes the row everywhere ----------

	private SbomComponent saveComponent(UUID org, String canonical, boolean terminal) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(org);
		sc.setCanonicalPurl(canonical);
		sc.setIdentities(List.of(new ComponentIdentity("purl", canonical)));
		if (terminal) {
			sc.setFlowControl(new SbomComponentFlowControl(
					ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
					SbomComponentService.TERMINAL_REASON_UNMATCHABLE_PURL_TYPE));
		}
		return sbomComponentRepository.save(sc);
	}

	@Test
	public void terminalGenericRowLeavesEveryMatchableSurface() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String generic = "pkg:generic/example-" + UUID.randomUUID() + ".h?path=%2Fusr%2Finclude";
		String npm = "pkg:npm/real-dep-" + UUID.randomUUID().toString().substring(0, 8) + "@1.0.0";
		saveComponent(org, generic, true);
		saveComponent(org, npm, false);

		List<String> matchable = sbomComponentRepository
				.findMatchableByOrgOrdered(org.toString()).stream()
				.map(SbomComponent::getCanonicalPurl).collect(Collectors.toList());
		assertFalse(matchable.contains(generic), "terminal generic row must not ship to DTrack buckets");
		assertTrue(matchable.contains(npm), "resolvable rows are unaffected");

		List<String> candidates = sbomComponentRepository
				.findUnenrichedMatchableByOrgOrdered(org.toString(), 1000).stream()
				.map(SbomComponent::getCanonicalPurl).collect(Collectors.toList());
		assertFalse(candidates.contains(generic),
				"terminal generic row must not occupy the enrichment candidate window");
		assertTrue(candidates.contains(npm));
	}
}
