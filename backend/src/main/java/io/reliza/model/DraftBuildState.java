/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/** Where a draft's build job stands (FDAW-1 D3); NONE on a draft never rebuilt. */
public enum DraftBuildState {
	NONE, REQUESTED, RUNNING, BUILT, FAILED
}
