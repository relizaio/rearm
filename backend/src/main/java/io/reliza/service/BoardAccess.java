/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.UUID;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.UserPermission.PermissionFunction;

/**
 * What the caller of a board-level write or read may do (board-permissions.md §4; architecture
 * d8e7bd7e §3.3): hold a function on a board, or at the organization, for a board that hangs off no
 * perspective yet. Built by {@link AuthorizationService} for a key or a person; {@link #ALL} is the
 * board's own writes and the callers that checked already.
 */
public interface BoardAccess {

	boolean onBoard(UUID board, PermissionFunction function, CallType callType) throws RelizaException;

	boolean atOrganization(PermissionFunction function, CallType callType) throws RelizaException;

	BoardAccess ALL = new BoardAccess() {
		@Override public boolean onBoard(UUID board, PermissionFunction function, CallType callType) { return true; }
		@Override public boolean atOrganization(PermissionFunction function, CallType callType) { return true; }
	};

	/** Whether every function is held on the board. */
	default boolean onBoard(UUID board, CallType callType, PermissionFunction... functions) throws RelizaException {
		for (PermissionFunction fn : functions) {
			if (!onBoard(board, fn, callType)) return false;
		}
		return true;
	}

	/**
	 * Whether the caller holds anything a board read asks on the board: BOARD_READ (which the other
	 * board functions imply) or CONFIGURATION_READ (which CONFIGURATION_WRITE implies). A board the
	 * caller does not see is answered as one that does not exist, so a refusal never confirms it.
	 */
	default boolean sees(UUID board) throws RelizaException {
		return onBoard(board, PermissionFunction.BOARD_READ, CallType.ESSENTIAL_READ)
				|| onBoard(board, PermissionFunction.CONFIGURATION_READ, CallType.ESSENTIAL_READ);
	}

	/** Whether every function is held at the organization. */
	default boolean atOrganization(CallType callType, PermissionFunction... functions) throws RelizaException {
		for (PermissionFunction fn : functions) {
			if (!atOrganization(fn, callType)) return false;
		}
		return true;
	}
}
