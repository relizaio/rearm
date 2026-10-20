/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ApiKey;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.Component;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.service.GetComponentService;

/**
 * SCORE-52 T-17 and T-18 through the resolvers (carried from SCORE-47 T-26, relaxed by ADR-6): an
 * existing artifact is listed by reference on {@code updateRelease} and {@code addReleaseManual}
 * when the caller writes every other component holding it, or the whole organization. A writer on
 * B only is refused for A's artifact with one text that names no holder; a writer on A and B, an
 * organization writer and a global admin pass; once a third component C also holds it, a writer on
 * A and B (or on C and B) is refused and a writer on A, B and C passes; a RELEASE writer re-lists
 * an artifact held by B alone; a holderless artifact needs organization-wide write; an archived or a session's artifact
 * is refused whoever asks; a missing and a foreign uuid share the not-found text. A programmatic
 * create and rebuild list their fresh uploads through the floor without the user rule.
 */
class SbomUpdateGuardAttachmentTest extends SbomUpdateGuardProgrammaticTestBase {

	private static final String REFUSED = "cannot be attached by reference";
	private static final String UPDATE = "mutation($r: ReleaseInput!) { updateRelease(release: $r) { uuid } }";
	private static final String ADD = "mutation($r: ReleaseInput!) { addReleaseManual(release: $r) { uuid } }";
	private static final AtomicInteger VERSION = new AtomicInteger();

	@Autowired private GetComponentService getComponentService;

