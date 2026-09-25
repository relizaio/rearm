/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
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
   
	

	/**
	 * A fixture email that cannot collide with a previous run's.
	 *
	 * <p>These tests write to a long-lived database and nothing deletes what they create, so a
	 * hardcoded address is registered exactly once and every later run fails on "Email already
	 * registered" -- not on the behaviour under test, and not recoverably: the suite stays red until
	 * someone clears the table by hand. It only takes one interrupted run to get there.
	 */
	private static String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID() + "@reliza.io";
	}

	/**
	 * Likewise for the OAuth id. Nothing rejects a duplicate today, so sharing one across runs
	 * fails nothing -- but it leaves a pile of users behind one id, and
	 * {@code getUserByOauthIdAndType} resolves that to an arbitrary one of them.
	 */
	private static String uniqueOauthId() {
		return "test_githubid_" + UUID.randomUUID();
	}

	@Test
	public void testCreateUserProper() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		User u = userService.createUser("Test User 1", uniqueEmail("1test"), false, List.of(org.getUuid()),
								uniqueOauthId(), OauthType.GITHUB, WhoUpdated.getTestWhoUpdated());
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
		User u = userService.createUser("Test User 4", uniqueEmail("4test"), false,
								List.of(org.getUuid()), uniqueOauthId(), OauthType.GITHUB, WhoUpdated.getTestWhoUpdated());
		UserData uRet = userService.getUserData(u.getUuid()).get();
		Assertions.assertEquals(u.getUuid(), uRet.getUuid());
	}
	
	@Test
	public void findUserByEmail() throws RelizaException {
		// TODO: it fails bc we don't have yet constraint on having no more than one same email
		Organization org = testInitializer.obtainOrganization();
		User u = userService.createUser("Test User 5", uniqueEmail("5test"), false,
													List.of(org.getUuid()), uniqueOauthId(), OauthType.GITHUB, WhoUpdated.getTestWhoUpdated());
		UserData uOrig = UserData.dataFromRecord(u);
		UserData uRet = userService.getUserDataByEmail(uOrig.getEmail()).get();
		Assertions.assertEquals(uOrig.getUuid(), uRet.getUuid());
	}
	
}
