/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import graphql.ExecutionResult;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.UserData;
import io.reliza.model.dto.ReleaseDto;

/**
 * componentRefs, what the first test round leaves open (TPF-100, mutation round 2, the mutant tester's T-1 to
 * T-5): the kinds filter inside a perspective, the refusal of an unauthenticated call, the perspective gate for
 * a caller of another organization, a list that reads no component document, and the lazy lifecycle when
 * kinds are named.
 */
public class ComponentRefsGapsIntegrationTest extends ComponentRefsTestBase {

	private void release(Fx f, UUID component, String version, ReleaseLifecycle lifecycle) throws Exception {
		Branch base = branchService.getBaseBranchOfComponent(component).orElseThrow();
		ossReleaseService.createRelease(ReleaseDto.builder().component(component).org(f.a()).branch(base.getUuid())
				.version(version).status(ReleaseStatus.ACTIVE).lifecycle(lifecycle).build(), WU);
	}

	// T-1: the kinds filter applies inside a perspective

	@Test
	public void theKindsFilterAppliesInsideAPerspective() throws Exception {
		Fx f = fixture();
		UserData uOrg = f.callers().get("uOrg");
		for (String type : TYPES) {
			assertTrue(rows(uOrg, "components", f.a(), type, f.perspective(), List.of("HELM")).isEmpty(), "components " + type);
			assertTrue(rows(uOrg, "componentRefs", f.a(), type, f.perspective(), List.of("HELM")).isEmpty(),
					"componentRefs asked for HELM inside a perspective of generic members: " + type);
			assertEquals(rows(uOrg, "components", f.a(), type, f.perspective(), SOFTWARE).keySet(),
					rows(uOrg, "componentRefs", f.a(), type, f.perspective(), SOFTWARE).keySet(), "the software form, " + type);
		}
	}

	// T-2: an unauthenticated call is refused on both fields

	@Test
	public void anUnauthenticatedCallIsRefusedOnBothFields() throws Exception {
		Fx f = fixture();
		doReturn(Optional.of(f.callers().get("uOrg"))).when(userService).getUserDataByAuth(any());
		for (String field : List.of("components", "componentRefs")) {
			SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
			Map<String, Object> vars = new LinkedHashMap<>();
			vars.put("o", f.a().toString());
			vars.put("t", "COMPONENT");
			vars.put("p", null);
			vars.put("k", null);
			ExecutionResult r = dgsQueryExecutor.execute(ARGS.formatted(field, "{ uuid }"), vars);
			refusal(r, field);
		}
	}

	// T-3: the perspective gate pairs the perspective with its organization

	@Test
	public void aCallerOfAnotherOrganizationIsRefusedAnEmptyPerspectiveOfThatOrganizationToo() throws Exception {
		Fx f = fixture();
		UUID emptyOfB = perspectiveService.createPerspective(f.b(), "empty-" + UUID.randomUUID(), WU).getUuid();
		UserData uB = f.callers().get("uB");
		for (String type : TYPES) {
			for (UUID perspective : List.of(f.perspectiveOfB(), emptyOfB)) {
				String viaDocuments = refusal(call(uB, "components", "{ uuid }", f.a(), type, perspective, null), "components");
				String viaRefs = refusal(call(uB, "componentRefs", "{ uuid }", f.a(), type, perspective, null), "componentRefs");
				assertEquals(viaDocuments, viaRefs, type + " " + perspective);
			}
		}
	}

	// T-4: the refs list reads no component document

	@Test
	public void theRefsListLoadsNoComponentEntity() throws Exception {
		Fx f = fixture();
		UserData uOrg = f.callers().get("uOrg");
		var st = statistics();
		st.setStatisticsEnabled(true);
		st.clear();
		rows(uOrg, "componentRefs", f.a(), "ANY", null, null);
		long refsLoads = st.getEntityStatistics(Component.class.getName()).getLoadCount();
		st.clear();
		rows(uOrg, "components", f.a(), "ANY", null, null);
		long documentLoads = st.getEntityStatistics(Component.class.getName()).getLoadCount();

		assertEquals(0, refsLoads, "the projection reads columns, not component documents");
		assertTrue(documentLoads > 0, "the document list does read them: " + documentLoads);
	}

	// T-5: the batched lifecycle statement also runs when kinds are named

	@Test
	public void theLifecycleStatementRunsOnceWhenKindsAreNamedToo() throws Exception {
		Fx f = fixture();
		release(f, f.c1(), "1.0.0", ReleaseLifecycle.DRAFT);
		release(f, f.c2(), "1.0.0", ReleaseLifecycle.GENERAL_AVAILABILITY);
		UserData uOrg = f.callers().get("uOrg");
		String bare = "{ uuid }", withLifecycle = "{ uuid effectiveLifecycle }";

		for (UUID perspective : new UUID[] {null, f.perspective()}) {
			String type = null == perspective ? "COMPONENT" : "ANY";
			long without = statements(() -> call(uOrg, "components", bare, f.a(), type, perspective, null));
			long with = statements(() -> call(uOrg, "components", withLifecycle, f.a(), type, perspective, null));
			long withoutKinds = statements(() -> call(uOrg, "components", bare, f.a(), type, perspective, SOFTWARE));
			long withKinds = statements(() -> call(uOrg, "components", withLifecycle, f.a(), type, perspective, SOFTWARE));
			assertEquals(without + 1, with, "unnamed kinds, perspective " + perspective);
			assertEquals(withoutKinds + 1, withKinds, "named kinds, perspective " + perspective);
		}
	}
}
