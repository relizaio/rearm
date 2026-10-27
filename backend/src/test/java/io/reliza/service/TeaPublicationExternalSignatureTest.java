/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.Link;
import io.reliza.model.ReleaseData;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublishedCollection;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.model.tea.TeaCollectionUpdateReasonType;
import io.reliza.model.tea.publication.TeaArtifactDocument;
import io.reliza.model.tea.publication.TeaArtifactFormatDocument;
import io.reliza.service.TeaPublicationService.TeaChildOutcome;
import io.reliza.service.TeaPublicationService.TeaChildPublishResult;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.TeaPublicationService.TeaRideAlongOutcome;
import io.reliza.service.TeaPublicationService.TeaRideAlongStatus;
import io.reliza.service.tea.TeaOpenApiSchemas;
import tools.jackson.databind.JsonNode;

/**
 * Task TEA-20, design section 5.1 rows 1 to 10: the publication engine publishes a signature stored outside
 * ReARM as the format's {@code signatureUrl} when its first non-blank link passes the TEA-19 url rule, and
 * otherwise publishes the format unsigned with a skip row naming the signature row. A signature stored in ReARM
 * is unchanged. Shares the fixture of {@link TeaPublicationServiceTestBase}.
 */
class TeaPublicationExternalSignatureTest extends TeaPublicationServiceTestBase {

	private static final String LINK = "https://files.example/manual.pdf.sig";

	private UUID pdf;
	private UUID sig;

	/**
	 * A REARM-stored PDF (the TEA-5 rideAlongInventory shape, with a full-length SHA-256 so the artifact object
	 * passes the 1.0.0 schema) whose one child is the SIGNATURE row
	 * {@code manual.pdf.sig} at version 3, stored as given, with these links in order; the inventory is the
	 * release's SBOM and the PDF, the signature is not in it; rawArtifacts ALL.
	 */
	private void signedPdf(StoredIn sigStoredIn, String... links) {
		pdf = artifact(ArtifactType.USER_DOCUMENT, "manual.pdf", "2", StoredIn.REARM, false, null);
		artifacts.get(pdf).getDigestRecords().add(new DigestRecord(DigestAlgorithm.SHA_256, "BB".repeat(32), DigestScope.ORIGINAL_FILE));
		artifacts.get(pdf).getTags().add(new TagRecord(CommonVariables.MEDIA_TYPE_FIELD, "application/pdf", Removable.NO));
		sig = artifact(ArtifactType.SIGNATURE, "manual.pdf.sig", "3", sigStoredIn, false, null);
		setLinks(sig, links);
		artifacts.get(pdf).setArtifacts(new ArrayList<>(List.of(sig)));
		setInventory(release, List.of(inventoryBom, pdf));
		profile.setRawArtifacts(TeaRawArtifacts.ALL);
	}

	private void setLinks(UUID artifact, String... uris) {
		List<Link> l = new ArrayList<>();
		for (String uri : uris) {
			Link link = new Link();
			link.setUri(uri);
			l.add(link);
		}
		artifacts.get(artifact).setDownloadLinks(l);
	}

	private static TeaPublishedEntry entryOf(TeaPublishedCollectionData c, UUID artifact) {
		return c.getEntries().stream().filter(e -> artifact.equals(e.artifact())).findFirst().orElseThrow();
	}

	/** The artifact object named {@code name} in a collection document. */
	private static JsonNode artifactObject(String document, String name) {
		for (JsonNode a : Utils.OM.readTree(document).get("artifacts")) {
			if (name.equals(a.get("name").asText())) return a;
		}
		throw new AssertionError("no artifact " + name + " in " + document);
	}

	private static JsonNode pdfFormat(String document) {
		return artifactObject(document, "manual.pdf").get("formats").get(0);
	}

	private static List<String> keys(JsonNode n) {
		return new ArrayList<>(n.propertyNames());
	}

	private List<TeaRideAlongOutcome> rowsOf(TeaPublishResult r, UUID artifact) {
		return r.artifacts().stream().filter(o -> artifact.equals(o.artifact())).toList();
	}

	private void assertPublishedUnsigned(TeaPublishResult r) {
		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		List<TeaRideAlongOutcome> own = rowsOf(r, pdf);
		assertEquals(1, own.size(), own.toString());
		assertEquals(TeaRideAlongStatus.PUBLISHED, own.get(0).status());
		assertNull(own.get(0).detail());
		TeaPublishedEntry e = entryOf(r.collection(), pdf);
		assertNull(e.signatureArtifact());
		assertNull(e.signatureRevision());
		assertNull(e.frozen().formats().get(0).signatureUrl());
		assertFalse(pdfFormat(r.collection().getDocument()).has("signatureUrl"));
		assertFalse(r.collection().getDocument().contains("signatureUrl"), r.collection().getDocument());
	}

