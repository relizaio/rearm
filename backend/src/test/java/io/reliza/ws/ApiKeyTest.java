/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.AuthHeaderParse.AuthHeaderParseBuilder;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
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
		String apiKey = apiKeyService.setObjectApiKey(p.getUuid(), ApiTypeEnum.COMPONENT, null, null, null, false, WhoUpdated.getTestWhoUpdated());
		// verify api key
		AuthHeaderParseBuilder ahpBuilder = AuthHeaderParse.builder();
		ahpBuilder.objUuid(p.getUuid());
		ahpBuilder.type(ApiTypeEnum.COMPONENT);
		ahpBuilder.apiKey(apiKey);
		Assertions.assertNotNull(apiKeyService.isMatchingApiKey(ahpBuilder.build()));
	}

	/** Notes are normalised on write and an over-long edit is refused (task t20261013-072815-12524). */
	@Test
	public void keyNotesAreNormalisedOnWrite() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey ak = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "ci\u0007 key\n", WhoUpdated.getTestWhoUpdated());
		Assertions.assertEquals("ci key", apiKeyService.getApiKeyDto(ak.getUuid()).orElseThrow().getNotes());
	}

	@Test
	public void overLongNotesEditIsRefused() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey ak = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "kept", WhoUpdated.getTestWhoUpdated());
		Assertions.assertThrows(RelizaException.class,
				() -> apiKeyService.setNotesOnApiKey(ak.getUuid(), "a".repeat(513), WhoUpdated.getTestWhoUpdated()));
		Assertions.assertEquals("kept", apiKeyService.getApiKeyDto(ak.getUuid()).orElseThrow().getNotes());
	}
	
	
}
