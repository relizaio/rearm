/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.service.FindingPurlBridge;
import io.reliza.service.FindingPurlBridge.ComponentIndex;
import io.reliza.service.FindingPurlBridge.InventoryState;
import io.reliza.service.FindingPurlBridge.ReleaseInventory;
import io.reliza.ws.ReleaseInventoryDataLoader.InventoryLookup;

/**
 * One inventory read per release in a batch, mapped back in key order, and a
 * release whose read throws marked failed rather than empty, without taking
 * the other releases of the batch down with it.
 */
class ReleaseInventoryDataLoaderTest {

	@Test
	void oneReadPerReleaseAndAFailureStaysWithItsRelease() {
		UUID ok = UUID.randomUUID();
		UUID broken = UUID.randomUUID();
		ReleaseInventory inventory = new ReleaseInventory(InventoryState.SETTLED,
				ComponentIndex.of(Map.of(UUID.randomUUID(), "pkg:npm/a@1")), Map.of());
		FindingPurlBridge bridge = mock(FindingPurlBridge.class);
		when(bridge.inventoryOf(ok)).thenReturn(inventory);
		when(bridge.inventoryOf(broken)).thenThrow(new IllegalStateException("db down"));
		ReleaseInventoryDataLoader loader = new ReleaseInventoryDataLoader();
		ReflectionTestUtils.setField(loader, "findingPurlBridge", bridge);

		List<InventoryLookup> out = loader.load(List.of(ok, broken, ok)).toCompletableFuture().join();

		assertEquals(3, out.size());
		assertSame(inventory, out.get(0).inventory());
		assertFalse(out.get(0).failed());
		assertTrue(out.get(1).failed());
		assertNull(out.get(1).inventory());
		assertSame(inventory, out.get(2).inventory());
		verify(bridge, times(1)).inventoryOf(ok);
	}
}
