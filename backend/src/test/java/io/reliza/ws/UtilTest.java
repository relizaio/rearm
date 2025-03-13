/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
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
}
