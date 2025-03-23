/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Unit test related to Component functionality
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
public class UserTest 
{

	@Autowired
    private UserService userService;
	
	@Autowired
	private TestInitializer testInitializer;
   
	
	@Test
	public void testCreateUserProper() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		User u = userService.createUser("Test User 1", "1test@reliza.io", false, List.of(org.getUuid()), "test_githubid", OauthType.GITHUB, WhoUpdated.getTestWhoUpdated());
		UserData uSaved = userService.getUserData(u.getUuid()).get();
		Assertions.assertEquals(u.getUuid(), uSaved.getUuid());
	}
	
	@Test
	public void findUserByUuidFail() {
		UUID genUuid = UUID.randomUUID();
		Optional<UserData> oud = userService.getUserData(genUuid);
		Assertions.assertFalse(oud.isPresent());
	}
	
	@Test
	public void findUserByUuidSuccess() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		User u = userService.createUser("Test User 4", "4test@reliza.io", false,
								List.of(org.getUuid()), "test_githubid", OauthType.GITHUB, WhoUpdated.getTestWhoUpdated());
		UserData uRet = userService.getUserData(u.getUuid()).get();
		Assertions.assertEquals(u.getUuid(), uRet.getUuid());
	}
	
	@Test
	public void findUserByEmail() throws RelizaException {
		// TODO: it fails bc we don't have yet constraint on having no more than one same email
		Organization org = testInitializer.obtainOrganization();
		Long timestamp = System.currentTimeMillis();
		User u = userService.createUser("Test User 5", timestamp + "5test@reliza.io", false,
													List.of(org.getUuid()), "test_githubid", OauthType.GITHUB, WhoUpdated.getTestWhoUpdated());
		UserData uOrig = UserData.dataFromRecord(u);
		UserData uRet = userService.getUserDataByEmail(uOrig.getEmail()).get();
		Assertions.assertEquals(uOrig.getUuid(), uRet.getUuid());
	}
	
}
