/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.ApprovalState;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseInputEvent;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaPublicationData;
import io.reliza.service.TeaPublicationService.TeaPublicationState;

/**
 * TEA-7, round 3, case 47 (T-10, the R6 replay): a publish whose pass evaluated the release
 * before a disapproval committed reaches the act after the hide ran; the gate asks the rules
 * again under the lease and withholds it, noting it once on the release. The rules decide, not a
 * stamp: once the hide rule stops matching, the same request publishes. The stale request is
 * written with the repository statement, as case 39 writes its rows.
 */
public class TeaOutputEventActGateIntegrationTest extends TeaOutputEventQueueIntegrationTestBase {

	private static final String COMMENT = "Action 'p' fired by rule 'qa approved'";

	private List<String> teaEvents(UUID release) {
		return events(release, ReleaseUpdateScope.TEA_PUBLICATION).stream()
				.map(e -> e.rua() + " " + e.oldValue() + " -> " + e.newValue()).toList();
	}

	/** The withheld notes the act wrote (the pass writes "withheld while", the act "withheld before publishing"). */
	private List<ReleaseUpdateEvent> withheldAtTheAct(UUID release) {
		return withheld(release).stream().filter(e -> e.message().contains("withheld before publishing")).toList();
	}

	@Test
	void aPublishWhosePassPredatesAHideThatRanIsWithheldAtTheAct() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID release = w.release();
		UUID e1 = approvalEntry();
		UUID e2 = approvalEntry();
		ReleaseOutputEvent p = teaAction(EventType.TEA_PUBLISH, "p");
		ReleaseOutputEvent h = teaAction(EventType.TEA_HIDE, "h");
		ReleaseInputEvent publishRule = rule("qa approved", approved(e1), p);
		ReleaseInputEvent hideRule = rule("security disapproved", disapproved(e2), h);
		configure(w.component(), List.of(publishRule, hideRule), p, h);

		approve(release, e1);
		teaOutputEventService.runRequested(release);
		assertEquals(1, head(release).orElseThrow().getLatestVersion());
		vote(release, e2, ApprovalState.DISAPPROVED);
		teaOutputEventService.runRequested(release);
		assertEquals(TeaPublicationState.HIDDEN, head(release).orElseThrow().getState());
		assertEquals(1, triggerRows(release, h.getUuid()).size(), "'h' fired");
		List<String> eventsAfterHide = teaEvents(release);

		// the stale pass: it evaluated the release before the disapproval committed
		assertEquals(1, releaseRepository.requestTeaAct(release, "PUBLISH", p.getUuid().toString(), "p", COMMENT));
		teaOutputEventService.runRequested(release);

		TeaPublicationData head = head(release).orElseThrow();
		assertEquals(TeaPublicationState.HIDDEN, head.getState());
		assertEquals(1, head.getLatestVersion());
		assertEquals(1, collectionRepository.listByPublication(head.getUuid()).size(), "no collection was minted");
		assertEquals(eventsAfterHide, teaEvents(release), "no new TEA_PUBLICATION event");
		List<ReleaseUpdateEvent> note = withheldAtTheAct(release);
		assertEquals(1, note.size());
		assertEquals(p.getUuid(), note.get(0).objectId());
		assertEquals("p", note.get(0).oldValue());
		assertEquals("TEA_PUBLISH withheld: HIDE_RULE_MATCHES", note.get(0).newValue());
		assertEquals("Action 'p': withheld before publishing while action 'h' matches on rule 'security disapproved'",
				note.get(0).message());
		assertTrue(noTeaActKey(release), "every teaAct key is gone");

		// the same stale request again: withheld again, noted once
		assertEquals(1, releaseRepository.requestTeaAct(release, "PUBLISH", p.getUuid().toString(), "p", COMMENT));
		teaOutputEventService.runRequested(release);
		assertEquals(1, withheldAtTheAct(release).size(), "no second note");
		assertEquals(eventsAfterHide, teaEvents(release));
		assertTrue(noTeaActKey(release));

		// an admin disables the hide rule: the rules as they are now let the same request publish
		hideRule.setEnabled(false);
		configure(w.component(), List.of(publishRule, hideRule), p, h);
		assertEquals(1, releaseRepository.requestTeaAct(release, "PUBLISH", p.getUuid().toString(), "p", COMMENT));
		teaOutputEventService.runRequested(release);
		head = head(release).orElseThrow();
		assertEquals(TeaPublicationState.PUBLISHED, head.getState(), "the hidden release is published again");
		List<String> tea = teaEvents(release);
		assertEquals(eventsAfterHide.size() + 1, tea.size());
		// the content did not change, so the collection version is the hidden one (case 37 adds SBOMs for v2)
		assertEquals("CHANGED HIDDEN -> PUBLISHED v" + head.getLatestVersion(), tea.get(tea.size() - 1));
		assertTrue(noTeaActKey(release));
	}
}