	/** The one skip row of the signature, which stands right before the PDF's own row. */
	private TeaRideAlongOutcome skipRow(TeaPublishResult r, TeaRideAlongStatus status) {
		List<TeaRideAlongOutcome> rows = rowsOf(r, sig);
		assertEquals(1, rows.size(), rows.toString());
		assertEquals(status, rows.get(0).status());
		assertEquals(r.artifacts().indexOf(rows.get(0)) + 1, r.artifacts().indexOf(rowsOf(r, pdf).get(0)),
				"the signature's row precedes the format's row: " + r.artifacts());
		return rows.get(0);
	}

	// ---------------------------------------------------------------- row 1

	@Test
	void anExternallyStoredSignatureWithAConformingLinkIsPublishedAsSignatureUrl() throws Exception {
		signedPdf(StoredIn.EXTERNALLY, LINK);

		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		List<TeaRideAlongOutcome> own = rowsOf(r, pdf);
		assertEquals(1, own.size());
		assertEquals(TeaRideAlongStatus.PUBLISHED, own.get(0).status());
		assertNull(own.get(0).detail());
		assertTrue(rowsOf(r, sig).isEmpty(), r.artifacts().toString());
		TeaPublishedEntry e = entryOf(r.collection(), pdf);
		assertEquals(sig, e.signatureArtifact());
		assertEquals(Integer.valueOf(3), e.signatureRevision());
		assertEquals(LINK, e.frozen().formats().get(0).signatureUrl());
		JsonNode format = pdfFormat(r.collection().getDocument());
		assertFalse(format.has("url"));
		assertEquals(LINK, format.get("signatureUrl").asText());
		assertEquals(List.of("mediaType", "signatureUrl", "checksums"), keys(format));
		TeaOpenApiSchemas.assertConforms(artifactObject(r.collection().getDocument(), "manual.pdf").toString(), "artifact");
	}

	// ---------------------------------------------------------------- row 2

	@Test
	void anExternallyStoredSignatureOfAnExternalFormatIsPublishedAsSignatureUrl() throws Exception {
		UUID external = artifact(ArtifactType.RELEASE_NOTES, "notes.html", "1", StoredIn.EXTERNALLY, false, null);
		setLinks(external, "https://example.com/notes.html");
		artifacts.get(external).getDigestRecords().add(new DigestRecord(DigestAlgorithm.SHA_256, "cc33", DigestScope.ORIGINAL_FILE));
		UUID notesSig = artifact(ArtifactType.SIGNATURE, "notes.html.sig", "1", StoredIn.EXTERNALLY, false, null);
		setLinks(notesSig, "https://example.com/notes.html.sig");
		artifacts.get(external).setArtifacts(new ArrayList<>(List.of(notesSig)));
		setInventory(release, List.of(inventoryBom, external));
		profile.setRawArtifacts(TeaRawArtifacts.ALL);

		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		TeaPublishedEntry e = entryOf(r.collection(), external);
		assertEquals("https://example.com/notes.html", e.url());
		assertEquals(notesSig, e.signatureArtifact());
		JsonNode format = artifactObject(r.collection().getDocument(), "notes.html").get("formats").get(0);
		assertEquals("https://example.com/notes.html", format.get("url").asText());
		assertEquals("https://example.com/notes.html.sig", format.get("signatureUrl").asText());
		assertEquals(List.of("mediaType", "url", "signatureUrl", "checksums"), keys(format));
	}

	// ---------------------------------------------------------------- row 3

	@Test
	void aRearmStoredSignatureIsUnchanged() throws Exception {
		signedPdf(StoredIn.REARM, LINK);

		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		TeaPublishedEntry e = entryOf(r.collection(), pdf);
		assertEquals(sig, e.signatureArtifact());
		assertEquals(Integer.valueOf(3), e.signatureRevision());
		assertNull(e.frozen().formats().get(0).signatureUrl());
		assertEquals(List.of("mediaType", "checksums"), keys(pdfFormat(r.collection().getDocument())));
		assertFalse(r.collection().getDocument().contains("signatureUrl"));
		assertTrue(rowsOf(r, sig).isEmpty(), "a hosted signature is no outcome row of its own");
	}

