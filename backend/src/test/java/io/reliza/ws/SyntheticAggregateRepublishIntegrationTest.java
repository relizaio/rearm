/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.StripBom;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.Component;
import io.reliza.model.Organization;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.service.ArtifactAttachmentGuard.ArtifactHolders;
import io.reliza.service.SbomWriteAuthority;
import io.reliza.service.SyntheticArtifactService;

/**
 * TEA-36 4.2: the TEA publication engine under main's rule B (SCORE-52) and SCORE-56. The engine
 * writes a named aggregate through {@link SyntheticArtifactService} under a component-scoped
 * authority (the release's component, nothing organization-wide), the holder walk sees the
 * release's synthetic binding (TEA-4) as a holder, and the TEA-28 in-place re-publish (same row,
 * same serialNumber, the row's version plus one, which now reads rebom meta.bomVersion) is AUTHOR
 * through the walk. A writer of another component and no principal are refused the same update,
 * and the create path refuses a generated row as inventory, bound (3.5 (iii), F-9) or unbound
 * through its marks alone (run 1 T-1).
 *
 * <p>The world: one organization, components a and b, one ASSEMBLED release of a with no
 * inventory; rebom is the in-memory {@link RebomStore}.
 */
class SyntheticAggregateRepublishIntegrationTest extends SbomUpdateGuardTestBase {

	private static final String ADD = "mutation($r: ReleaseInput!) { addReleaseManual(release: $r) { uuid } }";

	@Autowired private SyntheticArtifactService syntheticArtifactService;

	private record Engine(Organization org, Component a, Component b, UUID release, UUID serial) {}

