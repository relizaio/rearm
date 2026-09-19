/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.List;
import java.util.UUID;

import io.reliza.model.DeviceLifecycle;
import io.reliza.model.ReleaseData;

/**
 * Optional Pro-side device support window for an artifact.
 *
 * <p>A device support window is a commitment about a physical device -- the thing a regulator asks
 * about -- and devices, sites, clients and shipments are Pro-only models. CE has none of them, so
 * an artifact downloaded from CE carries no device properties, which is the honest answer rather
 * than a missing feature: there is no device in CE to make a commitment about.
 *
 * <p>Only the artifact-download resolution is behind this interface. The rest of the resolver's
 * surface (per device, per shipment, per component) is reached from Pro-side datafetchers that CE
 * does not have, so it needs no indirection.
 */
public interface DeviceLifecycleHook {

	/**
	 * The window declared for a release, through the product component that ships it.
	 *
	 * @return the window, or null when the component declares none
	 */
	DeviceLifecycle forRelease(ReleaseData rd);

	/**
	 * The window to stamp on an artifact served from these releases.
	 *
	 * @param releases the releases the artifact is attached to
	 * @param releaseUuid the release the caller named, if any; disambiguates an artifact reachable
	 *        from several products
	 * @return the window, or null when none applies or the products disagree
	 */
	DeviceLifecycle forArtifactReleases(List<ReleaseData> releases, UUID releaseUuid);
}
