/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * TPF-139: the four tables whose Statistics totals are counted once a night into the
 * organization's record data (OrganizationData.totals) instead of on request. The enum is the only way a table is named across
 * the nightly service, the count and stamp repositories, the request-path map and the log lines,
 * so no string naming a table or a column reaches a statement.
 */
public enum OrgTotalsTable {
	RELEASES("releases"),
	ARTIFACTS("artifacts"),
	COMMITS("commits"),
	DELIVERABLES("deliverables");

	/** The one key the Statistics totals map gains beside the four cached counts: the oldest counted-at instant. */
	public static final String COUNTED_AT_KEY = "counted_at";

	private final String mapKey;

	OrgTotalsTable(String mapKey) {
		this.mapKey = mapKey;
	}

	/** The key of this table's count in the totalsAnalytics map, as before TPF-139. */
	public String getMapKey() {
		return mapKey;
	}
}