	private Engine engine() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org);
		Component b = component(org);
		return new Engine(org, a, b, release(org, a, List.of()), UUID.randomUUID());
	}

	/** The engine's document: {@code cdx(serial, version)} with a marker so each version differs in content. */
	private static String aggregate(UUID serial, int version, String marker) {
		return cdx("urn:uuid:" + serial, version).replace("\"components\":[]",
				"\"metadata\":{\"timestamp\":\"" + marker + "\"},\"components\":[]");
	}

	private static Resource file(UUID serial, int version, String marker) {
		return file(aggregate(serial, version, marker)).getResource();
	}

	/** The engine's dto: a CycloneDX BOM stored in ReARM with the aggregate's system tags, every one Removable.NO. */
	private static ArtifactDto dto(UUID row) {
		List<TagRecord> tags = new ArrayList<>(List.of(
				new TagRecord(CommonVariables.TEA_AGGREGATE_TAG_KEY, "true", Removable.NO),
				new TagRecord(CommonVariables.TEA_PROFILE_NAME_TAG_KEY, "default", Removable.NO),
				new TagRecord(CommonVariables.TEA_PUBLICATION_TAG_KEY, UUID.randomUUID().toString(), Removable.NO),
				new TagRecord(CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY, "sha256:tea36", Removable.NO)));
		return ArtifactDto.builder().uuid(row).type(ArtifactType.BOM).bomFormat(BomFormat.CYCLONEDX)
				.storedIn(StoredIn.REARM).displayIdentifier("tea36-aggregate-" + UUID.randomUUID()).tags(tags).build();
	}

	/** The dto of a direct {@code uploadArtifact} call: upload() fills the org in, a direct call does not (F-8). */
	private static ArtifactDto directDto(Engine e, UUID row) {
		ArtifactDto d = dto(row);
		d.setOrg(e.org().getUuid());
		return d;
	}

	private static RebomOptions options() {
		return new RebomOptions("tea36", "tea36 org", "1.0.0", ArtifactBelongsTo.SYNTHETIC, null, StripBom.FALSE, null);
	}

	/** First publish: written and bound to the release's synthetic list, version 1. */
	private UUID firstPublish(Engine e) throws RelizaException {
		return syntheticArtifactService.write(e.release(), dto(null), file(e.serial(), 1, "v1"), options(), WU);
	}

	/** The engine's in-place re-publish of {@code row}: same uuid, same serial, {@code version}; already bound. */
	private UUID republish(Engine e, UUID row, int version) throws RelizaException {
		return syntheticArtifactService.upload(e.release(), dto(row), file(e.serial(), version, "v" + version),
				options(), WU);
	}

	private String latest(Engine e) throws RelizaException {
		return artifactService.getArtifactBomLatestVersion(e.serial(), e.org().getUuid());
	}

	/** The engine's formula (TeaPublicationService.aggregateEntry): the row's version plus one. */
	private void assertEngineNextIsRebomNext(Engine e, UUID row) throws RelizaException {
		assertEquals(Integer.parseInt(versionOf(row)) + 1, Integer.parseInt(latest(e)) + 1);
	}

	// 1
	@Test
	void inPlaceRepublishBumpsByOneUnderRebomMeta() throws Exception {
		Engine e = engine();
		UUID row = firstPublish(e);
		assertEquals("1", versionOf(row));
		assertEquals("1", latest(e));
		assertTrue(sharedReleaseService.getReleaseData(e.release()).orElseThrow().getSyntheticArtifacts().contains(row));

		assertEngineNextIsRebomNext(e, row);
		assertEquals(row, republish(e, row, 2));
		assertEquals("2", versionOf(row));
		assertEquals("2", latest(e));
		assertEquals(2, rebom.versionsStored(e.serial()));

		assertEngineNextIsRebomNext(e, row);
		assertEquals(row, republish(e, row, 3));
		assertEquals("3", versionOf(row));
		assertEquals("3", latest(e));
		assertEquals(3, rebom.versionsStored(e.serial()));
	}

	// 2
	@Test
	void equalVersionIsRefusedAsNotIncremented() throws Exception {
		Engine e = engine();
		UUID row = firstPublish(e);
		int calls = rebom.addBomCalls(e.serial());
		RelizaException refused = assertThrows(RelizaException.class,
				() -> syntheticArtifactService.upload(e.release(), dto(row), file(e.serial(), 1, "v1-again"), options(),
						WU));
		assertEquals("Uploaded bom should have an incremented version", refused.getMessage());
		assertEquals(calls, rebom.addBomCalls(e.serial()));
		assertEquals("1", versionOf(row));
	}

	// 3
	@Test
	void theEngineWritesUnderAComponentScopedAuthority() throws Exception {
		Engine e = engine();
		firstPublish(e);
		ArgumentCaptor<SbomWriteAuthority> captor = ArgumentCaptor.forClass(SbomWriteAuthority.class);
		verify(uploads).uploadArtifact(any(), any(), any(), captor.capture(), any());
		SbomWriteAuthority a = captor.getValue();
		assertFalse(a.orgWideWrite());
		assertTrue(a.canWrite(e.a().getUuid()));
		assertFalse(a.canWrite(e.b().getUuid()));
		assertFalse(a.canWrite(UUID.randomUUID()));
		assertEquals("SYSTEM:SYNTHETIC:" + e.a().getUuid(), a.describe());
	}

	// 4
	@Test
	void theReleasesComponentHoldsTheBoundRow() throws Exception {
		Engine e = engine();
		UUID row = firstPublish(e);
		ArtifactHolders bound = guard.holdersOfArtifact(e.org().getUuid(), row);
		assertEquals(Set.of(e.a().getUuid()), bound.components());
		assertFalse(bound.unresolved());

		UUID unbound = syntheticArtifactService.upload(e.release(), dto(null), file(UUID.randomUUID(), 1, "unbound"),
				options(), WU);
		ArtifactHolders none = guard.holdersOfArtifact(e.org().getUuid(), unbound);
		assertEquals(Set.of(), none.components());
		assertFalse(none.unresolved());
	}

	// 5
	@Test
	void theInPlaceRepublishPassesRuleBThroughTheHolderWalk() throws Exception {
		Engine e = engine();
		UUID row = firstPublish(e);
		clearInvocations(guard);
		assertEquals(row, republish(e, row, 2));
		assertFalse(isCopy(row));
		assertEquals(2, rebom.versionsStored(e.serial()));
		verify(guard, times(1)).holdersOfArtifact(e.org().getUuid(), row);

		// a writer of a has the same rights as the engine, no more and no less
		UserData writerOfA = writer(e.org(), PermissionScope.COMPONENT, e.a().getUuid());
		assertEquals(row, uploads.uploadArtifact(directDto(e, row), file(e.serial(), 3, "v3"), options(),
				authorities.forUser(writerOfA, e.org().getUuid()), WU));
		assertFalse(isCopy(row));
		assertEquals(3, rebom.versionsStored(e.serial()));
		assertEquals("3", versionOf(row));
	}

	// 6
	@Test
	void anotherComponentsWriterAndNoPrincipalAreRefused() throws Exception {
		Engine e = engine();
		UUID row = firstPublish(e);
		UserData writerOfB = writer(e.org(), PermissionScope.COMPONENT, e.b().getUuid());
		for (SbomWriteAuthority x : List.of(authorities.forUser(writerOfB, e.org().getUuid()),
				SbomWriteAuthority.NO_PRINCIPAL)) {
			int stored = rebom.versionsStored(e.serial());
			int calls = rebom.addBomCalls(e.serial());
			assertThrows(SbomUpdateNotPermittedException.class, () -> uploads.uploadArtifact(directDto(e, row),
					file(e.serial(), 2, "v2"), options(), x, WU), x.describe());
			assertEquals(stored, rebom.versionsStored(e.serial()), x.describe());
			assertEquals(calls, rebom.addBomCalls(e.serial()), x.describe());
			assertEquals("1", versionOf(row), x.describe());
		}
	}

	// 7
	@Test
	void theCreatePathRefusesTheBoundAggregateAsInventory() throws Exception {
		Engine e = engine();
		UUID row = firstPublish(e);
		StatusEnum statusBefore = artifactService.getArtifactData(row).orElseThrow().getStatus();
		String expected = "artifact " + row + " is a generated artifact of another release; it cannot be attached"
				+ " as inventory, replaced or versioned through a release";
		Map<String, UserData> callers = new LinkedHashMap<>();
		callers.put("36.9.1", writer(e.org(), PermissionScope.COMPONENT, e.a().getUuid()));
		callers.put("36.9.2", orgWriter(e.org()));
		for (Map.Entry<String, UserData> c : callers.entrySet()) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("org", e.org().getUuid().toString());
			r.put("branch", branchService.getBaseBranchOfComponent(e.a().getUuid()).orElseThrow().getUuid().toString());
			r.put("version", c.getKey());
			r.put("artifacts", List.of(row.toString()));
			ExecutionResult result = as(c.getValue(), ADD, Map.of("r", r));
			GraphQLError error = onlyError(result);
			assertEquals("BAD_REQUEST", error.getErrorType().toString(), c.getKey());
			assertEquals(expected, errorMessage(result), c.getKey());
			assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.releases WHERE"
					+ " record_data->>'component' = ? AND record_data->>'version' = ?", Long.class,
					e.a().getUuid().toString(), c.getKey()), c.getKey());
		}
		assertEquals(statusBefore, artifactService.getArtifactData(row).orElseThrow().getStatus());
		assertFalse(StatusEnum.ARCHIVED == artifactService.getArtifactData(row).orElseThrow().getStatus());
		assertTrue(sharedReleaseService.getReleaseData(e.release()).orElseThrow().getSyntheticArtifacts().contains(row));
		ArtifactHolders holders = guard.holdersOfArtifact(e.org().getUuid(), row);
		assertEquals(Set.of(e.a().getUuid()), holders.components());
		assertFalse(holders.unresolved());
	}

	// 8
	/**
	 * The mark arm of 3.5 (iii) on its own (TEA-36 run 1 T-1): a generated row that was uploaded but
	 * never bound (upload() binds nothing, as in case 4) is in no synthetic list, and the user rule lets
	 * an organization-wide writer list a holderless row. The create path still refuses it with TEA-4's
	 * text, through the generator's marks alone.
	 */
	@Test
	void theCreatePathRefusesAnUnboundGeneratedRowByItsMarks() throws Exception {
		Engine e = engine();
		UUID row = syntheticArtifactService.upload(e.release(), dto(null), file(e.serial(), 1, "unbound"), options(),
				WU);
		assertTrue(sharedReleaseService.findReleasesBySyntheticArtifact(row, e.org().getUuid()).isEmpty());
		assertFalse(sharedReleaseService.getReleaseData(e.release()).orElseThrow().getSyntheticArtifacts().contains(row));
		assertEquals(Set.of(), guard.holdersOfArtifact(e.org().getUuid(), row).components());
		StatusEnum statusBefore = artifactService.getArtifactData(row).orElseThrow().getStatus();
		String version = "36.9.3";
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("org", e.org().getUuid().toString());
		r.put("branch", branchService.getBaseBranchOfComponent(e.a().getUuid()).orElseThrow().getUuid().toString());
		r.put("version", version);
		r.put("artifacts", List.of(row.toString()));
		ExecutionResult result = as(orgWriter(e.org()), ADD, Map.of("r", r));
		GraphQLError error = onlyError(result);
		assertEquals("BAD_REQUEST", error.getErrorType().toString());
		assertEquals("artifact " + row + " is a generated artifact of another release; it cannot be attached"
				+ " as inventory, replaced or versioned through a release", errorMessage(result));
		assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.releases WHERE"
				+ " record_data->>'component' = ? AND record_data->>'version' = ?", Long.class,
				e.a().getUuid().toString(), version));
		assertEquals(statusBefore, artifactService.getArtifactData(row).orElseThrow().getStatus());
		assertTrue(sharedReleaseService.findReleasesBySyntheticArtifact(row, e.org().getUuid()).isEmpty());
	}
}
