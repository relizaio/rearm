/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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

import io.reliza.common.Utils;
import io.reliza.model.IntegrationData;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.model.VulnerabilityRecordData.AffectedIdentityType;
import io.reliza.model.VulnerabilityRecordData.AffectedRange;
import io.reliza.model.VulnerabilityRecordData.AffectedRangeType;
import io.reliza.model.VulnerabilityRecordData.RangeSourceKey;
import io.reliza.model.VulnerabilityRecordData.UpstreamSource;
import io.reliza.model.VulnerabilityRecordData.VulnSourceSnapshot;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.service.IntegrationService.AffectedRangesFetchResult;
import io.reliza.service.IntegrationService.AffectedRangesFetchStatus;
import io.reliza.service.IntegrationService.DtrackAffectedComponentRaw;
import io.reliza.service.VulnerabilityRecordService.FetchedAffectedRanges;
import io.reliza.service.VulnerabilityRecordService.UpsertOutcome;
import io.reliza.service.VulnerabilityRecordService.UpsertResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * Affected ranges from Dependency-Track: the binding of its
 * {@code affectedComponents} (live shapes captured from DT 4.14.2 and
 * 5.1.0, internal uuids stripped), which records are fetched and from which
 * sources, and the bounded fetch right after a drain. The HTTP side runs
 * against a local server standing in for Dependency-Track, as in
 * {@link IntegrationServiceSingleVulnFetchTest}.
 */
class IntegrationServiceAffectedRangesTest {

	private static final String PREFIX = "/api/v1/vulnerability/source/";

	private HttpServer server;
	/** "SOURCE/id" -> status and body; anything unmapped answers 404. */
	private final Map<String, Map.Entry<Integer, String>> responses = new HashMap<>();
	private final List<String> requested = Collections.synchronizedList(new ArrayList<>());
	/** "SOURCE/id" keys the server answers only after two seconds. */
	private final Set<String> slow = Collections.synchronizedSet(new HashSet<>());

	private VulnerabilityRecordService vulnerabilityRecordService;
	private IntegrationService service;
	private String endpoint;
	private final UUID org = UUID.randomUUID();

