/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.dto;

import java.util.List;

import io.reliza.model.KevRansomwareCampaign;
import io.reliza.model.KevRansomwareStatus;

/**
 * One source's assertion about a CVE, flattened for the GraphQL detail
 * surface. Maps to the {@code KevSourceAssertion} schema type by
 * simple-name lookup. {@code revokedDate} non-null means the source
 * withdrew the listing — the CVE still reads as known-exploited, the date
 * is shown as a note.
 */
public record KevSourceAssertion(
		String source,
		String revokedDate,
		String vendorProject,
		String product,
		String vulnerabilityName,
		String dateAdded,
		String shortDescription,
		String requiredAction,
		String dueDate,
		KevRansomwareStatus ransomwareStatus,
		List<KevRansomwareCampaign> ransomwareCampaigns,
		String notes,
		List<String> cwes) {}
