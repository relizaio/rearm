/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;

import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.DeliverableDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;

/**
 * TPF-44: addArtifactManual attaches only to a deliverable or source code entry of the release it
 * names, by the rule addArtifactProgrammatic applies (ReleaseService.resolveReleaseDeliverable and
 * resolveReleaseSourceCodeEntry, shared by both lanes), for add and replace, before anything is
 * uploaded or written. Before this the check covered the named release alone, so a writer of
 * component A (through a component or perspective grant) could attach artifacts to component B's
 * deliverables and commits in the same organization. The named component must be the release's.
 * A caller who cannot write the release is refused before any named object is looked up, and a
 * writer reads one not-found text per kind for a missing, foreign or unrelated id. Called through
 * the real resolver as a signed-in user (architecture-1 section 4).
 */
public class ReleaseDatafetcherAddArtifactManualTargetBindingTest
		extends ReleaseDatafetcherAddArtifactManualTargetBindingTestBase {

	private static final String DELIVERABLE = "DELIVERABLE";
	private static final String SCE = "SCE";

	/** Sides A and B of one organization, X of a second one. */
	private record Pair(Side a, Side b, Side x) {
		List<Side> all() { return List.of(a, b, x); }
	}

	private Pair pair() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID otherOrg = testInitializer.obtainOrganization().getUuid();
		return new Pair(side(org), side(org), side(otherOrg));
	}

	// ---------------- refused, nothing uploaded or written ----------------

	@Test
	public void case1AnotherComponentsDeliverableIsRefused() throws Exception {
		Pair p = pair();
		for (UserData caller : List.of(writerOf(p.a()), perspectiveWriterOf(p.a()))) {
			stubUpload(p.a().org());
			UUID random = UUID.randomUUID();
			List<Map<String, String>> inputs = List.of(
					targets("belongsTo", DELIVERABLE, "deliverable", p.b().deliverable().toString()),
					targets("deliverable", p.b().deliverable().toString()),
					targets("belongsTo", DELIVERABLE, "deliverable", random.toString()),
					targets("belongsTo", DELIVERABLE, "deliverable", p.x().deliverable().toString()));
			List<UUID> named = List.of(p.b().deliverable(), p.b().deliverable(), random, p.x().deliverable());
			for (int i = 0; i < inputs.size(); i++) {
				Map<String, String> t = inputs.get(i);
				refusedWithNothingWritten(RelizaException.class, DELIVERABLE_NOT_FOUND + named.get(i), p.all(),
						() -> addAs(caller, p.a().release(), null, t, fileArtifact()));
			}
		}
	}

	@Test
	public void case2ASourceCodeEntryOutsideTheReleasesCommitsIsRefused() throws Exception {
		Pair p = pair();
		Side e = side(CommonVariables.EXTERNAL_PROJ_ORG_UUID);
		List<Side> sides = List.of(p.a(), p.b(), p.x(), e);
		UserData writerA = writerOf(p.a());
		stubUpload(p.a().org());
		UUID random = UUID.randomUUID();
		for (UUID sce : List.of(p.b().sce(), random, SourceCodeEntryData.NULL_SCE_UUID, p.x().sce())) {
			refusedWithNothingWritten(RelizaException.class, SCE_NOT_FOUND + sce, sides,
					() -> addAs(writerA, p.a().release(), null, targets("belongsTo", SCE, "sce", sce.toString()),
							fileArtifact()));
		}

		ossReleaseService.updateRelease(ReleaseDto.builder().uuid(p.a().release())
				.commits(List.of(p.a().sce(), e.sce())).build(), WU);
		assertTrue(sharedReleaseService.getReleaseData(p.a().release()).orElseThrow().getAllCommits()
				.contains(e.sce()), "fixture: the external entry is one of the release's commits");
		refusedWithNothingWritten(RelizaException.class, SCE_NOT_FOUND + e.sce(), sides,
				() -> addAs(writerA, p.a().release(), null, targets("belongsTo", SCE, "sce", e.sce().toString()),
						fileArtifact()));
	}

	@Test
	public void case3AReplacementCannotBeRetargeted() throws Exception {
		Pair p = pair();
		UserData writerA = writerOf(p.a());
		UUID x0 = artifact(p.a().org(), ArtifactType.OTHER);
		releaseService.addArtifact(x0, p.a().release(), WU);
		stubUpload(p.a().org());
		Snapshot before = snapshot(x0);

		refusedWithNothingWritten(RelizaException.class, DELIVERABLE_NOT_FOUND + p.b().deliverable(), List.of(p.b()),
				() -> addAs(writerA, p.a().release(), x0,
						targets("belongsTo", DELIVERABLE, "deliverable", p.b().deliverable().toString()), fileArtifact()));
		refusedWithNothingWritten(RelizaException.class, SCE_NOT_FOUND + p.b().sce(), List.of(p.b()),
				() -> addAs(writerA, p.a().release(), x0, targets("belongsTo", SCE, "sce", p.b().sce().toString()),
						fileArtifact()));

		assertEquals(before, snapshot(x0));
		assertEquals(List.of(x0), releaseArtifacts(p.a().release()));
	}

	@Test
	public void case4TheSameComponentsDeliverableOrCommitOfAnotherReleaseIsRefused() throws Exception {
		Pair p = pair();
		UUID r2 = releaseOn(p.a(), "1.0.1", List.of());
		UserData writerA = writerOf(p.a());
		stubUpload(p.a().org());

		refusedWithNothingWritten(RelizaException.class, DELIVERABLE_NOT_FOUND + p.a().deliverable(), List.of(p.a()),
				() -> addAs(writerA, r2, null,
						targets("belongsTo", DELIVERABLE, "deliverable", p.a().deliverable().toString()), fileArtifact()));
		refusedWithNothingWritten(RelizaException.class, SCE_NOT_FOUND + p.a().sce(), List.of(p.a()),
				() -> addAs(writerA, r2, null, targets("belongsTo", SCE, "sce", p.a().sce().toString()), fileArtifact()));

		assertEquals(List.of(), releaseArtifacts(r2));
	}

	@Test
	public void case5TheNamedComponentMustBeTheReleases() throws Exception {
		Pair p = pair();
		UserData writerA = writerOf(p.a());
		List<Side> ab = List.of(p.a(), p.b());
		refusedWithNothingWritten(RelizaException.class, COMPONENT_NOT_FOUND, ab,
				() -> addAs(writerA, p.a().release(), null, targets("component", p.b().component().toString()),
						externalArtifact()));
		refusedWithNothingWritten(RelizaException.class, COMPONENT_NOT_FOUND, ab,
				() -> addAs(writerA, p.a().release(), null, targets("component", UUID.randomUUID().toString()),
						externalArtifact()));

		ReleaseData returned = addAs(writerA, p.a().release(), null,
				targets("component", p.a().component().toString()), externalArtifact());
		assertEquals(p.a().release(), returned.getUuid());
		assertEquals(1, releaseArtifacts(p.a().release()).size());
	}

	@Test
	public void case6NoWriteOnTheNamedReleaseIsRefusedBeforeAnyLookup() throws Exception {
		Pair p = pair();
		UserData readerA = user(p.a().org(), PermissionScope.COMPONENT, p.a().component(), PermissionType.READ_ONLY);
		UserData writerB = writerOf(p.b());
		stubUpload(p.a().org());
		List<Map<String, String>> inputs = List.of(
				targets("belongsTo", DELIVERABLE, "deliverable", UUID.randomUUID().toString()),
				targets("belongsTo", SCE, "sce", UUID.randomUUID().toString()),
				targets("component", UUID.randomUUID().toString()),
				targets("belongsTo", DELIVERABLE, "deliverable", p.b().deliverable().toString()));
		for (UserData caller : List.of(readerA, writerB)) {
			for (Map<String, String> t : inputs) {
				refusedWithNothingWritten(AccessDeniedException.class, null, List.of(p.a(), p.b()),
						() -> addAs(caller, p.a().release(), null, t, fileArtifact()));
			}
		}
	}

	/** Decision 5: refused as on the API-key lane, even for a writer of the deliverable's own component. */
	@Test
	public void case7AnotherComponentsDeliverableListedOnTheReleaseIsRefused() throws Exception {
		Pair p = pair();
		variantService.addOutboundDeliverables(List.of(p.b().deliverable()), p.a().variant(), WU);
		assertTrue(variantService.getBaseVariantForRelease(p.a().release()).getOutboundDeliverables()
				.contains(p.b().deliverable()), "fixture: reused outbound deliverable recorded");
		UUID withInbound = releaseOn(p.a(), "1.0.2", List.of(p.b().deliverable()));
		assertTrue(sharedReleaseService.getReleaseData(withInbound).orElseThrow().getInboundDeliverables()
				.contains(p.b().deliverable()), "fixture: inbound deliverable recorded");
		stubUpload(p.a().org());
		List<UUID> bDeliverableBefore = deliverableArtifacts(p.b().deliverable());

		for (UUID release : List.of(p.a().release(), withInbound)) {
			for (UserData caller : List.of(writerOf(p.a()), orgWriter(p.a().org()))) {
				refusedWithNothingWritten(RelizaException.class, DELIVERABLE_NOT_FOUND + p.b().deliverable(),
						List.of(p.b()), () -> addAs(caller, release, null,
								targets("belongsTo", DELIVERABLE, "deliverable", p.b().deliverable().toString()),
								fileArtifact()));
			}
		}
		assertEquals(bDeliverableBefore, deliverableArtifacts(p.b().deliverable()));
	}

	// ---------------- accepted ----------------

	@Test
	public void case8TheReleasesOwnOutboundDeliverableIsAccepted() throws Exception {
		for (boolean throughPerspective : List.of(false, true)) {
			Side a = pair().a(); // a fresh pair of sides each
			UUID org = a.org();
			UserData caller = throughPerspective ? perspectiveWriterOf(a) : writerOf(a);
			Map<String, String> t = targets("belongsTo", DELIVERABLE, "deliverable", a.deliverable().toString());

			ReleaseData returned = addAs(caller, a.release(), null, t, externalArtifact());
			assertEquals(a.release(), returned.getUuid());
			List<UUID> attached = deliverableArtifacts(a.deliverable());
			assertEquals(1, attached.size(), attached.toString());
			assertEquals(List.of(), releaseArtifacts(a.release()));

			UUID premade = stubUpload(org);
			Mockito.clearInvocations(artifactService);
			addAs(caller, a.release(), null, t, fileArtifact());
			assertEquals(List.of(attached.get(0), premade), deliverableArtifacts(a.deliverable()));
			verify(artifactService, times(1)).uploadArtifact(any(), any(), any(), any(), any());
		}
	}

	@Test
	public void case9TheReleasesOwnCommitsAreAccepted() throws Exception {
		Pair p = pair();
		Side a = p.a();
		UUID s2 = sourceCodeEntryService.createSourceCodeEntry(SceDto.builder().branch(a.branch()).vcs(a.vcs())
				.organizationUuid(a.org()).commit(randomCommit()).build(), WU).getUuid();
		ossReleaseService.updateRelease(ReleaseDto.builder().uuid(a.release()).commits(List.of(a.sce(), s2)).build(), WU);
		UserData writerA = writerOf(a);

		for (UUID sce : List.of(a.sce(), s2)) {
			addAs(writerA, a.release(), null, targets("belongsTo", SCE, "sce", sce.toString()), externalArtifact());
			List<SCEArtifact> attached = sceArtifacts(sce);
			assertEquals(1, attached.size(), attached.toString());
			assertEquals(a.component(), attached.get(0).componentUuid());
			assertTrue(artifactService.getArtifactData(attached.get(0).artifactUuid()).isPresent());
		}
		assertEquals(List.of(), releaseArtifacts(a.release()));
	}

	@Test
	public void case10AnInboundDeliverableOfTheReleasesOwnComponentIsAccepted() throws Exception {
		Pair p = pair();
		Side a = p.a();
		UUID inbound = deliverableService.createDeliverable(DeliverableDto.builder()
				.displayIdentifier("tpf44-inbound-" + UUID.randomUUID()).branch(a.branch())
				.type(CdxType.FILE).build(), WU).getUuid();
		UUID r3 = releaseOn(a, "1.0.3", List.of(inbound));

		ReleaseData returned = addAs(writerOf(a), r3, null,
				targets("belongsTo", DELIVERABLE, "deliverable", inbound.toString()), externalArtifact());

		assertEquals(r3, returned.getUuid());
		assertEquals(1, deliverableArtifacts(inbound).size());
	}

	@Test
	public void case11AReplacementOnTheReleasesOwnDeliverableAndCommitIsAccepted() throws Exception {
		Pair p = pair();
		Side a = p.a();
		UUID x1 = artifact(a.org(), ArtifactType.OTHER);
		deliverableService.addArtifact(a.deliverable(), x1, WU);
		UUID z1 = artifact(a.org(), ArtifactType.OTHER);
		sourceCodeEntryService.addArtifact(a.sce(), new SCEArtifact(z1, a.component()), WU);
		UserData writerA = writerOf(a);

		UUID premade = stubUpload(a.org());
		addAs(writerA, a.release(), x1, targets("belongsTo", DELIVERABLE, "deliverable", a.deliverable().toString()),
				fileArtifact());
		assertEquals(List.of(premade), deliverableArtifacts(a.deliverable()));

		UUID premade2 = stubUpload(a.org());
		addAs(writerA, a.release(), z1, targets("belongsTo", SCE, "sce", a.sce().toString()), fileArtifact());
		assertEquals(List.of(new SCEArtifact(premade2, a.component())), sceArtifacts(a.sce()));
	}
}
