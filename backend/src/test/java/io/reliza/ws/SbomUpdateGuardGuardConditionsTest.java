/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.Organization;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.service.ArtifactAttachmentGuard.ArtifactHolders;
import io.reliza.service.SbomWriteAuthority;

/**
 * SCORE-52 T-17 and T-18, each guard condition on its own, against the guard directly (carried from
 * SCORE-47 T-26). The floor ({@code createRelease}, {@code doUpdateRelease}) checks only the
 * organization and agent sessions: an artifact held by another component passes it, one of another
 * organization or a missing one is not found, a session's is refused. The user rule adds archived
 * rows and the holders: a row held by the release's own component alone passes; archived, it is
 * refused by the user rule and still passes the floor, which does not look at status.
 */
class SbomUpdateGuardGuardConditionsTest extends SbomUpdateGuardTestBase {

	private static void assertRefusedByReference(UUID artifact, Executable call) {
		RelizaException e = assertThrows(RelizaException.class, call);
		assertTrue(e.getMessage().startsWith("Artifact cannot be attached by reference: " + artifact), e.getMessage());
	}

	private SbomWriteAuthority componentWriter(World w, UUID component) throws RelizaException {
		return authorities.forUser(writer(w.org(), PermissionScope.COMPONENT, component), w.org().getUuid());
	}

	@Test
	void anArchivedRowHeldByTheComponentAloneIsRefusedByTheUserRuleOnly() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		UUID a = w.a().getUuid();
		SbomWriteAuthority writerA = componentWriter(w, a);
		assertEquals(new ArtifactHolders(Set.of(a), false), guard.holdersOfArtifact(org, w.x()));
		guard.refuseUnheldReference(org, a, w.releaseA(), List.of(w.x()), writerA);
		guard.refuseForeignAttachment(org, a, w.releaseA(), List.of(w.x()));

		artifactService.archiveArtifact(w.x(), WU);

		assertEquals(StatusEnum.ARCHIVED, artifactService.getArtifactData(w.x()).map(ArtifactData::getStatus).orElseThrow());
		assertEquals(new ArtifactHolders(Set.of(a), false), guard.holdersOfArtifact(org, w.x()),
				"archiving leaves the holder");
		assertRefusedByReference(w.x(), () -> guard.refuseUnheldReference(org, a, w.releaseA(), List.of(w.x()), writerA));
		guard.refuseForeignAttachment(org, a, w.releaseA(), List.of(w.x()));
	}

	@Test
	void aSessionRowHeldByTheComponentAloneIsRefusedByBothRules() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		UUID a = w.a().getUuid();
		UUID session = artifactService.createArtifact(ArtifactDto.builder().org(org).type(ArtifactType.BOM)
				.bomFormat(BomFormat.CYCLONEDX).displayIdentifier("score52-session-" + UUID.randomUUID())
				.internalBom(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.AGENT_SESSION)).build(), WU).getUuid();
		jdbcTemplate.update("UPDATE rearm.releases SET record_data = jsonb_set(record_data, '{artifacts}',"
				+ " record_data->'artifacts' || to_jsonb(?::text)) WHERE uuid = ?", session.toString(), w.releaseA());
		assertEquals(Set.of(a), guard.holdersOfArtifact(org, session).components());
		SbomWriteAuthority orgAdmin = authorities.forUser(orgWriter(w.org()), org);

		assertRefusedByReference(session, () -> guard.refuseUnheldReference(org, a, w.releaseA(), List.of(session),
				orgAdmin));
		assertRefusedByReference(session, () -> guard.refuseForeignAttachment(org, a, w.releaseA(), List.of(session)));
	}

	// T-18
	@Test
	void theFloorPassesAnotherComponentsArtifactAndRefusesAForeignOrMissingOne() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		guard.refuseForeignAttachment(org, w.b().getUuid(), w.releaseB(), List.of(w.x()));

		Organization other = testInitializer.obtainOrganization();
		UUID foreign = artifact(other.getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, true);
		UUID missing = UUID.randomUUID();
		RelizaException e = assertThrows(RelizaException.class,
				() -> guard.refuseForeignAttachment(org, w.b().getUuid(), w.releaseB(), List.of(w.y(), foreign, missing)));
		assertEquals("Artifact not found in this organization: [" + foreign + ", " + missing + "]", e.getMessage());
	}

	// T-17, at the guard
	@Test
	void theUserRuleAsksForWriteOnEveryOtherHolderOrOrganizationWideWrite() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		UUID b = w.b().getUuid();
		UUID holderless = bomArtifact(org, UUID.randomUUID(), "1");
		SbomWriteAuthority writerB = componentWriter(w, b);
		SbomWriteAuthority writerAB = authorities.forUser(writerOn(w.org(), w.a().getUuid(), b), org);
		SbomWriteAuthority orgAdmin = authorities.forUser(orgWriter(w.org()), org);

		assertRefusedByReference(w.x(), () -> guard.refuseUnheldReference(org, b, w.releaseB(), List.of(w.x()), writerB));
		guard.refuseUnheldReference(org, b, w.releaseB(), List.of(w.x()), writerAB);
		guard.refuseUnheldReference(org, b, w.releaseB(), List.of(w.x()), orgAdmin);
		guard.refuseUnheldReference(org, b, w.releaseB(), List.of(w.y()), writerB);

		assertEquals(new ArtifactHolders(Set.of(), false), guard.holdersOfArtifact(org, holderless));
		assertRefusedByReference(holderless,
				() -> guard.refuseUnheldReference(org, b, w.releaseB(), List.of(holderless), writerAB));
		guard.refuseUnheldReference(org, b, w.releaseB(), List.of(holderless), orgAdmin);
	}
}
