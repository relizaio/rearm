/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.List;
import java.util.UUID;

/**
 * A channel that names SBOM profiles (task TEA-27): TEA publishing settings now, package settings
 * (TEA-29) later. The catalog never learns the channels; it asks every registered consumer before a
 * delete, so a profile a channel names is not deleted where the name would stop resolving.
 */
public interface SbomProfileConsumer {

	/** Every scoped settings of the organization that names this profile: (scope, object, a label for the refusal). */
	List<ScopedSettingsRef> referencesTo(UUID org, String name);
}
