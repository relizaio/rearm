/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.RelizaObject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OssPerspectiveService {

	public static class PerspectiveData implements RelizaObject {
		public PerspectiveType getType() {
			return null;
		}

		@Override
		public UUID getUuid() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public UUID getOrg() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public UUID getResourceGroup() {
			// TODO Auto-generated method stub
			return null;
		}
	}

	/**
	 * Aggregated perspective-layer contribution to the sid PURL policy for a component.
	 * {@code enabled} is null when no real perspective produced an enable/disable decision;
	 * {@code segments} is null when no real perspective contributed authority segments.
	 */
	public record PerspectiveSidResolution(Boolean enabled, List<String> segments) {
		public static PerspectiveSidResolution none() {
			return new PerspectiveSidResolution(null, null);
		}
	}

	/**
	 * A perspective as the TEA profile code sees it (task TEA-2). Same shape as in ReARM Pro, so
	 * the shared code compiles in both editions.
	 */
	public record TeaPerspectiveRef(UUID uuid, String name, PerspectiveType type) {}

	/** CE has no perspectives, so TEA PERSPECTIVE-scope profiles are refused as a ReARM Pro feature. */
	public boolean teaPerspectivesSupported() {
		return false;
	}

	/** Part of ReARM Pro only: CE components have no perspectives to resolve a TEA profile through. */
	public List<TeaPerspectiveRef> teaPerspectivesOfComponent(ComponentData cd) {
		return List.of();
	}

	/** Part of ReARM Pro only. */
	public Optional<TeaPerspectiveRef> teaPerspectiveRef(UUID uuid) {
		return Optional.empty();
	}

	/** Part of ReARM Pro only. */
	public Optional<RelizaObject> teaPerspectiveObject(UUID uuid) {
		return Optional.empty();
	}

	/**
	 * Part of ReARM Pro only
	 * @param uuid
	 * @return
	 */
	public Optional<PerspectiveData> getPerspectiveData (UUID uuid) {
		return Optional.empty();
	}

	/**
	 * Walks the component's real perspectives and returns their aggregated sid override
	 * contribution (enabled + segments). Throws on perspective-level conflicts.
	 * Part of ReARM Pro only — CE has no real perspectives, so this returns
	 * {@link PerspectiveSidResolution#none()}.
	 */
	public PerspectiveSidResolution resolvePerspectiveSidOverrides(ComponentData cd) throws RelizaException {
		return PerspectiveSidResolution.none();
	}
}
