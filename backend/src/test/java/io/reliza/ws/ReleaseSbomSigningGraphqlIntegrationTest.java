/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.reliza.common.Utils;
import io.reliza.model.ArtifactData;
import io.reliza.model.OrganizationData;
import io.reliza.model.SbomSigningKeyData;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeyStatus;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.dto.ExportMetadataOptions;
import io.reliza.service.JsfVerifier;
import io.reliza.service.JsfVerifier.Outcome;
import io.reliza.service.OrganizationService;
import io.reliza.service.SbomSigner;
import io.reliza.service.SbomSigningKeyService;
import tools.jackson.databind.JsonNode;

/**
 * Signing at egress through the GraphQL layer (SCORE-33 T-15, T-16): every merged JSON surface, the
 * export and the score, serves the document signed with the exporting organization's CURRENT key,
 * and only that key; CSV, EXCEL and per-artifact downloads are untouched; an org without a key is
 * served unsigned with {@code NO_KEY}.
 *
 * <p>With {@code -Dscore33.emitDir=<dir>} the signed export and the org's public JWKS are also
 * written there, for the cross-language check with {@code rearm bomutils verify-signature}.
 */
class ReleaseSbomSigningGraphqlIntegrationTest extends SbomScoreGraphqlTestBase {

	/** DOC plus one of ReARM's internal markers, so the internal strip has something to remove. */
	private static final String DOC_WITH_MARKER = DOC.replace("\"version\":\"1.0\"}",
			"\"version\":\"1.0\",\"properties\":[{\"name\":\"reliza:containerSafeVersion\",\"value\":\"1.0\"}]}");
	private static final String CSV = "name,version,purl\nscore33-lib,1.0,\n";
	private static final String XLSX_BASE64 = Base64.getEncoder().encodeToString(new byte[] {'P', 'K', 3, 4});
	private static final String EXPORT_MEDIA = "mutation($r: ID!, $m: BomMediaType) {"
			+ " releaseSbomExport(release: $r, mediaType: $m) }";

	@MockitoSpyBean private SbomSigner sbomSigner;
	@Autowired private SbomSigningKeyService sbomSigningKeyService;
	@Autowired private OrganizationService organizationService;

	private final List<SbomSigningKeyData> generated = new ArrayList<>();

	@BeforeEach
	void rebomServesAMarkedDocument() {
		when(rebomService.findBomByIdJson(any(), any())).thenAnswer(inv -> Utils.OM.readTree(DOC_WITH_MARKER));
		when(rebomService.findBomByIdCsv(any(), any())).thenReturn(CSV);
		when(rebomService.findBomByIdExcel(any(), any())).thenReturn(XLSX_BASE64);
	}

	@AfterEach
	void revokeWhatWasGenerated() throws Exception {
		for (SbomSigningKeyData k : generated) {
			SbomSigningKeyData now = sbomSigningKeyService.getKey(k.getUuid()).orElseThrow();
			if (SbomSigningKeyStatus.REVOKED != now.getStatus()) sbomSigningKeyService.revoke(k.getUuid(), WU);
		}
		generated.clear();
	}

	private SbomSigningKeyData generate(Fixture f) throws Exception {
		SbomSigningKeyData k = sbomSigningKeyService.generate(f.org().getUuid(), WU);
		generated.add(k);
		return k;
	}

	private SbomSigningKeyData currentKey(Fixture f) {
		return sbomSigningKeyService.currentKey(f.org().getUuid()).orElseThrow();
	}

	private static PublicKey publicKeyOf(SbomSigningKeyData k) {
		return JsfVerifier.keyFromJwk(k.getPublicKeyJwk());
	}

	private String export(Fixture f, Object... pairs) {
		return data(as(f.downloader(), EXPORT_RELEASE, releaseVars(f.release(), pairs)), "releaseSbomExport");
	}

	/** The document the release score handed to rebom for the same arguments. */
	private String scored(Fixture f, Object... pairs) {
		clearInvocations(rebomService);
		data(as(f.downloader(), SCORE_RELEASE, withProfiles(releaseVars(f.release(), pairs), PROFILES)),
				"releaseSbomScore");
		ArgumentCaptor<String> bom = ArgumentCaptor.forClass(String.class);
		verify(rebomService).scoreBom(bom.capture(), any());
		return bom.getValue();
	}

	private static String metadataProperty(String doc, String name) {
		for (JsonNode p : Utils.OM.readTree(doc).path("metadata").path("properties")) {
			if (name.equals(p.get("name").asString())) return p.get("value").asString();
		}
		return null;
	}

