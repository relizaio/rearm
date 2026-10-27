/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.UserData;
import io.reliza.model.dto.ReleaseDto;

/**
 * componentRefs against components, what the second test round leaves open (TPF-100, mutation round 3, the
 * mutant tester's T-7 to T-10): the kinds filter inside a perspective whose members are of every kind, the
 * perspective gate for each pairing of reader, organization and perspective, the component documents a refs
 * call loads for every caller, type and kinds form, and the lazy lifecycle for every kinds form.
 */
public class ComponentRefsKindsGatesLoadsIntegrationTest extends ComponentRefsTestBase {

	private static final List<List<String>> KINDS_FORMS = java.util.Arrays.<List<String>>asList(null, List.of(), SOFTWARE,
			List.of("GENERIC"), List.of("HELM"), List.of("BOARD_DOCUMENT"), List.of("GENERIC", "BOARD_DOCUMENT"),
			List.of("HELM", "BOARD_DOCUMENT"));

	private void release(Fx f, UUID component, String version, ReleaseLifecycle lifecycle) throws Exception {
		Branch base = branchService.getBaseBranchOfComponent(component).orElseThrow();
		ossReleaseService.createRelease(ReleaseDto.builder().component(component).org(f.a()).branch(base.getUuid())
				.version(version).status(ReleaseStatus.ACTIVE).lifecycle(lifecycle).build(), WU);
	}

	private void setKind(UUID component, String kind) {
		jdbcTemplate.update("UPDATE rearm.components SET record_data = jsonb_set(record_data, '{kind}', to_jsonb(?::text)) WHERE uuid = ?",
				kind, component);
	}

	/** The perspective of the fixture with the board documents in it as well. */
	private void documentsIntoThePerspective(Fx f) throws Exception {
		assertTrue(f.documents().size() >= 2, "the board's document and its check-report series: " + f.documents());
		for (UUID document : f.documents()) {
			componentService.setPerspectives(document, List.of(f.perspective()), WU);
		}
	}

