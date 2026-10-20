/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseOutputEvent;

/**
 * The two reproduced mutations through the executor (task TPF-63, architecture-1 section 4 row 3): an
 * input that does not fit the resolver's types, and the VCS repository's one-trigger invariant, answer
 * BAD_REQUEST naming the field instead of "Internal server error", and write nothing. The rig, mocks
 * and helpers are {@link InputConversionGraphQlTestBase}'s.
 */
public class InputConversionGraphQlTest extends InputConversionGraphQlTestBase {

	private static final String SET = "mutation set($vcs: ID!, $triggers: [ReleaseOutputEventInput!]!) "
			+ "{ setVcsRepositoryOutputTriggers(vcsUuid: $vcs, triggers: $triggers) { uuid } }";

	private UUID vcs() {
		return vcsRepositoryService.provisionVcsRepository(testInitializer.obtainOrganization().getUuid(),
				"github.com/example/tpf63-" + UUID.randomUUID(), VcsType.GIT, WU);
	}

	private List<ReleaseOutputEvent> triggersOf(UUID vcs) {
		return List.copyOf(vcsRepositoryService.getVcsRepositoryData(vcs).orElseThrow().getOutputTriggers());
	}

	// row 3 case 1
	@Test
	void anUnknownStoredInNamesTheFieldAndItsValues() throws RelizaException {
		UUID release = release();
		List<UUID> before = artifactsOf(release);
		ExecutionResult r = dgsQueryExecutor.execute(ADD, map("in", addInput(release, artifact("EXTERNAL"))));
		refused(r, "artifactInput.artifact.storedIn: must be one of REARM, EXTERNALLY", "BAD_REQUEST",
				() -> artifactsOf(release), before);
	}

	// row 3 case 2
	@Test
	void aReleaseThatIsNotAUuid() {
		Map<String, Object> in = map("release", "not-a-uuid", "belongsTo", "RELEASE", "artifact", artifact("EXTERNALLY"));
		ExecutionResult r = dgsQueryExecutor.execute(ADD, map("in", in));
		refused(r, "artifactInput.release: not a uuid", "BAD_REQUEST", null, null);
	}