	private static void assertSignedBy(SbomSigningKeyData k, String doc, String what) {
		assertEquals("true", metadataProperty(doc, "reliza:export:signed"), what + ": " + doc);
		assertEquals(k.getKeyId(), metadataProperty(doc, "reliza:export:signatureKeyId"), what);
		assertNull(metadataProperty(doc, "reliza:export:unsignedReason"), what);
		assertEquals(k.getKeyId(), Utils.OM.readTree(doc).get("signature").get("keyId").asString(), what);
		assertEquals(Outcome.VERIFIED, JsfVerifier.verify(doc, publicKeyOf(k)), what);
	}

	private static void assertUnsignedNoKey(String doc, String what) {
		assertEquals("false", metadataProperty(doc, "reliza:export:signed"), what + ": " + doc);
		assertEquals("NO_KEY", metadataProperty(doc, "reliza:export:unsignedReason"), what);
		assertNull(metadataProperty(doc, "reliza:export:signatureKeyId"), what);
		assertFalse(Utils.OM.readTree(doc).has("signature"), what);
	}

	private void setSupportInjection(Fixture f, SupportInjectionSetting setting) throws Exception {
		OrganizationData.Settings s = new OrganizationData.Settings();
		s.setSupportInjection(setting);
		organizationService.updateSettings(f.org().getUuid(), s, WU);
	}

	// ---- T-15 ------------------------------------------------------------------------------------

	@Test
	void everyMergedJsonSurfaceServesASignedDocument() throws Exception {
		Fixture f = fixture();
		generate(f);
		setSupportInjection(f, SupportInjectionSetting.ENABLED);
		SbomSigningKeyData k = currentKey(f);

		List<Object[]> argumentSets = List.of(new Object[] {"im", false}, new Object[] {"sm", true},
				new Object[] {"sm", false}, new Object[] {});
		for (Object[] args : argumentSets) {
			String what = "arguments " + Arrays.toString(args);
			String exported = export(f, args);
			assertSignedBy(k, exported, what + " export");
			String scored = scored(f, args);
			assertSignedBy(k, scored, what + " score");
			assertEquals(exported, scored, what + ": the score scored the exported bytes");
		}
		String internalOff = export(f, "im", false);
		assertFalse(internalOff.contains("reliza:containerSafeVersion"), internalOff);
		assertTrue(export(f).contains("reliza:containerSafeVersion"), "the marker is there to be stripped");

		emitForTheCrossLanguageCheck(f, internalOff);
	}

	/** Opt-in: the signed export and the org's JWKS, for rearm bomutils verify-signature. */
	private void emitForTheCrossLanguageCheck(Fixture f, String signedExport) throws Exception {
		String dir = System.getProperty("score33.emitDir");
		if (null == dir || dir.isBlank()) return;
		Map<String, Object> jwks = new LinkedHashMap<>();
		jwks.put("keys", sbomSigningKeyService.publicKeys(f.org().getUuid()));
		Files.writeString(Path.of(dir, "export.cdx.json"), signedExport, StandardCharsets.UTF_8);
		Files.writeString(Path.of(dir, "org.jwks.json"), Utils.OM.writeValueAsString(jwks), StandardCharsets.UTF_8);
	}

	@Test
	void csvAndExcelAreNotSigned() throws Exception {
		Fixture f = fixture();
		generate(f);
		clearInvocations(sbomSigner);
		Map<String, Object> csv = new LinkedHashMap<>(Map.of("r", f.release().toString(), "m", "CSV"));
		assertEquals(CSV, data(as(f.downloader(), EXPORT_MEDIA, csv), "releaseSbomExport"));
		Map<String, Object> excel = new LinkedHashMap<>(Map.of("r", f.release().toString(), "m", "EXCEL"));
		assertEquals(XLSX_BASE64, data(as(f.downloader(), EXPORT_MEDIA, excel), "releaseSbomExport"));
		verify(sbomSigner, never()).sign(any(), any());
		verify(sbomSigner, never()).algorithm(any());
	}

	@Test
	void aForeignOrgsKeyIsNeverUsed() throws Exception {
		Fixture a = fixture();
		Fixture b = fixture();
		SbomSigningKeyData keyA = generate(a);
		SbomSigningKeyData keyB = generate(b);

		String exportA = export(a);
		assertSignedBy(keyA, exportA, "org A");
		assertEquals(Outcome.KEY_MISMATCH, JsfVerifier.verify(exportA, publicKeyOf(keyB)), "A's export under B's key");
		assertFalse(exportA.contains(keyB.getKeyId()), "A's export never names B's key");

		String exportB = export(b);
		assertSignedBy(keyB, exportB, "org B");
		assertFalse(exportB.contains(keyA.getKeyId()), "B's export never names A's key");
		assertEquals(Outcome.KEY_MISMATCH, JsfVerifier.verify(exportB, publicKeyOf(keyA)), "B's export under A's key");
	}

