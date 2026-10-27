/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.SbomProfileData.SbomDependencyDepth;
import io.reliza.model.SbomProfileData.SbomInternalMetadata;
import io.reliza.model.SettingsScope;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import tools.jackson.databind.JsonNode;

/**
 * Task TEA-28, design 4.15 (threat-model mitigations M1 and M4): the TEA surface serves the named aggregates of
 * a PUBLIC publication from its stored collection versions with no spec-shape change: two BOM artifacts named
 * after their profiles, each at its own uuid with its own bytes and the download file name {@code <name>.cdx.json}
 * (D10); a removed profile's revision still served from the versions naming it; a re-added one the same object;
 * a legacy-named revision served under its frozen name.
 */
class TeaSurfaceProfilesIntegrationTest extends TeaSurfaceIntegrationTestBase {

	private static final String CDX = "application/vnd.cyclonedx+json";

	private String base() {
		return root() + "/tea/" + getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow().getTeaUuid()
				+ "/v1.0.0";
	}

	private String download(UUID tea, int revision) {
		return base() + "/artifact/" + tea + "/" + revision + "/download?mediaType=" + URLEncoder.encode(CDX,
				StandardCharsets.UTF_8);
	}

	private JsonNode collection(UUID head, String version) throws Exception {
		HttpResponse<String> r = get(base() + "/componentRelease/" + head + "/collection/" + version, null);
		assertEquals(200, r.statusCode(), r.body());
		return Utils.OM.readTree(r.body());
	}

	private JsonNode artifact(UUID tea, int revision) throws Exception {
		HttpResponse<String> r = get(base() + "/artifact/" + tea + "/" + revision, null);
		assertEquals(200, r.statusCode(), r.body());
		return Utils.OM.readTree(r.body());
	}

	private static List<String> names(JsonNode collection) {
		List<String> out = new ArrayList<>();
		collection.get("artifacts").forEach(a -> out.add(a.get("name").asString()));
		return out;
	}

	private static JsonNode elementOf(JsonNode collection, UUID tea) {
		for (JsonNode a : collection.get("artifacts")) if (tea.toString().equals(a.get("uuid").asString())) return a;
		throw new AssertionError("no element " + tea);
	}

	private TeaPublishedEntry entry(UUID head, int version, String profile) {
		return teaPublicationService.getCollection(head, version).orElseThrow().getEntries().stream()
				.filter(e -> TeaArtifactOrigin.SYNTHETIC == e.origin() && profile.equals(e.sbomProfileName())).findFirst()
				.orElseThrow();
	}

	private void assertDownloads(TeaPublishedEntry e, String fileName) throws Exception {
		HttpResponse<byte[]> d = getBytes(download(e.teaUuid(), e.revision()), null, null);
		assertEquals(200, d.statusCode());
		assertArrayEquals(retained.get(e.checksums().get(0).algValue()), d.body(), "its uploaded bytes");
		assertEquals(CDX, d.headers().firstValue("Content-Type").orElse(null));
		assertEquals("attachment; filename=\"" + fileName + "\"", d.headers().firstValue("Content-Disposition").orElse(null));
	}

	private void nameTheOrgRow(String... names) throws Exception {
		teaProfileService.saveProfile(org.getUuid(), TeaProfileScope.ORGANIZATION, null,
				profileInput(TeaPublishing.ENABLED, TeaVisibility.PUBLIC, TeaRawArtifacts.NONE,
						TeaProductComponents.PUBLISH_WITH_PRODUCT).profiles(List.of(names)).build(), TeaCaller.ORG_ADMIN, WU);
	}

	@Test
	void twoNamedAggregatesAreServedAndARemovedOneStaysServedAndAReaddedOneIsTheSameObject() throws Exception {
		saveOrgRecipe();
		saveSbomProfile(SettingsScope.ORGANIZATION, null, "top", SbomDependencyDepth.TOP_LEVEL_ONLY,
				SbomInternalMetadata.EXCLUDE);
		saveOrgProfileNaming(TeaVisibility.PUBLIC, TeaRawArtifacts.NONE, "default", "top");
		World w = componentReleaseWithPurl(org, "pkg:generic/tea28-" + tag() + "@1.0.0", List.of());
		tldOnlyPurls.put(w.release(), List.of());
		UUID head = publish(w.release(), TeaPublishMode.PUBLISH).publication().getUuid();

		JsonNode v1 = collection(head, "1");
		assertEquals(List.of("default", "top"), names(v1));
		v1.get("artifacts").forEach(a -> assertEquals("BOM", a.get("type").asString()));
		TeaPublishedEntry def = entry(head, 1, "default");
		TeaPublishedEntry top = entry(head, 1, "top");
		assertNotEquals(def.teaUuid(), top.teaUuid());
		for (TeaPublishedEntry e : List.of(def, top)) {
			assertEquals(e.sbomProfileName(), artifact(e.teaUuid(), 1).get("name").asString());
			assertDownloads(e, e.sbomProfileName() + ".cdx.json");
		}
		JsonNode topObject = artifact(top.teaUuid(), 1);

		// top removed: the latest lists one, version 1 still two, and top's revision is still served
		nameTheOrgRow("default");
		publish(w.release(), TeaPublishMode.REPUBLISH);
		assertEquals(List.of("default"), names(collection(head, "latest")));
		assertEquals(List.of("default", "top"), names(collection(head, "1")));
		assertDownloads(top, "top.cdx.json");

		// top added back with unchanged content: the same object at the same revision
		nameTheOrgRow("default", "top");
		publish(w.release(), TeaPublishMode.REPUBLISH);
		assertEquals(topObject, artifact(top.teaUuid(), 1));
		assertEquals(topObject, elementOf(collection(head, "latest"), top.teaUuid()));
	}

	@Test
	void aLegacyNamedRevisionIsServedUnderItsFrozenNameUntilItsContentChanges() throws Exception {
		saveOrgProfile(TeaVisibility.PUBLIC, TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentReleaseWithPurl(org, "pkg:generic/tea28-" + tag() + "@1.0.0", List.of());
		UUID head = publish(w.release(), TeaPublishMode.PUBLISH).publication().getUuid();
		TeaPublishedEntry def = entry(head, 1, "default");
		String legacy = "legacy-1.0.0-tea-aggregate.cdx.json";
		TeaFrozenEntries.freezeName(collectionRepository, head, 1, def.teaUuid(), legacy);

		assertEquals(legacy, artifact(def.teaUuid(), 1).get("name").asString());
		assertDownloads(entry(head, 1, "default"), legacy);

		mergedPurls.put(w.release(), new ArrayList<>(List.of(mergedPurls.get(w.release()).get(0), "pkg:generic/dep@2")));
		publish(w.release(), TeaPublishMode.REPUBLISH);
		TeaPublishedEntry bumped = entry(head, 2, "default");
		assertEquals(2, bumped.revision());
		assertEquals("default", artifact(def.teaUuid(), 2).get("name").asString());
		assertDownloads(bumped, "default.cdx.json");
		assertEquals(legacy, artifact(def.teaUuid(), 1).get("name").asString(), "revision 1 keeps its frozen name");
		TeaPublishedCollectionData v1 = teaPublicationService.getCollection(head, 1).orElseThrow();
		assertEquals(legacy, elementOf(Utils.OM.readTree(v1.getDocument()), def.teaUuid()).get("name").asString());
	}
}
