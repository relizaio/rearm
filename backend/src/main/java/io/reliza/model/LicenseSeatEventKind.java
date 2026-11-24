/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

/** The admin-visible license seat events (TPF-167, TPF-165 D9), all of type LICENSE_SEAT_EVENT. */
public enum LicenseSeatEventKind {
	SYNC_SKIPPED,
	NO_ROLE_REFUSED,
	SEAT_FULL_REFUSED,
	OVER_LIMIT_REFUSED
}
