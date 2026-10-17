/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.model;

/**
 * How a usage row found its task, recorded because the answer changes what the number means.
 *
 * <p>{@code EXPLICIT} is the client naming the task it was working on. {@code IMPLICIT} is the
 * server matching the report's window against the session's one open assignment.
 * {@code COORDINATOR} is a session holding a board's coordinator seat with no assignment -- real
 * work, billed to the board rather than to any task. {@code UNATTRIBUTED} is everything else,
 * including a session with two concurrent assignments and no explicit task, which is why the CLI
 * sends the task whenever it knows it.
 */
public enum SessionUsageAttribution {
	EXPLICIT,
	IMPLICIT,
	COORDINATOR,
	UNATTRIBUTED
}
