/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.reliza.model.LevelOfSupport;
import io.reliza.model.Organization;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportAttestationRequest;
import io.reliza.model.SupportBulkSelector;
import io.reliza.model.SupportBulkSelectorForm;

/**
 * FDAW-14 row 7 (REQ-5): the selector resolved server-side by exact purl fields, org-scoped, non-root,
 * in a release or organisation scope, and counted with {@code LIMIT cap + 1} before any row is loaded.
 */
class SbomComponentBulkSelectorRepositoryTest extends ReleaseSbomJustificationTestBase {

	private Set<String> purlsOf(List<UUID> ids, List<SbomComponent> all) {
		Set<UUID> set = Set.copyOf(ids);
		return all.stream().filter(c -> set.contains(c.getUuid())).map(SbomComponent::getCanonicalPurl)
				.collect(Collectors.toSet());
	}

	private static SupportBulkSelector type(String t) {
		return SupportBulkSelector.validated(SupportBulkSelectorForm.TYPE, t, null);
	}

	private static SupportBulkSelector ns(String t, String n) {
		return SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, t, n);
	}

	@Test
	void theSelectorMatchesExactTypeAndNamespaceInTheOrganizationOnly() {
		List<SbomComponent> all = new ArrayList<>(components(org, null, List.of(
				"pkg:deb/debian/curl@7.88", "pkg:deb/ubuntu/curl@7.88", "pkg:npm/%40acme/a@1", "pkg:npm/%40acme_b/x@1",
				"pkg:npm/left-pad@1", "pkg:golang/github.com/foo/bar@v1", "pkg:generic/x@1"), false));
		all.addAll(components(org, null, List.of("pkg:npm/%40acme/root@1"), true));
		Organization other = testInitializer.obtainOrganization();
		components(other, null, List.of("pkg:deb/debian/curl@7.88"), false);
		UUID o = org.getUuid();

		assertEquals(Set.of("pkg:deb/debian/curl@7.88", "pkg:deb/ubuntu/curl@7.88"),
				purlsOf(sbomComponentService.resolveSupportSelector(o, type("deb"), null), all));
		assertEquals(Set.of("pkg:deb/debian/curl@7.88"),
				purlsOf(sbomComponentService.resolveSupportSelector(o, ns("deb", "debian"), null), all));
		assertEquals(Set.of("pkg:npm/%40acme/a@1"),
				purlsOf(sbomComponentService.resolveSupportSelector(o, ns("npm", "@acme"), null), all),
				"not %40acme_b, not the root, not left-pad");
		assertTrue(sbomComponentService.resolveSupportSelector(o, ns("npm", "@acme_"), null).isEmpty(),
				"an underscore is a literal, never a wildcard");
		assertTrue(sbomComponentService.resolveSupportSelector(o, ns("npm", "%"), null).isEmpty(),
				"a percent sign is a literal, never a wildcard");
		assertThrows(IllegalArgumentException.class, () -> type("np%"));
		assertEquals(Set.of("pkg:golang/github.com/foo/bar@v1"),
				purlsOf(sbomComponentService.resolveSupportSelector(o, ns("golang", "github.com/foo"), null), all));
	}

	@Test
	void theCountIsCappedBeforeAnyRowIsLoadedAndTheReleaseScopeNarrowsIt() throws Exception {
		List<String> big = new ArrayList<>();
		for (int i = 0; i <= SbomComponentService.BULK_SUPPORT_MAX_IDS; i++) big.add("pkg:npm/%40big/n" + i + "@1");
		List<SbomComponent> bigRows = components(org, null, big, false);
		World w = componentRelease(null);
		List<SbomComponent> inRelease = components(org, w.release(), List.of("pkg:deb/debian/curl@7.88"), false);
		// two of the 1001 are in the release too
		UUID release = w.release();
		List<UUID> releaseSet = new ArrayList<>(sbomComponentService.findReleaseComponentUuids(release));
		releaseSet.add(bigRows.get(0).getUuid());
		releaseSet.add(bigRows.get(1).getUuid());

		UUID o = org.getUuid();
		assertEquals(SbomComponentService.BULK_SUPPORT_MAX_IDS + 1,
				sbomComponentService.resolveSupportSelector(o, type("npm"), null).size(), "LIMIT cap + 1");
		long audits = count("sbom_component_support_audit", "org = ?", o);
		long acts = count("support_bulk_acts", "org = ?", o);
		SupportAttestationRequest level = new SupportAttestationRequest(LevelOfSupport.ACTIVELY_MAINTAINED, null, null,
				null, null, null, null, null, null, Set.of(), null);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
				sbomComponentService.bulkSetSbomComponentSupportBySelector(o, type("npm"), null, null, level,
						UUID.randomUUID()));
		assertEquals(SbomComponentService.selectorOverCap(type("npm"), false), e.getMessage());
		assertEquals(audits, count("sbom_component_support_audit", "org = ?", o));
		assertEquals(acts, count("support_bulk_acts", "org = ?", o));
		assertEquals(0, count("sbom_component_support", "org = ?", o), "no row was written");

		assertEquals(Set.of(bigRows.get(0).getUuid(), bigRows.get(1).getUuid()),
				Set.copyOf(sbomComponentService.resolveSupportSelector(o, type("npm"), releaseSet)));
		assertEquals(List.of(inRelease.get(0).getUuid()),
				sbomComponentService.resolveSupportSelector(o, type("deb"), releaseSet));
		var releaseCount = sbomComponentService.countSupportSelector(o, type("npm"), releaseSet);
		assertEquals(2, releaseCount.matched());
		assertFalse(releaseCount.overCap());
		var orgCount = sbomComponentService.countSupportSelector(o, type("npm"), null);
		assertEquals(SbomComponentService.BULK_SUPPORT_MAX_IDS + 1, orgCount.matched());
		assertTrue(orgCount.overCap());
		assertTrue(sbomComponentService.resolveSupportSelector(o, type("npm"), List.of()).isEmpty(),
				"an empty release scope matches nothing, never the organization");
		assertEquals(acts, count("support_bulk_acts", "org = ?", o), "a count writes nothing");
	}
}
