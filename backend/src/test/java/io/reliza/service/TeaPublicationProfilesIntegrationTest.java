/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.common.Utils;
import io.reliza.common.oss.LicensingConstants;
import io.reliza.exceptions.TeaPublishRefusedException;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.ArtifactData;
import io.reliza.model.SbomProfileData;
import io.reliza.model.SbomProfileData.SbomDependencyDepth;
import io.reliza.model.SbomProfileData.SbomInternalMetadata;
import io.reliza.model.SettingsScope;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.TeaPublicationData.TeaAggregateArtifact;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.UserData;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.model.tea.TeaArtifactType;
import io.reliza.model.tea.TeaCollectionUpdateReasonType;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.saas.PerspectiveService;
import tools.jackson.databind.JsonNode;

/**
 * Task TEA-28, design 4.12: one aggregate per named profile against the test database. The mocked merge
 * answers no purl for a top-level-only request ({@code tldOnlyPurls}), so {@code top}, saved TOP_LEVEL_ONLY,
 * and {@code default} produce different documents; the rows, the collection versions, their stored documents
 * and the GraphQL fields are read back.
 */
class TeaPublicationProfilesIntegrationTest extends TeaPublicationIntegrationTestBase {

	@Autowired JdbcTemplate jdbc;
	@Autowired PerspectiveService perspectiveService;

	private static final String VIEW_PROFILES = "query($r: ID!) { releaseTeaPublicationView(release: $r) { canPublish "
			+ "refusal refusalMessage profiles publication { uuid aggregateArtifact aggregateArtifacts { profileName "
			+ "artifact } } } }";
	private static final String ENTRIES = "query($p: ID!) { teaPublicationCollections(publication: $p) { version "
			+ "entries { artifact teaUuid revision origin name sbomProfileName sbomProfileUuid sbomProfileRevision } } }";

	private TeaPublicationData head(UUID release) {
		return teaPublicationService.getPublicationOfRelease(release).orElseThrow();
	}

	private List<TeaPublishedCollectionData> versions(UUID release) {
		return teaPublicationService.listCollections(head(release).getUuid());
	}

	private TeaPublishedCollectionData latest(UUID release) {
		return teaPublicationService.getLatestCollection(head(release).getUuid()).orElseThrow();
	}

	private static TeaPublishedEntry named(TeaPublishedCollectionData c, String profile) {
		return c.getEntries().stream().filter(e -> profile.equals(e.sbomProfileName())).findFirst().orElseThrow();
	}

	private static List<String> aggregateNames(TeaPublishedCollectionData c) {
		return c.getEntries().stream().filter(e -> TeaArtifactOrigin.SYNTHETIC == e.origin()).map(TeaPublishedEntry::name)
				.toList();
	}

	/** The element of {@code teaUuid} in a version's stored document. */
	private static JsonNode element(TeaPublishedCollectionData c, UUID teaUuid) {
		for (JsonNode a : Utils.OM.readTree(c.getDocument()).get("artifacts")) {
			if (teaUuid.toString().equals(a.get("uuid").asString())) return a;
		}
		throw new AssertionError("no element " + teaUuid + " in version " + c.getVersion());
	}

	private void nameTheOrgRow(String... names) throws Exception {
		teaProfileService.saveProfile(org.getUuid(), TeaProfileScope.ORGANIZATION, null,
				profileInput(TeaPublishing.ENABLED, TeaVisibility.PRIVATE, TeaRawArtifacts.NONE,
						TeaProductComponents.PUBLISH_WITH_PRODUCT).profiles(List.of(names)).build(), TeaCaller.ORG_ADMIN, WU);
	}

