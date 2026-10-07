/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.DocumentRef;
import io.reliza.model.ReleaseData.ReleaseLifecycle;

/**
 * Whether a role's pass stands, as routing reads it (gaps §1.8): a pass, or a rejection a person
 * decided over, counts until an agent publishes a newer version of one of the role's inputs.
 */
class AgentRoutingServiceTest {

	private final AgentRoutingService routing = new AgentRoutingService();
	private final AgentDocumentService documents = mock(AgentDocumentService.class);
	private final SharedReleaseService releases = mock(SharedReleaseService.class);

	@ParameterizedTest(name = "{0}, decided over {1}, inputs moved {2} -> {3}")
	@CsvSource({
			"PASSED,   false, false, true",
			"PASSED,   false, true,  false",
			"REJECTED, false, false, false",
			"REJECTED, false, true,  false",
			"REJECTED, true,  false, true",
			"REJECTED, true,  true,  false",
	})
	void aPassStandsUntilItsInputsMove(SignOffOutcome outcome, boolean decidedOver, boolean inputsMoved,
			boolean expected) {
		ReflectionTestUtils.setField(routing, "agentDocumentService", documents);
		ReflectionTestUtils.setField(routing, "sharedReleaseService", releases);
		AgentBoardData board = new AgentBoardData();
		board.setBlockingPriority(1);

		AgentTaskRoleConfigData rc = new AgentTaskRoleConfigData();
		ReflectionTestUtils.setField(rc, "uuid", UUID.randomUUID());
		rc.setName("reviewer");
		rc.setRequiredInputs(List.of(new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.ARCHITECTURE,
				InputScope.TASK, null, null, null)));

		AgentTaskData td = new AgentTaskData();
		ReflectionTestUtils.setField(td, "uuid", UUID.randomUUID());
		SignOff last = new SignOff(rc.getName(), rc.getUuid(), UUID.randomUUID(), UUID.randomUUID(),
				ZonedDateTime.now().minusHours(2), ZonedDateTime.now().minusHours(1), outcome, null, null, null);
		td.addSignOff(last);
		when(documents.rejectionDecidedOver(any(), eq(1), eq(last))).thenReturn(decidedOver);

		if (inputsMoved) {
			// An agent's new design, handed over after the hop closed.
			UUID design = UUID.randomUUID();
			ReleaseData rd = new ReleaseData();
			rd.setLifecycle(ReleaseLifecycle.ASSEMBLED);
			ReflectionTestUtils.setField(rd, "createdDate", ZonedDateTime.now());
			rd.setDocument(new DocumentRef(RearmSpecificationType.ARCHITECTURE, "design.md", null, null, null,
					null, td.getUuid(), UUID.randomUUID(), 2, null));
			when(releases.getReleaseData(design)).thenReturn(Optional.of(rd));
			td.addRelease(design);
		}

		assertEquals(expected, routing.hasCurrentPass(td, board, rc));
	}
}
