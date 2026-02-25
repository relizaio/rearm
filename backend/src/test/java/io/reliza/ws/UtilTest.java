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
}
