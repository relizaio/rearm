/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.saas;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.common.Utils;
import io.reliza.model.RelizaData;
import lombok.Data;

/**
 * One CISA KEV catalog entry, stored as the {@code record_data} JSONB
 * blob on {@link KevRecord}. Field names mirror the upstream feed
 * (<a href="https://www.cisa.gov/known-exploited-vulnerabilities-catalog">CISA
 * KEV catalog</a>) except {@code cveID → cveId} (normalized at parse) and
 * {@code knownRansomwareCampaignUse}, which the feed publishes as the
 * strings {@code "Known"}/{@code "Unknown"} and we normalize to a Boolean.
 *
 * <p>{@code dateAdded}/{@code dueDate} stay as the feed's plain
 * {@code yyyy-MM-dd} strings — they are display-only on the read surface,
 * and storing them verbatim keeps the JSONB byte-stable for the sync's
 * changed-content comparison.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KevRecordData implements RelizaData {

	private String cveId;
	private String vendorProject;
	private String product;
	private String vulnerabilityName;
	private String dateAdded;
	private String shortDescription;
	private String requiredAction;
	private String dueDate;
	private Boolean knownRansomwareCampaignUse;
	private String notes;
	private List<String> cwes = new ArrayList<>();

	public static KevRecordData dataFromRecord(KevRecord kr) {
		if (kr.getSchemaVersion() != 0) {
			throw new IllegalStateException("KevRecord schema version is "
					+ kr.getSchemaVersion() + ", which is not currently supported");
		}
		KevRecordData krd = Utils.OM.convertValue(kr.getRecordData(), KevRecordData.class);
		krd.setCveId(kr.getCveId());
		return krd;
	}

	public Map<String, Object> toRecordData() {
		return Utils.dataToRecord(this);
	}
}
