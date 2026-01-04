/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;


import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.reliza.common.CommonVariables;
import io.reliza.model.OrganizationData;
import io.reliza.service.GetOrganizationService;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
public class AdminWs {

	@Autowired
	private GetOrganizationService getOrganizationService;
	
	@GetMapping("/api/manual/v1/fetchCsrf")
    public CsrfToken csrf(CsrfToken token) {
		log.debug("FETCHCSRF called");
 		return token;
    }

	@GetMapping("/api/healthCheck")
	public ResponseEntity<String> getHealthCheck() {
		log.trace("in REST healthcheck query");
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(CommonVariables.EXTERNAL_PROJ_ORG_UUID);
		if (ood.isPresent() && ood.get().getUuid().equals(CommonVariables.EXTERNAL_PROJ_ORG_UUID)) {
			return ResponseEntity.ok("OK");
		} else {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("HealthCheck Failed");
		}
	}

}
