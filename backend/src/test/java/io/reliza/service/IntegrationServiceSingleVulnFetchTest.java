/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sun.net.httpserver.HttpServer;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.IntegrationData;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.model.VulnerabilityRecordData.UpstreamSource;
import io.reliza.model.VulnerabilityRecordData.VulnSourceSnapshot;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.service.VulnerabilityRecordService.UpsertOrigin;

/**
 * {@link IntegrationService#fetchSingleVulnerabilityFromDtrack} against a
 * local HTTP server standing in for Dependency-Track, so the real WebClient,
 * status handling and URL encoding are exercised. Pins: the id guard, the
 * "not found" vs "failed" vs "key refused" outcomes, the attempt cap, the
 * alias round, and alias seeding from an existing record (which keeps a GHSA
 * refresh on the CVE-keyed row instead of creating a second one).
 */
class IntegrationServiceSingleVulnFetchTest {

	private static final String PREFIX = "/api/v1/vulnerability/source/";

	private HttpServer server;
	/** "SOURCE/id" -> status and body; anything unmapped answers 404. */
	private final Map<String, Map.Entry<Integer, String>> responses = new HashMap<>();
	private final List<String> requested = Collections.synchronizedList(new ArrayList<>());
	private final List<String> apiKeys = Collections.synchronizedList(new ArrayList<>());

	private VulnerabilityRecordService vulnerabilityRecordService;
	private IntegrationService service;
	private final UUID org = UUID.randomUUID();
	private final WhoUpdated wu = WhoUpdated.getAutoWhoUpdated();

