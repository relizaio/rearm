/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sun.net.httpserver.HttpServer;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.DependencyTrackVersion;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.IntegrationRepository;

/**
 * Dependency-Track version detection: what {@code /api/version} maps to, a
 * probe that fails leaves the integration undetected rather than guessing,
 * the re-detect sweep stores what it can tell (including a version 4
 * upgraded in place) and leaves the rest alone, and an undetected
 * integration is taken as the current generation.
 */
class IntegrationServiceDtrackVersionDetectTest {

	private static void inject(Object target, String field, Object value) throws Exception {
		Field f = IntegrationService.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static EncryptionService passThroughEncryption() {
		EncryptionService e = mock(EncryptionService.class);
		when(e.encrypt(anyString())).thenAnswer(i -> i.getArgument(0));
		when(e.decrypt(anyString())).thenAnswer(i -> i.getArgument(0));
		return e;
	}

	/** Answers every request with {@code status} and {@code body}; returns its base URI. */
	private static URI serve(HttpServer server, int status, String body) {
		server.createContext("/", exchange -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
			if (bytes.length > 0) {
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(bytes);
				}
			}
			exchange.close();
		});
		server.start();
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
	}

	private static DependencyTrackVersion probe(int status, String body) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		try {
			return new IntegrationService(mock(IntegrationRepository.class)).probeDtrackVersion(serve(server, status, body), "k");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void theReportedMajorVersionDecides() throws Exception {
		assertEquals(DependencyTrackVersion.V5, probe(200, "{\"application\":\"Dependency-Track\",\"version\":\"5.1.0\"}"));
		assertEquals(DependencyTrackVersion.V4, probe(200, "{\"application\":\"Dependency-Track\",\"version\":\"4.14.2\"}"));
	}

	@Test
	void anInstanceThatDoesNotSayIsNotGuessed() {
		assertThrows(RelizaException.class, () -> probe(503, "busy"));
		assertThrows(RelizaException.class, () -> probe(200, "{\"version\":\"snapshot\"}"));
		assertThrows(RelizaException.class, () -> probe(200, "{\"application\":\"Dependency-Track\"}"));
		assertThrows(RelizaException.class, () -> probe(200, "not json"));
	}

	/** Probes by URI: a mapped version, or a failure for anything else. */
	private static class ProbingService extends IntegrationService {
		final Map<URI, DependencyTrackVersion> versions = new HashMap<>();
		final List<URI> probed = new ArrayList<>();

		ProbingService(IntegrationRepository repository) {
			super(repository);
		}

		@Override
		protected DependencyTrackVersion probeDtrackVersion(URI baseUri, String apiToken) throws RelizaException {
			probed.add(baseUri);
			DependencyTrackVersion v = versions.get(baseUri);
			if (v == null) throw new RelizaException("no answer");
			return v;
		}
	}

	@Test
	void aFailedProbeOnCreateLeavesTheVersionUndetected() throws Exception {
		IntegrationRepository repository = mock(IntegrationRepository.class);
		when(repository.save(any(Integration.class))).thenAnswer(i -> i.getArgument(0));
		ProbingService svc = new ProbingService(repository);
		inject(svc, "encryptionService", passThroughEncryption());

		Integration created = svc.createIntegration(CommonVariables.BASE_INTEGRATION_IDENTIFIER, UUID.randomUUID(),
				IntegrationType.DEPENDENCYTRACK, URI.create("https://unreachable.example"), "k", null, null,
				WhoUpdated.getAutoWhoUpdated());

		IntegrationData stored = IntegrationData.dataFromRecord(created);
		assertNull(stored.getDtrackVersion());
		assertEquals(IntegrationData.DEFAULT_DTRACK_VERSION, stored.getEffectiveDtrackVersion());
	}

	private static Integration dtrack(UUID org, URI uri, DependencyTrackVersion version) {
		IntegrationData d = IntegrationData.integrationDataFactory(CommonVariables.BASE_INTEGRATION_IDENTIFIER, org,
				IntegrationType.DEPENDENCYTRACK, uri, "k", uri);
		d.setDtrackVersion(version);
		Integration i = new Integration();
		i.setRecordData(Utils.dataToRecord(d));
		return i;
	}

	/**
	 * The sweep stores in place through IntegrationRepository.stampDtrackVersion (TPF-71 / TPF-107, architecture
	 * 5.3): the repository is a mock, the two landing stamps answer 1 and every other combination the default 0,
	 * which is how the row deleted meanwhile is not brought back. What the row then holds is the database's
	 * (IntegrationDtrackVersionStampTest).
	 */
	private static Map<String, Object> withVersion(Integration row, DependencyTrackVersion version) {
		IntegrationData d = IntegrationData.dataFromRecord(row);
		d.setDtrackVersion(version);
		return Utils.dataToRecord(d);
	}

	@Test
	void theSweepStoresWhatItCanTellAndLeavesTheRestAlone() throws Exception {
		URI legacy = URI.create("https://dt4.example");
		URI unreachable = URI.create("https://down.example");
		URI upgraded = URI.create("https://dt-upgraded.example");
		URI quietV4 = URI.create("https://dt4-quiet.example");
		URI deleted = URI.create("https://deleted.example");
		URI stillV4Uri = URI.create("https://dt4-same.example");
		Integration undetectedV4 = dtrack(UUID.randomUUID(), legacy, null);
		Integration stillV4 = dtrack(UUID.randomUUID(), stillV4Uri, DependencyTrackVersion.V4);
		Integration undetectedDown = dtrack(UUID.randomUUID(), unreachable, null);
		Integration storedV4NowV5 = dtrack(UUID.randomUUID(), upgraded, DependencyTrackVersion.V4);
		Integration storedV4Down = dtrack(UUID.randomUUID(), quietV4, DependencyTrackVersion.V4);
		Integration deletedMeanwhile = dtrack(UUID.randomUUID(), deleted, null);
		Integration detectedV5 = dtrack(UUID.randomUUID(), URI.create("https://dt5.example"), DependencyTrackVersion.V5);
		List<Integration> rows = List.of(undetectedV4, undetectedDown, storedV4NowV5, storedV4Down, deletedMeanwhile,
				detectedV5, stillV4);
		IntegrationRepository repository = mock(IntegrationRepository.class);
		when(repository.listBaseIntegrationsByType(IntegrationType.DEPENDENCYTRACK.name())).thenReturn(rows);
		when(repository.stampDtrackVersion(eq(undetectedV4.getUuid()), eq("V4"), isNull(), eq(legacy.toString())))
				.thenReturn(1);
		when(repository.stampDtrackVersion(eq(storedV4NowV5.getUuid()), eq("V5"), eq("V4"), eq(upgraded.toString())))
				.thenReturn(1);
		// a version 4 that answers 4 again changes nothing: it must not reach the statement, even one that would say 1
		when(repository.stampDtrackVersion(eq(stillV4.getUuid()), any(), any(), any())).thenReturn(1);
		ProbingService svc = new ProbingService(repository);
		inject(svc, "encryptionService", passThroughEncryption());
		svc.versions.put(legacy, DependencyTrackVersion.V4);
		svc.versions.put(stillV4Uri, DependencyTrackVersion.V4);
		svc.versions.put(upgraded, DependencyTrackVersion.V5);
		svc.versions.put(deleted, DependencyTrackVersion.V5);

		assertEquals(2, svc.redetectDtrackVersions());
		// The rows as the table now holds them: the two stamps landed (the mock repository writes nothing).
		undetectedV4.setRecordData(withVersion(undetectedV4, DependencyTrackVersion.V4));
		storedV4NowV5.setRecordData(withVersion(storedV4NowV5, DependencyTrackVersion.V5));
		// Again at once: what is still undetected is probed every run (the mock
		// still lists the deleted row), a version 4 only once a day.
		svc.probed.clear();
		assertEquals(0, svc.redetectDtrackVersions());
		assertEquals(List.of(unreachable, deleted), svc.probed);

		ArgumentCaptor<UUID> uuids = ArgumentCaptor.forClass(UUID.class);
		ArgumentCaptor<String> detected = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> known = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> uris = ArgumentCaptor.forClass(String.class);
		// The two that landed, then the deleted row's stamp on each run (it answered 0 and was not counted).
		verify(repository, times(4)).stampDtrackVersion(uuids.capture(), detected.capture(), known.capture(),
				uris.capture());
		assertEquals(List.of(undetectedV4.getUuid(), storedV4NowV5.getUuid(), deletedMeanwhile.getUuid(),
				deletedMeanwhile.getUuid()), uuids.getAllValues());
		assertEquals(List.of("V4", "V5", "V5", "V5"), detected.getAllValues());
		assertEquals(Arrays.asList(null, "V4", null, null), known.getAllValues());
		assertEquals(List.of(legacy.toString(), upgraded.toString(), deleted.toString(), deleted.toString()),
				uris.getAllValues());
		verify(repository, never()).stampDtrackVersion(eq(stillV4.getUuid()), any(), any(), any());
		verify(repository, never()).save(any());
		verify(repository, never()).findById(any());
	}

	@Test
	void anUndetectedIntegrationIsTakenAsTheCurrentGeneration() {
		IntegrationData d = new IntegrationData();
		assertEquals(DependencyTrackVersion.V5, d.getEffectiveDtrackVersion());
		d.setDtrackVersion(DependencyTrackVersion.V4);
		assertEquals(DependencyTrackVersion.V4, d.getEffectiveDtrackVersion());
	}
}
