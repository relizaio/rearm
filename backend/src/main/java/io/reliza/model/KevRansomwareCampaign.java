/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * A named ransomware campaign (optionally with a reference URL) associated
 * with a vulnerability. Carried in the {@code ransomwareCampaigns} list of
 * {@link KevAssertionData} and surfaced on the GraphQL detail type.
 *
 * <p>CISA does not supply campaign names — this stays empty for CISA
 * assertions and fills in once a richer source (e.g. VulnCheck KEV) is
 * wired. Doubles as the GraphQL {@code KevRansomwareCampaign} type via
 * DGS simple-name mapping.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KevRansomwareCampaign {
	private String name;
	private String url;
}
