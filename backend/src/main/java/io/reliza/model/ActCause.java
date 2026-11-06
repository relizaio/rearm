/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * Why a submission act row exists (FDAW-1 D2): a person did it (USER, the one cause that names an
 * actor), it followed another act (SYSTEM), or a data migration wrote it (MIGRATION).
 */
public enum ActCause {
	USER, SYSTEM, MIGRATION
}