	private UUID draftRelease(Organization org, Component c, List<UUID> artifacts) throws Exception {
		return ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid()).org(org.getUuid())
				.branch(branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow().getUuid())
				.version("52.17." + VERSION.incrementAndGet()).status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.DRAFT).artifacts(new ArrayList<>(artifacts)).build(), WU).getUuid();
	}

	private ExecutionResult update(UserData user, World w, UUID release, List<UUID> artifacts) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("uuid", release.toString());
		r.put("org", w.org().getUuid().toString());
		if (null != artifacts) r.put("artifacts", artifacts.stream().map(UUID::toString).toList());
		return as(user, UPDATE, Map.of("r", r));
	}

	private ExecutionResult add(UserData user, World w, Component c, List<UUID> artifacts, String version) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("org", w.org().getUuid().toString());
		r.put("branch", branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow().getUuid().toString());
		r.put("version", version);
		r.put("artifacts", artifacts.stream().map(UUID::toString).toList());
		return as(user, ADD, Map.of("r", r));
	}

	private void assertRefusedByReference(ExecutionResult r, World w) {
		GraphQLError error = onlyError(r);
		assertEquals("BAD_REQUEST", error.getErrorType().toString());
		assertTrue(error.getMessage().contains(REFUSED), error.getMessage());
		assertFalse(error.getMessage().contains(w.a().getUuid().toString()), error.getMessage());
		assertFalse(error.getMessage().contains(getComponentService.getComponentData(w.a().getUuid()).orElseThrow()
				.getName()), error.getMessage());
	}

	private long artifactEvents(UUID release) {
		ReleaseData rd = sharedReleaseService.getReleaseData(release).orElseThrow();
		return null == rd.getUpdateEvents() ? 0
				: rd.getUpdateEvents().stream().filter(e -> e.rus() == ReleaseUpdateScope.ARTIFACT).count();
	}

	/** The resolver refused: the user rule ran once and the service floor never did. */
	private void refusedByTheUserRule() throws Exception {
		verify(guard, times(1)).refuseUnheldReference(any(), any(), any(), any(), any());
		verify(guard, never()).refuseForeignAttachment(any(), any(), any(), any());
		clearInvocations(guard);
	}

	private void passes(ExecutionResult r) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
	}

	@Test
	void aWriterOnBOnlyIsRefusedForAsArtifactAndAWriterOnBothPasses() throws Exception {
		World w = world();
		long events = artifactEvents(w.releaseB());
		clearInvocations(guard);
		for (UserData u : List.of(writer(w.org(), PermissionScope.RELEASE, w.releaseB()),
				writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid()))) {
			assertRefusedByReference(update(u, w, w.releaseB(), List.of(w.y(), w.x())), w);
			refusedByTheUserRule();
		}
		assertEquals(List.of(w.y()), artifactsOf(w.releaseB()));
		assertEquals(events, artifactEvents(w.releaseB()), "no ARTIFACT event added");

		passes(update(writerOn(w.org(), w.a().getUuid(), w.b().getUuid()), w, w.releaseB(), List.of(w.y(), w.x())));
		assertEquals(List.of(w.y(), w.x()), artifactsOf(w.releaseB()));
		verify(guard, times(1)).refuseForeignAttachment(any(), any(), any(), any());
	}

	/**
	 * ADR-6 asks for write on every other holder, not on one of them: with A's artifact also listed on
	 * C, a writer on B and one of A or C is refused, and a writer on all three passes.
	 */
	@Test
	void anArtifactHeldByTwoOtherComponentsNeedsWriteOnBothOfThem() throws Exception {
		World w = world();
		Component c = component(w.org());
		release(w.org(), c, List.of(w.x()));
		String nameOfC = getComponentService.getComponentData(c.getUuid()).orElseThrow().getName();
		long events = artifactEvents(w.releaseB());
		clearInvocations(guard);
		for (UserData u : List.of(writerOn(w.org(), w.a().getUuid(), w.b().getUuid()),
				writerOn(w.org(), c.getUuid(), w.b().getUuid()))) {
			ExecutionResult refused = update(u, w, w.releaseB(), List.of(w.y(), w.x()));
			assertRefusedByReference(refused, w);
			assertFalse(errorMessage(refused).contains(c.getUuid().toString()), errorMessage(refused));
			assertFalse(errorMessage(refused).contains(nameOfC), errorMessage(refused));
			refusedByTheUserRule();
		}
		assertEquals(List.of(w.y()), artifactsOf(w.releaseB()));
		assertEquals(events, artifactEvents(w.releaseB()), "no ARTIFACT event added");

		passes(update(writerOn(w.org(), w.a().getUuid(), c.getUuid(), w.b().getUuid()), w, w.releaseB(),
				List.of(w.y(), w.x())));
		assertEquals(List.of(w.y(), w.x()), artifactsOf(w.releaseB()));
		verify(guard, times(1)).refuseUnheldReference(any(), any(), any(), any(), any());
		verify(guard, times(1)).refuseForeignAttachment(any(), any(), any(), any());
	}

	@Test
	void anOrganizationWriterAndAGlobalAdminPass() throws Exception {
		World w = world();
		UserData globalAdmin = writer(w.org(), PermissionScope.RELEASE, w.releaseB());
		ReflectionTestUtils.setField(globalAdmin, "globalAdmin", true);
		UUID draft1 = draftRelease(w.org(), w.b(), List.of(w.y()));
		UUID draft2 = draftRelease(w.org(), w.b(), List.of(w.y()));

		passes(update(orgWriter(w.org()), w, draft1, List.of(w.y(), w.x())));
		passes(update(globalAdmin, w, draft2, List.of(w.y(), w.x())));
		assertEquals(List.of(w.y(), w.x()), artifactsOf(draft1));
		assertEquals(List.of(w.y(), w.x()), artifactsOf(draft2));
	}

	@Test
	void addReleaseManualFollowsTheSameRuleAndAMissingOrForeignUuidIsNotFound() throws Exception {
		World w = world();
		assertRefusedByReference(add(writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid()), w, w.b(),
				List.of(w.x()), "52.17.b"), w);
		assertEquals(0, releaseRows(w.b(), "52.17.b"));
		passes(add(writerOn(w.org(), w.a().getUuid(), w.b().getUuid()), w, w.b(), List.of(w.x()), "52.17.b2"));
		assertEquals(1, releaseRows(w.b(), "52.17.b2"));

		UUID random = UUID.randomUUID();
		Organization other = testInitializer.obtainOrganization();
		UUID foreign = artifact(other.getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, true);
		UserData releaseWriter = writer(w.org(), PermissionScope.RELEASE, w.releaseB());
		for (UUID r : List.of(random, foreign)) {
			assertEquals("Artifact not found in this organization: [" + r + "]",
					errorMessage(update(releaseWriter, w, w.releaseB(), List.of(w.y(), r))));
		}
	}

	@Test
	void anArchivedOrASessionArtifactIsRefusedForEveryPrincipal() throws Exception {
		World w = world();
		UUID session = artifactService.createArtifact(ArtifactDto.builder().org(w.org().getUuid()).type(ArtifactType.BOM)
				.bomFormat(BomFormat.CYCLONEDX).displayIdentifier("score52-" + UUID.randomUUID())
				.internalBom(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.AGENT_SESSION)).build(), WU).getUuid();
		UUID archived = bomArtifact(w.org().getUuid(), UUID.randomUUID(), "1");
		release(w.org(), w.b(), List.of(archived));
		artifactService.archiveArtifact(archived, WU);
		assertEquals(StatusEnum.ARCHIVED, statusOf(archived));
		UUID draft = draftRelease(w.org(), w.b(), List.of(w.y()));
		for (UserData u : List.of(writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid()), orgWriter(w.org()))) {
			assertRefusedByReference(update(u, w, draft, List.of(w.y(), session)), w);
			assertRefusedByReference(update(u, w, draft, List.of(w.y(), archived)), w);
		}
		assertEquals(List.of(w.y()), artifactsOf(draft));
	}

	@Test
	void theComponentsOwnArtifactAndUnchangedListsPass() throws Exception {
		World w = world();
		UUID z = artifact(w.org().getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, true);
		release(w.org(), w.b(), List.of(z));
		UUID draft = draftRelease(w.org(), w.b(), List.of(w.y()));
		UserData releaseWriter = writer(w.org(), PermissionScope.RELEASE, draft);
		clearInvocations(guard);

		passes(update(releaseWriter, w, draft, List.of(w.y(), z)));
		assertEquals(List.of(w.y(), z), artifactsOf(draft));
		verify(guard, times(1)).refuseUnheldReference(any(), any(), any(), any(), any());
		verify(guard, times(1)).refuseForeignAttachment(any(), any(), any(), any());

		clearInvocations(guard);
		passes(update(releaseWriter, w, draft, List.of(w.y(), z)));
		passes(update(releaseWriter, w, draft, null));
		assertEquals(List.of(w.y(), z), artifactsOf(draft));
		passes(update(releaseWriter, w, draft, List.of()));
		assertEquals(List.of(), artifactsOf(draft));
		verify(guard, never()).holdersOfArtifact(any(), any());
	}

	@Test
	void aHolderlessArtifactNeedsOrganizationWideWrite() throws Exception {
		World w = world();
		UUID neverListed = bomArtifact(w.org().getUuid(), UUID.randomUUID(), "1");
		UUID replaced = bomArtifact(w.org().getUuid(), w.serial(), "1");
		UUID draftA = draftRelease(w.org(), w.a(), List.of(replaced));
		jdbcTemplate.update("UPDATE rearm.releases SET record_data = jsonb_set(record_data, '{artifacts}',"
				+ " '[]'::jsonb) WHERE uuid = ?", draftA);
		assertNotEquals(StatusEnum.ARCHIVED, statusOf(replaced), "an unlisted row is not archived");
		UUID draft = draftRelease(w.org(), w.b(), List.of(w.y()));
		clearInvocations(guard);

		for (UUID holderless : List.of(neverListed, replaced)) {
			assertRefusedByReference(update(writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid()), w, draft,
					List.of(w.y(), holderless)), w);
			refusedByTheUserRule();
			assertRefusedByReference(update(writerOn(w.org(), w.a().getUuid(), w.b().getUuid()), w, draft,
					List.of(w.y(), holderless)), w);
			refusedByTheUserRule();
		}
		passes(update(orgWriter(w.org()), w, draft, List.of(w.y(), neverListed, replaced)));
		assertEquals(List.of(w.y(), neverListed, replaced), artifactsOf(draft));
	}

	private StatusEnum statusOf(UUID artifact) {
		return artifactService.getArtifactData(artifact).map(ArtifactData::getStatus).orElse(null);
	}

	@Test
	void aProgrammaticCreateAndRebuildListTheirFreshUploadsWithoutTheUserRule() throws Exception {
		World w = world();
		onlySerialIsRefused(w);
		ApiKey keyB = componentKey(w, w.b());
		String branch = branchService.getBaseBranchOfComponent(w.b().getUuid()).orElseThrow().getUuid().toString();
		Map<String, Object> input = map(CommonVariables.COMPONENT_FIELD, w.b().getUuid().toString(),
				CommonVariables.BRANCH_FIELD, branch, CommonVariables.VERSION_FIELD, "52.17.g",
				CommonVariables.LIFECYCLE_FIELD, ReleaseLifecycle.DRAFT.name(), "artifacts", entries(fresh()));
		clearInvocations(guard);

		ReleaseData created = releaseDatafetcher.addReleaseProgrammatic(env(keyB, "release", input));
		assertEquals(List.of(accepted.get(0)), artifactsOf(created.getUuid()));

		Map<String, Object> rebuild = new LinkedHashMap<>(input);
		rebuild.put("artifacts", entries(fresh()));
		rebuild.put("rebuildRelease", true);
		releaseDatafetcher.addReleaseProgrammatic(env(keyB, "release", rebuild));
		assertEquals(List.of(accepted.get(1)), artifactsOf(created.getUuid()));

		verify(guard, never()).refuseUnheldReference(any(), any(), any(), any(), any());
		verify(guard, times(2)).refuseForeignAttachment(any(), any(), any(), any());
	}
}
