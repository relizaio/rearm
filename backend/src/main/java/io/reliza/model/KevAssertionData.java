/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.common.Utils;
import lombok.Data;

/**
 * One source's view of a known-exploited vulnerability, stored as the
 * {@code record_data} JSONB blob on {@link KevAssertion}. The
 * {@code source}, {@code cveId} and {@code revokedDate} live on the entity
 * columns, not here; this is the per-source descriptive payload.
 *
 * <p>Field names mirror the CISA feed where they line up. Ransomware use is
 * a {@link KevRansomwareStatus} enum (the feed's {@code "Known"}/{@code
 * "Unknown"} strings, plus {@code UNSPECIFIED} when absent) rather than a
 * boolean; {@code ransomwareCampaigns} is reserved for richer sources and
 * stays empty for CISA.
 *
 * <p>{@code dateAdded}/{@code dueDate} stay feed-format {@code yyyy-MM-dd}
 * strings — display-only, and storing them verbatim keeps the JSONB
 * byte-stable for the sync's changed-content comparison. {@code dueDate} is
 * a CISA-specific signal; other sources leave it null.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KevAssertionData implements RelizaData {

	private String cveId;
	private String vendorProject;
	private String product;
	private String vulnerabilityName;
	private String dateAdded;
	private String shortDescription;
	private String requiredAction;
	private String dueDate;
	private KevRansomwareStatus ransomwareStatus = KevRansomwareStatus.UNSPECIFIED;
	private List<KevRansomwareCampaign> ransomwareCampaigns = new ArrayList<>();
	private String notes;
	private List<String> cwes = new ArrayList<>();

	public static KevAssertionData dataFromRecord(KevAssertion ka) {
		if (ka.getSchemaVersion() != 0) {
			throw new IllegalStateException("KevAssertion schema version is "
					+ ka.getSchemaVersion() + ", which is not currently supported");
		}
		KevAssertionData kad = Utils.OM.convertValue(ka.getRecordData(), KevAssertionData.class);
		kad.setCveId(ka.getCveId());
		return kad;
	}

	public Map<String, Object> toRecordData() {
		return Utils.dataToRecord(this);
	}
}