	// TPF-64 architecture-2 section 4 row 4: the conversion at updateBranch runs before any read or write
	@Test
	void aBranchUuidThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute("mutation($b: BranchInput!) { updateBranch(branch: $b) { uuid } }",
				map("b", map("uuid", "not-a-uuid", "name", "x")));
		refused(r, "branch.uuid: not a uuid", "BAD_REQUEST", null, null);
	}

	// row 3 case 3
	@Test
	void nestedArtifactsAreRefused() throws RelizaException {
		UUID release = release();
		List<UUID> before = artifactsOf(release);
		Map<String, Object> artifact = artifact("EXTERNALLY");
		artifact.put("artifacts", List.of(map("type", "OTHER", "displayIdentifier", "child")));
		ExecutionResult r = dgsQueryExecutor.execute(ADD, map("in", addInput(release, artifact)));
		refused(r, "artifactInput.artifact.artifacts: nested artifacts are not accepted by addArtifactManual; "
				+ "add each artifact with its own call", "BAD_REQUEST", () -> artifactsOf(release), before);
	}

	// row 3 case 4
	@Test
	void noArtifactIsRefused() throws RelizaException {
		UUID release = release();
		List<UUID> before = artifactsOf(release);
		ExecutionResult r = dgsQueryExecutor.execute(ADD, map("in", addInput(release, null)));
		refused(r, "artifactInput.artifact: required", "BAD_REQUEST", () -> artifactsOf(release), before);
	}

	// row 3 case 5
	@Test
	void noInputIsRefused() {
		ExecutionResult r = dgsQueryExecutor.execute(ADD, Map.of());
		refused(r, "artifactInput: required", "BAD_REQUEST", null, null);
	}

	// row 3 case 6
	@Test
	void aValidArtifactIsAdded() throws RelizaException {
		UUID release = release();
		int before = artifactsOf(release).size();
		ExecutionResult r = dgsQueryExecutor.execute(ADD, map("in", addInput(release, artifact("EXTERNALLY"))));
		assertTrue(r.getErrors().isEmpty(), "no errors: " + r.getErrors());
		assertEquals(before + 1, artifactsOf(release).size());
	}

	// row 3 case 7
	@Test
	void anIntegrationThatIsNotAUuid() {
		UUID vcs = vcs();
		List<ReleaseOutputEvent> before = triggersOf(vcs);
		ExecutionResult r = dgsQueryExecutor.execute(SET, map("vcs", vcs.toString(), "triggers",
				List.of(map("name", "v", "type", "EXTERNAL_VALIDATION", "integration", "not-a-uuid"))));
		refused(r, "triggers[0].integration: not a uuid", "BAD_REQUEST", () -> triggersOf(vcs), before);
	}

	// row 3 case 8
	@Test
	void anotherTriggerTypeIsRefused() {
		UUID vcs = vcs();
		List<ReleaseOutputEvent> before = triggersOf(vcs);
		ExecutionResult r = dgsQueryExecutor.execute(SET, map("vcs", vcs.toString(), "triggers",
				List.of(map("name", "n", "type", "INTEGRATION_TRIGGER"))));
		refused(r, "triggers[0].type: must be EXTERNAL_VALIDATION on a VCS repository", "BAD_REQUEST",
				() -> triggersOf(vcs), before);
	}

	// row 3 case 9
	@Test
	void twoTriggersAreRefused() {
		UUID vcs = vcs();
		List<ReleaseOutputEvent> before = triggersOf(vcs);
		ExecutionResult r = dgsQueryExecutor.execute(SET, map("vcs", vcs.toString(), "triggers",
				List.of(map("name", "a", "type", "EXTERNAL_VALIDATION"), map("name", "b", "type", "EXTERNAL_VALIDATION"))));
		refused(r, "triggers: a VCS repository takes at most one trigger", "BAD_REQUEST", () -> triggersOf(vcs), before);
	}

	/**
	 * Row 3 case 10: unchanged, pinned -- an enum name the schema does not declare never reaches the
	 * resolver. The DGS test executor wraps graphql-java's variable-coercion error in a TypedGraphQLError
	 * (getErrorType() BAD_REQUEST, extensions errorType BAD_REQUEST), so the ValidationError is read
	 * from the classification it carries, as SbomScoreReleaseGraphqlIntegrationTest.isValidationError does.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void anUndeclaredEventTypeStaysTheSchemaRefusal() {
		UUID vcs = vcs();
		List<ReleaseOutputEvent> before = triggersOf(vcs);
		ExecutionResult r = dgsQueryExecutor.execute(SET, map("vcs", vcs.toString(), "triggers",
				List.of(map("name", "n", "type", "TEA_PUBLISH"))));
		assertEquals(1, r.getErrors().size(), "one refusal: " + r.getErrors());
		GraphQLError err = r.getErrors().get(0);
		assertEquals("Variable 'triggers' has an invalid value: Invalid input for enum 'EventType'. "
				+ "No value found for name 'TEA_PUBLISH'", err.getMessage());
		Map<String, Object> extensions = (Map<String, Object>) err.toSpecification().get("extensions");
		assertEquals("ValidationError", extensions.get("classification"), String.valueOf(extensions));
		assertEquals(before, triggersOf(vcs));
	}

	// row 3 case 11
	@Test
	void oneExternalValidationTriggerIsSet() {
		UUID vcs = vcs();
		ExecutionResult r = dgsQueryExecutor.execute(SET, map("vcs", vcs.toString(), "triggers",
				List.of(map("name", "pr-validation", "type", "EXTERNAL_VALIDATION"))));
		assertTrue(r.getErrors().isEmpty(), "no errors: " + r.getErrors());
		List<ReleaseOutputEvent> triggers = triggersOf(vcs);
		assertEquals(1, triggers.size());
		assertEquals(EventType.EXTERNAL_VALIDATION, triggers.get(0).getType());
	}
}
