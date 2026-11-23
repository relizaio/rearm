/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import io.reliza.service.SeatGate;
import io.reliza.ws.App;

/**
 * The seat gate is a required bean and fails closed: this edition wires exactly one, the no-op
 * {@link OssSeatGate}. The shared services autowire it, so a missing or a second gate breaks every
 * signed-in request.
 */
@SpringBootTest(classes = {App.class})
class OssSeatGateBeanTest {

	@Autowired
	private ApplicationContext ctx;

	@Test
	void theOnlySeatGateIsTheOssGate() {
		assertEquals(1, ctx.getBeansOfType(SeatGate.class).size(), ctx.getBeansOfType(SeatGate.class).keySet().toString());
		assertEquals(OssSeatGate.class, AopUtils.getTargetClass(ctx.getBean(SeatGate.class)));
	}
}
