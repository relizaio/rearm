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

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Organization;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.OrganizationService;
import io.reliza.service.UserService;
import io.reliza.service.ComponentService;
import io.reliza.service.GetComponentService;

/**
 * Unit test related to Component functionality
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
public class ComponentTest 
{
	@Autowired
    private ComponentService ComponentService;

	@Autowired
    private GetComponentService getComponentService;
	
	@Autowired
    private OrganizationService organizationService;
	
	private Organization obtainOrganization() {
		return organizationService.getOrganization(UserService.USER_ORG).get();
	}
	
	@Test
    public void testProjectRetreival() {
		ComponentService.listAllComponentData();
        Assertions.assertTrue( true );
    }
	
	@Test
	public void testcreateComponentWithoutNameThrowsIllegalState() throws RelizaException {
		Assertions.assertThrows(IllegalStateException.class,
				() -> ComponentService.createComponent(null, null, ComponentType.COMPONENT, WhoUpdated.getTestWhoUpdated()));
	}
	
	@Test
	public void findProjectByUuidFail() {
		UUID genUuid = UUID.randomUUID();
		Optional<Component> op = getComponentService.getComponent(genUuid);
		Assertions.assertFalse(op.isPresent());
	}
	
	@Test
	public void createAndfindProjectByUuidSuccess() throws RelizaException {
		Organization org = obtainOrganization();
		Component p = ComponentService.createComponent("testFindProject", org.getUuid(), ComponentType.COMPONENT, WhoUpdated.getTestWhoUpdated());
		Optional<Component> op = getComponentService.getComponent(p.getUuid());
		Assertions.assertTrue(op.isPresent());
		Assertions.assertEquals(p.getUuid(), op.get().getUuid());
	}
	
	@Test
	public void listProjectsByOrg() throws RelizaException {
		Organization org = obtainOrganization();
		@SuppressWarnings("unused")
		Component p1 = ComponentService.createComponent("testFindProjectList1", org.getUuid(), ComponentType.COMPONENT, WhoUpdated.getTestWhoUpdated());
		@SuppressWarnings("unused")
		Component p2 = ComponentService.createComponent("testFindProjectList2", org.getUuid(), ComponentType.COMPONENT, WhoUpdated.getTestWhoUpdated());
		List<Component> projectList = ComponentService.listComponentsByOrganization(org.getUuid(), ComponentType.COMPONENT);
		Assertions.assertEquals(2, projectList.size());
	}

}
