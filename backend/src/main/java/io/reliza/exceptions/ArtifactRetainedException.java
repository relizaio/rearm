/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.exceptions;

import java.util.List;
import java.util.UUID;

import io.reliza.model.ArtifactPin;

/**
 * An archive, unbind or replace of an artifact a package version pins was refused (task FDAW-5,
 * B17; operator decision O9: a file a package version pins is retained). Unchecked, as
 * {@link ActionRefusedException} is, so the {@code @Transactional} entry points roll the whole
 * request back instead of committing what it wrote before the refusal; {@code GraphQLExceptionHandlers}
 * answers it as BAD_REQUEST with the message as it is. Callers read {@link #pins()}, never the text.
 */
public class ArtifactRetainedException extends RuntimeException {

	private static final long serialVersionUID = 2026110501L;

	private final transient List<ArtifactPin> pins;
	private final UUID release;

	public ArtifactRetainedException(String message, List<ArtifactPin> pins) {
		super(message);
		this.pins = List.copyOf(pins);
		this.release = this.pins.isEmpty() ? null : this.pins.get(0).release();
	}

	/** The pins that refused the action, in the hook's order. */
	public List<ArtifactPin> pins() {
		return pins;
	}

	/** The release of the first pin. */
	public UUID release() {
		return release;
	}
}