	@BeforeEach
	void setUp() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getRawPath();
			String key = path.startsWith(PREFIX) ? path.substring(PREFIX.length()).replace("/vuln/", "/") : path;
			requested.add(key);
			if (slow.contains(key)) {
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
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
		endpoint = "http://127.0.0.1:" + server.getAddress().getPort();

		EncryptionService encryptionService = mock(EncryptionService.class);
		when(encryptionService.decrypt(anyString())).thenReturn("dt-key");
		vulnerabilityRecordService = mock(VulnerabilityRecordService.class);
		IntegrationService real = new IntegrationService(mock(IntegrationRepository.class));
		inject(real, "encryptionService", encryptionService);
		inject(real, "vulnerabilityRecordService", vulnerabilityRecordService);
		service = spy(real);
		IntegrationData dt = new IntegrationData();
		dt.setUri(URI.create(endpoint));
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

	private static final String DT4_SHAPES = "/dtrack/affected-components-live-shapes.json";
	/** Dependency-Track 5 responses keep every top-level field but the text ones, to bind as they come. */
	private static final String DT5_SHAPES = "/dtrack/affected-components-live-shapes-dt5.json";

	/** The captured single-vulnerability responses, keyed "SOURCE/id". */
	private static JsonNode liveShapes(String resource) throws Exception {
		try (InputStream in = IntegrationServiceAffectedRangesTest.class.getResourceAsStream(resource)) {
			return Utils.OM.readTree(in);
		}
	}

	private static List<AffectedRange> rangesOf(String key) throws Exception {
		return rangesOf(DT4_SHAPES, key);
	}

	private static List<AffectedRange> rangesOf(String resource, String key) throws Exception {
		JsonNode vuln = liveShapes(resource).get(key);
		return AffectedRange.normalize(IntegrationService.toAffectedRanges(bind(vuln.get("affectedComponents")),
				vuln.get("source").asString()));
	}

	private static List<DtrackAffectedComponentRaw> bind(JsonNode affectedComponents) {
		return Utils.OM.convertValue(affectedComponents, new TypeReference<List<DtrackAffectedComponentRaw>>() {});
	}

	/** A range's package, type and bounds, without its attributions. */
	private static String boundsOf(AffectedRange r) {
		return r.getIdentity() + " " + r.getRangeType() + " " + r.getExactVersion() + " "
				+ r.getVersionStartIncluding() + " " + r.getVersionStartExcluding() + " "
				+ r.getVersionEndIncluding() + " " + r.getVersionEndExcluding();
	}

	private void respondLive(String key) throws Exception {
		respondLive(DT4_SHAPES, key);
	}

	private void respondLive(String resource, String key) throws Exception {
		responses.put(key, Map.entry(200, Utils.OM.writeValueAsString(liveShapes(resource).get(key))));
	}

	/** A record with snapshots of these "SOURCE/id" pairs. */
	private static VulnerabilityRecordData record(String primaryVulnId, String... sourceAndIds) {
		VulnerabilityRecordData d = new VulnerabilityRecordData();
		d.setUuid(UUID.randomUUID());
		d.setPrimaryVulnId(primaryVulnId);
		List<VulnSourceSnapshot> snaps = new ArrayList<>();
		for (String sourceAndId : sourceAndIds) {
			String[] parts = sourceAndId.split("/", 2);
			VulnSourceSnapshot s = new VulnSourceSnapshot();
			s.setUpstreamSource(UpstreamSource.valueOf(parts[0]));
			s.setUpstreamVulnId(parts[1]);
			snaps.add(s);
		}
		d.setSources(snaps);
		return d;
	}

	/** What a drain's upserts report for records they created. */
	private static List<UpsertResult> inserted(VulnerabilityRecordData... records) {
		return Arrays.stream(records).map(d -> new UpsertResult(d, UpsertOutcome.INSERTED)).toList();
	}

	// --- binding: live shapes ---------------------------------------------

	@Test
	void githubRangesKeepEachPackageAndBothAttributions() throws Exception {
		List<AffectedRange> ranges = rangesOf("GITHUB/GHSA-f2jv-r9rf-7988");
		assertEquals(4, ranges.size());
		AffectedRange npm = ranges.stream().filter(r -> r.getIdentity().equals("pkg:npm/handlebars")).findFirst()
				.orElseThrow();
		assertEquals(AffectedIdentityType.PURL, npm.getIdentityType());
		assertEquals(AffectedRangeType.RANGE, npm.getRangeType());
		assertNull(npm.getVersionStartIncluding());
		assertEquals("4.7.7", npm.getVersionEndExcluding());
		assertEquals(List.of(UpstreamSource.GITHUB, UpstreamSource.OSV), npm.getSources());
	}

	@Test
	void osvDjangoRangesAreThreeSeparateRanges() throws Exception {
		List<AffectedRange> ranges = rangesOf("OSV/PYSEC-2018-5");
		assertEquals(List.of("1.11", "1.8", "2.0"), ranges.stream().map(AffectedRange::getVersionStartIncluding).toList());
		assertEquals(List.of("1.11.11", "1.8.19", "2.0.3"), ranges.stream().map(AffectedRange::getVersionEndExcluding).toList());
		assertTrue(ranges.stream().allMatch(r -> r.getSources().equals(List.of(UpstreamSource.OSV))));
	}

	@Test
	void anUnfixedDebianPackageIsARangeCoveringEveryVersion() throws Exception {
		// DT4 sends these rows without a version type.
		List<AffectedRange> ranges = rangesOf("OSV/DEBIAN-CVE-2015-3276");
		assertEquals(3, ranges.size());
		for (AffectedRange r : ranges) {
			assertTrue(r.getIdentity().startsWith("pkg:deb/debian/openldap?"), r.getIdentity());
			assertEquals(AffectedRangeType.RANGE, r.getRangeType());
			assertNull(r.getVersionStartIncluding());
			assertNull(r.getVersionEndExcluding());
			assertNull(r.getExactVersion());
		}
	}

	@Test
	void dependencyTrack5StartsAnUnfixedPackageAtZeroAndItIsStoredAsEveryVersion() throws Exception {
		JsonNode zlib = liveShapes(DT5_SHAPES).get("OSV/DEBIAN-CVE-2023-45853").get("affectedComponents");
		assertTrue(zlib.toString().contains("\"versionStartIncluding\":\"0\""), "the capture should start a range at 0");

		List<AffectedRange> ranges = rangesOf(DT5_SHAPES, "OSV/DEBIAN-CVE-2023-45853");

		AffectedRange unfixed = ranges.stream()
				.filter(r -> r.getIdentity().equals("pkg:deb/debian/zlib?arch=source&distro=bookworm")).findFirst()
				.orElseThrow();
		assertEquals(AffectedRangeType.RANGE, unfixed.getRangeType());
		assertNull(unfixed.getVersionStartIncluding());
		assertNull(unfixed.getVersionEndExcluding());
		AffectedRange fixed = ranges.stream()
				.filter(r -> r.getIdentity().equals("pkg:deb/debian/zlib?arch=source&distro=trixie")).findFirst()
				.orElseThrow();
		assertEquals("1:1.3.dfsg-2", fixed.getVersionEndExcluding());
	}

	@Test
	void dependencyTrack4And5StoreTheSameAdvisoryTheSameWay() throws Exception {
		JsonNode dt5 = liveShapes(DT5_SHAPES);
		List<String> keys = new ArrayList<>();
		liveShapes(DT4_SHAPES).propertyNames().forEach(keys::add);
		assertEquals(6, keys.size());
		for (String key : keys) {
			assertTrue(dt5.has(key), key);
			// Attributions differ with the OSV ecosystems each instance mirrors, so compare without them.
			assertEquals(rangesOf(DT4_SHAPES, key).stream().map(IntegrationServiceAffectedRangesTest::boundsOf).toList(),
					rangesOf(DT5_SHAPES, key).stream().map(IntegrationServiceAffectedRangesTest::boundsOf).toList(), key);
		}
	}

	/** One Dependency-Track {@code affectedComponents} row, bound and normalized. */
	private static AffectedRange boundRow(String json) throws Exception {
		List<AffectedRange> ranges = AffectedRange.normalize(
				IntegrationService.toAffectedRanges(bind(Utils.OM.readTree("[" + json + "]")), "OSV"));
		assertEquals(1, ranges.size());
		return ranges.get(0);
	}

	@Test
	void aVersionTypeWeDoNotKnowKeepsWhatCameWithItWithoutAType() throws Exception {
		AffectedRange r = boundRow("""
				{"identityType": "PURL", "identity": "pkg:golang/example.com/a", "versionType": "GIT",
				 "version": "abc000", "versionStartIncluding": "0", "versionEndExcluding": "abc123"}
				""");

		assertNull(r.getRangeType());
		assertEquals("abc000", r.getExactVersion());
		assertEquals("0", r.getVersionStartIncluding());
		assertEquals("abc123", r.getVersionEndExcluding());
	}

	@Test
	void aRangeFromZeroKeepsItsFix() throws Exception {
		AffectedRange r = boundRow("""
				{"identityType": "PURL", "identity": "pkg:npm/a", "versionType": "RANGE",
				 "versionStartIncluding": "0", "versionEndExcluding": "1.2"}
				""");

		assertEquals(AffectedRangeType.RANGE, r.getRangeType());
		assertNull(r.getVersionStartIncluding());
		assertEquals("1.2", r.getVersionEndExcluding());
	}

	@Test
	void aRowWithBoundsButNoTypeIsARange() throws Exception {
		AffectedRange r = boundRow("""
				{"identityType": "PURL", "identity": "pkg:npm/a", "versionStartIncluding": "0", "versionEndExcluding": "1.2"}
				""");

		assertEquals(AffectedRangeType.RANGE, r.getRangeType());
		assertNull(r.getVersionStartIncluding());
		assertEquals("1.2", r.getVersionEndExcluding());
	}

	@Test
	void aRowWithAVersionButNoTypeIsThatVersion() throws Exception {
		AffectedRange r = boundRow("""
				{"identityType": "PURL", "identity": "pkg:npm/a", "version": "1.0.0"}
				""");

		assertEquals(AffectedRangeType.EXACT, r.getRangeType());
		assertEquals("1.0.0", r.getExactVersion());
	}

	@Test
	void exactAndInclusiveOnlyRowsKeepTheirShape() throws Exception {
		List<AffectedRange> ranges = rangesOf("GITHUB/GHSA-jfh8-c2jp-5v3q");
		assertEquals(10, ranges.size());
		AffectedRange exact = ranges.stream()
				.filter(r -> r.getIdentity().equals("pkg:maven/org.xbib.elasticsearch/log4j")).findFirst().orElseThrow();
		assertEquals(AffectedRangeType.EXACT, exact.getRangeType());
		assertEquals("6.3.2.1", exact.getExactVersion());
		AffectedRange inclusive = ranges.stream()
				.filter(r -> r.getIdentity().equals("pkg:maven/com.guicedee.services/log4j-core")).findFirst().orElseThrow();
		assertEquals("1.2.1.2-jre17", inclusive.getVersionEndIncluding());
		assertNull(inclusive.getVersionEndExcluding());
	}

	@Test
	void nvdCpeRowsAreDropped() throws Exception {
		assertEquals(List.of(), rangesOf("NVD/CVE-2021-44228"));
	}

	// --- fetchAffectedRanges --------------------------------------------------

	@Test
	void fetchesFromDependencyTrack5() throws Exception {
		respondLive(DT5_SHAPES, "GITHUB/GHSA-jfh8-c2jp-5v3q");
		respondLive(DT5_SHAPES, "OSV/DEBIAN-CVE-2023-45853");
		VulnerabilityRecordData d = record("CVE-2021-44228", "NVD/CVE-2021-44228", "GITHUB/GHSA-jfh8-c2jp-5v3q",
				"OSV/DEBIAN-CVE-2023-45853");

		AffectedRangesFetchResult result = service.fetchAffectedRanges(org, d);

		assertEquals(AffectedRangesFetchStatus.FETCHED, result.status());
		assertEquals(2, result.requests());
		assertEquals(14, AffectedRange.normalize(result.fetched().ranges()).size());
	}

	@Test
	void fetchesOnlyTheGithubAndOsvSnapshotsOfTheRecord() throws Exception {
		respondLive("GITHUB/GHSA-jfh8-c2jp-5v3q");
		VulnerabilityRecordData log4shell = record("CVE-2021-44228", "NVD/CVE-2021-44228", "GITHUB/GHSA-jfh8-c2jp-5v3q");

		AffectedRangesFetchResult result = service.fetchAffectedRanges(org, log4shell);

		assertEquals(List.of("GITHUB/GHSA-jfh8-c2jp-5v3q"), requested);
		assertEquals(AffectedRangesFetchStatus.FETCHED, result.status());
		assertEquals(1, result.requests());
		assertEquals(10, AffectedRange.normalize(result.fetched().ranges()).size());
	}

	@Test
	void everyGithubAndOsvIdTheRecordKnowsIsFetchedNotJustItsSnapshots() throws Exception {
		respondLive("OSV/PYSEC-2018-5");
		respondLive("OSV/DEBIAN-CVE-2015-3276");
		// One OSV snapshot, as the record keeps; the other OSV advisory it had before is remembered.
		VulnerabilityRecordData d = record("PYSEC-2018-5", "OSV/PYSEC-2018-5");
		d.setAffectedRangesSourceKeys(List.of(new RangeSourceKey(UpstreamSource.OSV, "DEBIAN-CVE-2015-3276")));

		AffectedRangesFetchResult result = service.fetchAffectedRanges(org, d);

		assertEquals(Set.of("OSV/PYSEC-2018-5", "OSV/DEBIAN-CVE-2015-3276"), Set.copyOf(requested));
		assertEquals(6, AffectedRange.normalize(result.fetched().ranges()).size());
		assertEquals(d.rangeFetchKeys(), result.fetched().fromKeys());
	}

	@Test
	void aRecordWithManyIdsIsFetchedWholeAndOtherSourcesAreNotRequested() throws Exception {
		VulnerabilityRecordData d = record("CVE-2024-0001", "OSV/PYSEC-2024-1", "OTHER/sonatype-2024-0001");
		List<RangeSourceKey> known = new ArrayList<>();
		for (String id : List.of("DEBIAN-CVE-2024-0001", "UBUNTU-CVE-2024-0001", "ALPINE-CVE-2024-0001",
				"CGA-2024-0001", "BIT-2024-0001", "GO-2024-0001", "RUSTSEC-2024-0001")) {
			known.add(new RangeSourceKey(UpstreamSource.OSV, id));
		}
		d.setAffectedRangesSourceKeys(known);
		d.setAliases(new LinkedHashSet<>(List.of("CVE-2024-0001", "SNYK-JS-FOO-1", "sonatype-2024-0001")));

		AffectedRangesFetchResult result = service.fetchAffectedRanges(org, d);

		assertEquals(AffectedRangesFetchStatus.FETCHED, result.status());
		assertEquals(8, result.requests());
		assertTrue(requested.stream().allMatch(k -> k.startsWith("OSV/")), requested.toString());
	}

	@Test
	void noAnswerInTimeCountsAsUnreachableAndStopsTheRound() throws Exception {
		Field timeout = IntegrationService.class.getDeclaredField("singleVulnFetchTimeout");
		timeout.setAccessible(true);
		timeout.set(service, Duration.ofMillis(300));
		slow.add("GITHUB/GHSA-r28v-mw67-m5p9");

		AffectedRangesFetchResult result = service.fetchAffectedRanges(org,
				record("CVE-2018-7536", "GITHUB/GHSA-r28v-mw67-m5p9", "OSV/PYSEC-2018-5"));

		assertEquals(AffectedRangesFetchStatus.UNREACHABLE, result.status());
		assertEquals(List.of("GITHUB/GHSA-r28v-mw67-m5p9"), requested);
	}

	@Test
	void aRecordWithoutGithubOrOsvSourcesIsFetchedWithoutARequest() {
		AffectedRangesFetchResult result = service.fetchAffectedRanges(org, record("CVE-2020-0001", "NVD/CVE-2020-0001"));
		assertEquals(AffectedRangesFetchStatus.FETCHED, result.status());
		assertEquals(0, result.requests());
		assertEquals(List.of(), result.fetched().ranges());
		assertTrue(requested.isEmpty());
	}

	@Test
	void aSourceThatIsNotFoundContributesNothing() throws Exception {
		respondLive("OSV/PYSEC-2018-5");
		AffectedRangesFetchResult result = service.fetchAffectedRanges(org,
				record("CVE-2018-7536", "GITHUB/GHSA-r28v-mw67-m5p9", "OSV/PYSEC-2018-5"));
		assertEquals(AffectedRangesFetchStatus.FETCHED, result.status());
		assertEquals(2, result.requests());
		assertEquals(3, result.fetched().ranges().size());
	}

	@Test
	void aFailedSourceYieldsNoRanges() throws Exception {
		respondLive("OSV/PYSEC-2018-5");
		responses.put("GITHUB/GHSA-r28v-mw67-m5p9", Map.entry(500, "boom"));
		AffectedRangesFetchResult result = service.fetchAffectedRanges(org,
				record("CVE-2018-7536", "GITHUB/GHSA-r28v-mw67-m5p9", "OSV/PYSEC-2018-5"));
		assertEquals(AffectedRangesFetchStatus.FAILED, result.status());
		assertNull(result.fetched());
	}

	@Test
	void aGatewayAnsweringForDependencyTrackMeansItIsUnreachable() {
		for (int gatewayStatus : List.of(502, 503, 504)) {
			requested.clear();
			responses.put("GITHUB/GHSA-r28v-mw67-m5p9", Map.entry(gatewayStatus, "down"));
			responses.put("OSV/PYSEC-2018-5", Map.entry(gatewayStatus, "down"));

			AffectedRangesFetchResult result = service.fetchAffectedRanges(org,
					record("CVE-2018-7536", "GITHUB/GHSA-r28v-mw67-m5p9", "OSV/PYSEC-2018-5"));

			assertEquals(AffectedRangesFetchStatus.UNREACHABLE, result.status(), "status " + gatewayStatus);
			// The round stops at the first: the other key would get the same.
			assertEquals(1, requested.size(), "status " + gatewayStatus);
		}
	}

	@Test
	void aRefusedKeyIsReported() {
		responses.put("GITHUB/GHSA-f2jv-r9rf-7988", Map.entry(401, ""));
		AffectedRangesFetchResult result = service.fetchAffectedRanges(org,
				record("GHSA-f2jv-r9rf-7988", "GITHUB/GHSA-f2jv-r9rf-7988"));
		assertEquals(AffectedRangesFetchStatus.AUTH_REJECTED, result.status());
	}

	@Test
	void aDependencyTrackThatCannotBeReachedIsReportedAsSuch() throws Exception {
		int closedPort;
		try (ServerSocket probe = new ServerSocket(0)) {
			closedPort = probe.getLocalPort();
		}
		IntegrationData down = new IntegrationData();
		down.setUri(URI.create("http://127.0.0.1:" + closedPort));
		down.setSecret("encrypted");
		doReturn(Optional.of(down)).when(service).getIntegrationDataByOrgTypeIdentifier(any(), any(), any());

		AffectedRangesFetchResult result = service.fetchAffectedRanges(org,
				record("CVE-2018-7536", "GITHUB/GHSA-r28v-mw67-m5p9", "OSV/PYSEC-2018-5"));

		assertEquals(AffectedRangesFetchStatus.UNREACHABLE, result.status());
		assertNull(result.fetched());
	}

	@Test
	void anOrgWithoutDependencyTrackHasNothingToFetch() {
		doReturn(Optional.empty()).when(service).getIntegrationDataByOrgTypeIdentifier(any(), any(), any());
		AffectedRangesFetchResult result = service.fetchAffectedRanges(org,
				record("GHSA-f2jv-r9rf-7988", "GITHUB/GHSA-f2jv-r9rf-7988"));
		assertEquals(AffectedRangesFetchStatus.NO_INTEGRATION, result.status());
		assertTrue(requested.isEmpty());
	}

	// --- right after a drain ----------------------------------------------------

	@Test
	void afterADrainOnlyNeverFetchedRecordsAreFetchedUpToTheLimit() throws Exception {
		respondLive("GITHUB/GHSA-f2jv-r9rf-7988");
		respondLive("OSV/PYSEC-2018-5");
		VulnerabilityRecordData alreadyFetched = record("GHSA-aaaa-bbbb-cccc", "GITHUB/GHSA-aaaa-bbbb-cccc");
		alreadyFetched.setAffectedRangesFetchedAt(ZonedDateTime.now());
		VulnerabilityRecordData nvdOnly = record("CVE-2020-0001", "NVD/CVE-2020-0001");
		VulnerabilityRecordData handlebars = record("GHSA-f2jv-r9rf-7988", "GITHUB/GHSA-f2jv-r9rf-7988");
		VulnerabilityRecordData django = record("PYSEC-2018-5", "OSV/PYSEC-2018-5");
		VulnerabilityRecordData overLimit = record("GHSA-dddd-eeee-ffff", "GITHUB/GHSA-dddd-eeee-ffff");

		service.fetchAffectedRangesInline(org, inserted(alreadyFetched, nvdOnly, handlebars, django, overLimit),
				endpoint, "dt-key", 2, Duration.ofMinutes(1));

		// A record that needs no request does not count against the limit.
		assertEquals(List.of("GITHUB/GHSA-f2jv-r9rf-7988", "OSV/PYSEC-2018-5"), requested);
		ArgumentCaptor<UUID> stored = ArgumentCaptor.forClass(UUID.class);
		verify(vulnerabilityRecordService, times(3)).storeAffectedRanges(stored.capture(), any(FetchedAffectedRanges.class));
		assertEquals(List.of(nvdOnly.getUuid(), handlebars.getUuid(), django.getUuid()), stored.getAllValues());
	}

	@Test
	void afterADrainARecordItDidNotCreateIsLeftToTheSweep() throws Exception {
		respondLive("OSV/PYSEC-2018-5");
		VulnerabilityRecordData existing = record("PYSEC-2018-5", "OSV/PYSEC-2018-5");

		// Created earlier, never fetched, but not new: rewritten by this drain,
		// or left alone by it.
		service.fetchAffectedRangesInline(org,
				List.of(new UpsertResult(existing, UpsertOutcome.UPDATED), new UpsertResult(existing, UpsertOutcome.UNCHANGED)),
				endpoint, "dt-key", 100, Duration.ofMinutes(1));

		assertTrue(requested.isEmpty());
		verify(vulnerabilityRecordService, never()).storeAffectedRanges(any(), any());
	}

	@Test
	void afterADrainTheFirstFailureStopsTheRest() throws Exception {
		responses.put("GITHUB/GHSA-f2jv-r9rf-7988", Map.entry(500, "boom"));
		respondLive("OSV/PYSEC-2018-5");

		service.fetchAffectedRangesInline(org, inserted(record("GHSA-f2jv-r9rf-7988", "GITHUB/GHSA-f2jv-r9rf-7988"),
				record("PYSEC-2018-5", "OSV/PYSEC-2018-5")), endpoint, "dt-key", 100, Duration.ofMinutes(1));

		assertEquals(List.of("GITHUB/GHSA-f2jv-r9rf-7988"), requested);
		verify(vulnerabilityRecordService, never()).storeAffectedRanges(any(), any());
	}

	@Test
	void afterADrainTheStoredRangesAreTheFetchedOnes() throws Exception {
		respondLive("OSV/PYSEC-2018-5");
		VulnerabilityRecordData django = record("PYSEC-2018-5", "OSV/PYSEC-2018-5");

		service.fetchAffectedRangesInline(org, inserted(django), endpoint, "dt-key", 100, Duration.ofMinutes(1));

		ArgumentCaptor<FetchedAffectedRanges> fetched = ArgumentCaptor.forClass(FetchedAffectedRanges.class);
		verify(vulnerabilityRecordService).storeAffectedRanges(eq(django.getUuid()), fetched.capture());
		assertEquals(rangesOf("OSV/PYSEC-2018-5"), AffectedRange.normalize(fetched.getValue().ranges()));
	}
}
