/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.reliza.model.SystemInfoData.EncProps;
import lombok.Data;

@Data
@ConfigurationProperties(prefix="relizaprops")
public class RelizaConfigProps {
	private String protocol;
	private EncProps encryption;
	private String baseuri;

	private String rejectPendingReleasesRate;
	
	private String installationType;

	/**
	 * Deployment-wide default for the UI home dashboard: APP (the
	 * classic metrics/search dashboard) or BOARDS (agent task boards).
	 * A user's own choice (persisted in browser localStorage) always
	 * wins over this default. Unset/unrecognized resolves to APP.
	 */
	private String defaultDashboard;

	/**
	 * ReARM Pro branding preset (a bundle under branding/saas/ on the classpath). Blank, absent
	 * or "default" means the Reliza branding; resolved by BrandingService.
	 */
	private String brandingPreset;

	/**
	 * TPF-142: the TEA custom domains a global administrator may assign to organizations, a
	 * comma-separated list of bare hostnames (env RELIZAPROP_TEA_CUSTOM_DOMAINS, rendered by the Pro
	 * chart from tea.customDomains). The baseuri host is assignable without being listed. Parsed and
	 * validated once at startup by TeaHostAssignmentService.
	 */
	private String teaCustomDomains;
}
