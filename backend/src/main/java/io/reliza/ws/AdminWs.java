/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.reliza.service.ApiKeyService;
import io.reliza.service.OrganizationService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
public class AdminWs {
	
	@Autowired
	ApiKeyService apiKeyService;
	
	@Autowired
	OrganizationService organizationService;
	
	@Autowired
	UserService userService;
	
	@GetMapping("/api/manual/v1/fetchCsrf")
    public CsrfToken csrf(CsrfToken token) {
		log.info("FETCHCSRF called");
 		return token;
    }
	

}
