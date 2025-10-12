/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.reliza.service.tea.TeaTransformerService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
public class WellKnownWs {
	
	@Autowired
	private TeaTransformerService teaTransformerService;

    @GetMapping(value = "/.well-known/tea", produces = "application/json")
    public Map<String, Object> teaWellKnown ()  {
    	return Map.of("schemaVersion", 1, "endpoints", Map.of("url", teaTransformerService.getServerBaseUri(), "versions", List.of("0.2.0-beta.2")));
    }
}