	@Test
	@SuppressWarnings("unchecked")
	void namedAggregatesAcrossAddChangeRemoveAndReadd() throws Exception {
		saveOrgRecipe();
		saveSbomProfile(SettingsScope.ORGANIZATION, null, "top", SbomDependencyDepth.TOP_LEVEL_ONLY,
				SbomInternalMetadata.EXCLUDE);
		saveOrgProfileNaming(TeaVisibility.PRIVATE, TeaRawArtifacts.NONE, "default", "top");
		World w = componentRelease("pkg:generic/pump@1.0.0");
		tldOnlyPurls.put(w.release(), List.of());

		// the first publish: two rows, one version, two BOM artifacts named after the profiles
		assertEquals(TeaPublishOutcome.PUBLISHED, publish(w.release(), TeaPublishMode.PUBLISH).outcome());
		List<UUID> synthetic = stored(w.release()).getSyntheticArtifacts();
		assertEquals(2, synthetic.size());
		TeaPublishedCollectionData v1 = latest(w.release());
		assertEquals(TeaCollectionUpdateReasonType.INITIAL_RELEASE, v1.getUpdateReasonType());
		assertEquals(List.of("default", "top"), aggregateNames(v1));
		assertTrue(v1.getEntries().stream().allMatch(e -> TeaArtifactType.BOM == e.teaType()));
		UUID defaultTea = named(v1, "default").teaUuid();
		UUID topTea = named(v1, "top").teaUuid();
		assertNotEquals(defaultTea, topTea);
		JsonNode doc = Utils.OM.readTree(v1.getDocument()).get("artifacts");
		assertEquals("default", doc.get(0).get("name").asString());
		assertEquals("top", doc.get(1).get("name").asString());
		TeaPublicationData head = head(w.release());
		UUID defaultRow = TeaAggregates.aggregateOf(head, "default");
		UUID topRow = TeaAggregates.aggregateOf(head, "top");
		assertEquals(List.of(new TeaAggregateArtifact("default", defaultRow), new TeaAggregateArtifact("top", topRow)),
				head.getAggregateArtifacts());
		assertEquals(List.of(defaultRow, topRow), synthetic);

		UserData admin = admin();
		Map<String, Object> view = data(asUser(admin, VIEW_PROFILES, vars("r", w.release().toString())),
				"releaseTeaPublicationView");
		Map<String, Object> pub = (Map<String, Object>) view.get("publication");
		assertEquals(defaultRow.toString(), pub.get("aggregateArtifact"), "the deprecated field: the default row");
		assertEquals(List.of(Map.of("profileName", "default", "artifact", defaultRow.toString()),
				Map.of("profileName", "top", "artifact", topRow.toString())), pub.get("aggregateArtifacts"));
		assertEquals(List.of("default", "top"), view.get("profiles"));
		List<Map<String, Object>> cols = data(asUser(admin, ENTRIES, vars("p", head.getUuid().toString())),
				"teaPublicationCollections");
		List<Map<String, Object>> entries = (List<Map<String, Object>>) cols.get(0).get("entries");
		SbomProfileData topProfile = sbomProfileService.getProfile(org.getUuid(), SettingsScope.ORGANIZATION, null, "top")
				.orElseThrow();
		SbomProfileData defaultProfile = sbomProfileService.getProfile(org.getUuid(), SettingsScope.ORGANIZATION, null,
				"default").orElseThrow();
		assertEquals("default", entries.get(0).get("sbomProfileName"));
		assertEquals(defaultProfile.getUuid().toString(), entries.get(0).get("sbomProfileUuid"));
		assertEquals(defaultProfile.getRevision(), entries.get(0).get("sbomProfileRevision"));
		assertEquals("top", entries.get(1).get("sbomProfileName"));
		assertEquals(topProfile.getUuid().toString(), entries.get(1).get("sbomProfileUuid"));
		assertEquals(topProfile.getRevision(), entries.get(1).get("sbomProfileRevision"));

		// unchanged: nothing uploaded, no version
		int uploads = uploaded.size();
		assertEquals(TeaPublishOutcome.UNCHANGED, publish(w.release(), TeaPublishMode.REPUBLISH).outcome());
		assertEquals(1, versions(w.release()).size());
		assertEquals(uploads, uploaded.size());

		// top's recipe changes: only top's row goes up, in place
		ArtifactData topBefore = artifactService.getArtifactData(topRow).orElseThrow();
		SbomProfileData topSaved = saveSbomProfile(SettingsScope.ORGANIZATION, null, "top", SbomDependencyDepth.FULL,
				SbomInternalMetadata.EXCLUDE);
		assertEquals(TeaPublishOutcome.REPUBLISHED, publish(w.release(), TeaPublishMode.REPUBLISH).outcome());
		TeaPublishedCollectionData v2 = latest(w.release());
		assertEquals(2, v2.getVersion());
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_UPDATED, v2.getUpdateReasonType());
		ArtifactData topAfter = artifactService.getArtifactData(topRow).orElseThrow();
		assertEquals("2", topAfter.getVersion());
		assertEquals(topBefore.getInternalBom().id(), topAfter.getInternalBom().id(), "same serial");
		assertEquals("1", artifactService.getArtifactData(defaultRow).orElseThrow().getVersion());
		assertEquals(named(v1, "default"), named(v2, "default"), "default carried verbatim");
		assertEquals(Integer.valueOf(topSaved.getRevision()), named(v2, "top").sbomProfileRevision());
		assertEquals(1, topSaved.getRevision());