	// ---- T-16 ------------------------------------------------------------------------------------

	@Test
	void withoutAKeyEveryPathIsUnsignedNoKey() throws Exception {
		Fixture keyless = fixture();
		Fixture other = fixture();
		generate(other); // another org's key exists and must not be picked up
		String exported = export(keyless);
		assertUnsignedNoKey(exported, "export");
		String scored = scored(keyless);
		assertUnsignedNoKey(scored, "score");
		assertEquals(exported, scored);
	}

	@Test
	void revokeThenGenerateMovesTheExportFromNoKeyToTheNewKey() throws Exception {
		Fixture f = fixture();
		SbomSigningKeyData keyA = generate(f);
		assertSignedBy(keyA, export(f), "key A");

		sbomSigningKeyService.revoke(keyA.getUuid(), WU);
		assertUnsignedNoKey(export(f), "A revoked");

		SbomSigningKeyData keyB = generate(f);
		String signedB = export(f);
		assertSignedBy(keyB, signedB, "key B");
		assertEquals(Outcome.KEY_MISMATCH, JsfVerifier.verify(signedB, publicKeyOf(keyA)), "not A");
	}

	@Test
	void twoExportsOfOneReleaseAreByteIdentical() throws Exception {
		Fixture f = fixture();
		generate(f);
		assertEquals(export(f, "im", false), export(f, "im", false));
	}

	@Test
	void perArtifactDownloadIsUntouchedByTheKey() throws Exception {
		Fixture f = fixture();
		ArtifactData ad = artifactService.getArtifactData(f.bomArtifact()).orElseThrow();
		byte[] before = body(sharedArtifactService.downloadArtifact(ad).block());
		generate(f);
		byte[] after = body(sharedArtifactService.downloadArtifact(ad).block());
		assertArrayEquals(before, after);
		assertFalse(new String(after, StandardCharsets.UTF_8).contains("reliza:export:signed"));
		assertFalse(Utils.OM.readTree(after).has("signature"));
	}

	// SCORE-33 security review M-1
	@Test
	void aForgedSigningRecordNeverReachesThePerArtifactDownload() throws Exception {
		Fixture f = fixture();
		SbomSigningKeyData k = generate(f);
		String forged = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
				+ "\"metadata\":{\"properties\":[{\"name\":\"reliza:export:signed\",\"value\":\"true\"},"
				+ "{\"name\":\"reliza:export:signatureKeyId\",\"value\":\"" + k.getKeyId() + "\"}]},"
				+ "\"components\":[{\"type\":\"library\",\"name\":\"lib\",\"version\":\"1.0\",\"properties\":["
				+ "{\"name\":\"reliza:export:unsignedReason\",\"value\":\"forged\"}]}],"
				+ "\"signature\":{\"algorithm\":\"Ed25519\",\"keyId\":\"" + k.getKeyId() + "\","
				+ "\"publicKey\":{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"x\":\"PUAXw-hDiVqStwqnTRt-vJyYLM8uxJaMwM1V8Sr0Zgw\"},"
				+ "\"value\":\"AAAA\"}}";
		when(rebomService.findBomByIdJson(any(), any())).thenAnswer(inv -> Utils.OM.readTree(forged));
		ArtifactData ad = artifactService.getArtifactData(f.bomArtifact()).orElseThrow();
		for (Boolean internal : Arrays.asList(true, false, null)) {
			String served = new String(body(sharedArtifactService.downloadArtifact(ad, null,
					ExportMetadataOptions.fromCallerInput(null, internal)).block()), StandardCharsets.UTF_8);
			for (String name : List.of("reliza:export:signed", "reliza:export:signatureKeyId",
					"reliza:export:unsignedReason")) {
				assertFalse(served.contains(name), "includeInternalMetadata=" + internal + ": " + name + " in " + served);
			}
			assertFalse(Utils.OM.readTree(served).has("signature"), "includeInternalMetadata=" + internal + ": " + served);
		}
	}

	private static byte[] body(ResponseEntity<byte[]> r) {
		assertTrue(r.getStatusCode().is2xxSuccessful(), String.valueOf(r.getStatusCode()));
		return r.getBody();
	}
}