	/**
	 * Decision 2: a signature row is external only when it says so. A row whose storedIn is null is hosted, even
	 * with a conforming link: it keeps signatureArtifact and signatureRevision and gets no signatureUrl.
	 */
	@Test
	void aSignatureRowWithNoStoredInIsHosted() throws Exception {
		captureServiceWarnings();
		signedPdf(null, LINK);
		assertNull(artifacts.get(sig).getStoredIn());

		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		TeaPublishedEntry e = entryOf(r.collection(), pdf);
		assertEquals(sig, e.signatureArtifact());
		assertEquals(Integer.valueOf(3), e.signatureRevision());
		assertNull(e.frozen().formats().get(0).signatureUrl());
		assertEquals(List.of("mediaType", "checksums"), keys(pdfFormat(r.collection().getDocument())));
		assertFalse(r.collection().getDocument().contains("signatureUrl"));
		assertFalse(r.collection().getDocument().contains(LINK), "the link of a hosted signature is never frozen");
		assertTrue(rowsOf(r, sig).isEmpty(), "a hosted signature is no outcome row of its own");
		assertTrue(capturedWarnings().isEmpty(), capturedWarnings().toString());
	}

	// ---------------------------------------------------------------- row 4

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void anExternallyStoredSignatureWithoutALinkIsSkippedAndTheFormatPublishesUnsigned(boolean blankLink)
			throws Exception {
		captureServiceWarnings();
		if (blankLink) signedPdf(StoredIn.EXTERNALLY, " ");
		else signedPdf(StoredIn.EXTERNALLY);

		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		assertPublishedUnsigned(r);
		TeaRideAlongOutcome skip = skipRow(r, TeaRideAlongStatus.SKIPPED_NO_DOWNLOAD_LINK);
		assertEquals("signature manual.pdf.sig (" + sig + ") is stored externally without a download link", skip.detail());
		assertTrue(skip.detail().startsWith("signature manual.pdf.sig ("), skip.detail());
		List<String> warnings = capturedWarnings();
		assertEquals(1, warnings.size(), warnings.toString());
		assertTrue(warnings.get(0).contains(sig.toString()), warnings.get(0));
		assertTrue(warnings.get(0).contains(pdf.toString()), warnings.get(0));
		assertTrue(warnings.get(0).contains("not published: SKIPPED_NO_DOWNLOAD_LINK"), warnings.get(0));
		assertFalse(warnings.get(0).contains("https"), warnings.get(0));
	}

	// ---------------------------------------------------------------- row 5

	static Stream<Arguments> badLinks() {
		String tooLong = "https://files.example/" + "a".repeat(4075);
		return Stream.of(
				Arguments.of("/manual.pdf.sig",
						"download link /manual.pdf.sig: is relative; an absolute http or https url is required"),
				Arguments.of("javascript:alert(1)", "download link javascript:alert(1): has a scheme other than http or https"),
				Arguments.of("https://user@files.example/x.sig", "download link https://user@files.example/x.sig: carries "
						+ "user information before the host, which is never published"),
				Arguments.of(tooLong, "download link " + tooLong.substring(0, 256)
						+ "...: is 4097 octets, over the 4096 this server publishes"),
				Arguments.of("https://files.example/na\u00efve.sig", "download link https://files.example/na?ve.sig: has a "
						+ "character outside printable US-ASCII at index 24 (U+00EF); percent-encode it"),
				// checked and frozen as stored: a padded link is refused by the rule, never trimmed into a pass
				Arguments.of(" " + LINK, "download link  " + LINK + ": has a character outside printable US-ASCII at "
						+ "index 0 (U+0020); percent-encode it"),
				Arguments.of(LINK + " ", "download link " + LINK + " : has a character outside printable US-ASCII at "
						+ "index 36 (U+0020); percent-encode it"));
	}

	@ParameterizedTest
	@MethodSource("badLinks")
	void anExternallyStoredSignatureWhoseLinkIsNotAnHttpUrlIsSkippedByTheUrlRule(String link, String described)
			throws Exception {
		captureServiceWarnings();
		signedPdf(StoredIn.EXTERNALLY, link);

		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		assertPublishedUnsigned(r);
		TeaRideAlongOutcome skip = skipRow(r, TeaRideAlongStatus.SKIPPED_LINK_NOT_HTTP_URL);
		assertEquals("signature manual.pdf.sig (" + sig + "): " + described, skip.detail());
		assertFalse(r.collection().getDocument().contains(link), "the refused link is never frozen");
		List<String> warnings = capturedWarnings();
		assertEquals(1, warnings.size(), warnings.toString());
		assertTrue(warnings.get(0).contains("not published: SKIPPED_LINK_NOT_HTTP_URL"), warnings.get(0));
		assertTrue(warnings.get(0).contains(sig.toString()), warnings.get(0));
		assertFalse(warnings.get(0).contains(link), warnings.get(0));
		assertFalse(warnings.get(0).contains("download link"), warnings.get(0));
	}

