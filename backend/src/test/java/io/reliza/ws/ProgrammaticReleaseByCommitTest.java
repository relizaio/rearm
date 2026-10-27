/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.User;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.ReleaseService;
import io.reliza.service.UserService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * TPF-98 (TPF-72 child 1): {@code getReleaseByCommitProgrammatic} and
 * {@code getReleasesByCommitProgrammatic} beyond the table rows 15 and 16 of
 * {@link ProgrammaticReleaseWriteOrderTest}: one answer ({@code "{}"} / {@code []}) for every miss,
 * exact and org-scoped matching on the head commit only, the newest release first with no status
 * filter, and the key-type matrix of {@code getReleaseByReleaseVersionProgrammatic} (row 2), asserted
 * as parity arm by arm.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ProgrammaticReleaseByCommitTest extends ProgrammaticReleaseWriteOrderTestBase {

	@Autowired private GetSourceCodeEntryService getSourceCodeEntryService;
	@Autowired private ReleaseService releaseService;
	@Autowired private UserService userService;

	// ---------- calls and fixtures of the by-commit cases ----------

	private static String randomCommit() {
		return UUID.randomUUID().toString().replace("-", "") + "dddddddd";
	}

	/** Row 15 (the String field) with tenant key and arguments; asserts no error. */
	private String one(ApiKey key, Object component, String commit) {
		return (String) result(asKey(key, row(15).op(), m("c", component, "commit", commit)), row(15).field());
	}

	/** Row 16 (the list field) with tenant key and arguments; asserts no error. */
	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> all(ApiKey key, Object component, String commit) {
		return (List<Map<String, Object>>) result(asKey(key, row(16).op(), m("c", component, "commit", commit)),
				row(16).field());
	}

	/** The miss vocabulary: exactly "{}" on row 15 and exactly [] on row 16. */
	private void missBoth(ApiKey key, Object component, String commit) {
		assertEquals("{}", one(key, component, commit), "row 15 with [" + commit + "]");
		assertEquals(List.of(), all(key, component, commit), "row 16 with [" + commit + "]");
	}

	private static List<Object> field(List<Map<String, Object>> rels, String name) {
		return rels.stream().map(rel -> rel.get(name)).toList();
	}

	private UUID entry(UUID branch, UUID vcs, UUID org, String commit) throws RelizaException {
		return sourceCodeEntryService.createSourceCodeEntry(SceDto.builder()
				.branch(branch).vcs(vcs).organizationUuid(org).commit(commit).build(), WU).getUuid();
	}

	private UUID release(UUID component, UUID org, UUID branch, String version, UUID head, List<UUID> commits,
			ReleaseStatus status) throws RelizaException {
		ReleaseDto.ReleaseDtoBuilder dto = ReleaseDto.builder().component(component).org(org).branch(branch)
				.version(version).sourceCodeEntry(head).status(status).lifecycle(ReleaseLifecycle.DRAFT);
		if (null != commits) dto.commits(commits);
		return ossReleaseService.createRelease(dto.build(), WU).getUuid();
	}

	/** The OBOM without what differs between two exports of one release (serial number, timestamp). */
	private static JsonNode obomCore(String doc) {
		JsonNode node = Utils.OM.readTree(doc);
		if (node instanceof ObjectNode o) {
			o.remove("serialNumber");
			if (o.get("metadata") instanceof ObjectNode meta) meta.remove("timestamp");
		}
		return node;
	}

	private static void assertSameDocument(String expected, String actual, String message) {
		assertEquals(obomCore(expected), obomCore(actual), message);
	}

	// ---------- case 9: one text for every miss ----------

	@Test
	public void case9ByCommitAnswersOneTextForEveryMiss() throws RelizaException {
		World w = world();
		Tenant t = w.a();
		Ids a = w.idsOfA();
		ApiKey key = t.freeformKey();
		UUID vcsOfA = ComponentData.dataFromRecord(t.component()).getVcs();

		// the service method is org-bound and exact (before the second-repository entry below exists)
		assertEquals(Set.of(t.sce()), getSourceCodeEntryService.findSceUuidsByCommitAndOrg(t.org(), t.commit()));
		assertEquals(Set.of(), getSourceCodeEntryService.findSceUuidsByCommitAndOrg(w.b().org(), t.commit()));
		assertEquals(Set.of(), getSourceCodeEntryService.findSceUuidsByCommitAndOrg(t.org(), "%"));
		assertEquals(Set.of(), getSourceCodeEntryService.findSceUuidsByCommitAndOrg(t.org(), t.commit().substring(0, 7)));

		// (a) a commit nobody recorded; (b) a commit recorded by another organization
		missBoth(key, a.component(), randomCommit());
		missBoth(key, a.component(), w.b().commit());

		// (c) a commit recorded in A, on a release of the sibling only; and A's commit asked of the sibling
		Branch sb = branchService.getBaseBranchOfComponent(t.sibling().getUuid()).orElseThrow();
		String siblingCommit = randomCommit();
		UUID siblingEntry = entry(sb.getUuid(), vcsOfA, t.org(), siblingCommit);
		release(t.sibling().getUuid(), t.org(), sb.getUuid(), "1.0.0", siblingEntry, null, ReleaseStatus.ACTIVE);
		missBoth(key, a.component(), siblingCommit);
		missBoth(key, t.sibling().getUuid(), t.commit());
		assertEquals(1, all(key, t.sibling().getUuid(), siblingCommit).size(), "control: the sibling's own head commit");

		// (d) changelog-only: e2 is in the commits[] of 1.0.5 alone, whose head is e3
		String c2 = randomCommit();
		String c3 = randomCommit();
		UUID e2 = entry(t.branch().getUuid(), vcsOfA, t.org(), c2);
		UUID e3 = entry(t.branch().getUuid(), vcsOfA, t.org(), c3);
		release(a.component(), t.org(), t.branch().getUuid(), "1.0.5", e3, List.of(e2), ReleaseStatus.ACTIVE);
		assertTrue(sharedReleaseService.findReleaseDatasBySce(e2, t.org()).stream()
				.anyMatch(rd -> "1.0.5".equals(rd.getVersion())), "the release query reaches 1.0.5 through commits[]");
		missBoth(key, a.component(), c2);
		List<Map<String, Object>> viaHead = all(key, a.component(), c3);
		assertEquals(List.of("1.0.5"), field(viaHead, "version"), "control: 1.0.5 through its head");

		// (e) empty, blank, case, prefix and wildcard inputs match nothing
		String prefix7 = t.commit().substring(0, 7);
		for (String x : List.of("", "   ", t.commit().toUpperCase(Locale.ROOT), prefix7, "%", "_", prefix7 + "%")) {
			missBoth(key, a.component(), x);
		}

		// (f) surrounding whitespace is trimmed
		for (String x : List.of(t.commit() + "\n", " " + t.commit())) {
			String doc = one(key, a.component(), x);
			assertTrue(doc.contains("\"1.0.0\""), doc);
			assertEquals(List.of("1.0.0"), field(all(key, a.component(), x), "version"));
		}

		// the same commit on a second repository of A: two entries, still only A's release on A's component
		UUID vcs2 = vcsRepositoryService.provisionVcsRepository(t.org(),
				"github.com/example/order-" + UUID.randomUUID(), VcsType.GIT, WU);
		Component comp2 = componentService.createComponent("order_" + UUID.randomUUID(), t.org(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", vcs2, WU);
		Branch b2 = branchService.getBaseBranchOfComponent(comp2.getUuid()).orElseThrow();
		UUID secondEntry = entry(b2.getUuid(), vcs2, t.org(), t.commit());
		assertEquals(Set.of(t.sce(), secondEntry),
				getSourceCodeEntryService.findSceUuidsByCommitAndOrg(t.org(), t.commit()));
		assertTrue(one(key, a.component(), t.commit()).contains("\"1.0.0\""));
		assertEquals(List.of(t.release().toString()), field(all(key, a.component(), t.commit()), "uuid"));

		// (g) the system gate comes before the commit lookup
		licenseStatus.setSystemSealed(true);
		for (int n : List.of(15, 16)) {
			assertEquals(SEALED, refusal(asKey(key, row(n).op(), m("c", a.component(), "commit", w.b().commit())),
					row(n).field()), row(n).field());
		}
		licenseStatus.setSystemSealed(false);
	}

	// ---------- case 10: several releases on one head commit ----------

	@Test
	public void case10TwoReleasesOnOneCommitAnswerTheNewest() throws RelizaException {
		World w = world();
		Tenant t = w.a();
		ApiKey key = t.freeformKey();
		UUID comp = t.component().getUuid();

		UUID second = release(comp, t.org(), t.branch().getUuid(), "1.0.1", t.sce(), null, ReleaseStatus.ACTIVE);
		String doc = one(key, comp, t.commit());
		assertTrue(doc.contains("\"1.0.1\""), doc);
		assertSameDocument(releaseService.exportReleaseAsObom(second).toString(), doc, "the newest release's OBOM");
		List<Map<String, Object>> rels = all(key, comp, t.commit());
		assertEquals(List.of(second.toString(), t.release().toString()), field(rels, "uuid"));
		assertEquals(List.of("1.0.1", "1.0.0"), field(rels, "version"));
		ZonedDateTime newer = ZonedDateTime.parse(String.valueOf(rels.get(0).get("createdDate")));
		ZonedDateTime older = ZonedDateTime.parse(String.valueOf(rels.get(1).get("createdDate")));
		assertFalse(newer.isBefore(older), newer + " before " + older);

		// an ARCHIVED release is returned too: no status filter, as by-version
		UUID third = release(comp, t.org(), t.branch().getUuid(), "1.0.2", t.sce(), null, ReleaseStatus.ARCHIVED);
		assertTrue(one(key, comp, t.commit()).contains("\"1.0.2\""));
		assertEquals(List.of(third.toString(), second.toString(), t.release().toString()),
				field(all(key, comp, t.commit()), "uuid"));
	}

	// ---------- case 11: the key-type matrix is by-version's ----------

	/** Row 2 first, then row 15 with the tenant's commit: both answer, the same document. */
	private void admittedAlike(String arm, ApiKey key, Object componentRef, String commit) {
		String byVersion = (String) result(asKey(key, row(2).op(), m("c", componentRef)), row(2).field());
		String byCommit = one(key, componentRef, commit);
		assertTrue(byVersion.contains("\"1.0.0\""), arm + ": " + byVersion);
		assertSameDocument(byVersion, byCommit, arm);
	}

	/** Row 2 first, then row 15: both refuse, with the same text, which is the expected one. */
	private void refusedAlike(String arm, ApiKey key, Object componentRef, String commit, String expected) {
		String byVersion = refusal(asKey(key, row(2).op(), m("c", componentRef)), row(2).field());
		String byCommit = refusal(asKey(key, row(15).op(), m("c", componentRef, "commit", commit)), row(15).field());
		assertEquals(byVersion, byCommit, arm + ": by-commit refuses as by-version");
		assertEquals(expected, byCommit, arm);
	}

	@Test
	public void case11KeyTypesMatchByVersion() throws RelizaException {
		World w = world();
		Tenant t = w.a();
		UUID comp = t.component().getUuid();
		String name = ComponentData.dataFromRecord(t.component()).getName();
		String commit = t.commit();

		String tag = UUID.randomUUID().toString().substring(0, 8);
		User owner = userService.createUser("Order " + tag, "order-" + tag + "@order.io", true, List.of(t.org()),
				"order-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(owner.getUuid(), t.org(), PermissionScope.ORGANIZATION, t.org(),
				PermissionType.READ_ONLY, List.of(PermissionFunction.RESOURCE), null, WU);
		ApiKey userKey = apiKeyService.createObjectApiKey(owner.getUuid(), ApiTypeEnum.USER, t.org(),
				UUID.randomUUID().toString(), "order test user key", WU);
		apiKeyService.setPermissionsOnApiKey(userKey.getUuid(), null, List.of(new PermissionDto(t.org(),
				PermissionScope.COMPONENT, comp, PermissionType.READ_ONLY, Set.of(PermissionFunction.RESOURCE), null)), WU);
		userKey = apiKeyService.getApiKey(userKey.getUuid()).orElseThrow();

		User owner2 = userService.createUser("Leaver " + tag, "leaver-" + tag + "@order.io", true, List.of(w.b().org()),
				"leaver-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		ApiKey leaverKey = apiKeyService.createObjectApiKey(owner2.getUuid(), ApiTypeEnum.USER, t.org(),
				UUID.randomUUID().toString(), "order test leaver key", WU);
		leaverKey = apiKeyService.getApiKey(leaverKey.getUuid()).orElseThrow();

		ApiKey instanceKey = apiKeyService.createObjectApiKey(UUID.randomUUID(), ApiTypeEnum.INSTANCE, t.org(), null,
				"order test instance key", WU);
		instanceKey = apiKeyService.getApiKey(instanceKey.getUuid()).orElseThrow();

		admittedAlike("COMPONENT key, uuid", t.componentKey(), comp, commit);
		admittedAlike("ORGANIZATION_RW key, uuid", t.orgKey(), comp, commit);
		admittedAlike("ORGANIZATION_RW key, name", t.orgKey(), name, commit);
		admittedAlike("FREEFORM key, uuid", t.freeformKey(), comp, commit);
		admittedAlike("FREEFORM key, name", t.freeformKey(), name, commit);
		admittedAlike("FREEFORM key with the AGENT function only, uuid", w.ca().key(), comp, commit);
		admittedAlike("USER key whose owner holds READ, uuid", userKey, comp, commit);

		refusedAlike("COMPONENT key of the sibling", t.siblingComponentKey(), comp, commit, NOT_AUTHORIZED);
		refusedAlike("COMPONENT key, name", t.componentKey(), name, commit,
				"A component name cannot be used with a component-scoped API key; pass the uuid or omit it");
		refusedAlike("B's ORGANIZATION_RW key", w.b().orgKey(), comp, commit, NOT_AUTHORIZED);
		refusedAlike("FREEFORM key with READ on the sibling only", t.narrowKey(), comp, commit, NOT_AUTHORIZED);
		refusedAlike("USER key whose owner is a member of B only", leaverKey, comp, commit, NOT_AUTHORIZED);
		refusedAlike("INSTANCE key", instanceKey, comp, commit, "Must provide component UUID or name as input");

		// the Basic form answers what the bearer form answers
		Row r = row(15);
		Map<String, Object> vars = m("c", comp, "commit", commit);
		String basic = (String) result(asBasic(freeformId(t.org(), t.freeformKey().getKeyOrder()), t.freeformSecret(),
				r.op(), vars), r.field());
		assertSameDocument(one(t.freeformKey(), comp, commit), basic, "Basic form");
	}
}
