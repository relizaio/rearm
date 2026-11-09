/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.ws.App;

/**
 * The registry holds the VEX handler as its Spring proxy, so the handler's REQUIRES_NEW
 * boundary on {@code dispatch} applies and a failed import cannot roll back the upload's
 * transaction.
 */
@SpringBootTest(classes = {App.class})
class ArtifactImportRegistryIntegrationTest {

	@Autowired private ArtifactImportRegistry registry;

	@Test
	void vexHandlerIsRegisteredAsATransactionalProxy() throws NoSuchMethodException {
		assertTrue(registry.isImportable(ArtifactType.VEX));
		ArtifactImportHandler vex = registry.handlerBeans.stream()
			.filter(h -> h.type() == ArtifactType.VEX).findFirst().orElseThrow();
		assertTrue(AopUtils.isAopProxy(vex));

		Transactional tx = AopUtils.getTargetClass(vex).getMethod("dispatch", UUID.class, ArtifactDto.class,
				ReleaseData.class, ArtifactBelongsTo.class, byte[].class, WhoUpdated.class)
			.getAnnotation(Transactional.class);
		assertEquals(Propagation.REQUIRES_NEW, tx.propagation());
	}
}
