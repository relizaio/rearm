/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.reliza.model.SbomComponent;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.ReleaseArtifactIndexRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportAuditRepository;
import io.reliza.repositories.SbomComponentSupportRepository;

/**
 * Covers {@link SbomComponentService#searchSbomComponentByPurl}, the purl to
 * sbom_components lookup behind the findings-table dependency-graph link.
 *
 * <p>The regression these guard: the lookup keyed on
 * {@code Utils.canonicalizePurl}, whose PackageURL round-trip writes a Debian
 * {@code +} as {@code %2B} and an epoch colon as {@code %3A}, while rebom
 * persists both raw. Every {@code +debNNuN} or epoch-versioned package resolved
 * to nothing, and the graph page said "No SBOM component found". Rows copied
 * from a live sandbox.
 */
@ExtendWith(MockitoExtension.class)
class SbomComponentPurlLookupTest {

	@Mock private SbomComponentRepository sbomComponentRepository;
	@Mock private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Mock private ReleaseArtifactIndexRepository releaseArtifactIndexRepository;
	@Mock private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Mock private SbomComponentSupportRepository sbomComponentSupportRepository;
	@Mock private SbomComponentSupportAuditRepository sbomComponentSupportAuditRepository;

	private SbomComponentService service;

	private final UUID ORG = UUID.randomUUID();

	private static final String PERL = "pkg:deb/debian/perl@5.36.0-7+deb12u3?distro=debian-12";
	private static final String ZLIB = "pkg:deb/debian/zlib@1:1.2.13.dfsg-1?distro=debian-12";
	private static final String PCRE2 = "pkg:deb/debian/pcre2@10.42-1?distro=debian-12";

	/** The org's sbom_components rows, served by the IN query as the DB would. */
	private final List<SbomComponent> rows = new ArrayList<>();

	@BeforeEach
	void setUp() {
		service = new SbomComponentService(
				sbomComponentRepository, artifactSbomComponentRepository,
				releaseArtifactIndexRepository, artifactCanonicalMapRepository,
				sbomComponentSupportRepository, sbomComponentSupportAuditRepository);
		lenient().when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(anyString(), anyCollection()))
				.thenAnswer(inv -> {
					Collection<?> wanted = inv.getArgument(1);
					return rows.stream().filter(sc -> wanted.contains(sc.getCanonicalPurl())).toList();
				});
	}

	private SbomComponent stored(String canonicalPurl) {
		SbomComponent sc = new SbomComponent();
		sc.setUuid(UUID.randomUUID());
		sc.setOrg(ORG);
		sc.setCanonicalPurl(canonicalPurl);
		rows.add(sc);
		return sc;
	}

	@Test
	void plusAndEpochVersionsResolveAgainstRebomsRows() {
		SbomComponent perl = stored(PERL);
		SbomComponent zlib = stored(ZLIB);
		SbomComponent pcre2 = stored(PCRE2);

		assertEquals(perl.getUuid(), service.searchSbomComponentByPurl(PERL, ORG));
		assertEquals(zlib.getUuid(), service.searchSbomComponentByPurl(ZLIB, ORG));
		assertEquals(pcre2.getUuid(), service.searchSbomComponentByPurl(PCRE2, ORG));
		// Non-identity qualifiers (arch) drop out, as in the stored canonical.
		assertEquals(perl.getUuid(), service.searchSbomComponentByPurl(
				"pkg:deb/debian/perl@5.36.0-7+deb12u3?arch=amd64&distro=debian-12", ORG));
	}

	@Test
	void anEncodedPurlFindsTheRawRow() {
		SbomComponent perl = stored(PERL);
		SbomComponent zlib = stored(ZLIB);

		assertEquals(perl.getUuid(), service.searchSbomComponentByPurl(
				"pkg:deb/debian/perl@5.36.0-7%2Bdeb12u3?distro=debian-12", ORG));
		assertEquals(zlib.getUuid(), service.searchSbomComponentByPurl(
				"pkg:deb/debian/zlib@1%3A1.2.13.dfsg-1?distro=debian-12", ORG));
	}

	@Test
	void rowsOfOlderWriterErasAreFoundToo() {
		SbomComponent glibc = stored("pkg:deb/debian/glibc@2.36-9%2Bdeb12u13?distro=debian-12");
		SbomComponent shadow = stored("pkg:deb/debian/shadow@1:4.13%2Bdfsg1-1%2Bdeb12u2?distro=debian-12");

		assertEquals(glibc.getUuid(), service.searchSbomComponentByPurl(
				"pkg:deb/debian/glibc@2.36-9+deb12u13?distro=debian-12", ORG));
		assertEquals(shadow.getUuid(), service.searchSbomComponentByPurl(
				"pkg:deb/debian/shadow@1:4.13+dfsg1-1+deb12u2?distro=debian-12", ORG));
	}

	@Test
	void theCallersOwnSpellingIsTriedForCharactersOnlyItKeepsRaw() {
		// PackageURL writes a PyPI epoch '!' as %21; rebom keeps it raw, and the
		// version spellings only vary '+' and ':'. The caller's bytes find it.
		SbomComponent foo = stored("pkg:pypi/foo@1!2.0");

		assertEquals(foo.getUuid(), service.searchSbomComponentByPurl("pkg:pypi/foo@1!2.0", ORG));
	}

	@Test
	void aVersionlessPurlResolves() {
		SbomComponent lodash = stored("pkg:npm/lodash");

		assertEquals(lodash.getUuid(), service.searchSbomComponentByPurl("pkg:npm/lodash", ORG));
	}

	@Test
	void otherVersionsAndDistrosAreNotMatched() {
		stored("pkg:deb/debian/perl@5.36.0-7+deb12u2?distro=debian-12");
		stored("pkg:deb/debian/perl@5.36.0-7+deb12u3?distro=debian-11");
		stored("pkg:deb/debian/perl@5.36.0-7+deb12u3");

		assertNull(service.searchSbomComponentByPurl(PERL, ORG));
	}

	@Test
	void aQualifierKeyTheParserLowercasesDoesNotReachADistrolessRow() {
		// The byte-preserving canonicalizer drops 'DISTRO' (not a preserved key
		// in that case); the parser keeps it as distro. Different identities.
		stored("pkg:deb/debian/perl@5.36.0-7+deb12u3");

		assertNull(service.searchSbomComponentByPurl(
				"pkg:deb/debian/perl@5.36.0-7+deb12u3?DISTRO=debian-12", ORG));
	}

	@Test
	void everyLookupIsOneIndexedQuery() {
		assertNull(service.searchSbomComponentByPurl(PERL, ORG));

		verify(sbomComponentRepository, times(1)).findByOrgAndCanonicalPurlIn(anyString(), anyCollection());
	}

	@Test
	void noOrgOrNoPurlLooksNothingUp() {
		assertNull(service.searchSbomComponentByPurl(PERL, null));
		assertNull(service.searchSbomComponentByPurl("perl", ORG));
		verifyNoInteractions(sbomComponentRepository);
	}
}
