/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.AuthHeaderParse.AuthHeaderParseBuilder;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Organization;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.ApiKeyService;
import io.reliza.ws.oss.TestInitializer;
import io.reliza.service.ComponentService;

/**
 * Unit test related to Component functionality
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
public class ApiKeyTest
{
	@Autowired
    private ComponentService componentService;
	
	@Autowired
	private ApiKeyService apiKeyService;
	
	@Autowired
	private TestInitializer testInitializer;
	
	@Test
	public void setApiKeyToProject() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component p = componentService.createComponent("testApiKeyProj", org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		String apiKey = apiKeyService.setObjectApiKey(p.getUuid(), ApiTypeEnum.COMPONENT, null, null, null, WhoUpdated.getTestWhoUpdated());
		// verify api key
		AuthHeaderParseBuilder ahpBuilder = AuthHeaderParse.builder();
		ahpBuilder.objUuid(p.getUuid());
		ahpBuilder.type(ApiTypeEnum.COMPONENT);
		ahpBuilder.apiKey(apiKey);
		Assertions.assertNotNull(apiKeyService.isMatchingApiKey(ahpBuilder.build()));
	}
	
	
}
