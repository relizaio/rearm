/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.cyclonedx.model.Hash;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.service.CdxImportService;

/** Task TEA-6, design 4.2 case 3: the stored digest vocabulary survives the TEA 1.0.0 checksum enum. */
class DigestAlgorithmTest {

	/** The strings the TEA 0.4.0 generated enum wrote into every stored digest record. */
	private static final Map<DigestAlgorithm, String> STORED = new LinkedHashMap<>();
	static {
		STORED.put(DigestAlgorithm.MD5, "MD5");
		STORED.put(DigestAlgorithm.SHA_1, "SHA-1");
		STORED.put(DigestAlgorithm.SHA_256, "SHA-256");
		STORED.put(DigestAlgorithm.SHA_384, "SHA-384");
		STORED.put(DigestAlgorithm.SHA_512, "SHA-512");
		STORED.put(DigestAlgorithm.SHA3_256, "SHA3-256");
		STORED.put(DigestAlgorithm.SHA3_384, "SHA3-384");
		STORED.put(DigestAlgorithm.SHA3_512, "SHA3-512");
		STORED.put(DigestAlgorithm.BLAKE2B_256, "BLAKE2b-256");
		STORED.put(DigestAlgorithm.BLAKE2B_384, "BLAKE2b-384");
		STORED.put(DigestAlgorithm.BLAKE2B_512, "BLAKE2b-512");
		STORED.put(DigestAlgorithm.BLAKE3, "BLAKE3");
	}

	@Test
	void everyConstantWritesAndReadsTheStoredString() {
		assertEquals(12, DigestAlgorithm.values().length);
		for (Map.Entry<DigestAlgorithm, String> e : STORED.entrySet()) {
			assertEquals("\"" + e.getValue() + "\"", Utils.OM.writeValueAsString(e.getKey()));
			assertEquals(e.getKey(), Utils.OM.readValue("\"" + e.getValue() + "\"", DigestAlgorithm.class));
			assertEquals(e.getKey(), DigestAlgorithm.fromValue(e.getKey().name()), "the constant name reads too");
		}
	}

	@Test
	void toTeaMapsByTheStringAndMd5NotAtAll() {
		assertTrue(DigestAlgorithm.MD5.toTea().isEmpty(), "TEA 1.0.0 cannot express MD5");
		for (DigestAlgorithm a : DigestAlgorithm.values()) {
			if (a == DigestAlgorithm.MD5) continue;
			TeaChecksumType t = a.toTea().orElseThrow();
			assertEquals(a.getValue(), t.getValue());
		}
	}

	@Test
	void theGraphqlEnumListsExactlyTheConstantNames() throws Exception {
		String schema = Files.readString(Path.of("src/main/resources/schema/schema.graphqls"));
		Matcher m = Pattern.compile("enum\\s+TeaArtifactChecksumType\\s*\\{([^}]*)\\}").matcher(schema);
		assertTrue(m.find(), "TeaArtifactChecksumType not found in schema.graphqls");
		Set<String> gql = Arrays.stream(m.group(1).split("\\s+")).map(String::trim)
				.filter(s -> !s.isEmpty() && !s.startsWith("#")).collect(Collectors.toSet());
		Set<String> java = Arrays.stream(DigestAlgorithm.values()).map(Enum::name).collect(Collectors.toSet());
		assertEquals(java, gql);
	}

	@Test
	void aStoredMd5DigestRecordStillReads() {
		Map<String, Object> record = Map.of("digestRecords", List.of(Map.of("algo", "MD5",
				"digest", "9e107d9d372bb6826bd81d3542a419d6", "scope", "ORIGINAL_FILE")));
		ArtifactData ad = Utils.OM.convertValue(record, ArtifactData.class);
		DigestRecord dr = ad.getDigestRecords().iterator().next();
		assertEquals(DigestAlgorithm.MD5, dr.algo());
		assertEquals(DigestScope.ORIGINAL_FILE, dr.scope());
	}

	// 4
	@Test
	void userAndCycloneDxMd5InputStillMeansMd5() {
		assertEquals(DigestAlgorithm.MD5, Utils.parseDigestType("md5"));
		CdxImportService cdx = new CdxImportService();
		assertEquals(DigestAlgorithm.MD5, ReflectionTestUtils.invokeMethod(cdx, "mapHashAlgorithm", Hash.Algorithm.MD5));
		assertEquals(DigestAlgorithm.MD5, ReflectionTestUtils.invokeMethod(cdx, "mapHashAlgorithm", "MD5"));
	}
}
