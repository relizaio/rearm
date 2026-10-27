/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.reliza.model.ScopedSettingsMode;

/**
 * Where {@link ScopedSettingsResolver} reads the rows of one scoped settings type (task TEA-27): a
 * pure data adapter with no logic of its own beyond {@link #normalized}. The TEA publishing settings
 * store has one row per scope and object; an SBOM profile store is bound to one name.
 */
public interface ScopedSettingsStore<T> {

	/** What the rows are, for log and refusal text: "TEA profile", "SBOM profile default". */
	String label();

	Optional<T> organizationRow(UUID org);

	Optional<T> perspectiveRow(UUID org, UUID perspective);

	/** The rows of the given perspectives; the engine keeps those of candidates only. */
	List<T> perspectiveRows(UUID org, Collection<UUID> perspectives);

	Optional<T> componentRow(UUID org, UUID component);

	/** The built-in settings a scope resolves to when no row applies; empty when the type has none. */
	Optional<T> builtInDefault(UUID org);

	UUID uuidOf(T row);

	/** The row's object: the perspective or component uuid, null at ORGANIZATION. */
	UUID objectOf(T row);

	/** The row's mode; null at ORGANIZATION and PERSPECTIVE. */
	ScopedSettingsMode modeOf(T row);

	UUID followedPerspectiveOf(T row);

	/** The effective value of a resolved row: a detached copy when it changes anything, never the stored row. */
	T normalized(UUID org, T row);
}
