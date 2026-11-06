/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import io.reliza.model.ArtifactPin;

/**
 * Which package versions pin a set of artifacts (task FDAW-5, B17): implemented by ReARM Pro over
 * its submission versions and injected {@code @Autowired(required = false)} into
 * {@link ArtifactRetentionLock}. CE has no implementation, so nothing is ever pinned there.
 */
public interface ArtifactRetentionHook {

	/**
	 * The pins of every version of {@code org} that names any of {@code artifacts}, ordered by
	 * artifact, release, submission, version; one query, never one per artifact.
	 */
	List<ArtifactPin> pinsOf(UUID org, Collection<UUID> artifacts);
}
