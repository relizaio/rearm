/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.Link;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.model.tea.TeaArtifactType;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.model.tea.publication.TeaArtifactDocument;
import io.reliza.model.tea.publication.TeaArtifactFormatDocument;
import io.reliza.model.tea.publication.TeaChecksumDocument;
import io.reliza.service.ArtifactService;
import io.reliza.service.SharedArtifactService;
import io.reliza.service.TeaAuthorizationService.TeaAccess;
import io.reliza.service.TeaAuthorizationService.TeaCaller;
import io.reliza.service.TeaPublicationService;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationExposure;
import io.reliza.service.tea.TeaArtifactSurfaceService.ArtifactRevision;
import io.reliza.service.tea.TeaArtifactSurfaceService.RawBytes;
import io.reliza.service.tea.TeaSurfaceService.Access;
import io.reliza.service.tea.TeaSurfaceService.Decision;
import io.reliza.service.tea.TeaSurfaceService.TeaOrg;
import io.reliza.ws.tea.TeaJson;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

/** Task TEA-10, design 4.1 cases 1 to 5 and round 2 case 21: resolution, the frozen object, content and signatures, collaborators mocked. */
class TeaArtifactSurfaceServiceTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID ORG_TEA = UUID.randomUUID();
	private static final TeaOrg TORG = new TeaOrg(ORG, ORG_TEA, "https://tea.example.com/tea/" + ORG_TEA);
	private static final TeaCaller ANON = TeaCaller.anonymous();
	private static final String CDX = "application/vnd.cyclonedx+json";
	private static final ZonedDateTime T0 = ZonedDateTime.parse("2026-10-01T00:00:00Z");

	private TeaArtifactSurfaceService service;
	private TeaPublicationService pubs;
	private TeaSurfaceService surface;
	private ArtifactService artifacts;
	private SharedArtifactService shared;

	private final UUID tea = UUID.randomUUID();
	private final UUID row = UUID.randomUUID();
	private final UUID pubA = UUID.randomUUID();
	private final UUID pubB = UUID.randomUUID();
	private final Map<UUID, Access> accesses = new LinkedHashMap<>();

	@BeforeEach
	void wire() {
		service = new TeaArtifactSurfaceService();
		pubs = mock(TeaPublicationService.class);
		surface = mock(TeaSurfaceService.class);
		artifacts = mock(ArtifactService.class);
		shared = mock(SharedArtifactService.class);
		ReflectionTestUtils.setField(service, "teaPublicationService", pubs);
		ReflectionTestUtils.setField(service, "teaSurfaceService", surface);
		ReflectionTestUtils.setField(service, "artifactService", artifacts);
		ReflectionTestUtils.setField(service, "sharedArtifactService", shared);
		for (UUID p : List.of(pubA, pubB)) {
			TeaPublicationData pd = new TeaPublicationData();
			pd.setUuid(p);
			pd.setOrg(ORG);
			when(pubs.getPublication(p)).thenReturn(Optional.of(pd));
		}
		when(surface.evaluate(any(), any(), any())).thenAnswer(inv -> {
			TeaPublicationData p = inv.getArgument(2);
			return accesses.getOrDefault(p.getUuid(), new Access(TeaAccess.CONCEAL, null));
		});
	}

	// ---------------------------------------------------------------- fixtures

	private static Access serve(TeaExposureState state) {
		return new Access(TeaAccess.SERVE, new TeaPublicationExposure(state, null, null, null, null, null));
	}

	private static Access refuse(TeaAccess a) {
		return new Access(a, null);
	}

	private TeaArtifactDocument frozen(int revision, String name, String mediaType, String signatureUrl,
			List<TeaChecksumDocument> checksums) {
		return new TeaArtifactDocument(tea, revision, name, TeaArtifactType.LICENSE, "2026-10-01T00:00:00Z",
				List.of(new TeaArtifactFormatDocument(mediaType, null, null, signatureUrl, checksums)));
	}

	private TeaPublishedEntry entry(int revision, String name, UUID signature, Integer signatureRevision) {
		return entry(revision, name, signature, signatureRevision, null, "ab".repeat(32));
	}

	/** An entry whose one CDX format carries {@code signatureUrl} and the SHA-256 checksum {@code sha256}. */
	private TeaPublishedEntry entry(int revision, String name, UUID signature, Integer signatureRevision, String signatureUrl,
			String sha256) {
		List<TeaChecksumDocument> sums = List.of(new TeaChecksumDocument(TeaChecksumType.SHA_256, sha256));
		return new TeaPublishedEntry(row, tea, revision, TeaArtifactOrigin.REAL, TeaArtifactType.LICENSE, name, CDX, sums,
				null, signature, signatureRevision, frozen(revision, name, CDX, signatureUrl, sums));
	}

	/** A collection row whose document's artifacts are {@code documented} and whose entries are {@code entries}. */
	private static TeaPublishedCollectionData row(UUID publication, int version, ZonedDateTime created,
			List<TeaPublishedEntry> entries, List<TeaArtifactDocument> documented) {
		TeaPublishedCollectionData c = new TeaPublishedCollectionData();
		c.setUuid(publication);
		c.setVersion(version);
		c.setOrg(ORG);
		ReflectionTestUtils.setField(c, "createdDate", created);
		c.setEntries(new ArrayList<>(entries));
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("uuid", publication.toString());
		doc.put("version", version);
		doc.put("artifacts", documented);
		c.setDocument(TeaJson.MAPPER.writeValueAsString(doc));
		return c;
	}

	private static TeaPublishedCollectionData row(UUID publication, int version, ZonedDateTime created, TeaPublishedEntry... entries) {
		return row(publication, version, created, List.of(entries), java.util.Arrays.stream(entries).map(TeaPublishedEntry::frozen).toList());
	}

	private void rows(TeaPublishedCollectionData... rows) {
		when(pubs.listCollectionsNamingArtifact(ORG, tea)).thenReturn(List.of(rows));
	}

	private static ArtifactRevision served(Decision<ArtifactRevision> d) {
		assertTrue(d.served(), "refused: " + d.refusal());
		return d.value();
	}

	// ---------------------------------------------------------------- 1

	@Test
	void resolutionDecidesPerPublicationAndTakesTheStrongestRefusal() {
		rows();
		assertEquals(TeaAccess.CONCEAL, service.latest(ANON, TORG, tea).refusal(), "no row");

		rows(row(pubA, 1, T0, entry(1, "a1", null, null)), row(pubA, 2, T0.plusDays(1), entry(3, "a3", null, null)),
				row(pubB, 1, T0.plusDays(2), entry(5, "b5", null, null)));
		accesses.put(pubA, serve(TeaExposureState.PUBLIC));
		accesses.put(pubB, refuse(TeaAccess.CONCEAL));
		ArtifactRevision latest = served(service.latest(ANON, TORG, tea));
		assertEquals(3, latest.revision());
		assertEquals("a3", latest.artifactObject().get("name").asString());
		assertEquals(pubA, latest.publication().getUuid());
		assertEquals(2, latest.collectionVersion());
		TeaPublicationData a = pubs.getPublication(pubA).orElseThrow();
		verify(surface, times(1)).evaluate(any(), any(), eq(a));
		for (int r : new int[] { 5, 2, 0, -1 }) {
			assertEquals(TeaAccess.CONCEAL, service.revision(ANON, TORG, tea, r).refusal(), "revision " + r);
		}
		assertEquals(1, served(service.revision(ANON, TORG, tea, 1)).revision());

		accesses.put(pubB, refuse(TeaAccess.UNAUTHENTICATED));
		assertEquals(TeaAccess.UNAUTHENTICATED, service.revision(ANON, TORG, tea, 5).refusal());
		assertEquals(3, served(service.latest(ANON, TORG, tea)).revision(), "latest is judged over what is served");

		accesses.put(pubA, refuse(TeaAccess.UNAUTHENTICATED));
		assertEquals(TeaAccess.UNAUTHENTICATED, service.latest(ANON, TORG, tea).refusal());

		accesses.put(pubA, refuse(TeaAccess.CONCEAL));
		accesses.put(pubB, refuse(TeaAccess.INVALID_TOKEN));
		assertEquals(TeaAccess.INVALID_TOKEN, service.latest(ANON, TORG, tea).refusal());

		// a publication that is gone conceals its candidates; the other still serves
		accesses.put(pubA, serve(TeaExposureState.PUBLIC));
		when(pubs.getPublication(pubA)).thenReturn(Optional.empty());
		accesses.put(pubB, serve(TeaExposureState.PRIVATE));
		assertEquals(5, served(service.latest(ANON, TORG, tea)).revision());
		assertEquals(TeaAccess.CONCEAL, service.revision(ANON, TORG, tea, 3).refusal());
		verifyNoInteractions(artifacts, shared);
	}

	@Test
	void anotherOrganizationsRowIsNeverAsked() {
		assertEquals(TeaAccess.CONCEAL, service.latest(ANON, new TeaOrg(UUID.randomUUID(), ORG_TEA, "x"), tea).refusal());
		verify(pubs, never()).getPublication(any());
	}

	// ---------------------------------------------------------------- 2

	@Test
	void theFirstPublicationOfARevisionFixesItsObject() {
		rows(row(pubA, 1, T0, entry(2, "older", null, null)), row(pubB, 4, T0.plusDays(1), entry(2, "newer", null, null)));
		accesses.put(pubA, serve(TeaExposureState.PRIVATE));
		accesses.put(pubB, serve(TeaExposureState.PUBLIC));
		ArtifactRevision r = served(service.revision(ANON, TORG, tea, 2));
		assertEquals("older", r.artifactObject().get("name").asString());
		assertEquals(1, r.collectionVersion());
		assertEquals(pubA, r.publication().getUuid());
		assertTrue(r.publiclyAccessible(), "either served publication PUBLIC");

		accesses.put(pubB, serve(TeaExposureState.PRIVATE));
		assertFalse(served(service.revision(ANON, TORG, tea, 2)).publiclyAccessible(), "both PRIVATE");
		// a PUBLIC publication that is not served to this caller does not make the content public
		accesses.put(pubB, refuse(TeaAccess.CONCEAL));
		assertFalse(served(service.revision(ANON, TORG, tea, 2)).publiclyAccessible());
	}

	// ---------------------------------------------------------------- 3

	@Test
	void aDocumentThatLacksTheEntryIsConcealedWithOneError() {
		TeaPublishedEntry e = entry(4, "four", null, null);
		rows(row(pubA, 1, T0, List.of(e), List.of(frozen(3, "three", CDX, null, List.of()))));
		accesses.put(pubA, serve(TeaExposureState.PUBLIC));
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class)) {
			assertEquals(TeaAccess.CONCEAL, service.revision(ANON, TORG, tea, 4).refusal());
			assertEquals(1, logs.errors().size(), logs.errors().toString());
			assertTrue(logs.errors().get(0).contains(pubA.toString()) && logs.errors().get(0).contains(tea.toString()));
		}
		verifyNoInteractions(artifacts, shared);
	}

	// ---------------------------------------------------------------- 4

	private ArtifactRevision servedRevision(int revision, TeaPublishedEntry e) {
		rows(row(pubA, 1, T0, e));
		accesses.put(pubA, serve(TeaExposureState.PUBLIC));
		return served(service.revision(ANON, TORG, tea, revision));
	}

	@Test
	void contentReadsThatRevisionsRowThroughTheRawDownload() throws Exception {
		ArtifactRevision r = servedRevision(2, entry(2, "lic.txt", null, null));
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", row);
		ad.setVersion("2");
		byte[] bytes = "license".getBytes(StandardCharsets.UTF_8);
		when(artifacts.getArtifactDataByVersion(row, 2)).thenReturn(Optional.of(ad));
		when(shared.downloadRawArtifact(ad)).thenReturn(Mono.just(ResponseEntity.ok(bytes)));
		RawBytes b = service.content(r, CDX);
		assertArrayEquals(bytes, b.bytes());
		assertEquals(io.reliza.common.Utils.bytesToHexSha256(bytes), b.sha256());
		assertEquals(CDX, b.contentType());
		assertEquals("lic.txt", b.fileName());
		InOrder order = inOrder(artifacts, shared);
		order.verify(artifacts).getArtifactDataByVersion(row, 2);
		order.verify(shared).downloadRawArtifact(ad);
		verify(shared, never()).downloadArtifact(any());

		when(artifacts.getArtifactDataByVersion(row, 2)).thenReturn(Optional.empty());
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class)) {
			assertThrows(IllegalStateException.class, () -> service.content(r, CDX));
			assertEquals(1, logs.errors().size(), logs.errors().toString());
		}
		when(artifacts.getArtifactDataByVersion(row, 2)).thenReturn(Optional.of(ad));
		when(shared.downloadRawArtifact(ad)).thenReturn(Mono.error(new IllegalStateException("registry down")));
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class)) {
			assertThrows(IllegalStateException.class, () -> service.content(r, CDX));
			assertEquals(1, logs.errors().size(), logs.errors().toString());
		}

		assertEquals(Optional.of("ab".repeat(32)), service.storedContentSha256(r, CDX));
		assertEquals(Optional.empty(), service.storedContentSha256(r, "text/plain"));
	}

	@Test
	void aFormatWithoutSha256HasNoStoredDigest() {
		List<TeaChecksumDocument> sha512 = List.of(new TeaChecksumDocument(TeaChecksumType.SHA_512, "cd".repeat(64)));
		TeaPublishedEntry e = new TeaPublishedEntry(row, tea, 1, TeaArtifactOrigin.REAL, TeaArtifactType.LICENSE, "s512",
				"text/plain", sha512, null, null, null, frozen(1, "s512", "text/plain", null, sha512));
		assertEquals(Optional.empty(), service.storedContentSha256(servedRevision(1, e), "text/plain"));
	}

	// ---------------------------------------------------------------- 5

	@Test
	void theSignatureIsTheRowTheEntryRecorded() {
		ArtifactRevision none = servedRevision(1, entry(1, "lic", null, null));
		assertEquals(Optional.empty(), service.signatureRow(none));
		verifyNoInteractions(artifacts);

		UUID sig = UUID.randomUUID();
		ArtifactData sd = new ArtifactData();
		ReflectionTestUtils.setField(sd, "uuid", sig);
		sd.setOrg(ORG);
		sd.setVersion("1");
		when(artifacts.getArtifactDataByVersion(sig, 1)).thenReturn(Optional.of(sd));
		assertEquals(Optional.of(sd), service.signatureRow(servedRevision(1, entry(1, "lic", sig, 1))));
		verify(artifacts).getArtifactDataByVersion(sig, 1);

		when(artifacts.getArtifactDataByVersion(sig, null)).thenReturn(Optional.of(sd));
		assertEquals(Optional.of(sd), service.signatureRow(servedRevision(1, entry(1, "lic", sig, null))));
		verify(artifacts).getArtifactDataByVersion(sig, null);

		ArtifactData foreign = new ArtifactData();
		ReflectionTestUtils.setField(foreign, "uuid", sig);
		foreign.setOrg(UUID.randomUUID());
		when(artifacts.getArtifactDataByVersion(sig, 1)).thenReturn(Optional.of(foreign));
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class)) {
			assertEquals(Optional.empty(), service.signatureRow(servedRevision(1, entry(1, "lic", sig, 1))));
			assertEquals(1, logs.errors().size(), logs.errors().toString());
		}
		when(artifacts.getArtifactDataByVersion(sig, 1)).thenReturn(Optional.empty());
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class)) {
			assertEquals(Optional.empty(), service.signatureRow(servedRevision(1, entry(1, "lic", sig, 1))));
			assertEquals(1, logs.errors().size(), logs.errors().toString());
		}
	}

	// ---------------------------------------------------------------- 21

	@Test
	void anExternalUrlIsTheFrozenFormatsAndAnExternalLocationTheRowsFirstLink() {
		ObjectNode o = TeaJson.MAPPER.createObjectNode();
		o.put("uuid", tea.toString());
		o.put("version", 1);
		var formats = o.putArray("formats");
		formats.addObject().put("mediaType", CDX).putNull("url");
		formats.addObject().put("mediaType", "text/markdown").put("url", "https://files.example/notes-1.md ");
		formats.addObject().put("mediaType", "text/plain").put("url", "  ");
		ArtifactRevision r = new ArtifactRevision(tea, 1, null, 1, null, o, true);
		assertEquals(Optional.of("https://files.example/notes-1.md"), service.externalUrl(r, "text/markdown"), "trimmed");
		assertEquals(Optional.empty(), service.externalUrl(r, CDX), "a hosted format");
		assertEquals(Optional.empty(), service.externalUrl(r, "application/pdf"), "an unmatched media type");
		assertEquals(Optional.empty(), service.externalUrl(r, "text/plain"), "a blank url");
		assertEquals(Optional.empty(), service.externalUrl(r, null));
		verifyNoInteractions(artifacts, shared);

		ArtifactData rearm = new ArtifactData();
		rearm.setStoredIn(StoredIn.REARM);
		rearm.setDownloadLinks(List.of(new Link("https://files.example/r.sig", Link.ContentEnum.OCTET_STREAM)));
		assertEquals(Optional.empty(), TeaArtifactSurfaceService.externalLocation(rearm), "stored in ReARM");
		ArtifactData ext = new ArtifactData();
		ReflectionTestUtils.setField(ext, "uuid", UUID.randomUUID());
		ext.setStoredIn(StoredIn.EXTERNALLY);
		ext.setDownloadLinks(List.of(new Link(" ", Link.ContentEnum.OCTET_STREAM),
				new Link("https://files.example/x.sig", Link.ContentEnum.OCTET_STREAM)));
		assertEquals(Optional.of("https://files.example/x.sig"), TeaArtifactSurfaceService.externalLocation(ext));
		ArtifactRevision served = new ArtifactRevision(tea, 1, null, 1, null, o, true);
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class)) {
			assertFalse(service.isLinklessExternalSignature(served, ext), "a link to point at");
			assertFalse(service.isLinklessExternalSignature(served, rearm), "bytes in ReARM");
			assertEquals(List.of(), logs.warnings());
			for (List<Link> none : List.of(List.<Link>of(), List.of(new Link("", Link.ContentEnum.OCTET_STREAM)))) {
				ext.setDownloadLinks(none);
				assertEquals(Optional.empty(), TeaArtifactSurfaceService.externalLocation(ext), none.toString());
			}
			assertTrue(service.isLinklessExternalSignature(served, ext));
			assertEquals(1, logs.warnings().size(), logs.warnings().toString());
			assertTrue(logs.warnings().get(0).contains(ext.getUuid().toString()) && logs.warnings().get(0).contains(tea.toString()));
			assertEquals(List.of(), logs.errors());
		}
		verifyNoInteractions(artifacts, shared);
	}

	@Test
	void theStoredSignatureDigestPrefersTheRetainedUpload() {
		ArtifactData sd = new ArtifactData();
		String uploaded = "aa".repeat(32);
		String declared = "bb".repeat(32);
		sd.setDigestRecords(new LinkedHashSet<>(List.of(new DigestRecord(DigestAlgorithm.SHA_256, declared, DigestScope.ORIGINAL_FILE),
				new DigestRecord(DigestAlgorithm.SHA_512, "cc".repeat(64), DigestScope.AS_UPLOADED),
				new DigestRecord(DigestAlgorithm.SHA_256, "dd".repeat(32), DigestScope.OCI_STORAGE),
				new DigestRecord(DigestAlgorithm.SHA_256, uploaded.toUpperCase(), DigestScope.AS_UPLOADED))));
		ArtifactRevision r = servedRevision(1, entry(1, "lic", null, null));
		assertEquals(Optional.of(uploaded), TeaArtifactSurfaceService.storedSignatureSha256(r, sd));
		sd.setDigestRecords(new LinkedHashSet<>(Set.of(new DigestRecord(DigestAlgorithm.SHA_256, declared, DigestScope.ORIGINAL_FILE),
				new DigestRecord(DigestAlgorithm.SHA_256, "dd".repeat(32), DigestScope.OCI_STORAGE))));
		assertEquals(Optional.of(declared), TeaArtifactSurfaceService.storedSignatureSha256(r, sd));
		sd.setDigestRecords(new LinkedHashSet<>(Set.of(new DigestRecord(DigestAlgorithm.SHA_256, "dd".repeat(32), DigestScope.RAW_OCI_STORAGE))));
		assertEquals(Optional.empty(), TeaArtifactSurfaceService.storedSignatureSha256(r, sd));
	}

	// ---------------------------------------------------------------- 29 (round 4, T-8; amended by round 6 row 34)

	@Test
	void aSignatureMediaTypeTagOver1024CharactersIsNotOne() throws Exception {
		ArtifactRevision r = servedRevision(1, entry(1, "lic", null, null));
		String tooLong = "a".repeat(1020) + "/json";
		String exact = "a".repeat(1019) + "/json";
		assertTrue(tooLong.length() == 1025 && exact.length() == 1024);
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG)) {
			assertEquals("application/octet-stream", service.signature(r, tagged(tooLong)).contentType());
			assertEquals(1, logs.aboveDebug().size(), logs.aboveDebug().toString());
			assertTrue(logs.aboveDebug().get(0).startsWith("ERROR: "), logs.aboveDebug().get(0));
			assertEquals(0, logs.withThrowable(), "no stack trace");
		}
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG)) {
			assertEquals(exact, service.signature(r, tagged(exact)).contentType(), "the boundary");
			assertEquals("text/plain", service.signature(r, tagged("text/plain")).contentType());
			assertEquals(List.of(), logs.aboveDebug());
			assertEquals(0, logs.withThrowable());
		}
		// round 6 row 34 (decisions 22 and 24): a tag the parser refuses, or whose rendering is not printable
		// ASCII, is octet-stream with one ERROR carrying the tag through LogSafe; one that parses is its rendering
		Map<String, String> refused = new LinkedHashMap<>();
		refused.put("text/plain;\r\ncharset=nope", "text/plain;??charset=nope");
		refused.put("text/pl\r\nain", "text/pl??ain");
		refused.put("text\r\n/plain", "text??/plain");
		refused.put("text/plain;a=b\tc", "text/plain;a=b?c");
		refused.put("text/plain;a=\"b\r\nc\"", "text/plain;a=\"b??c\"");
		for (Map.Entry<String, String> t : refused.entrySet()) {
			try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG)) {
				assertEquals("application/octet-stream", service.signature(r, tagged(t.getKey())).contentType(), t.getValue());
				assertEquals(1, logs.aboveDebug().size(), t.getValue() + ": " + logs.aboveDebug());
				String line = logs.aboveDebug().get(0);
				assertTrue(line.startsWith("ERROR: ") && line.contains(t.getValue()), line);
				assertFalse(line.contains("\r") || line.contains("\n") || line.contains("\t"), "no control in the line: " + t.getValue());
				assertEquals(0, logs.withThrowable(), t.getValue());
			}
		}
		Map<String, String> rendered = new LinkedHashMap<>();
		rendered.put("text/plain;\r\ncharset", "text/plain");
		rendered.put("text/plain;\r\nx-a=b", "text/plain;x-a=b");
		rendered.put("Text/Plain; Charset=UTF-8", "text/plain;Charset=UTF-8");
		rendered.put("application/json\r\n", "application/json");
		rendered.put("text/plain;\tcharset=utf-8", "text/plain;charset=utf-8");
		rendered.put("text/*", "application/octet-stream");
		for (Map.Entry<String, String> t : rendered.entrySet()) {
			try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG)) {
				assertEquals(t.getValue(), service.signature(r, tagged(t.getKey())).contentType(), t.getValue());
				assertEquals(List.of(), logs.aboveDebug(), t.getValue());
				assertEquals(0, logs.withThrowable(), t.getValue());
			}
		}
	}

	// ---------------------------------------------------------------- round 5 row 30

	@Test
	void anExternalSignatureUrlIsTheFrozenFormatsSignatureUrl() {
		ArtifactRevision withUrl = servedRevision(1, entry(1, "lic", null, null, " https://files.example/lic.sig ", "ab".repeat(32)));
		assertEquals(Optional.of("https://files.example/lic.sig"), service.externalSignatureUrl(withUrl, CDX), "stripped");
		assertEquals(Optional.empty(), service.externalSignatureUrl(withUrl, "text/plain"), "an unmatched media type");
		assertEquals(Optional.empty(), service.externalSignatureUrl(withUrl, null));
		assertEquals(Optional.empty(), service.externalSignatureUrl(servedRevision(1, entry(1, "lic", null, null)), CDX), "absent");
		ObjectNode o = TeaJson.MAPPER.createObjectNode();
		o.put("uuid", tea.toString());
		o.put("version", 1);
		var formats = o.putArray("formats");
		formats.addObject().put("mediaType", CDX).putNull("signatureUrl");
		formats.addObject().put("mediaType", "text/plain").put("signatureUrl", "  ");
		formats.addObject().put("mediaType", "text/markdown").put("url", "https://files.example/notes.md");
		ArtifactRevision r = new ArtifactRevision(tea, 1, null, 1, null, o, true);
		assertEquals(Optional.empty(), service.externalSignatureUrl(r, CDX), "null");
		assertEquals(Optional.empty(), service.externalSignatureUrl(r, "text/plain"), "blank");
		assertEquals(Optional.empty(), service.externalSignatureUrl(r, "text/markdown"), "the content url is not the signature's");
		verifyNoInteractions(artifacts, shared);
	}

	// ---------------------------------------------------------------- round 5 row 31

	private static ArtifactData signatureRowAt(StoredIn storedIn, String... links) {
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", UUID.randomUUID());
		ad.setStoredIn(storedIn);
		ad.setVersion("1\n2");
		List<Link> l = new ArrayList<>();
		for (String link : links) l.add(new Link(link, Link.ContentEnum.OCTET_STREAM));
		ad.setDownloadLinks(l);
		return ad;
	}

	@Test
	void aPreRuleLiveLinkIsServedOnlyWhenTheUrlRuleAdmitsIt() {
		ArtifactRevision r = servedRevision(1, entry(1, "lic", null, null));
		for (String ok : List.of("https://files.example/a.sig", "HTTP://FILES.EXAMPLE/a.sig")) {
			ArtifactData row = signatureRowAt(StoredIn.EXTERNALLY, " ", ok);
			assertEquals(Optional.of(ok), TeaArtifactSurfaceService.admittedExternalLocation(row), ok);
			try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG)) {
				assertFalse(service.isLinklessExternalSignature(r, row), ok);
				assertEquals(List.of(), logs.aboveDebug(), ok);
			}
		}
		Map<String, String> refused = new LinkedHashMap<>();
		refused.put("ftp://files.example/a.sig", "SCHEME_NOT_HTTP");
		refused.put("https://u:p@files.example/a.sig", "USERINFO");
		refused.put("//files.example/a.sig", "NO_SCHEME");
		refused.put("/a.sig", "NO_SCHEME");
		refused.put("https://files.example/na\u00efve.sig", "NOT_PRINTABLE_ASCII");
		refused.put("https://files.example/x.sig\r\n", "NOT_PRINTABLE_ASCII");
		refused.put(" https://files.example/a.sig", "NOT_PRINTABLE_ASCII");
		String long4100 = "https://files.example/" + "a".repeat(4100 - 22);
		assertEquals(4100, long4100.length());
		refused.put(long4100, "TOO_LONG");
		refused.put(null, "EMPTY");
		for (Map.Entry<String, String> c : refused.entrySet()) {
			ArtifactData row = null == c.getKey() ? signatureRowAt(StoredIn.EXTERNALLY) : signatureRowAt(StoredIn.EXTERNALLY, c.getKey());
			String what = c.getValue() + " " + (null == c.getKey() ? "no link" : c.getKey().length() + " characters");
			assertEquals(Optional.empty(), TeaArtifactSurfaceService.admittedExternalLocation(row), what);
			try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG)) {
				assertTrue(service.isLinklessExternalSignature(r, row), what);
				assertEquals(1, logs.aboveDebug().size(), what + ": " + logs.aboveDebug());
				String line = logs.aboveDebug().get(0);
				assertTrue(line.startsWith("WARN: ") && line.contains(row.getUuid().toString()) && line.contains("version 1?2 ")
						&& line.contains("(" + c.getValue() + ")"), line);
				assertFalse(line.contains("files.example") || line.contains("ftp"), "the link is never logged: " + line);
				assertEquals(0, logs.withThrowable(), what);
			}
		}
		ArtifactData rearm = signatureRowAt(StoredIn.REARM, "https://files.example/a.sig");
		assertEquals(Optional.empty(), TeaArtifactSurfaceService.admittedExternalLocation(rearm), "stored in ReARM");
		assertFalse(service.isLinklessExternalSignature(r, rearm));
		verifyNoInteractions(artifacts, shared);
	}

	// ---------------------------------------------------------------- round 5 row 33

	@Test
	void aStoredDigestThatIsNot64HexDigitsIsNotAValidator() {
		String upper = "AB".repeat(32);
		assertEquals(Optional.of("ab".repeat(32)), service.storedContentSha256(servedRevision(1, entry(1, "lic", null, null, null, upper)), CDX));
		Map<String, Integer> malformed = new LinkedHashMap<>();
		malformed.put("ab".repeat(31) + "a", 63);
		malformed.put("ab".repeat(32) + "a", 65);
		malformed.put("ab".repeat(31) + "ag", 64);
		malformed.put("ab".repeat(31) + "a\r", 64);
		for (Map.Entry<String, Integer> m : malformed.entrySet()) {
			ArtifactRevision r = servedRevision(1, entry(1, "lic", null, null, null, m.getKey()));
			try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG)) {
				assertEquals(Optional.empty(), service.storedContentSha256(r, CDX), m.getValue() + " characters");
				assertMalformedDigest(logs.aboveDebug(), 0, 1, "content", m.getValue(), m.getKey());
				assertEquals(0, logs.withThrowable());
			}
			for (DigestScope scope : List.of(DigestScope.AS_UPLOADED, DigestScope.ORIGINAL_FILE)) {
				ArtifactData sd = new ArtifactData();
				sd.setDigestRecords(new LinkedHashSet<>(List.of(new DigestRecord(DigestAlgorithm.SHA_256, m.getKey(), scope))));
				try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG)) {
					assertEquals(Optional.empty(), TeaArtifactSurfaceService.storedSignatureSha256(r, sd), scope + " " + m.getValue());
					assertMalformedDigest(logs.aboveDebug(), 0, 1, "signature " + scope.name(), m.getValue(), m.getKey());
					assertEquals(0, logs.withThrowable());
				}
			}
		}
		ArtifactRevision r = servedRevision(1, entry(1, "lic", null, null));
		String declared = "cd".repeat(32);
		String bad63 = "ab".repeat(31) + "a";
		String bad65 = "ef".repeat(32) + "e";
		ArtifactData sd = new ArtifactData();
		sd.setDigestRecords(new LinkedHashSet<>(List.of(new DigestRecord(DigestAlgorithm.SHA_256, bad63, DigestScope.AS_UPLOADED),
				new DigestRecord(DigestAlgorithm.SHA_256, declared.toUpperCase(), DigestScope.ORIGINAL_FILE))));
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG)) {
			assertEquals(Optional.of(declared), TeaArtifactSurfaceService.storedSignatureSha256(r, sd), "falls through to the declared digest");
			assertMalformedDigest(logs.aboveDebug(), 0, 1, "signature AS_UPLOADED", 63, bad63);
		}
		sd.setDigestRecords(new LinkedHashSet<>(List.of(new DigestRecord(DigestAlgorithm.SHA_256, bad63, DigestScope.AS_UPLOADED),
				new DigestRecord(DigestAlgorithm.SHA_256, bad65, DigestScope.ORIGINAL_FILE))));
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG)) {
			assertEquals(Optional.empty(), TeaArtifactSurfaceService.storedSignatureSha256(r, sd), "both malformed");
			assertMalformedDigest(logs.aboveDebug(), 0, 2, "signature AS_UPLOADED", 63, bad63);
			assertMalformedDigest(logs.aboveDebug(), 1, 2, "signature ORIGINAL_FILE", 65, bad65);
			assertEquals(0, logs.withThrowable());
		}
	}

	/** Line {@code at} of exactly {@code count} lines is the ERROR naming the TEA id, revision 1, the scope and the length, not the value. */
	private void assertMalformedDigest(List<String> lines, int at, int count, String scope, int length, String value) {
		assertEquals(count, lines.size(), lines.toString());
		String line = lines.get(at);
		assertTrue(line.startsWith("ERROR: ") && line.contains(tea.toString()) && line.contains("revision 1 ")
				&& line.contains("stored " + scope + " SHA-256 of " + length + " characters"), line);
		assertFalse(line.contains(value.strip()), "the value is never logged: " + line);
	}

	// ---------------------------------------------------------------- round 6 rows 41 and 42

	@Test
	void theContentTypeIsTheRenderingOfTheParsedMediaType() throws Exception {
		ArtifactRevision r = servedRevision(2, entry(2, "lic.txt", null, null));
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", row);
		ad.setVersion("2");
		byte[] bytes = "license".getBytes(StandardCharsets.UTF_8);
		when(artifacts.getArtifactDataByVersion(row, 2)).thenReturn(Optional.of(ad));
		when(shared.downloadRawArtifact(ad)).thenReturn(Mono.just(ResponseEntity.ok(bytes)));
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG)) {
			for (String[] c : new String[][] { { "text/plain;\r\ncharset", "text/plain" },
					{ "text/plain; charset=utf-8", "text/plain;charset=utf-8" } }) {
				RawBytes b = service.content(r, c[0]);
				assertEquals(c[1], b.contentType(), c[1]);
				assertArrayEquals(bytes, b.bytes());
				assertEquals(io.reliza.common.Utils.bytesToHexSha256(bytes), b.sha256());
				assertEquals("lic.txt", b.fileName());
			}
			assertEquals(List.of(), logs.aboveDebug());
		}
	}

	@Test
	void theFetchedRowsVersionIsLoggedThroughLogSafe() throws Exception {
		ArtifactRevision r = servedRevision(1, entry(1, "lic", null, null));
		ArtifactData sd = tagged("application/octet-stream");
		sd.setVersion("1\n2");
		when(shared.downloadRawArtifact(sd)).thenReturn(Mono.error(new IllegalStateException("registry down")));
		try (TeaLogCapture logs = TeaLogCapture.of(TeaArtifactSurfaceService.class, Level.DEBUG)) {
			assertThrows(IllegalStateException.class, () -> service.signature(r, sd));
			assertEquals(1, logs.aboveDebug().size(), logs.aboveDebug().toString());
			String line = logs.aboveDebug().get(0);
			assertTrue(line.startsWith("ERROR: ") && line.contains("version 1?2"), line);
			assertFalse(line.contains("\n") || line.contains("\r"), line);
			assertEquals(1, logs.withThrowable(), "the cause of a failed read keeps its trace");
		}
	}

	private ArtifactData tagged(String mediaType) throws Exception {
		ArtifactData sd = new ArtifactData();
		ReflectionTestUtils.setField(sd, "uuid", UUID.randomUUID());
		sd.setOrg(ORG);
		sd.setVersion("1");
		sd.setTags(new ArrayList<>(List.of(new io.reliza.common.CommonVariables.TagRecord(
				io.reliza.common.CommonVariables.MEDIA_TYPE_FIELD, mediaType, io.reliza.common.CommonVariables.Removable.YES))));
		when(shared.downloadRawArtifact(sd)).thenReturn(Mono.just(ResponseEntity.ok("sig".getBytes(StandardCharsets.UTF_8))));
		return sd;
	}
}
