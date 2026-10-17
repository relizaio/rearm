/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.Utils;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomMediaType;
import io.reliza.service.ReleaseRebomService;
import io.reliza.service.SharedReleaseService;
import tools.jackson.databind.JsonNode;

/**
 * SCORE-11 (T-6, T-7): the excludeFileComponents argument of releaseSbomExport and releaseSbomScore,
 * against the test database, with rebom mocked as a store that merges a document carrying a file
 * component and serves it filtered (as rebom's merge does) when the merge was asked to leave files out.
 */
class ExcludeFileComponentsGraphqlIntegrationTest extends SbomScoreGraphqlTestBase {

	private static final String PROPERTY = "reliza:export:fileComponentsExcluded";
	/** A ReARM-internal marker, which includeInternalMetadata=false does remove. */
	private static final String INTERNAL_MARKER = "reliza:containerSafeVersion";

	/** A merged document with one library and one file, the file a direct dependency of the root. */
	private static final String FULL_DOC = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
			+ "\"metadata\":{\"component\":{\"type\":\"application\",\"name\":\"caf\u00e9\",\"bom-ref\":\"root\"}},"
			+ "\"components\":[{\"type\":\"library\",\"name\":\"lib\",\"version\":\"1.0\",\"bom-ref\":\"lib\"},"
			+ "{\"type\":\"file\",\"name\":\"/usr/bin/tool\",\"bom-ref\":\"file\"}],"
			+ "\"dependencies\":[{\"ref\":\"root\",\"dependsOn\":[\"lib\",\"file\"]}]}";
	/** The same merge as rebom stores it with excludeFileComponents. */
	private static final String FILTERED_DOC = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
			+ "\"metadata\":{\"component\":{\"type\":\"application\",\"name\":\"caf\u00e9\",\"bom-ref\":\"root\"},"
			+ "\"properties\":[{\"name\":\"" + PROPERTY + "\",\"value\":\"1\"},"
			+ "{\"name\":\"" + INTERNAL_MARKER + "\",\"value\":\"1.2.3\"}]},"
			+ "\"components\":[{\"type\":\"library\",\"name\":\"lib\",\"version\":\"1.0\",\"bom-ref\":\"lib\"}],"
			+ "\"dependencies\":[{\"ref\":\"root\",\"dependsOn\":[\"lib\"]}]}";

	@Autowired private ReleaseRebomService releaseRebomService;
	@Autowired private SharedReleaseService sharedReleaseService;

	private final Set<UUID> filteredMerges = ConcurrentHashMap.newKeySet();

	@BeforeEach
	void rebomMergesAndFilters() throws Exception {
		filteredMerges.clear();
		when(rebomService.mergeAndStoreBoms(anyList(), any(), any())).thenAnswer(inv -> {
			RebomOptions options = inv.getArgument(1);
			UUID id = UUID.randomUUID();
			if (Boolean.TRUE.equals(options.excludeFileComponents())) filteredMerges.add(id);
			return id;
		});
		when(rebomService.findBomByIdJson(any(), any())).thenAnswer(inv ->
				Utils.OM.readTree(filteredMerges.contains(inv.<UUID>getArgument(0)) ? FILTERED_DOC : FULL_DOC));
	}

	private String export(Fixture f, Map<String, Object> vars) {
		return data(as(f.downloader(), EXPORT_RELEASE, vars), "releaseSbomExport");
	}

	private static List<String> componentTypes(String doc) throws Exception {
		List<String> types = new ArrayList<>();
		for (JsonNode c : Utils.OM.readTree(doc).get("components")) types.add(c.get("type").asString());
		return types;
	}

	private static String excludedCount(String doc) throws Exception {
		return metadataProperty(doc, PROPERTY);
	}

	private static String metadataProperty(String doc, String name) throws Exception {
		for (JsonNode p : Utils.OM.readTree(doc).path("metadata").path("properties")) {
			if (name.equals(p.path("name").asString())) return p.path("value").asString();
		}
		return null;
	}

	/**
	 * The excludeFileComponents value of the release's download-log rows, oldest first, once there
	 * are {@code expected} of them: the log is written asynchronously.
	 */
	private List<String> loggedExcludeFileComponents(UUID release, int expected) throws InterruptedException {
		List<String> values = List.of();
		for (int i = 0; i < 40 && values.size() < expected; i++) {
			if (i > 0) Thread.sleep(250);
			values = jdbcTemplate.queryForList("select record_data->'downloadConfig'->>'excludeFileComponents'"
					+ " from rearm.download_logs where record_data->>'subjectUuid' = ?"
					+ " order by created_date", String.class, release.toString());
		}
		return values;
	}

