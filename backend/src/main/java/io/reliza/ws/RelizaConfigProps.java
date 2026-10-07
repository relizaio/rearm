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

	private String enableBetaTea;

	/**
	 * Deployment-wide default for the UI home dashboard: APP (the
	 * classic metrics/search dashboard) or BOARDS (agent task boards).
	 * A user's own choice (persisted in browser localStorage) always
	 * wins over this default. Unset/unrecognized resolves to APP.
	 */
	private String defaultDashboard;
}
