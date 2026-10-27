/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.service.saas.TeaActDispatcher;

/**
 * TEA-7, section 4.3: the queue classes run every act deterministically from the test thread
 * ({@code teaOutputEventService.runRequested(release)}) once the pass's transaction has returned.
 * The after-commit hop is therefore a mock here: a real hop would race the test for the claim.
 * The hop itself is TeaOutputEventHopIntegrationTest's.
 */
abstract class TeaOutputEventQueueIntegrationTestBase extends TeaOutputEventIntegrationTestBase {

	@MockitoBean TeaActDispatcher teaActDispatcher;

	/** A release whose approval rule TEA_PUBLISHes, approved: one request queued. */
	record Approved(World w, ReleaseOutputEvent publish) {}

	Approved approvedWithPublishRule(String purl) throws Exception {
		World w = componentRelease(purl);
		UUID entry = approvalEntry();
		ReleaseOutputEvent publish = teaAction(EventType.TEA_PUBLISH, "publish-on-approval");
		configure(w.component(), rule("approved", approved(entry), publish), publish);
		approve(w.release(), entry);
		assertTrue(queued(w.release()), "the approval queued a publish");
		return new Approved(w, publish);
	}
}
