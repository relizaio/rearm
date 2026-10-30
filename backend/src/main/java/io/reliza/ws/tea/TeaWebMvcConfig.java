/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link TeaJsonConverter} ahead of the default converters, so every TEA model body is
 * written by the TEA mapper (task TEA-6). Spring 7's server builder replaces the deprecated
 * extendMessageConverters; a custom converter goes before the defaults there.
 */
@Configuration
public class TeaWebMvcConfig implements WebMvcConfigurer {

	@Override
	public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
		builder.addCustomConverter(new TeaJsonConverter());
	}
}
