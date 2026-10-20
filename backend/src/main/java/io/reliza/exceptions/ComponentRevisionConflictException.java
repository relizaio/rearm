/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.exceptions;

import java.time.ZonedDateTime;
import java.util.UUID;

import io.reliza.common.CommonVariables.ProgrammaticType;

/**
 * A component settings save was prepared from an older revision than the one stored (TPF-61):
 * {@code ComponentService.updateComponent} compared the caller's expected revision with the row it
 * had just locked and refreshed, found a difference, and wrote nothing.
 *
 * <p>The caller decides on {@code extensions.code = COMPONENT_REVISION_CONFLICT}, never on the
 * message: {@code GraphQLExceptionHandlers} surfaces the current revision and who changed the
 * component last, and when. It extends {@link RelizaException} so it travels through every
 * {@code throws RelizaException} on the path, and so a client that does not know the code still
 * reads a plain BAD_REQUEST with the message.
 */
public class ComponentRevisionConflictException extends RelizaException {

	private static final long serialVersionUID = 1L;

	public static final String ERROR_CODE = "COMPONENT_REVISION_CONFLICT";

	private final int expectedRevision;
	private final int currentRevision;
	private final UUID lastUpdatedBy;
	private final ProgrammaticType lastUpdatedType;
	private final UUID lastUpdatedActor;
	private final ZonedDateTime lastUpdatedDate;

	/**
	 * @param expectedRevision the revision the caller's copy was read at
	 * @param currentRevision the stored revision, read under the row lock
	 * @param lastUpdatedBy the last writer (a user uuid, a key uuid), null for the system
	 * @param lastUpdatedType the {@link ProgrammaticType} of the last write, null when unknown
	 * @param lastUpdatedActor the person behind a key write, null otherwise
	 * @param lastUpdatedDate the time of the last write of the row
	 */
	public ComponentRevisionConflictException(int expectedRevision, int currentRevision, UUID lastUpdatedBy,
			ProgrammaticType lastUpdatedType, UUID lastUpdatedActor, ZonedDateTime lastUpdatedDate) {
		super("Component was changed since it was read: expected revision " + expectedRevision
				+ ", current revision " + currentRevision);
		this.expectedRevision = expectedRevision;
		this.currentRevision = currentRevision;
		this.lastUpdatedBy = lastUpdatedBy;
		this.lastUpdatedType = lastUpdatedType;
		this.lastUpdatedActor = lastUpdatedActor;
		this.lastUpdatedDate = lastUpdatedDate;
	}

	public int getExpectedRevision() {
		return expectedRevision;
	}

	public int getCurrentRevision() {
		return currentRevision;
	}

	public UUID getLastUpdatedBy() {
		return lastUpdatedBy;
	}

	public ProgrammaticType getLastUpdatedType() {
		return lastUpdatedType;
	}

	public UUID getLastUpdatedActor() {
		return lastUpdatedActor;
	}

	public ZonedDateTime getLastUpdatedDate() {
		return lastUpdatedDate;
	}
}
