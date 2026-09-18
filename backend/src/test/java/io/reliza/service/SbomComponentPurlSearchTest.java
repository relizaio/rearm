/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.reliza.model.SbomComponent;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.ReleaseArtifactIndexRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportAuditRepository;
import io.reliza.service.SbomComponentService.ComponentPurlToSbom;
import io.reliza.service.SbomComponentService.SbomComponentSearchQuery;

/**
 * Covers the search-term routing in
 * {@link SbomComponentService#searchSbomComponentsBatch}.
 *
 * <p>The regression these guard: the org-wide search box has always been
 * labelled "Name or Purl", but every term went to an exact-equality match on
 * {@code record_data->>'name'}. A pasted purl is never equal to a bare name, so
 * it silently returned nothing.
 */
@ExtendWith(MockitoExtension.class)
class SbomComponentPurlSearchTest {

	@Mock private SbomComponentRepository sbomComponentRepository;
	@Mock private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Mock private ReleaseArtifactIndexRepository releaseArtifactIndexRepository;
	@Mock private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Mock private SbomComponentSupportAuditRepository sbomComponentSupportAuditRepository;

	private SbomComponentService service;

	private final UUID ORG = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		service = new SbomComponentService(
				sbomComponentRepository, artifactSbomComponentRepository,
				releaseArtifactIndexRepository, artifactCanonicalMapRepository,
				sbomComponentSupportAuditRepository);
	}

	private SbomComponent comp(String canonicalPurl) {
		SbomComponent sc = new SbomComponent();
		sc.setUuid(UUID.randomUUID());
		sc.setOrg(ORG);
		sc.setCanonicalPurl(canonicalPurl);
		return sc;
	}

	private List<ComponentPurlToSbom> search(String term, String version) {
		return service.searchSbomComponentsBatch(
				List.of(new SbomComponentSearchQuery(term, version)), ORG);
	}

	@Test
	void versionPinnedPurlResolvesViaCanonicalPurlNotName() {
		SbomComponent sc = comp("pkg:npm/lodash@4.17.21");
		when(sbomComponentRepository.findByOrgAndCanonicalPurl(ORG, "pkg:npm/lodash@4.17.21"))
				.thenReturn(Optional.of(sc));

		List<ComponentPurlToSbom> out = search("pkg:npm/lodash@4.17.21", null);

		assertEquals(1, out.size(), "a pasted purl must resolve -- this returning 0 is the original bug");
		assertEquals("pkg:npm/lodash@4.17.21", out.get(0).purl());
		assertEquals(List.of(sc.getUuid()), out.get(0).sbomComponents());
		verify(sbomComponentRepository, never())
				.searchByOrgAndNameAndOptionalVersion(anyString(), anyString(), any());
	}

	@Test
	void versionlessPurlFansOutOverEveryVersionOfTheCoordinate() {
		SbomComponent v20 = comp("pkg:npm/lodash@4.17.20");
		SbomComponent v21 = comp("pkg:npm/lodash@4.17.21");
		when(sbomComponentRepository.searchByOrgAndCanonicalPurlCoordinate(
				ORG.toString(), "pkg:npm/lodash", "pkg:npm/lodash"))
				.thenReturn(List.of(v20, v21));

		List<ComponentPurlToSbom> out = search("pkg:npm/lodash", null);

		assertEquals(2, out.size(), "a versionless purl must fan out -- advisories quote the bare coordinate");
		assertEquals(List.of("pkg:npm/lodash@4.17.20", "pkg:npm/lodash@4.17.21"),
				out.stream().map(ComponentPurlToSbom::purl).toList());
	}

	/**
	 * {@code _} is a single-character LIKE wildcard and survives into plenty of
	 * canonical purls, so the pattern arm must receive an escaped copy while
	 * the equality arm receives the raw coordinate.
	 *
	 * <p>Uses a gem purl deliberately: the purl spec's pypi rule folds {@code _}
	 * to {@code -}, so a pypi coordinate can never exercise this. Types that
	 * preserve the underscore are the ones that need the escape.
	 */
	@Test
	void underscoreInCoordinateIsLikeEscapedForThePatternArmOnly() {
		when(sbomComponentRepository.searchByOrgAndCanonicalPurlCoordinate(
				anyString(), anyString(), anyString())).thenReturn(List.of());

		search("pkg:gem/my_pkg", null);

		ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> escaped = ArgumentCaptor.forClass(String.class);
		verify(sbomComponentRepository).searchByOrgAndCanonicalPurlCoordinate(
				anyString(), raw.capture(), escaped.capture());
		assertEquals("pkg:gem/my_pkg", raw.getValue());
		assertEquals("pkg:gem/my\\_pkg", escaped.getValue());
	}

	/**
	 * The regression that motivated the fallback arm: identity qualifiers are
	 * preserved in a stored canonical (deb/apk/rpm keep {@code distro}), but an
	 * advisory quotes the bare coordinate. The exact lookup misses, so the
	 * coordinate scan has to pick it up and match on version.
	 *
	 * <p>The exact arm is stubbed loosely on purpose: canonicalization also
	 * percent-encodes a Debian epoch colon ({@code 1:2.5.2-3} becomes
	 * {@code 1%3A2.5.2-3}), which is a second, independent reason the exact
	 * lookup misses here. Pinning that byte form belongs in
	 * {@code UtilsCanonicalizePurlEncodingTest}, not in this routing test. The
	 * version match below survives it because both sides are parsed, not
	 * string-compared.
	 */
	@Test
	void versionPinnedPurlLackingIdentityQualifiersFallsBackToCoordinate() {
		SbomComponent stored = comp("pkg:deb/debian/attr@1:2.5.2-3?distro=debian-13");
		when(sbomComponentRepository.findByOrgAndCanonicalPurl(any(), anyString()))
				.thenReturn(Optional.empty());
		when(sbomComponentRepository.searchByOrgAndCanonicalPurlCoordinate(
				ORG.toString(), "pkg:deb/debian/attr", "pkg:deb/debian/attr"))
				.thenReturn(List.of(stored, comp("pkg:deb/debian/attr@9.9.9?distro=debian-13")));

		List<ComponentPurlToSbom> out = search("pkg:deb/debian/attr@1:2.5.2-3", null);

		assertEquals(1, out.size(), "only the pinned version may survive the fallback");
		assertEquals("pkg:deb/debian/attr@1:2.5.2-3?distro=debian-13", out.get(0).purl());
	}

	/** The exact arm must short-circuit -- no coordinate scan when it hits. */
	@Test
	void exactCanonicalHitSkipsTheCoordinateScan() {
		when(sbomComponentRepository.findByOrgAndCanonicalPurl(ORG, "pkg:npm/lodash@4.17.21"))
				.thenReturn(Optional.of(comp("pkg:npm/lodash@4.17.21")));

		search("pkg:npm/lodash@4.17.21", null);

		verify(sbomComponentRepository, never())
				.searchByOrgAndCanonicalPurlCoordinate(anyString(), anyString(), anyString());
	}

	@Test
	void plainNameStillTakesTheNamePathWithItsVersionFilter() {
		SbomComponent sc = comp("pkg:npm/lodash@4.17.21");
		when(sbomComponentRepository.searchByOrgAndNameAndOptionalVersion(
				ORG.toString(), "lodash", "4.17.21")).thenReturn(List.of(sc));

		List<ComponentPurlToSbom> out = search("lodash", "4.17.21");

		assertEquals(1, out.size());
		verify(sbomComponentRepository, never()).findByOrgAndCanonicalPurl(any(), anyString());
	}

	/**
	 * A purl is self-contained; splicing the separate version box into one that
	 * may already carry qualifiers produces malformed input more often than it
	 * helps, so the version argument is ignored for purl terms.
	 */
	@Test
	void separateVersionArgumentIsIgnoredForPurlTerms() {
		when(sbomComponentRepository.findByOrgAndCanonicalPurl(ORG, "pkg:npm/lodash@4.17.21"))
				.thenReturn(Optional.empty());

		search("pkg:npm/lodash@4.17.21", "1.0.0");

		verify(sbomComponentRepository).findByOrgAndCanonicalPurl(ORG, "pkg:npm/lodash@4.17.21");
		verify(sbomComponentRepository, never())
				.searchByOrgAndNameAndOptionalVersion(anyString(), anyString(), any());
	}

	@Test
	void unparseablePurlYieldsNoMatchesRatherThanFallingBackToName() {
		List<ComponentPurlToSbom> out = search("pkg:", null);

		assertTrue(out.isEmpty());
		verify(sbomComponentRepository, never())
				.searchByOrgAndNameAndOptionalVersion(anyString(), anyString(), any());
	}

	@Test
	void whitespacePaddedPurlIsStillTreatedAsAPurl() {
		when(sbomComponentRepository.findByOrgAndCanonicalPurl(ORG, "pkg:npm/lodash@4.17.21"))
				.thenReturn(Optional.of(comp("pkg:npm/lodash@4.17.21")));

		List<ComponentPurlToSbom> out = search("  pkg:npm/lodash@4.17.21  ", null);

		assertEquals(1, out.size());
	}
}
