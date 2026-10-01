/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.dataloader.BatchLoader;
import org.springframework.beans.factory.annotation.Autowired;

import com.netflix.graphql.dgs.DgsDataLoader;

import io.reliza.service.FindingPurlBridge;
import io.reliza.service.FindingPurlBridge.ReleaseInventory;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-request batch of release SBOM inventories for
 * {@code Vulnerability.sbomMatch}: every finding of a release matches against
 * one inventory read, however many findings the page shows. A failed read is
 * marked as such, so the field can answer null rather than "not in the SBOM".
 */
@Slf4j
@DgsDataLoader(name = ReleaseInventoryDataLoader.NAME)
public class ReleaseInventoryDataLoader implements BatchLoader<UUID, ReleaseInventoryDataLoader.InventoryLookup> {

	public static final String NAME = "releaseInventoryLoader";

	/** One release's inventory; {@code failed} when reading it threw. */
	public record InventoryLookup(ReleaseInventory inventory, boolean failed) {
		static final InventoryLookup FAILED = new InventoryLookup(null, true);
	}

	@Autowired
	private FindingPurlBridge findingPurlBridge;

	@Override
	public CompletionStage<List<InventoryLookup>> load(List<UUID> releases) {
		Map<UUID, InventoryLookup> byRelease = new LinkedHashMap<>();
		for (UUID release : releases) {
			byRelease.computeIfAbsent(release, r -> {
				try {
					return new InventoryLookup(findingPurlBridge.inventoryOf(r), false);
				} catch (Exception e) {
					log.error("Could not load the SBOM inventory of release {}", r, e);
					return InventoryLookup.FAILED;
				}
			});
		}
		List<InventoryLookup> out = new ArrayList<>(releases.size());
		for (UUID release : releases) out.add(byRelease.get(release));
		return CompletableFuture.completedFuture(out);
	}
}