		// top removed from the list
		nameTheOrgRow("default");
		publish(w.release(), TeaPublishMode.REPUBLISH);
		TeaPublishedCollectionData v3 = latest(w.release());
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_REMOVED, v3.getUpdateReasonType());
		assertTrue(v3.getUpdateReasonComment().contains("removed: top (" + topTea + ")"), v3.getUpdateReasonComment());
		assertTrue(stored(w.release()).getSyntheticArtifacts().contains(topRow), "top's row stays bound");

		// third added
		saveSbomProfile(SettingsScope.ORGANIZATION, null, "third", SbomDependencyDepth.FULL, SbomInternalMetadata.EXCLUDE);
		nameTheOrgRow("default", "third");
		publish(w.release(), TeaPublishMode.REPUBLISH);
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_ADDED, latest(w.release()).getUpdateReasonType());
		assertEquals(4, latest(w.release()).getVersion());

		// top added back after a save of its row that is no content change (F-2)
		jdbc.update("UPDATE rearm.artifacts SET last_updated_date = now() + interval '1 hour' WHERE uuid = ?", topRow);
		uploads = uploaded.size();
		nameTheOrgRow("default", "third", "top");
		publish(w.release(), TeaPublishMode.REPUBLISH);
		TeaPublishedCollectionData v5 = latest(w.release());
		assertEquals(5, v5.getVersion());
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_ADDED, v5.getUpdateReasonType());
		assertEquals(uploads, uploaded.size(), "no upload: top's row is reused at revision 2");
		assertEquals(topTea, named(v5, "top").teaUuid());
		assertEquals(2, named(v5, "top").revision());
		assertEquals(element(v2, topTea), element(v5, topTea), "the element version 2 froze, byte for byte");
		assertEquals(named(v2, "top"), named(v5, "top"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void aSecondNameThatNoLongerResolvesRefusesTheWholePublishAndThePreviewSaysSo() throws Exception {
		saveOrgRecipe();
		saveSbomProfile(SettingsScope.ORGANIZATION, null, "nope", SbomDependencyDepth.FULL, SbomInternalMetadata.EXCLUDE);
		saveOrgProfileNaming(TeaVisibility.PRIVATE, TeaRawArtifacts.NONE, "default", "nope");
		// the service path is refused by TEA-27's delete guard, which is the point of that guard
		assertEquals(1, jdbc.update("DELETE FROM rearm.sbom_profiles WHERE org = ? AND name = 'nope'", org.getUuid()));
		World w = componentRelease("pkg:generic/pump@1.0.0");

		TeaPublishRefusedException e = assertThrows(TeaPublishRefusedException.class,
				() -> publish(w.release(), TeaPublishMode.PUBLISH));

		assertEquals(TeaPublishRefusal.PROFILE_NOT_FOUND, e.reason());
		assertTrue(e.getMessage().contains("SBOM profile nope, named by the organization"), e.getMessage());
		assertTrue(publicationRepository.findByRelease(w.release()).isEmpty());
		assertTrue(stored(w.release()).getSyntheticArtifacts().isEmpty());
		assertTrue(mergesOf(w.release()).isEmpty(), "nothing generated, nothing uploaded");
		Map<String, Object> view = data(asUser(admin(), VIEW_PROFILES, vars("r", w.release().toString())),
				"releaseTeaPublicationView");
		assertEquals(false, view.get("canPublish"));
		assertEquals("PROFILE_NOT_FOUND", view.get("refusal"));
		assertEquals(List.of("default", "nope"), view.get("profiles"));
		assertNull(view.get("publication"));
	}

	@Test
	void aSecondNameInConflictBetweenPerspectivesRefusesProfileConflictNamingIt() throws Exception {
		assumeFalse(LicensingConstants.isOssEdition(), "perspectives are ReARM Pro");
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID p1 = perspectiveService.createPerspective(org.getUuid(), "tea28-p1-" + UUID.randomUUID(), WU).getUuid();
		UUID p2 = perspectiveService.createPerspective(org.getUuid(), "tea28-p2-" + UUID.randomUUID(), WU).getUuid();
		saveSbomProfile(SettingsScope.PERSPECTIVE, p1, "pp", SbomDependencyDepth.TOP_LEVEL_ONLY, SbomInternalMetadata.EXCLUDE);
		perspectiveService.setPerspectivesOnComponent(w.component(), List.of(p1, p2), TeaCaller.ORG_ADMIN, WU);
		// saved while only P1 defines pp: the save-time check is an early warning only
		teaProfileService.saveProfile(org.getUuid(), TeaProfileScope.COMPONENT, w.component(),
				profileInput(TeaPublishing.ENABLED, TeaVisibility.PRIVATE, TeaRawArtifacts.NONE,
						TeaProductComponents.PUBLISH_WITH_PRODUCT).mode(TeaComponentProfileMode.OVERRIDE)
						.profiles(List.of("default", "pp")).build(), TeaCaller.ORG_ADMIN, WU);
		saveSbomProfile(SettingsScope.PERSPECTIVE, p2, "pp", SbomDependencyDepth.FULL, SbomInternalMetadata.EXCLUDE);

		TeaPublishRefusedException e = assertThrows(TeaPublishRefusedException.class,
				() -> publish(w.release(), TeaPublishMode.PUBLISH));

		assertEquals(TeaPublishRefusal.PROFILE_CONFLICT, e.reason());
		assertTrue(e.getMessage().contains("SBOM profile pp resolves to a conflict between perspectives"), e.getMessage());
		assertTrue(mergesOf(w.release()).isEmpty());
		assertTrue(publicationRepository.findByRelease(w.release()).isEmpty());
	}

	@Test
	void aCeShapedRowPublishesOneAggregateNamedDefaultOnTheSameRowEveryTime() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");

		publish(w.release(), TeaPublishMode.PUBLISH);
		UUID row = TeaAggregates.defaultAggregate(head(w.release()));
		UUID tea = named(latest(w.release()), "default").teaUuid();
		mergedPurls.put(w.release(), List.of("pkg:generic/pump@1.0.0", "pkg:generic/seal@2"));
		publish(w.release(), TeaPublishMode.REPUBLISH);

		assertEquals(List.of(new TeaAggregateArtifact("default", row)), head(w.release()).getAggregateArtifacts());
		assertEquals(List.of(row), stored(w.release()).getSyntheticArtifacts());
		assertEquals(List.of("default"), aggregateNames(latest(w.release())));
		assertEquals(tea, named(latest(w.release()), "default").teaUuid());
		assertEquals(2, named(latest(w.release()), "default").revision());
		assertFalse(versions(w.release()).isEmpty());
	}
}
