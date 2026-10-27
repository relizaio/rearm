/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.model.UserData;

/**
 * TPF-100 (TPF-74 child 1), architecture round 1 section 9.1 tests 1-3: componentRefs returns what
 * components returns, for every caller class, type, kinds form and the perspective argument, and refuses
 * what it refuses with the same message.
 */
public class ComponentRefsEquivalenceIntegrationTest extends ComponentRefsTestBase {

	// ---------- 1. equivalence ----------

	@Test
	public void refsEqualComponentsForEveryCallerTypeAndKindsForm() throws Exception {
		Fx f = fixture();
		for (Map.Entry<String, UserData> caller : f.callers().entrySet()) {
			for (String type : TYPES) {
				for (List<String> kinds : java.util.Arrays.asList(null, SOFTWARE)) {
					String at = caller.getKey() + " " + type + " " + kinds;
					var full = rows(caller.getValue(), "components", f.a(), type, null, kinds);
					var refs = rows(caller.getValue(), "componentRefs", f.a(), type, null, kinds);
					assertEquals(full, refs, at + ": same uuids, same six scalars");
					assertEquals(expected(f, caller.getKey(), type, kinds), refs.keySet(), at + " " + refs.values());
				}
			}
		}
		var org = rows(f.callers().get("uOrg"), "componentRefs", f.a(), "ANY", null, null);
		assertEquals("ACTIVE", org.get(f.c6().toString()).get("status"), "a row without a status key reads ACTIVE");
		assertEquals(f.vcs().toString(), org.get(f.c3().toString()).get("vcs"));
		assertNull(org.get(f.c1().toString()).get("vcs"));
		assertEquals(f.a().toString(), org.get(f.p1().toString()).get("org"));
		assertEquals("PRODUCT", org.get(f.p1().toString()).get("type"));
		assertTrue(org.get(f.c1().toString()).get("name").toString().startsWith("c1-"));
	}

	// ---------- 2. archive and kinds ----------

	@Test
	public void archivedAndBoardDocumentRowsAreFilteredAsBefore() throws Exception {
		Fx f = fixture();
		String c5 = f.c5().toString(), d1 = f.d1().toString(), c6 = f.c6().toString();
		for (UserData u : f.callers().values()) {
			for (String type : TYPES) {
				for (List<String> kinds : java.util.Arrays.asList(null, SOFTWARE, List.of("BOARD_DOCUMENT"))) {
					assertFalse(rows(u, "components", f.a(), type, null, kinds).containsKey(c5));
					assertFalse(rows(u, "componentRefs", f.a(), type, null, kinds).containsKey(c5));
				}
			}
		}
		UserData uOrg = f.callers().get("uOrg");
		for (String field : List.of("components", "componentRefs")) {
			assertTrue(rows(uOrg, field, f.a(), "ANY", null, null).containsKey(d1), field);
			assertFalse(rows(uOrg, field, f.a(), "ANY", null, SOFTWARE).containsKey(d1), field);
			assertTrue(rows(uOrg, field, f.a(), "ANY", null, SOFTWARE).containsKey(c6), field + ": no kind key is GENERIC");
			assertTrue(rows(uOrg, field, f.a(), "ANY", null, List.of("GENERIC")).containsKey(c6), field);
			assertFalse(rows(uOrg, field, f.a(), "ANY", null, List.of("HELM")).containsKey(c6), field);
		}
	}

	// ---------- 3. the perspective argument ----------

	@Test
	public void thePerspectiveArgumentAnswersAndRefusesAlike() throws Exception {
		Fx f = fixture();
		for (String caller : List.of("uOrg", "uPersp", "uAdmin")) {
			UserData u = f.callers().get(caller);
			for (String type : TYPES) {
				var full = rows(u, "components", f.a(), type, f.perspective(), null);
				var refs = rows(u, "componentRefs", f.a(), type, f.perspective(), null);
				assertEquals(full, refs, caller + " " + type);
				Set<String> want = switch (type) {
					case "COMPONENT" -> str(Stream.of(f.c2()));
					case "PRODUCT" -> str(Stream.of(f.p1()));
					default -> str(Stream.of(f.c2(), f.p1()));
				};
				assertEquals(want, refs.keySet(), caller + " " + type);
			}
		}
		UserData uOrg = f.callers().get("uOrg");
		assertRefusedAlike(f.callers().get("uComp"), f.a(), f.perspective(), "a caller without a grant on P");
		assertRefusedAlike(uOrg, f.a(), UUID.randomUUID(), "an unknown perspective");
		assertRefusedAlike(uOrg, "nope", null, "an organization uuid that is not a uuid");
		assertRefusedAlike(uOrg, f.a(), f.perspectiveOfB(), "a perspective of B asked under A");
	}

	private void assertRefusedAlike(UserData u, Object org, UUID perspective, String what) {
		for (String type : TYPES) {
			String full = refusal(call(u, "components", SIX, org, type, perspective, null), "components");
			ExecutionResult r = call(u, "componentRefs", SIX, org, type, perspective, null);
			String refs = refusal(r, "componentRefs");
			assertNull(r.getData(), what + ": a non-null root field nulls data");
			assertEquals(full, refs, what + " " + type);
		}
	}
}
