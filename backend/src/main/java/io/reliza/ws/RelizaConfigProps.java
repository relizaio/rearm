/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
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
}
