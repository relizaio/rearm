/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.reliza.model.UserPermission.PermissionType;

/**
 * Unit coverage for {@link PermissionType#atLeast} -- the single home of the
 * tier-floor comparison shared by the component write-team rule
 * ({@code ComponentTeamService}) and the notification-inbox audience floor
 * ({@code NotificationDataFetcher}). Pins the boundary and null semantics so a
 * future ordering or floor change is caught here rather than drifting silently
 * between the two membership sites (the BUG 14-16 class).
 */
class UserPermissionTest {

    @Test
    void atLeastIsInclusiveAtTheFloor() {
        assertTrue(PermissionType.atLeast(PermissionType.READ_WRITE, PermissionType.READ_WRITE));
        assertTrue(PermissionType.atLeast(PermissionType.READ_ONLY, PermissionType.READ_ONLY));
    }

    @Test
    void atLeastIsTrueForStrongerTiers() {
        assertTrue(PermissionType.atLeast(PermissionType.ADMIN, PermissionType.READ_WRITE));
        assertTrue(PermissionType.atLeast(PermissionType.READ_WRITE, PermissionType.READ_ONLY));
    }

    @Test
    void atLeastIsFalseBelowTheFloor() {
        // The two production floors and the tiers they must exclude.
        assertFalse(PermissionType.atLeast(PermissionType.READ_ONLY, PermissionType.READ_WRITE));
        assertFalse(PermissionType.atLeast(PermissionType.ESSENTIAL_READ, PermissionType.READ_ONLY));
        assertFalse(PermissionType.atLeast(PermissionType.NONE, PermissionType.READ_ONLY));
    }

    @Test
    void atLeastIsNullSafe() {
        assertFalse(PermissionType.atLeast(null, PermissionType.READ_ONLY));
        assertFalse(PermissionType.atLeast(PermissionType.ADMIN, null));
        assertFalse(PermissionType.atLeast(null, null));
    }
}