	// ---------------------------------------------------------------- row 6

	@Test
	void theSecondSignatureLinkIsNeverTried() throws Exception {
		signedPdf(StoredIn.EXTERNALLY, "javascript:alert(1)", "https://files.example/x.sig");

		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		assertPublishedUnsigned(r);
		assertEquals("signature manual.pdf.sig (" + sig + "): download link javascript:alert(1): has a scheme other "
				+ "than http or https", skipRow(r, TeaRideAlongStatus.SKIPPED_LINK_NOT_HTTP_URL).detail());
		assertFalse(r.collection().getDocument().contains("https://files.example/x.sig"));
	}

	@Test
	void aBlankFirstSignatureLinkIsNoLinkAndTheNextIsPublished() throws Exception {
		signedPdf(StoredIn.EXTERNALLY, "", "https://files.example/x.sig");

		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		assertTrue(rowsOf(r, sig).isEmpty(), r.artifacts().toString());
		assertEquals("https://files.example/x.sig", entryOf(r.collection(), pdf).frozen().formats().get(0).signatureUrl());
		assertEquals("https://files.example/x.sig", pdfFormat(r.collection().getDocument()).get("signatureUrl").asText());
	}

	// ---------------------------------------------------------------- row 7

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void aSignatureRowInTheInventoryGetsBothRows(boolean conforming) throws Exception {
		captureServiceWarnings();
		if (conforming) signedPdf(StoredIn.EXTERNALLY, LINK);
		else signedPdf(StoredIn.EXTERNALLY);
		setInventory(release, List.of(inventoryBom, pdf, sig));

		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		List<TeaRideAlongStatus> sigRows = rowsOf(r, sig).stream().map(TeaRideAlongOutcome::status).toList();
		if (conforming) {
			assertEquals(List.of(TeaRideAlongStatus.SKIPPED_SIGNATURE_TYPE), sigRows);
			assertEquals(LINK, entryOf(r.collection(), pdf).frozen().formats().get(0).signatureUrl());
			assertEquals(1, capturedWarnings().size(), capturedWarnings().toString());
		} else {
			assertEquals(List.of(TeaRideAlongStatus.SKIPPED_NO_DOWNLOAD_LINK, TeaRideAlongStatus.SKIPPED_SIGNATURE_TYPE),
					sigRows, "the signature pass runs inside the PDF's turn, before the inventory reaches the row");
			assertEquals(2, capturedWarnings().size(), capturedWarnings().toString());
		}
	}

	// ---------------------------------------------------------------- row 8

