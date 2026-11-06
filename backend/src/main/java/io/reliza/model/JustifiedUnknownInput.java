/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/** A justified unknown as a caller submits it: the code and its free text (FDAW-14 3.2). */
public record JustifiedUnknownInput(JustifiedUnknownReason code, String text) {
}
