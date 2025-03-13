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
 * Unit test related to Product functionality
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
public class ProductTest 
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
	public void createdAndFindProductByUuidSuccess() throws RelizaException {
		Organization org = obtainOrganization();
		Component prod = componentService.createComponent("testFindProduct", org.getUuid(), ComponentType.PRODUCT, WhoUpdated.getTestWhoUpdated());
		Optional<Component> oProd = getComponentService.getComponent(prod.getUuid());
		Assertions.assertTrue(oProd.isPresent());
		Assertions.assertEquals(prod.getUuid(), oProd.get().getUuid());
	}
	
	@Test
	public void listProductsByOrg() throws RelizaException {
		Organization org = obtainOrganization();
		String p1rand = "testFindProductList1" + UUID.randomUUID().toString();
		String p2rand = "testFindProductList2" + UUID.randomUUID().toString();
		@SuppressWarnings("unused")
		Component prod1 = componentService.createComponent(p1rand, org.getUuid(), ComponentType.PRODUCT, WhoUpdated.getTestWhoUpdated());
		@SuppressWarnings("unused")
		Component prod2 = componentService.createComponent(p2rand, org.getUuid(), ComponentType.PRODUCT, WhoUpdated.getTestWhoUpdated());
		List<ComponentData> productList = componentService.listComponentDataByOrganization(org.getUuid(), ComponentType.PRODUCT);
		Assertions.assertTrue(productList.stream().filter(x -> x.getName().equals(p1rand)).toList().size() == 1);
		Assertions.assertTrue(productList.stream().filter(x -> x.getName().equals(p2rand)).toList().size() == 1);
	}
}