	/** What a call answered: its refusal, or the sorted uuids of its rows. */
	@SuppressWarnings("unchecked")
	private String outcome(ExecutionResult r, String field) {
		if (!r.getErrors().isEmpty()) return refusal(r, field);
		List<Map<String, Object>> list = (List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get(field);
		return "ok " + list.stream().map(m -> (String) m.get("uuid")).sorted().collect(Collectors.toList());
	}

	// T-7: the kinds filter of the perspective branch with members of every kind

	@Test
	public void theKindsFilterInAPerspectiveKeepsAndDropsEveryKindOfMember() throws Exception {
		Fx f = fixture();
		documentsIntoThePerspective(f);
		setKind(f.c2(), "HELM");
		UserData uOrg = f.callers().get("uOrg");
		Map<UUID, String> kindOf = new LinkedHashMap<>();
		kindOf.put(f.c2(), "HELM");
		kindOf.put(f.p1(), "GENERIC");
		f.documents().forEach(d -> kindOf.put(d, "BOARD_DOCUMENT"));

		for (String type : TYPES) {
			for (List<String> kinds : KINDS_FORMS) {
				Set<String> want = kindOf.keySet().stream()
						.filter(c -> "ANY".equals(type) || typeOf(c).name().equals(type))
						.filter(c -> null == kinds || kinds.isEmpty() || kinds.contains(kindOf.get(c)))
						.map(UUID::toString).collect(Collectors.toCollection(TreeSet::new));
				String where = type + " kinds " + kinds;
				assertEquals(want, rows(uOrg, "components", f.a(), type, f.perspective(), kinds).keySet(), "components " + where);
				assertEquals(want, rows(uOrg, "componentRefs", f.a(), type, f.perspective(), kinds).keySet(), "componentRefs " + where);
			}
		}
		// the software form leaves the board's documents out, no kinds keeps them
		Set<String> documents = str(f.documents().stream());
		assertTrue(rows(uOrg, "componentRefs", f.a(), "ANY", f.perspective(), SOFTWARE).keySet().stream().noneMatch(documents::contains));
		assertTrue(rows(uOrg, "componentRefs", f.a(), "ANY", f.perspective(), null).keySet().containsAll(documents));
	}

	// T-11: the kinds filter of the organization branch, for every kinds form, with members of every kind

	@Test
	public void theKindsFilterOfTheOrganizationBranchKeepsAndDropsEveryKindOfComponent() throws Exception {
		Fx f = fixture();
		setKind(f.c2(), "HELM");
		UserData uOrg = f.callers().get("uOrg");
		Map<UUID, String> kindOf = new LinkedHashMap<>();
		for (UUID c : f.live()) kindOf.put(c, "GENERIC");        // c6 has no kind key at all: GENERIC too
		kindOf.put(f.c2(), "HELM");
		f.documents().forEach(d -> kindOf.put(d, "BOARD_DOCUMENT"));
		assertTrue(kindOf.containsValue("HELM") && kindOf.containsValue("BOARD_DOCUMENT") && kindOf.containsValue("GENERIC"));

		for (String type : TYPES) {
			for (List<String> kinds : KINDS_FORMS) {
				Set<String> want = kindOf.keySet().stream()
						.filter(c -> "ANY".equals(type) || typeOf(c).name().equals(type))
						.filter(c -> null == kinds || kinds.isEmpty() || kinds.contains(kindOf.get(c)))
						.map(UUID::toString).collect(Collectors.toCollection(TreeSet::new));
				String where = type + " kinds " + kinds;
				assertEquals(want, rows(uOrg, "components", f.a(), type, null, kinds).keySet(), "components " + where);
				assertEquals(want, rows(uOrg, "componentRefs", f.a(), type, null, kinds).keySet(), "componentRefs " + where);
			}
		}
		// a single kind leaves the other members out
		Set<String> documents = str(f.documents().stream());
		Set<String> generic = rows(uOrg, "componentRefs", f.a(), "ANY", null, List.of("GENERIC")).keySet();
		assertTrue(generic.stream().noneMatch(documents::contains), "[GENERIC] holds no board document");
		assertFalse(generic.contains(f.c2().toString()), "[GENERIC] holds no HELM component");
		assertEquals(documents, rows(uOrg, "componentRefs", f.a(), "ANY", null, List.of("BOARD_DOCUMENT")).keySet(),
				"[BOARD_DOCUMENT] holds the board's documents and nothing else");
	}

	// T-8: the perspective gate for each pairing of the reader, the organization asked and the perspective

	@Test
	public void bothFieldsAnswerOrRefuseAlikeForEveryReaderOrganizationAndPerspectivePairing() throws Exception {
		Fx f = fixture();
		UUID emptyOfB = perspectiveService.createPerspective(f.b(), "empty-b-" + UUID.randomUUID(), WU).getUuid();
		UUID emptyOfA = perspectiveService.createPerspective(f.a(), "empty-a-" + UUID.randomUUID(), WU).getUuid();
		List<UUID> perspectives = List.of(emptyOfB, f.perspectiveOfB(), emptyOfA, f.perspective());
		List<String> readers = List.of("uAdmin", "uOrg", "uComp", "uProd", "uPersp", "uNone", "uB");
		int refused = 0, answered = 0;
		for (String reader : readers) {
			UserData user = f.callers().get(reader);
			for (UUID org : List.of(f.a(), f.b())) {
				for (UUID perspective : perspectives) {
					for (String type : TYPES) {
						String viaDocuments = outcome(call(user, "components", "{ uuid }", org, type, perspective, null), "components");
						String viaRefs = outcome(call(user, "componentRefs", "{ uuid }", org, type, perspective, null), "componentRefs");
						String where = reader + " org " + (org.equals(f.a()) ? "A" : "B") + " perspective " + perspective + " " + type;
						assertEquals(viaDocuments, viaRefs, where);
						if (viaDocuments.startsWith("ok")) answered++; else refused++;
					}
				}
			}
		}
		assertTrue(refused > 0 && answered > 0, "both outcomes occur: refused " + refused + ", answered " + answered);
		// the pairing the fixture is about: a reader of A, asked A, with an empty perspective of B
		String viaDocuments = outcome(call(f.callers().get("uOrg"), "components", "{ uuid }", f.a(), "ANY", emptyOfB, null), "components");
		String viaRefs = outcome(call(f.callers().get("uOrg"), "componentRefs", "{ uuid }", f.a(), "ANY", emptyOfB, null), "componentRefs");
		assertTrue(viaDocuments.contains("Not authorized"), viaDocuments);
		assertEquals(viaDocuments, viaRefs);
	}

	// T-9: a refs call loads fewer component documents than the same call to components, for every reader

	private long componentLoads(Runnable run) {
		var st = statistics();
		st.setStatisticsEnabled(true);
		st.clear();
		run.run();
		return st.getEntityStatistics(Component.class.getName()).getLoadCount();
	}

	@Test
	public void aRefsCallLoadsFewerComponentDocumentsThanComponentsForEveryReaderTypeAndKindsForm() throws Exception {
		Fx f = fixture();
		List<Long> shown = new ArrayList<>();
		for (String reader : List.of("uAdmin", "uOrg", "uComp", "uProd", "uPersp", "uNone")) {
			UserData user = f.callers().get(reader);
			long cascade = componentLoads(() -> authorizationService.readableComponentUuids(user, f.a()));
			for (String type : TYPES) {
				for (List<String> kinds : KINDS_FORMS) {
					String where = reader + " " + type + " kinds " + kinds;
					long refs = componentLoads(() -> call(user, "componentRefs", "{ uuid }", f.a(), type, null, kinds));
					long documents = componentLoads(() -> call(user, "components", "{ uuid }", f.a(), type, null, kinds));
					assertTrue(refs <= cascade, "refs loads only what the readable cascade loads: " + refs + " > " + cascade + ", " + where);
					assertTrue(refs < documents, "refs " + refs + " loads, components " + documents + ", " + where);
					shown.add(refs);
				}
			}
		}
		assertTrue(shown.size() == 6 * TYPES.size() * KINDS_FORMS.size());
	}

	@Test
	public void theRefsStatementCountDoesNotDependOnTheKindsNamed() throws Exception {
		Fx f = fixture();
		for (String reader : List.of("uOrg", "uComp", "uPersp")) {
			UserData user = f.callers().get(reader);
			for (String type : TYPES) {
				long bare = statements(() -> call(user, "componentRefs", "{ uuid }", f.a(), type, null, null));
				for (List<String> kinds : KINDS_FORMS) {
					assertEquals(bare, statements(() -> call(user, "componentRefs", "{ uuid }", f.a(), type, null, kinds)),
							reader + " " + type + " kinds " + kinds);
				}
			}
		}
	}

	// T-10: the batched lifecycle statement runs once for every kinds form, on both branches

	@Test
	public void theLifecycleStatementRunsOnceForEveryKindsFormOnBothBranches() throws Exception {
		Fx f = fixture();
		documentsIntoThePerspective(f);
		release(f, f.c1(), "1.0.0", ReleaseLifecycle.DRAFT);
		release(f, f.c2(), "1.0.0", ReleaseLifecycle.GENERAL_AVAILABILITY);
		release(f, f.c3(), "1.0.0", ReleaseLifecycle.GENERAL_AVAILABILITY);
		release(f, f.p1(), "1.0.0", ReleaseLifecycle.GENERAL_AVAILABILITY);
		for (UUID document : f.documents()) release(f, document, "1.0.0", ReleaseLifecycle.GENERAL_AVAILABILITY);
		UserData uOrg = f.callers().get("uOrg");

		for (UUID perspective : new UUID[] {null, f.perspective()}) {
			for (List<String> kinds : java.util.Arrays.<List<String>>asList(null, List.of(), SOFTWARE, List.of("GENERIC"),
					List.of("BOARD_DOCUMENT"), List.of("GENERIC", "BOARD_DOCUMENT"))) {
				int rows = rows(uOrg, "components", f.a(), "ANY", perspective, kinds).size();
				assertTrue(rows >= 2, "two or more components, or one statement per component and the batched one look alike: "
						+ rows + ", " + kinds);
				long without = statements(() -> call(uOrg, "components", "{ uuid }", f.a(), "ANY", perspective, kinds));
				long with = statements(() -> call(uOrg, "components", "{ uuid effectiveLifecycle }", f.a(), "ANY", perspective, kinds));
				assertEquals(without + 1, with, "perspective " + perspective + " kinds " + kinds);
			}
		}
	}
}
