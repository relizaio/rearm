/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.List;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.model.NoMatchableComponentsReason;
import io.reliza.model.dto.ReleaseMetricsDto.BomScanState;
import io.reliza.model.dto.ReleaseMetricsDto.BomScanTally;

/**
 * TPF-132: the two fields of ReleaseBomScan the property fetcher cannot resolve from
 * ReleaseMetricsDto.BomScanTally: state (derived) and reasons (served under the name the UI reads;
 * the record component is notScannableReasons). The source is the tally the metrics fetchers return as
 * DependencyTrackMetrics.bomScan (ReleaseDatafetcher.metricsOfRelease); scannableBoms and
 * notScannableBoms resolve from the record's components. Read-only: no root, no authorization of its
 * own (the parent release read is already authorised).
 */
@DgsComponent
public class ReleaseBomScanDataFetcher {

	/** The derived state: the same read the activation map makes (ReleaseActivationMapBuilder). */
	@DgsData(parentType = "ReleaseBomScan", field = "state")
	public BomScanState stateOfTally(DgsDataFetchingEnvironment dfe) {
		BomScanTally tally = dfe.getSource();
		return tally.state();
	}

	/** The reasons, never null: a stored tally without the list reads empty (ReleaseActivationMapBuilder). */
	@DgsData(parentType = "ReleaseBomScan", field = "reasons")
	public List<NoMatchableComponentsReason> reasonsOfTally(DgsDataFetchingEnvironment dfe) {
		BomScanTally tally = dfe.getSource();
		return tally.reasonsOrEmpty();
	}
}