	private RebomOptions lastMergeRequest() {
		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService, times(1)).mergeAndStoreBoms(anyList(), sent.capture(), any());
		return sent.getValue();
	}

	// T-6
	@Test
	void theSwitchExportsTheMergeWithoutFileComponentsAndLogsIt() throws Exception {
		Fixture f = fixture();
		String exported = export(f, releaseVars(f.release(), "fc", true));

		assertEquals(List.of("library"), componentTypes(exported));
		assertEquals("1", excludedCount(exported));
		assertEquals(Boolean.TRUE, lastMergeRequest().excludeFileComponents());
		assertEquals(List.of("true"), loggedExcludeFileComponents(f.release(), 1));
	}

	// T-6; run 1 T-1: the Export Release BOM dialog sends includeInternalMetadata=false by default
	@Test
	void theRecordOfLeftOutFilesSurvivesTheInternalMetadataStrip() throws Exception {
		Fixture f = fixture();
		String declined = export(f, releaseVars(f.release(), "fc", true, "im", false));
		String silent = export(f, releaseVars(f.release(), "fc", true));

		assertEquals("1", excludedCount(declined), "the file says files were left out, as the dialog promises");
		assertNull(metadataProperty(declined, INTERNAL_MARKER), "the strip itself still ran");
		assertEquals(List.of("library"), componentTypes(declined));
		assertEquals("1", excludedCount(silent));
		assertEquals("1.2.3", metadataProperty(silent, INTERNAL_MARKER));
	}

	// T-6
	@Test
	void withoutTheArgumentTheExportIsWhatItWasBefore() throws Exception {
		Fixture f = fixture();
		String absent = export(f, releaseVars(f.release()));
		RebomOptions sent = lastMergeRequest();
		String explicitFalse = export(f, releaseVars(f.release(), "fc", false));
		assertEquals(List.of("false", "false"), loggedExcludeFileComponents(f.release(), 2),
				"the parsed value is logged, absent and false alike");

		assertEquals(List.of("library", "file"), componentTypes(absent));
		assertNull(excludedCount(absent), "an unfiltered export says nothing about files");
		assertEquals(absent, explicitFalse, "false is the same request as absent, served from the same row");
		assertNull(sent.excludeFileComponents());
		assertFalse(Utils.OM.writeValueAsString(sent).contains("excludeFileComponents"),
				"the merge request to rebom is the one sent before the argument existed");
		assertEquals(absent, releaseService.exportReleaseSbom(f.release(), null, null, null, null,
				BomMediaType.JSON, f.org().getUuid(), WU, null),
				"byte-equal to the export through the pre-existing signature");
	}

	// T-6, T-11 at the API level: one stored row per setting, each served from cache afterwards
	@Test
	void eachSettingMergesOnceAndIsThenServedFromItsOwnRow() throws Exception {
		Fixture f = fixture();
		String off = export(f, releaseVars(f.release()));
		String on = export(f, releaseVars(f.release(), "fc", true));
		clearInvocations(rebomService);

		assertEquals(off, export(f, releaseVars(f.release())));
		assertEquals(on, export(f, releaseVars(f.release(), "fc", true)));
		verify(rebomService, times(0)).mergeAndStoreBoms(anyList(), any(), any());

		ReleaseData rd = sharedReleaseService.getReleaseData(f.release()).orElseThrow();
		List<ReleaseBom> rows = releaseRebomService.getReleaseBoms(rd);
		assertEquals(2, rows.size(), rows.toString());
		assertEquals(1, rows.stream().filter(r -> Boolean.TRUE.equals(r.rebomMergeOptions().excludeFileComponents())).count());
	}

	// T-7
	@Test
	void theScoreWithTheSwitchScoresTheFilteredExport() throws Exception {
		Fixture f = fixture();
		Map<String, Object> vars = releaseVars(f.release(), "fc", true);
		String report = data(as(f.downloader(), SCORE_RELEASE, withProfiles(vars, PROFILES)), "releaseSbomScore");
		assertEquals(REPORT, report);

		ArgumentCaptor<String> scored = ArgumentCaptor.forClass(String.class);
		verify(rebomService).scoreBom(scored.capture(), eq(PROFILES));
		verify(releaseService).exportReleaseSbom(eq(f.release()), any(), any(), eq(true), any(), any(),
				eq(BomMediaType.JSON), eq(f.org().getUuid()), any(), any(), any());
		assertEquals(List.of("library"), componentTypes(scored.getValue()));
		assertEquals(export(f, vars), scored.getValue(), "byte for byte the switched export");
	}

	// T-7
	@Test
	void theScoreWithoutTheSwitchKeepsTheFiles() throws Exception {
		Fixture f = fixture();
		data(as(f.downloader(), SCORE_RELEASE, withProfiles(releaseVars(f.release()), PROFILES)), "releaseSbomScore");
		ArgumentCaptor<String> scored = ArgumentCaptor.forClass(String.class);
		verify(rebomService).scoreBom(scored.capture(), eq(PROFILES));
		assertTrue(componentTypes(scored.getValue()).contains("file"));
		assertNull(excludedCount(scored.getValue()));
	}
}