	@Test
	void aSignatureUrlIsFrozenAndARepublishAtTheSameRevisionIsUnchanged() throws Exception {
		signedPdf(StoredIn.EXTERNALLY, LINK);
		TeaPublishResult first = publish(TeaPublishMode.PUBLISH);
		UUID publication = first.publication().getUuid();

		setLinks(sig, "https://elsewhere.example/moved.sig");
		TeaPublishResult same = publish(TeaPublishMode.REPUBLISH);

		assertEquals(TeaPublishOutcome.UNCHANGED, same.outcome());
		assertEquals(LINK, pdfFormat(collectionRows.get(publication + ":1").getDocument()).get("signatureUrl").asText());
		assertNull(collectionRows.get(publication + ":2"));

		artifacts.get(pdf).setVersion("3");
		TeaPublishResult bumped = publish(TeaPublishMode.REPUBLISH);

		assertEquals(TeaPublishOutcome.REPUBLISHED, bumped.outcome());
		assertEquals(2, bumped.collection().getVersion());
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_UPDATED, bumped.collection().getUpdateReasonType());
		assertEquals("https://elsewhere.example/moved.sig",
				pdfFormat(collectionRows.get(publication + ":2").getDocument()).get("signatureUrl").asText());
		assertEquals(LINK, pdfFormat(collectionRows.get(publication + ":1").getDocument()).get("signatureUrl").asText());
	}

	// ---------------------------------------------------------------- row 9 (design 3.5)

	@Test
	void aPreRuleEntryStandsUntilTheContentRowIsRevised() throws Exception {
		signedPdf(StoredIn.EXTERNALLY, LINK);
		UUID publication = publish(TeaPublishMode.PUBLISH).publication().getUuid();
		// version 1 rewritten as the TEA-5 engine froze it: signatureArtifact set, no signatureUrl
		TeaPublishedCollection row = collectionRows.get(publication + ":1");
		TeaPublishedCollectionData d = TeaPublishedCollectionData.dataFromRecord(row);
		d.setEntries(d.getEntries().stream().map(e -> pdf.equals(e.artifact()) ? preRule(e) : e).toList());
		row.setRecordData(d.toRecordData());
		row.setDocument(row.getDocument().replace("\"signatureUrl\":\"" + LINK + "\",", ""));
		String preRuleDocument = row.getDocument();
		assertFalse(preRuleDocument.contains("signatureUrl"), preRuleDocument);

		TeaPublishResult same = publish(TeaPublishMode.REPUBLISH);

		assertEquals(TeaPublishOutcome.UNCHANGED, same.outcome());
		assertEquals(preRuleDocument, collectionRows.get(publication + ":1").getDocument());
		assertNull(collectionRows.get(publication + ":2"));
		TeaPublishedEntry stands = entryOf(TeaPublishedCollectionData.dataFromRecord(row), pdf);
		assertEquals(sig, stands.signatureArtifact());
		assertNull(stands.frozen().formats().get(0).signatureUrl());

		artifacts.get(pdf).setVersion("3");
		TeaPublishResult revised = publish(TeaPublishMode.REPUBLISH);

		assertEquals(TeaPublishOutcome.REPUBLISHED, revised.outcome());
		assertEquals(LINK, pdfFormat(collectionRows.get(publication + ":2").getDocument()).get("signatureUrl").asText());
		assertEquals(LINK, entryOf(revised.collection(), pdf).frozen().formats().get(0).signatureUrl());
	}

	private static TeaPublishedEntry preRule(TeaPublishedEntry e) {
		TeaArtifactDocument f = e.frozen();
		TeaArtifactFormatDocument fmt = f.formats().get(0);
		TeaArtifactDocument frozen = new TeaArtifactDocument(f.uuid(), f.version(), f.name(), f.type(), f.createdDate(),
				List.of(new TeaArtifactFormatDocument(fmt.mediaType(), fmt.description(), fmt.url(), null, fmt.checksums())));
		return new TeaPublishedEntry(e.artifact(), e.teaUuid(), e.revision(), e.origin(), e.teaType(), e.name(),
				e.mediaType(), e.checksums(), e.url(), e.signatureArtifact(), e.signatureRevision(), frozen);
	}

	// ---------------------------------------------------------------- row 10 (the cascade)

	@Test
	void aCascadeChildWithAnExternalSignatureIsPublishedNotRefused() throws Exception {
		captureServiceWarnings();
		signedPdf(StoredIn.EXTERNALLY);
		// cascadeKnobs takes the narrower rawArtifacts of the two profiles: both ALL, so the ride-alongs run
		profile.setRawArtifacts(TeaRawArtifacts.ALL);
		TeaProfileData productProfile = profile(TeaVisibility.PUBLIC);
		productProfile.setRawArtifacts(TeaRawArtifacts.ALL);
		ReleaseData product = productOver("platform", resolvedFromOrg(productProfile), List.of(release.getUuid()));

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		TeaChildPublishResult c = r.children().stream().filter(x -> release.getUuid().equals(x.release())).findFirst()
				.orElseThrow();
		assertEquals(TeaChildOutcome.PUBLISHED, c.outcome());
		assertNotNull(c.publication());
		TeaPublishedCollection row = collectionRows.get(c.publication() + ":1");
		assertNotNull(row);
		TeaPublishedCollectionData stored = service.getLatestCollection(head().getUuid()).orElseThrow();
		TeaPublishedEntry e = entryOf(stored, pdf);
		assertNull(e.signatureArtifact());
		assertNull(e.signatureRevision());
		assertNull(e.frozen().formats().get(0).signatureUrl());
		assertFalse(row.getDocument().contains("signatureUrl"), row.getDocument());
		List<String> skips = capturedWarnings().stream().filter(w -> w.contains("SKIPPED_NO_DOWNLOAD_LINK")).toList();
		assertEquals(1, skips.size(), skips.toString());
		assertTrue(skips.get(0).contains(sig.toString()), skips.get(0));
		assertFalse(r.artifacts().stream().anyMatch(a -> sig.equals(a.artifact())),
				"the product's own result lists the top release's ride-alongs only");
	}
}
