/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.dto;

import java.util.List;

import io.reliza.model.KevRansomwareStatus;

/**
 * The KEV detail surface for one CVE: the aggregated ransomware status plus
 * every source's assertion (active and revoked). Maps to the GraphQL
 * {@code KevRecordDetails} type. Returned null by the fetcher when no
 * source has ever asserted the CVE.
 */
public record KevRecordDetails(
		String cveId,
		KevRansomwareStatus ransomwareStatus,
		List<KevSourceAssertion> assertions) {}