	@BeforeEach
	void setUp() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getRawPath();
			String key = path.startsWith(PREFIX) ? path.substring(PREFIX.length()).replace("/vuln/", "/") : path;
			requested.add(key);
			apiKeys.add(exchange.getRequestHeaders().getFirst("X-API-Key"));
			Map.Entry<Integer, String> r = responses.getOrDefault(key, Map.entry(404, ""));
			byte[] body = r.getValue().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(r.getKey(), body.length == 0 ? -1 : body.length);
			if (body.length > 0) {
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(body);
				}
			}
			exchange.close();
		});
		server.start();

		EncryptionService encryptionService = mock(EncryptionService.class);
		when(encryptionService.decrypt(anyString())).thenReturn("dt-key");
		vulnerabilityRecordService = mock(VulnerabilityRecordService.class);
		when(vulnerabilityRecordService.getByAlias(any(), anyString())).thenReturn(Optional.empty());

		IntegrationService real = new IntegrationService(mock(IntegrationRepository.class));
		inject(real, "encryptionService", encryptionService);
		inject(real, "vulnerabilityRecordService", vulnerabilityRecordService);
		service = spy(real);
		IntegrationData dt = new IntegrationData();
		dt.setUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
		dt.setSecret("encrypted");
		doReturn(Optional.of(dt)).when(service).getIntegrationDataByOrgTypeIdentifier(any(), any(), any());
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	private static void inject(Object target, String field, Object value) throws Exception {
		Field f = IntegrationService.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private void respond(String sourceAndId, int status, String body) {
		responses.put(sourceAndId, Map.entry(status, body));
	}

	private static String vulnJson(String vulnId, String source, String cveAlias, String ghsaAlias) {
		String aliases = (cveAlias == null && ghsaAlias == null) ? "[]"
				: "[{\"cveId\":" + (cveAlias == null ? "null" : "\"" + cveAlias + "\"")
						+ ",\"ghsaId\":" + (ghsaAlias == null ? "null" : "\"" + ghsaAlias + "\"") + "}]";
		return "{\"vulnId\":\"" + vulnId + "\",\"source\":\"" + source + "\",\"severity\":\"HIGH\","
				+ "\"description\":\"d\",\"aliases\":" + aliases + "}";
	}

	@SuppressWarnings("unchecked")
	private ArgumentCaptor<Set<String>> aliasCaptor() {
		return ArgumentCaptor.forClass(Set.class);
	}

	@SuppressWarnings("unchecked")
	private ArgumentCaptor<List<VulnSourceSnapshot>> snapshotCaptor() {
		return ArgumentCaptor.forClass(List.class);
	}

	@Test
	void invalidIdIsRejectedBeforeAnyRequest() {
		RelizaException e = assertThrows(RelizaException.class,
				() -> service.fetchSingleVulnerabilityFromDtrack(org, "../CVE-1", wu));
		assertEquals("Invalid vulnerability id", e.getMessage());
		assertTrue(requested.isEmpty());
	}

	@Test
	void notFoundEverywhereNamesTheSourcesSearched() {
		RelizaException e = assertThrows(RelizaException.class,
				() -> service.fetchSingleVulnerabilityFromDtrack(org, "CVE-2026-0001", wu));
		assertEquals("Vulnerability CVE-2026-0001 was not found in Dependency-Track (searched NVD, GITHUB, OSV)",
				e.getMessage());
		assertEquals(List.of("NVD/CVE-2026-0001", "GITHUB/CVE-2026-0001", "OSV/CVE-2026-0001"), requested);
		assertEquals(List.of("dt-key", "dt-key", "dt-key"), apiKeys);
	}

	@Test
	void refusedKeyStopsAfterTheFirstRequest() {
		respond("NVD/CVE-2026-0001", 401, "");
		respond("GITHUB/CVE-2026-0001", 401, "");
		respond("OSV/CVE-2026-0001", 401, "");
		RelizaException e = assertThrows(RelizaException.class,
				() -> service.fetchSingleVulnerabilityFromDtrack(org, "CVE-2026-0001", wu));
		assertEquals("Dependency-Track rejected this organization's API key; check the Dependency-Track integration",
				e.getMessage());
		assertEquals(List.of("NVD/CVE-2026-0001"), requested);
	}

	@Test
	void forbiddenIsTreatedLikeUnauthorized() {
		respond("OSV/PYSEC-2018-5", 403, "");
		assertThrows(RelizaException.class,
				() -> service.fetchSingleVulnerabilityFromDtrack(org, "PYSEC-2018-5", wu));
		assertEquals(List.of("OSV/PYSEC-2018-5"), requested);
	}

	@Test
	void serverErrorOnOneSourceStillTriesTheRestThenReportsFailure() {
		respond("NVD/CVE-2026-0001", 500, "boom");
		RelizaException e = assertThrows(RelizaException.class,
				() -> service.fetchSingleVulnerabilityFromDtrack(org, "CVE-2026-0001", wu));
		assertEquals("Failed to fetch vulnerability CVE-2026-0001 from Dependency-Track", e.getMessage());
		assertEquals(3, requested.size());
	}

	@Test
	void serverErrorOnOneSourceDoesNotBlockAHitOnAnother() throws Exception {
		respond("NVD/CVE-2026-0001", 500, "boom");
		respond("OSV/CVE-2026-0001", 200, vulnJson("CVE-2026-0001", "OSV", null, null));
		VulnerabilityRecordData merged = new VulnerabilityRecordData();
		when(vulnerabilityRecordService.upsertFromSnapshots(eq(org), any(), any(), eq(wu),
				eq(UpsertOrigin.MANUAL_REFRESH))).thenReturn(merged);

		assertSame(merged, service.fetchSingleVulnerabilityFromDtrack(org, "CVE-2026-0001", wu));
	}

	@Test
	void aliasRoundFetchesTheAdvisoriesTheRowNames() throws Exception {
		respond("OSV/PYSEC-2018-5", 200, vulnJson("PYSEC-2018-5", "OSV", "CVE-2018-7536", "GHSA-r28v-mw67-m5p9"));
		respond("GITHUB/GHSA-r28v-mw67-m5p9", 200, vulnJson("GHSA-r28v-mw67-m5p9", "GITHUB", "CVE-2018-7536", null));
		when(vulnerabilityRecordService.upsertFromSnapshots(any(), any(), any(), any(), any()))
				.thenReturn(new VulnerabilityRecordData());

		service.fetchSingleVulnerabilityFromDtrack(org, "PYSEC-2018-5", wu);

		assertEquals(List.of("OSV/PYSEC-2018-5", "GITHUB/GHSA-r28v-mw67-m5p9", "NVD/CVE-2018-7536"), requested);
		ArgumentCaptor<Set<String>> aliases = aliasCaptor();
		ArgumentCaptor<List<VulnSourceSnapshot>> snapshots = snapshotCaptor();
		verify(vulnerabilityRecordService).upsertFromSnapshots(eq(org), aliases.capture(), snapshots.capture(),
				eq(wu), eq(UpsertOrigin.MANUAL_REFRESH));
		assertEquals(Set.of("PYSEC-2018-5", "CVE-2018-7536", "GHSA-r28v-mw67-m5p9"), aliases.getValue());
		assertEquals(List.of(UpstreamSource.OSV, UpstreamSource.GITHUB),
				snapshots.getValue().stream().map(VulnSourceSnapshot::getUpstreamSource).toList());
	}

	@Test
	void onlyOneRowPerSourceIsKept() throws Exception {
		// The record's OSV snapshot is PYSEC-2018-5; refreshing the CVE must not
		// also take OSV's CVE-keyed row, which would overwrite that snapshot.
		VulnerabilityRecordData existing = new VulnerabilityRecordData();
		existing.setPrimaryVulnId("CVE-2018-7536");
		VulnSourceSnapshot osv = new VulnSourceSnapshot();
		osv.setUpstreamSource(UpstreamSource.OSV);
		osv.setUpstreamVulnId("PYSEC-2018-5");
		existing.setSources(new ArrayList<>(List.of(osv)));
		when(vulnerabilityRecordService.getByAlias(org, "CVE-2018-7536")).thenReturn(Optional.of(existing));
		respond("OSV/PYSEC-2018-5", 200, vulnJson("PYSEC-2018-5", "OSV", "CVE-2018-7536", null));
		respond("OSV/CVE-2018-7536", 200, vulnJson("CVE-2018-7536", "OSV", null, null));
		when(vulnerabilityRecordService.upsertFromSnapshots(any(), any(), any(), any(), any()))
				.thenReturn(new VulnerabilityRecordData());

		service.fetchSingleVulnerabilityFromDtrack(org, "CVE-2018-7536", wu);

		assertEquals(List.of("OSV/PYSEC-2018-5", "NVD/CVE-2018-7536", "GITHUB/CVE-2018-7536"), requested);
		ArgumentCaptor<List<VulnSourceSnapshot>> snapshots = snapshotCaptor();
		verify(vulnerabilityRecordService).upsertFromSnapshots(eq(org), any(), snapshots.capture(), eq(wu),
				eq(UpsertOrigin.MANUAL_REFRESH));
		assertEquals(List.of("PYSEC-2018-5"),
				snapshots.getValue().stream().map(VulnSourceSnapshot::getUpstreamVulnId).toList());
	}

	@Test
	void existingRecordSeedsAliasesAndRefetchesItsOwnSourceKeys() throws Exception {
		// The org's record is keyed by the CVE and carries a GitHub snapshot
		// under the GHSA id. DT's GitHub row comes back without aliases; the
		// upsert must still see the CVE so it lands on the existing row.
		VulnerabilityRecordData existing = new VulnerabilityRecordData();
		existing.setPrimaryVulnId("CVE-2024-0001");
		existing.setAliases(new LinkedHashSet<>(List.of("CVE-2024-0001", "GHSA-abcd-efgh-ijkl")));
		VulnSourceSnapshot gh = new VulnSourceSnapshot();
		gh.setUpstreamSource(UpstreamSource.GITHUB);
		gh.setUpstreamVulnId("GHSA-abcd-efgh-ijkl");
		existing.setSources(new ArrayList<>(List.of(gh)));
		when(vulnerabilityRecordService.getByAlias(org, "GHSA-abcd-efgh-ijkl")).thenReturn(Optional.of(existing));
		respond("GITHUB/GHSA-abcd-efgh-ijkl", 200, vulnJson("GHSA-abcd-efgh-ijkl", "GITHUB", null, null));
		when(vulnerabilityRecordService.upsertFromSnapshots(any(), any(), any(), any(), any()))
				.thenReturn(new VulnerabilityRecordData());

		service.fetchSingleVulnerabilityFromDtrack(org, "GHSA-abcd-efgh-ijkl", wu);

		assertEquals(List.of("GITHUB/GHSA-abcd-efgh-ijkl", "OSV/GHSA-abcd-efgh-ijkl"), requested);
		ArgumentCaptor<Set<String>> aliases = aliasCaptor();
		verify(vulnerabilityRecordService).upsertFromSnapshots(eq(org), aliases.capture(), any(), eq(wu),
				eq(UpsertOrigin.MANUAL_REFRESH));
		assertTrue(aliases.getValue().containsAll(Set.of("CVE-2024-0001", "GHSA-abcd-efgh-ijkl")));
	}

	@Test
	void attemptsAreCapped() throws Exception {
		VulnerabilityRecordData existing = new VulnerabilityRecordData();
		existing.setPrimaryVulnId("CVE-2024-0002");
		List<VulnSourceSnapshot> many = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			VulnSourceSnapshot s = new VulnSourceSnapshot();
			s.setUpstreamSource(UpstreamSource.OSV);
			s.setUpstreamVulnId("GO-2024-" + i);
			many.add(s);
		}
		existing.setSources(many);
		when(vulnerabilityRecordService.getByAlias(org, "CVE-2024-0002")).thenReturn(Optional.of(existing));

		assertThrows(RelizaException.class,
				() -> service.fetchSingleVulnerabilityFromDtrack(org, "CVE-2024-0002", wu));
		assertEquals(6, requested.size());
	}

	@Test
	void noIntegrationIsACleanError() throws Exception {
		doReturn(Optional.empty()).when(service).getIntegrationDataByOrgTypeIdentifier(any(), any(), any());
		RelizaException e = assertThrows(RelizaException.class,
				() -> service.fetchSingleVulnerabilityFromDtrack(org, "CVE-2026-0001", wu));
		assertEquals("No Dependency-Track integration is configured for this organization", e.getMessage());
		assertTrue(requested.isEmpty());
		verify(vulnerabilityRecordService, never()).upsertFromSnapshots(any(), any(), any(), any(), any());
	}
}
