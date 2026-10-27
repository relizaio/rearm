/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.AgentDocumentService;

/**
 * A NUL decoded from the element index of a prose document (task TPF-47, architecture-5 section 4 item 2 row 13,
 * sites 3 and 4): the publish and the check preview refuse it by its path inside the document, after the fetcher's
 * authorization, resolveCallingAgent and requireOrgTask (decision 10), so the rig is a real agent key holding the
 * fields' board gate, its session, and a task of that board, through the engine with the real authorization.
 */
public class NulInputAgentDocumentIntegrationTest extends AgentBoardEnforcementTestBase {

	@Autowired AgentDocumentService agentDocumentService;

	private static final String PREVIEW = "query p($s: ID!, $t: ID!, $e: String!) { agentElementCheckPreviewProgrammatic("
			+ "sessionUuid: $s, taskUuid: $t, specification: ARCHITECTURE, elements: $e) { catalogueVersion } }";
	private static final String PUBLISH = "mutation p($in: AgentDocumentPublishInput!) "
			+ "{ agentDocumentPublishProgrammatic(input: $in) { uuid } }";
	/** An element index whose first element's title holds the JSON escape of U+0000. */
	private static final String ELEMENTS =
			"{\"grammarVersion\":\"1.2\",\"elements\":[{\"id\":\"REQ-F-1\",\"title\":\"a\\u0000\"}]}";

	/** An agent key of the org (AGENT at the organization) holding the functions of the two fields' gate on board A. */
	private Caller agentOnBoardA(World w) throws RelizaException {
		List<PermissionFunction> preview = List.copyOf(
				AgentTaskDataFetcher.PROGRAMMATIC_BOARD_GATES.get("agentElementCheckPreviewProgrammatic").functions());
		assertEquals(preview, List.copyOf(
				AgentTaskDataFetcher.PROGRAMMATIC_BOARD_GATES.get("agentDocumentPublishProgrammatic").functions()));
		return key(w, on(w, PermissionScope.BOARD, w.a().getUuid(), PermissionType.READ_ONLY,
				preview.toArray(PermissionFunction[]::new)));
	}

	/** Exactly one error with that message, BAD_REQUEST. */
	private static void refused(ExecutionResult r, String message) {
		assertEquals(1, r.getErrors().size(), "one refusal: " + r.getErrors());
		GraphQLError err = r.getErrors().get(0);
		assertEquals(message, err.getMessage());
		assertEquals("BAD_REQUEST", String.valueOf(err.getErrorType()));
	}

	// item 2 row 13: the preview (site 4)
	@Test
	void aDecodedNulInThePreviewedElementIndexIsRefused() throws RelizaException {
		World w = world();
		Caller c = agentOnBoardA(w);
		ExecutionResult r = asKey(c.key(), PREVIEW, vars("s", c.session().getUuid().toString(),
				"t", w.onA().getUuid().toString(), "e", ELEMENTS));
		refused(r, "elements.elements[0].title: must not contain U+0000");
	}

	// item 2 row 13: the publish (site 3)
	@Test
	void aDecodedNulInThePublishedElementIndexIsRefused() throws RelizaException {
		World w = world();
		Caller c = agentOnBoardA(w);
		int before = agentDocumentService.documentsOfTask(w.onA()).size();
		ExecutionResult r = asKey(c.key(), PUBLISH, vars("in", vars("sessionUuid", c.session().getUuid().toString(),
				"taskUuid", w.onA().getUuid().toString(), "specification", "ARCHITECTURE", "path", "design/x.md",
				"digest", "sha256:" + "0".repeat(64), "elements", ELEMENTS)));
		refused(r, "input.elements.elements[0].title: must not contain U+0000");
		assertEquals(before, agentDocumentService.documentsOfTask(w.onA()).size());
	}

	// item 2 row 13: the order pin (decision 10), an unauthorized caller gets the authorization's answer
	@Test
	void aSessionTheKeyCannotUseIsAnsweredBeforeTheWalk() throws RelizaException {
		World w = world();
		Caller c = agentOnBoardA(w);
		UUID randomSession = UUID.randomUUID();
		ExecutionResult r = asKey(c.key(), PREVIEW, vars("s", randomSession.toString(),
				"t", w.onA().getUuid().toString(), "e", ELEMENTS));
		assertTrue(r.getErrors().stream().anyMatch(e -> ("Session not found: " + randomSession).equals(e.getMessage())),
				String.valueOf(r.getErrors()));
		assertFalse(r.getErrors().stream().anyMatch(e -> String.valueOf(e.getMessage()).contains("must not contain U+0000")),
				String.valueOf(r.getErrors()));
	}
}
