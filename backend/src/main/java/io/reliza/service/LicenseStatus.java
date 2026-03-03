/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

import io.reliza.common.oss.LicensingConstants;

/**
 * In-memory holder for license validity and system sealed state.
 * Checked by AuthorizationService on every request.
 * Updated by LicenseService on startup and hourly by the scheduler.
 */
@Component
public class LicenseStatus {

	private final AtomicBoolean licenseValid = new AtomicBoolean(LicensingConstants.isOssEdition());
	private final AtomicBoolean systemSealed = new AtomicBoolean(true);

	public boolean isLicenseValid() {
		return licenseValid.get();
	}

	public void setLicenseValid(boolean valid) {
		licenseValid.set(valid);
	}

	public boolean isSystemSealed() {
		return systemSealed.get();
	}

	public void setSystemSealed(boolean sealed) {
		systemSealed.set(sealed);
	}

	/**
	 * Returns true if the system is operational (not sealed and license valid or OSS).
	 */
	public boolean isSystemOperational() {
		if (LicensingConstants.isOssEdition()) {
			return !systemSealed.get();
		}
		return !systemSealed.get() && licenseValid.get();
	}
}
