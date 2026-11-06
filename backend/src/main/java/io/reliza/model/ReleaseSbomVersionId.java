/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** The composite key of {@link ReleaseSbomVersion}: the serial (head uuid) and the version (FDAW-7). */
public class ReleaseSbomVersionId implements Serializable {
	private static final long serialVersionUID = 20261105L;

	private UUID uuid;
	private int version;

	public ReleaseSbomVersionId() {}

	public ReleaseSbomVersionId(UUID uuid, int version) {
		this.uuid = uuid;
		this.version = version;
	}

	public UUID getUuid() {
		return uuid;
	}

	public int getVersion() {
		return version;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ReleaseSbomVersionId other)) return false;
		return version == other.version && Objects.equals(uuid, other.uuid);
	}

	@Override
	public int hashCode() {
		return Objects.hash(uuid, version);
	}
}
