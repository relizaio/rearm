/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import graphql.Assert;
import io.reliza.common.Utils;
import io.reliza.common.Utils.UuidDiff;
import io.reliza.exceptions.RelizaException;

/**
 * Unit test related to Utils functionality
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
public class UtilTest 
{
	
	@Test
	public void testIsStringUuid() throws RelizaException {
		String validUuid1 = "03053cfd-ef37-45ea-91c4-72b0f5191e34";
		boolean isValid1 = Utils.isStringUuid(validUuid1);
		Assert.assertTrue(isValid1);
		String invalidUuid2 = "a03053cfd-ef37-45ea-91c4-72b0f5191e34";
		boolean isValid2 = Utils.isStringUuid(invalidUuid2);
		Assert.assertFalse(isValid2);
	}
	
	@Test
	public void testDiffUuidLists() {
		List<UUID> origList = List.of(UUID.fromString("c3d94c5a-df7d-46c8-a9cc-ee835fb67580"));
		List<UUID> updList = List.of(UUID.fromString("c3d94c5a-df7d-46c8-a9cc-ee835fb67580"),
				UUID.fromString("3d44f1b2-8206-4aac-aaa4-01ed7749b3f8"));
		List<UuidDiff> diffList = Utils.diffUuidLists(origList, updList);
		Assertions.assertEquals(1, diffList.size());
		Assertions.assertEquals(diffList.get(0).object(), UUID.fromString("3d44f1b2-8206-4aac-aaa4-01ed7749b3f8"));
	}
	
	@Test
	public void testMinimizePurl_withQualifiers() {
		// Test Maven PURL with qualifiers
		String mavenPurl = "pkg:maven/org.springframework/spring-core@5.3.0?type=jar&classifier=sources";
		String minimized = Utils.minimizePurl(mavenPurl);
		Assertions.assertEquals("pkg:maven/org.springframework/spring-core@5.3.0", minimized);
	}
	
	@Test
	public void testMinimizePurl_npmWithQualifiers() {
		// Test NPM PURL with multiple qualifiers
		String npmPurl = "pkg:npm/lodash@4.17.21?arch=x64&os=linux";
		String minimized = Utils.minimizePurl(npmPurl);
		Assertions.assertEquals("pkg:npm/lodash@4.17.21", minimized);
	}
	
	@Test
	public void testMinimizePurl_pypiWithQualifiers() {
		// Test PyPI PURL with extension qualifier
		String pypiPurl = "pkg:pypi/requests@2.28.0?extension=tar.gz";
		String minimized = Utils.minimizePurl(pypiPurl);
		Assertions.assertEquals("pkg:pypi/requests@2.28.0", minimized);
	}
	
	@Test
	public void testMinimizePurl_withoutQualifiers() {
		// Test PURL that already has no qualifiers
		String purePurl = "pkg:npm/express@4.18.0";
		String minimized = Utils.minimizePurl(purePurl);
		Assertions.assertEquals("pkg:npm/express@4.18.0", minimized);
	}
	
	@Test
	public void testMinimizePurl_withNamespace() {
		// Test PURL with namespace and qualifiers
		String namespacePurl = "pkg:maven/com.google.guava/guava@31.0-jre?type=jar";
		String minimized = Utils.minimizePurl(namespacePurl);
		Assertions.assertEquals("pkg:maven/com.google.guava/guava@31.0-jre", minimized);
	}
	
	@Test
	public void testMinimizePurl_withSubpath() {
		// Test PURL with qualifiers and subpath (qualifiers must come before subpath in PURL spec)
		String subpathPurl = "pkg:golang/github.com/gorilla/mux@1.8.0?os=linux#pkg/mux";
		String minimized = Utils.minimizePurl(subpathPurl);
		Assertions.assertEquals("pkg:golang/github.com/gorilla/mux@1.8.0#pkg/mux", minimized);
	}
	
	@Test
	public void testMinimizePurl_nullInput() {
		// Test null input
		String minimized = Utils.minimizePurl(null);
		Assertions.assertNull(minimized);
	}
	
	@Test
	public void testMinimizePurl_emptyString() {
		// Test empty string input
		String minimized = Utils.minimizePurl("");
		Assertions.assertNull(minimized);
	}
	
	@Test
	public void testMinimizePurl_invalidPurl() {
		// Test invalid PURL format
		String invalidPurl = "not-a-valid-purl";
		String minimized = Utils.minimizePurl(invalidPurl);
		Assertions.assertNull(minimized);
	}
	
	@Test
	public void testMinimizePurl_noVersion() {
		// Test PURL without version but with qualifiers
		String noVersionPurl = "pkg:npm/lodash?arch=x64";
		String minimized = Utils.minimizePurl(noVersionPurl);
		Assertions.assertEquals("pkg:npm/lodash", minimized);
	}
	
	@Test
	public void testMinimizePurl_multipleQualifiers() {
		// Test PURL with many qualifiers
		String multiQualPurl = "pkg:nuget/Newtonsoft.Json@13.0.1?arch=x64&os=windows&runtime=net6.0&classifier=debug";
		String minimized = Utils.minimizePurl(multiQualPurl);
		Assertions.assertEquals("pkg:nuget/Newtonsoft.Json@13.0.1", minimized);
	}

	// canonicalizePurl must produce the exact canonical_purl form rebom computes
	// (see PRESERVED_QUALIFIERS in rebom's bomComponentExtractor.ts) — these
	// mirror rebom's unit tests for the shared cases.

	@Test
	public void testCanonicalizePurl_apkPreservesDistroStripsArch() {
		String purl = "pkg:apk/alpine/openssl@3.5.7-r0?arch=x86_64&distro=alpine-3.24.1";
		Assertions.assertEquals("pkg:apk/alpine/openssl@3.5.7-r0?distro=alpine-3.24.1",
				Utils.canonicalizePurl(purl));
	}

	@Test
	public void testCanonicalizePurl_debPreservesDistro() {
		String purl = "pkg:deb/debian/curl@7.50.3-1?arch=i386&distro=debian-9";
		Assertions.assertEquals("pkg:deb/debian/curl@7.50.3-1?distro=debian-9",
				Utils.canonicalizePurl(purl));
	}

	@Test
	public void testCanonicalizePurl_rpmPreservesDistroAndEpoch() {
		String purl = "pkg:rpm/fedora/curl@7.50.3-1.fc25?arch=i386&distro=fedora-25&epoch=1";
		Assertions.assertEquals("pkg:rpm/fedora/curl@7.50.3-1.fc25?distro=fedora-25&epoch=1",
				Utils.canonicalizePurl(purl));
	}

	@Test
	public void testCanonicalizePurl_apkWithoutDistroStaysBare() {
		String purl = "pkg:apk/alpine/openssl@3.5.7-r0?arch=x86_64";
		Assertions.assertEquals("pkg:apk/alpine/openssl@3.5.7-r0", Utils.canonicalizePurl(purl));
	}

	@Test
	public void testCanonicalizePurl_juliaPreservesUuid() {
		String purl = "pkg:julia/Dates@1.9.0?uuid=ade2ca70-3b73-5b8e-9b35-2c0d1c0e1f2a&os=linux";
		Assertions.assertEquals("pkg:julia/Dates@1.9.0?uuid=ade2ca70-3b73-5b8e-9b35-2c0d1c0e1f2a",
				Utils.canonicalizePurl(purl));
	}

	@Test
	public void testCanonicalizePurl_stripsQualifiersAndSubpathForOtherTypes() {
		String purl = "pkg:golang/github.com/gorilla/mux@1.8.0?os=linux#pkg/mux";
		Assertions.assertEquals("pkg:golang/github.com/gorilla/mux@1.8.0",
				Utils.canonicalizePurl(purl));
	}

	@Test
	public void testCanonicalizePurl_qualifierOrderIndependent() {
		String expected = "pkg:rpm/fedora/curl@7.50.3-1.fc25?distro=fedora-25&epoch=1";
		List<String> permutations = List.of(
				"pkg:rpm/fedora/curl@7.50.3-1.fc25?arch=i386&distro=fedora-25&epoch=1",
				"pkg:rpm/fedora/curl@7.50.3-1.fc25?epoch=1&arch=i386&distro=fedora-25",
				"pkg:rpm/fedora/curl@7.50.3-1.fc25?epoch=1&distro=fedora-25&arch=i386",
				"pkg:rpm/fedora/curl@7.50.3-1.fc25?distro=fedora-25&epoch=1&arch=i386");
		for (String purl : permutations) {
			Assertions.assertEquals(expected, Utils.canonicalizePurl(purl));
		}
	}
}
