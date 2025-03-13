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
import io.reliza.model.ComponentData;
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
    private ComponentService componentService;

	@Autowired
    private GetComponentService getComponentService;
	
	@Autowired
    private OrganizationService organizationService;
	
	private Organization obtainOrganization() {
		return organizationService.getOrganization(UserService.USER_ORG).get();
	}
	
	@Test
    public void testProjectRetreival() {
		componentService.listAllComponentData();
        Assertions.assertTrue( true );
    }
	
	@Test
	public void testcreateComponentWithoutNameThrowsIllegalState() throws RelizaException {
		Assertions.assertThrows(IllegalStateException.class,
				() -> componentService.createComponent(null, null, ComponentType.COMPONENT, WhoUpdated.getTestWhoUpdated()));
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
		Component p = componentService.createComponent("testFindProject", org.getUuid(), ComponentType.COMPONENT, WhoUpdated.getTestWhoUpdated());
		Optional<Component> op = getComponentService.getComponent(p.getUuid());
		Assertions.assertTrue(op.isPresent());
		Assertions.assertEquals(p.getUuid(), op.get().getUuid());
	}
	
	@Test
	public void listComponentsByOrg() throws RelizaException {
		Organization org = obtainOrganization();
		String c1rand = "testFindComponentList1" + UUID.randomUUID().toString();
		String c2rand = "testFindComponentList2" + UUID.randomUUID().toString();
		@SuppressWarnings("unused")
		Component p1 = componentService.createComponent(c1rand, org.getUuid(), ComponentType.COMPONENT, WhoUpdated.getTestWhoUpdated());
		@SuppressWarnings("unused")
		Component p2 = componentService.createComponent(c2rand, org.getUuid(), ComponentType.COMPONENT, WhoUpdated.getTestWhoUpdated());
		List<ComponentData> componentList = componentService.listComponentDataByOrganization(org.getUuid(), ComponentType.COMPONENT);
		Assertions.assertTrue(componentList.stream().filter(x -> x.getName().equals(c1rand)).toList().size() == 1);
		Assertions.assertTrue(componentList.stream().filter(x -> x.getName().equals(c2rand)).toList().size() == 1);
	}

}
