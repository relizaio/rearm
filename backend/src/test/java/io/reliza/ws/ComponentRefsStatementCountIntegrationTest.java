/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.model.Branch;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.ReleaseDto;

/**
 * TPF-100 (TPF-74 child 1), architecture round 1 section 9.1 tests 4, 5 and 8: the refs list issues no
 * per-row statement; the section 3 timings (printed); and, because the measurement rule fired,
 * components computes effectiveLifecycle only when a caller selects it.
 */
public class ComponentRefsStatementCountIntegrationTest extends ComponentRefsTestBase {

	// ---------- 4. no per-row statement ----------

	@Test
	public void theRefsListIssuesNoPerRowStatement() throws Exception {
		Fx f = fixture();
		UserData uOrg = f.callers().get("uOrg"), uComp = f.callers().get("uComp");
		long nOrg = statements(() -> call(uOrg, "componentRefs", SIX, f.a(), "COMPONENT", null, null));
		long nComp = statements(() -> call(uComp, "componentRefs", SIX, f.a(), "COMPONENT", null, null));
		for (int i = 0; i < 20; i++) component("more-" + i + "-" + UUID.randomUUID(), f.a(), ComponentType.COMPONENT, null);
		long nOrg20 = statements(() -> call(uOrg, "componentRefs", SIX, f.a(), "COMPONENT", null, null));
		long nComp20 = statements(() -> call(uComp, "componentRefs", SIX, f.a(), "COMPONENT", null, null));
		assertEquals(nOrg, nOrg20, "an organization reader: 20 more rows, no more statements");
		assertEquals(nComp, nComp20, "a component grant: 20 more rows, no more statements");
		long full = statements(() -> call(uOrg, "components", SIX, f.a(), "COMPONENT", null, null));
		assertTrue(nOrg20 <= full, "refs " + nOrg20 + " statements, components " + full);
	}

	// ---------- 5. timings for architecture section 3 (printed, not asserted) ----------

	private static long bestMicros(Runnable r) {
		long best = Long.MAX_VALUE;
		for (int i = 0; i < 7; i++) {
			long t = System.nanoTime();
			r.run();
			if (i >= 2) best = Math.min(best, (System.nanoTime() - t) / 1000);
		}
		return best;
	}

	@Test
	public void printsTheSectionThreeTimings() throws Exception {
		Fx f = fixture();
		int n = componentService.listComponentDataByOrganization(f.a(), ComponentType.COMPONENT).size();
		long full = bestMicros(() -> componentService.listComponentDataByOrganization(f.a(), ComponentType.COMPONENT));
		long refs = bestMicros(() -> componentService.listComponentRefsByOrganization(f.a(), ComponentType.COMPONENT));
		UserData grants = user(f.a(), PermissionScope.COMPONENT, f.p1());
		for (UUID c : List.of(f.c1(), f.c2(), f.c3(), f.c4(), f.c6())) {
			userService.setUserPermission(grants.getUuid(), f.a(), PermissionScope.COMPONENT, c, PermissionType.READ_ONLY,
					List.of(), null, WU);
		}
		UserData sixGrants = userService.getUserData(grants.getUuid()).orElseThrow();
		long cascadeOrg = bestMicros(() -> authorizationService.readableComponentUuids(f.callers().get("uOrg"), f.a()));
		long cascadeGrants = bestMicros(() -> authorizationService.readableComponentUuids(sixGrants, f.a()));
		List<ComponentData> list = componentService.listComponentDataByOrganization(f.a(), ComponentType.COMPONENT);
		long lifecycle = bestMicros(() -> componentService.populateEffectiveLifecycle(list));
		System.out.println("TPF-100 timing: listComponentDataByOrganization " + full + " us for " + n + " rows ("
				+ (full / Math.max(1, n)) + " us/row)");
		System.out.println("TPF-100 timing: listComponentRefsByOrganization " + refs + " us for " + n + " rows ("
				+ (refs / Math.max(1, n)) + " us/row)");
		System.out.println("TPF-100 timing: readableComponentUuids organization reader " + cascadeOrg + " us");
		System.out.println("TPF-100 timing: readableComponentUuids five COMPONENT grants and one product grant "
				+ cascadeGrants + " us");
		System.out.println("TPF-100 timing: populateEffectiveLifecycle on the fixture list " + lifecycle + " us");
		assertNull(authorizationService.readableComponentUuids(f.callers().get("uOrg"), f.a()));
	}

	// ---------- 8. effectiveLifecycle only when selected (section 7; the rule fired) ----------

	private void release(Fx f, UUID component, String version, ReleaseLifecycle lifecycle) throws Exception {
		Branch base = branchService.getBaseBranchOfComponent(component).orElseThrow();
		ossReleaseService.createRelease(ReleaseDto.builder().component(component).org(f.a()).branch(base.getUuid())
				.version(version).status(ReleaseStatus.ACTIVE).lifecycle(lifecycle).build(), WU);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void componentsComputesEffectiveLifecycleOnlyWhenSelected() throws Exception {
		Fx f = fixture();
		release(f, f.c1(), "1.0.0", ReleaseLifecycle.DRAFT);
		release(f, f.c1(), "1.0.1", ReleaseLifecycle.GENERAL_AVAILABILITY);
		release(f, f.c2(), "1.0.0", ReleaseLifecycle.GENERAL_AVAILABILITY);
		release(f, f.c2(), "1.0.1", ReleaseLifecycle.END_OF_SUPPORT);
		UserData uOrg = f.callers().get("uOrg");
		String bare = "{ uuid }", withLifecycle = "{ uuid effectiveLifecycle }";

		long without = statements(() -> call(uOrg, "components", bare, f.a(), "COMPONENT", null, null));
		long with = statements(() -> call(uOrg, "components", withLifecycle, f.a(), "COMPONENT", null, null));
		assertEquals(without + 1, with, "the lifecycle statement runs only for a caller that selects the field");

		ExecutionResult r = call(uOrg, "components", withLifecycle, f.a(), "COMPONENT", null, null);
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		Map<String, Object> byUuid = new TreeMap<>();
		for (Map<String, Object> m : (List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get("components")) {
			byUuid.put((String) m.get("uuid"), m.get("effectiveLifecycle"));
		}
		assertEquals("GENERAL_AVAILABILITY", byUuid.get(f.c1().toString()));
		assertEquals("END_OF_SUPPORT", byUuid.get(f.c2().toString()));
		assertTrue(byUuid.containsKey(f.c4().toString()));
		assertNull(byUuid.get(f.c4().toString()), "no releases, no lifecycle");

		ExecutionResult one = as(uOrg, "query($c: ID!) { component(componentUuid: $c) { effectiveLifecycle } }",
				Map.of("c", f.c1().toString()));
		assertTrue(one.getErrors().isEmpty(), one.getErrors().toString());
		assertEquals("GENERAL_AVAILABILITY", ((Map<String, Object>) ((Map<String, Object>) one.getData()).get("component"))
				.get("effectiveLifecycle"), "the one-element fallback, unchanged");

		long perspectiveWithout = statements(() -> call(uOrg, "components", bare, f.a(), "ANY", f.perspective(), null));
		long perspectiveWith = statements(() -> call(uOrg, "components", withLifecycle, f.a(), "ANY", f.perspective(), null));
		assertEquals(perspectiveWithout + 1, perspectiveWith, "the perspective branch too");
	}
}
