/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * The texts of one overlay write (FDAW-15 3.3 item 1), field by field: null leaves a field alone, an
 * empty string clears it, a value sets it.
 */
public record SbomComponentOverlayPatch(String supplier, String licence, String description, String origin) {}
