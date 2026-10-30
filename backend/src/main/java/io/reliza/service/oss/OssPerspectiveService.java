/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.common.CommonVariables.StatusEnum;
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

		public String getName() {
			return null;
		}

		public StatusEnum getStatus() {
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
	 * A perspective as the TEA profile code sees it (task TEA-2): the shared layer never names
	 * {@code PerspectiveData}, which exists only in ReARM Pro.
	 */
	public record TeaPerspectiveRef(UUID uuid, String name, PerspectiveType type) {}

	/**
	 * Whether this edition has perspectives at all; TEA PERSPECTIVE-scope profiles need them.
	 * Part of ReARM Pro only: CE has no perspectives, so this is false and every PERSPECTIVE-scope
	 * save is refused before any lookup.
	 */
	public boolean teaPerspectivesSupported() {
		return false;
	}

	/**
	 * The perspectives a component's TEA profile may resolve through.
	 * Part of ReARM Pro only: CE has no perspectives, so this is empty.
	 */
	public List<TeaPerspectiveRef> teaPerspectivesOfComponent(ComponentData cd) {
		return List.of();
	}

	/**
	 * The refs of the uuids that would be TEA candidates of a component of {@code org} holding them.
	 * Part of ReARM Pro only: CE has no perspectives, so this is empty.
	 */
	public List<TeaPerspectiveRef> teaPerspectiveRefs(UUID org, Collection<UUID> uuids) {
		return List.of();
	}

	/**
	 * An active perspective by uuid. Part of ReARM Pro only: CE has no perspectives, so every uuid
	 * is empty, the answer Pro gives for a missing one.
	 */
	public Optional<TeaPerspectiveRef> teaPerspectiveRef(UUID uuid) {
		return Optional.empty();
	}

	/**
	 * The perspective as an authorization object (its org), for the TEA profile calls.
	 * Part of ReARM Pro only: CE has no perspectives, so every uuid is empty.
	 */
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
	 * The perspective an object of {@code orgUuid} may be attached to. Part of ReARM Pro only: CE has no
	 * perspectives, so every uuid is "Perspective not found", the answer Pro gives for a missing one.
	 */
	public PerspectiveData requireAssignablePerspective(UUID orgUuid, UUID perspectiveUuid) throws RelizaException {
		return requireLivePerspective(orgUuid, perspectiveUuid);
	}

	/**
	 * The perspective a grant of {@code orgUuid} may name. Part of ReARM Pro only: CE has no perspectives,
	 * so every uuid is "Perspective not found", the answer Pro gives for a missing one.
	 */
	public PerspectiveData requireLivePerspective(UUID orgUuid, UUID perspectiveUuid) throws RelizaException {
		throw new RelizaException("Perspective not found: " + perspectiveUuid);
	}

	/**
	 * The organization's active real perspectives (not PRODUCT components).
	 * Part of ReARM Pro only — CE has no real perspectives, so this is empty.
	 */
	public List<PerspectiveData> listRealPerspectivesOfOrg (UUID orgUuid) {
		return List.of();
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

	/**
	 * The rows among {@code uuids} that may hold components of {@code orgUuid} (task TEA-8).
	 * Part of ReARM Pro only: CE has no perspectives, so none is assignable and this is empty.
	 */
	public List<PerspectiveData> assignablePerspectives(UUID orgUuid, Collection<UUID> uuids) {
		return List.of();
	}

	/**
	 * The uuids of {@link #assignablePerspectives}, in the same order. ComponentService.setPerspectives
	 * keeps only these on every save (task TEA-8). Part of ReARM Pro only: CE has no perspectives, so
	 * this is empty.
	 */
	public List<UUID> assignablePerspectiveUuids(UUID orgUuid, Collection<UUID> uuids) {
		return List.of();
	}

	/**
	 * The organization of the perspective row {@code uuid}; empty when there is no row.
	 * Part of ReARM Pro only: CE has no perspective rows, so this is empty.
	 */
	public Optional<UUID> orgOfPerspective(UUID uuid) {
		return Optional.empty();
	}
}
