/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import io.reliza.common.CommonVariables;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.service.DTrackService.ComponentLatestVersion;
import io.reliza.service.DTrackService.LatestVersionAnswer;
import io.reliza.service.DTrackService.LatestVersionLookup;
import io.reliza.service.DTrackService.RepositoryMetaListing;
import io.reliza.service.DTrackService.RepositoryMetaRejectedException;
import reactor.core.publisher.Mono;

/**
 * The latest-version reads from Dependency-Track, over a stubbed WebClient serving the
 * shapes captured live from DT 5.1.0 and DT 4.14.2 (2026-09-29): the project component
 * listing with {@code repositoryMeta} per component, paged by {@code X-Total-Count}, and
 * {@code repository/latest} answering 200 / 204 (no repository for the purl type) / 404
 * (package unknown).
 */
class DTrackServiceRepositoryMetaTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID PROJECT = UUID.randomUUID();

	private DTrackService service;
	private final List<URI> requested = new ArrayList<>();

	@BeforeEach
	void setUp() {
		service = new DTrackService();
		IntegrationService integrations = mock(IntegrationService.class);
		IntegrationData dt = new IntegrationData();
		dt.setUri(URI.create("http://dtrack.test"));
		dt.setSecret("encrypted");
		when(integrations.getIntegrationDataByOrgTypeIdentifier(eq(ORG), eq(IntegrationType.DEPENDENCYTRACK),
				eq(CommonVariables.BASE_INTEGRATION_IDENTIFIER))).thenReturn(Optional.of(dt));
		EncryptionService encryption = mock(EncryptionService.class);
		when(encryption.decrypt(any())).thenReturn("api-key");
		ReflectionTestUtils.setField(service, "integrationService", integrations);
		ReflectionTestUtils.setField(service, "encryptionService", encryption);
	}

	private void serve(Function<ClientRequest, ClientResponse> responder) {
		WebClient stub = WebClient.builder().exchangeFunction(req -> {
			requested.add(req.url());
			return Mono.just(responder.apply(req));
		}).build();
		ReflectionTestUtils.setField(service, "dtrackWebClient", stub);
	}

	private static ClientResponse json(String body, Integer totalCount) {
		ClientResponse.Builder b = ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, "application/json");
		if (totalCount != null) b.header("X-Total-Count", String.valueOf(totalCount));
		return b.body(body).build();
	}

	private static String fixture(String name) throws IOException {
		try (InputStream in = DTrackServiceRepositoryMetaTest.class.getResourceAsStream("/dtrack/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void dt5ListingGivesEachComponentItsLatestVersion() throws Exception {
		String body = fixture("repository-meta-components-dt5.json");
		serve(req -> json(body, 4));

		RepositoryMetaListing listing = service.syntheticFetchRepositoryMeta(ORG, PROJECT);

		assertEquals(List.of(
				new ComponentLatestVersion("pkg:npm/%40npmcli/agent@3.0.0", "5.0.2"),
				new ComponentLatestVersion("pkg:npm/just-diff@6.0.2", "6.0.2"),
				new ComponentLatestVersion("pkg:pypi/mercurial@6.3.2", "7.2.4"),
				// no repository for deb: no repositoryMeta at all
				new ComponentLatestVersion("pkg:deb/debian/base-files@12.4%2Bdeb12u15?distro=debian-12.15", null)),
				listing.components());
		assertEquals(1, listing.requests());
		assertEquals("/api/v1/component/project/" + PROJECT, requested.get(0).getPath());
		assertEquals("pageSize=100&pageNumber=1", requested.get(0).getQuery());
	}

	@Test
	void dt4ListingBindsTheSameWay() throws Exception {
		String body = fixture("repository-meta-components-dt4.json");
		serve(req -> json(body, 4));

		RepositoryMetaListing listing = service.syntheticFetchRepositoryMeta(ORG, PROJECT);

		assertEquals("1.15.0", listing.components().get(0).latestVersion());
		assertTrue(listing.components().subList(1, 4).stream().allMatch(c -> c.latestVersion() == null),
				"deb / apk / rpm carry no repositoryMeta on DT 4 either");
	}

	@Test
	void theListingPagesUntilTheTotalCount() throws Exception {
		StringBuilder page = new StringBuilder("[");
		for (int i = 0; i < 100; i++) page.append(i == 0 ? "" : ",").append("{\"purl\":\"pkg:npm/p").append(i).append("@1.0.0\"}");
		String full = page.append("]").toString();
		serve(req -> req.url().getQuery().endsWith("pageNumber=1") ? json(full, 101)
				: json("[{\"purl\":\"pkg:npm/last@1.0.0\",\"repositoryMeta\":{\"latestVersion\":\"2.0.0\"}}]", 101));

		RepositoryMetaListing listing = service.syntheticFetchRepositoryMeta(ORG, PROJECT);

		assertEquals(101, listing.components().size());
		assertEquals(2, listing.requests());
		assertEquals("pageSize=100&pageNumber=2", requested.get(1).getQuery());
	}

	@Test
	void aFailedPageFailsTheListingRatherThanReturningPartOfIt() {
		serve(req -> ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
		RelizaException unavailable = assertThrows(RelizaException.class, () -> service.syntheticFetchRepositoryMeta(ORG, PROJECT));
		assertFalse(unavailable instanceof RepositoryMetaRejectedException, "a 503 is the instance, not the request");
		// a project Dependency-Track no longer has: refused, not unavailable
		serve(req -> ClientResponse.create(HttpStatus.NOT_FOUND).build());
		assertThrows(RepositoryMetaRejectedException.class, () -> service.syntheticFetchRepositoryMeta(ORG, PROJECT));
	}

	@Test
	void aServerThatServesSmallerPagesIsReadToTheTotal() throws Exception {
		StringBuilder page = new StringBuilder("[");
		for (int i = 0; i < 50; i++) page.append(i == 0 ? "" : ",").append("{\"purl\":\"pkg:npm/p").append(i).append("@1.0.0\"}");
		String half = page.append("]").toString();
		serve(req -> json(half, 120));

		RepositoryMetaListing listing = service.syntheticFetchRepositoryMeta(ORG, PROJECT);

		// 50 a page against a total of 120: three pages, not one
		assertEquals(3, listing.requests());
	}

	@Test
	void aServerThatIgnoresPagingIsReadAtMostTheCap() throws Exception {
		StringBuilder page = new StringBuilder("[");
		for (int i = 0; i < 100; i++) page.append(i == 0 ? "" : ",").append("{\"purl\":\"pkg:npm/p").append(i).append("@1.0.0\"}");
		String full = page.append("]").toString();
		serve(req -> json(full, null));

		RepositoryMetaListing listing = service.syntheticFetchRepositoryMeta(ORG, PROJECT);

		assertEquals(DTrackService.REPOSITORY_META_MAX_PAGES, listing.requests());
	}

	@Test
	void repositoryLatestAnswersFoundOnBothVersions() throws Exception {
		String dt5 = "{\"repositoryType\":\"MAVEN\",\"namespace\":\"org.apache.logging.log4j\",\"name\":\"log4j-core\","
				+ "\"latestVersion\":\"2.26.1\",\"lastCheck\":1790581830413,\"latestVersionPublishedAt\":1782719092000}";
		String dt4 = "{\"repositoryType\":\"MAVEN\",\"namespace\":\"org.apache.logging.log4j\",\"name\":\"log4j-core\","
				+ "\"latestVersion\":\"3.0.0-beta3\",\"published\":1783326183000,\"lastCheck\":1790587282864}";
		serve(req -> json(dt5, null));
		assertEquals(new LatestVersionLookup(LatestVersionAnswer.FOUND, "2.26.1"),
				service.fetchLatestVersion(ORG, "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1"));
		serve(req -> json(dt4, null));
		assertEquals(new LatestVersionLookup(LatestVersionAnswer.FOUND, "3.0.0-beta3"),
				service.fetchLatestVersion(ORG, "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1"));
	}

	@Test
	void repositoryLatestTellsNoRepositoryFromUnknownPackage() throws Exception {
		serve(req -> ClientResponse.create(HttpStatus.NO_CONTENT).build());
		assertEquals(LatestVersionAnswer.NO_REPOSITORY_FOR_TYPE,
				service.fetchLatestVersion(ORG, "pkg:deb/debian/openssl?distro=debian-12").answer());
		serve(req -> ClientResponse.create(HttpStatus.NOT_FOUND)
				.body("The repository metadata for the specified component cannot be found.").build());
		LatestVersionLookup unknown = service.fetchLatestVersion(ORG, "pkg:npm/this-package-does-not-exist-zz9");
		assertEquals(LatestVersionAnswer.UNKNOWN_PACKAGE, unknown.answer());
		assertNull(unknown.latestVersion());
		serve(req -> ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
		assertThrows(RelizaException.class, () -> service.fetchLatestVersion(ORG, "pkg:npm/a@1.0.0"));
		// a purl its parser rejects: this purl only
		serve(req -> ClientResponse.create(HttpStatus.BAD_REQUEST).build());
		assertThrows(RepositoryMetaRejectedException.class, () -> service.fetchLatestVersion(ORG, "pkg:npm/a@1.0.0"));
		// an empty 200 is not "no repository for the type", which would skip the whole type
		serve(req -> ClientResponse.create(HttpStatus.OK).build());
		assertEquals(LatestVersionAnswer.UNKNOWN_PACKAGE, service.fetchLatestVersion(ORG, "pkg:npm/a@1.0.0").answer());
	}

	@Test
	void thePurlTravelsEncodedAsOneQueryParameter() throws Exception {
		serve(req -> ClientResponse.create(HttpStatus.NO_CONTENT).build());
		service.fetchLatestVersion(ORG, "pkg:npm/%40babel/traverse@7.22.0");
		assertEquals("purl=pkg%3Anpm%2F%2540babel%2Ftraverse%407.22.0", requested.get(0).getRawQuery());
	}
}
