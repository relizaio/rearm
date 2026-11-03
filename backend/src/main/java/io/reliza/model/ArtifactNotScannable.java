/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The not-scannable marker stored at artifacts.metrics.notScannable (TPF-125): written by the
 * synthetic fan-out when the canonical's coverage gate has no matchable component, cleared by the
 * first pass that finds one. Absent (Java null, no JSON key) on every scannable artifact. The four
 * counts are the gate classification the reason was derived from (SyntheticSbomService.GateView),
 * for the UI tooltip and the scan-status explainer. at is the pass instant, stored as fractional
 * epoch seconds exactly like lastScanned (Utils.OM WRITE_DATES_AS_TIMESTAMPS).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArtifactNotScannable(NoMatchableComponentsReason reason, ZonedDateTime at,
		int components, int root, int terminal, int unmatchable) implements Serializable {}
