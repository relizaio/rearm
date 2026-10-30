/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.junit.jupiter.api.BeforeEach;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.Branch;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ParentRelease;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.ArtifactUploadResponseDto;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.OASResponseDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.ws.App;
import reactor.core.publisher.Mono;

/**
 * Task TEA-6, design 4.8: the TEA 1.0.0 surface over real HTTP against the database, on the TEA-5
 * publication fixture. The world of the design (an org profile ENABLED PRIVATE; components
 * {@code pub} PUBLIC, {@code priv} PRIVATE, {@code hid} hidden, {@code low} CANCELLED, {@code off}
 * DISABLED; a product {@code prod} bundling pub and priv; an unpublished release; another org's
 * published release; a covering EXTERNAL key and one covering nothing) is built per test.
 * Anonymous requests carry a fresh X-Forwarded-For so the per-address rate limit never trips;
 * keyed requests wait out a 429.
 */
@SpringBootTest(classes = {App.class}, webEnvironment = WebEnvironment.RANDOM_PORT)
abstract class TeaSurfaceIntegrationTestBase extends TeaPublicationIntegrationTestBase {

	@Value("${local.server.port}") int port;
	@Autowired ExternalApiKeyService externalApiKeyService;
	/** TEA-10: the raw download answers the bytes retained under the row's digest; the parent's field gets the same spy. */
	@MockitoSpyBean SharedArtifactService sharedArtifactSpy;

	/** TEA-10: every byte array the OCI push stub received, by its sha256; what the raw download stub answers. */
	final Map<String, byte[]> retained = new ConcurrentHashMap<>();

	/**
	 * TEA-10, design 4.3: the OCI push records the bytes it is given (so the aggregate the engine
	 * uploads is retrievable by its AS_UPLOADED digest), and the raw download answers by the row's
	 * SHA-256 digest (AS_UPLOADED, else ORIGINAL_FILE, else OCI_STORAGE), which differs per revision.
	 */
	@BeforeEach
	void retainBytes() throws Exception {
		retained.clear();
		OASResponseDto push = new OASResponseDto();
		ArtifactUploadResponseDto oci = new ArtifactUploadResponseDto();
		oci.setDigest("sha256:" + "ab".repeat(32));
		oci.setMediaType("application/json");
		oci.setSize("100");
		push.setOciResponse(oci);
		push.setOciRepositoryName("downloadable-artifacts-test");
		doAnswer(inv -> {
			Resource resource = inv.getArgument(0);
			byte[] bytes = resource.getContentAsByteArray();
			retained.put(Utils.bytesToHexSha256(bytes), bytes);
			return push;
		}).when(artifactService).uploadFileToConfiguredOci(any(), anyString(), any());
		doAnswer(inv -> {
			ArtifactData ad = inv.getArgument(0);
			byte[] bytes = retainedSha256(ad).map(retained::get).orElse(null);
			if (null == bytes) return Mono.error(new RelizaException("nothing retained for artifact " + ad.getUuid()));
			return Mono.just(ResponseEntity.ok().body(bytes));
		}).when(sharedArtifactSpy).downloadRawArtifact(any());
	}

	static Optional<String> retainedSha256(ArtifactData ad) {
		if (null == ad.getDigestRecords()) return Optional.empty();
		for (DigestScope scope : List.of(DigestScope.AS_UPLOADED, DigestScope.ORIGINAL_FILE, DigestScope.OCI_STORAGE)) {
			Optional<String> d = ad.getDigestRecords().stream()
					.filter(r -> null != r && scope == r.scope() && DigestAlgorithm.SHA_256 == r.algo())
					.map(DigestRecord::digest).findFirst();
			if (d.isPresent()) return d;
		}
		return Optional.empty();
	}

	final HttpClient http = HttpClient.newHttpClient();

	/** A component release of the world: its release, its component, its head and its component's TEA id. */
	record Obj(UUID release, UUID component, UUID head, UUID teaComponent, String purl) {}

	record TeaWorld(String teaOrg, String base, String apiBase, Obj pub, Obj priv, Obj hid, Obj low, Obj off, Obj prod,
			UUID unpRelease, UUID foreignHead, UUID coverKey, String cover, String other) {}

	static final String BAD = "garbage-token";

	String root() {
		return "http://localhost:" + port;
	}

	// ---------------------------------------------------------------- http

	static String randomIp() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
	}

	HttpResponse<String> get(String url, String bearer, Map<String, String> headers) throws Exception {
		for (int attempt = 0; ; attempt++) {
			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET().header("X-Forwarded-For", randomIp());
			if (null != bearer) b.header("Authorization", "Bearer " + bearer);
			if (null != headers) headers.forEach(b::header);
			HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() != 429 || attempt >= 3) return r;
			long wait = Long.parseLong(r.headers().firstValue("Retry-After").orElse("5"));
			Thread.sleep((wait + 1) * 1000L);
		}
	}

	HttpResponse<String> get(String url, String bearer) throws Exception {
		return get(url, bearer, null);
	}

	HttpResponse<byte[]> getBytes(String url, String bearer, Map<String, String> headers) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET().header("X-Forwarded-For", randomIp());
		if (null != bearer) b.header("Authorization", "Bearer " + bearer);
		if (null != headers) headers.forEach(b::header);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> json(HttpResponse<String> r) {
		return Utils.OM.readValue(r.body(), Map.class);
	}

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> results(HttpResponse<String> r) {
		return (List<Map<String, Object>>) json(r).get("results");
	}

	static List<String> uuidsOf(HttpResponse<String> r) {
		return results(r).stream().map(m -> (String) m.get("uuid")).toList();
	}

	String token(ApiKey key) throws Exception {
		return token(key, org.getUuid());
	}

	/** A TEA access token of an EXTERNAL key of {@code keyOrg}, from that organization's token endpoint. */
	String token(ApiKey key, UUID keyOrg) throws Exception {
		String secret = apiKeyService.addApiKeySecret(key.getUuid(), null, WU);
		String keyId = ApiKeyService.keyIdOf(apiKeyService.getApiKey(key.getUuid()).orElseThrow());
		String teaOrg = getOrganizationService.getOrganizationData(keyOrg).orElseThrow().getTeaUuid().toString();
		String basic = "Basic " + Base64.getEncoder().encodeToString((keyId + ":" + secret).getBytes(StandardCharsets.UTF_8));
		HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(root() + "/tea/" + teaOrg + "/v1.0.0/token"))
				.header("Authorization", basic).header("X-Forwarded-For", randomIp())
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, r.statusCode(), r.body());
		return (String) json(r).get("access_token");
	}

	// ---------------------------------------------------------------- fixtures

	/** An ASSEMBLED component release of a new component with one SBOM, its release carrying {@code purl} as a PURL identifier. */
	World componentReleaseWithPurl(Organization o, String purl, List<RearmIdentifier> componentIds) throws Exception {
		String slug = "tea-6-" + UUID.randomUUID().toString().substring(0, 8);
		UUID component = componentService.createComponent(CreateComponentDto.builder()
				.organization(o.getUuid()).name(slug).type(ComponentType.COMPONENT).identifiers(componentIds)
				.versionSchema("semver").featureBranchVersioning("Branch.Micro").build(), WU).getUuid();
		Branch branch = branchService.findBranchByName(component, "main", true, WU).get();
		String version = "1.0." + Math.abs(UUID.randomUUID().hashCode() % 100000);
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(component)
				.branch(branch.getUuid()).org(o.getUuid()).status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.DRAFT).version(version)
				.identifiers(null == purl ? List.of() : List.of(new RearmIdentifier(RearmIdentifierType.PURL, purl)))
				.build(), WU).getUuid();
		releasesByVersion.put(version, release);
		UUID inventory = seedBom(o, slug + ".cdx.json");
		releaseService.addArtifact(inventory, release, WU);
		mergedPurls.put(release, new ArrayList<>(List.of(null == purl ? "pkg:generic/" + slug + "@" + version : purl)));
		ossReleaseService.updateReleaseLifecycle(release, ReleaseLifecycle.ASSEMBLED, WU);
		return new World(component, branch.getUuid(), release, inventory);
	}

	/** A product release bundling {@code children}, carrying {@code purl} as a PURL identifier. */
	UUID productWithPurl(List<UUID> children, String purl) throws Exception {
		String slug = "tea-6-product-" + UUID.randomUUID().toString().substring(0, 8);
		UUID component = componentService.createComponent(CreateComponentDto.builder()
				.organization(org.getUuid()).name(slug).type(ComponentType.PRODUCT)
				.versionSchema("semver").featureBranchVersioning("Branch.Micro").build(), WU).getUuid();
		Branch branch = branchService.getBaseBranchOfComponent(component).orElseThrow();
		String version = "2.0." + Math.abs(UUID.randomUUID().hashCode() % 100000);
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(component)
				.branch(branch.getUuid()).org(org.getUuid()).status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.ASSEMBLED).version(version)
				.identifiers(null == purl ? List.of() : List.of(new RearmIdentifier(RearmIdentifierType.PURL, purl)))
				.parentReleases(children.stream().map(c -> ParentRelease.minimalParentReleaseFactory(c)).toList())
				.build(), WU).getUuid();
		releasesByVersion.put(version, release);
		List<String> purls = new ArrayList<>();
		for (UUID c : children) purls.addAll(mergedPurls.getOrDefault(c, List.of()));
		mergedPurls.put(release, purls);
		return release;
	}

	/**
	 * One more ASSEMBLED release of {@code w}'s component on {@code w}'s branch with {@code version}, an
	 * SBOM seeded and bound as the first was (round 2, T-2).
	 */
	UUID anotherRelease(World w, String version) throws Exception {
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(w.component())
				.branch(w.branch()).org(org.getUuid()).status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.DRAFT).version(version).build(), WU).getUuid();
		releasesByVersion.put(version, release);
		String slug = "tea-6-" + UUID.randomUUID().toString().substring(0, 8);
		releaseService.addArtifact(seedBom(org, slug + ".cdx.json"), release, WU);
		mergedPurls.put(release, new ArrayList<>(List.of("pkg:generic/" + slug + "@" + version)));
		ossReleaseService.updateReleaseLifecycle(release, ReleaseLifecycle.ASSEMBLED, WU);
		return release;
	}

	/**
	 * One more ASSEMBLED release of {@code productRelease}'s product on its base branch with
	 * {@code version}, bundling the same children (round 2, T-2).
	 */
	UUID anotherProductRelease(UUID productRelease, String version) throws Exception {
		ReleaseData first = stored(productRelease);
		Branch branch = branchService.getBaseBranchOfComponent(first.getComponent()).orElseThrow();
		List<UUID> children = first.getParentReleases().stream().map(ParentRelease::getRelease).toList();
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(first.getComponent())
				.branch(branch.getUuid()).org(org.getUuid()).status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.ASSEMBLED).version(version)
				.parentReleases(children.stream().map(c -> ParentRelease.minimalParentReleaseFactory(c)).toList())
				.build(), WU).getUuid();
		releasesByVersion.put(version, release);
		List<String> purls = new ArrayList<>();
		for (UUID c : children) purls.addAll(mergedPurls.getOrDefault(c, List.of()));
		mergedPurls.put(release, purls);
		return release;
	}

	Obj obj(World w, String purl) {
		UUID head = teaPublicationService.getPublicationOfRelease(w.release()).map(p -> p.getUuid()).orElse(null);
		UUID tea = getComponentService.getComponentData(w.component()).orElseThrow().getTeaUuid();
		return new Obj(w.release(), w.component(), head, tea, purl);
	}

	void saveOrgProfileTei(TeaVisibility visibility, TeaTei tei, String teiDomain) throws Exception {
		teaProfileService.saveProfile(org.getUuid(), TeaProfileScope.ORGANIZATION, null,
				profileInput(TeaPublishing.ENABLED, visibility, TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT)
						.tei(tei).teiDomain(teiDomain).build(), TeaCaller.ORG_ADMIN, WU);
	}

	static String tag() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	/** The world of the design, 4.8. */
	TeaWorld world() throws Exception {
		saveOrgProfile(TeaVisibility.PRIVATE, TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		String t = tag();
		String pubPurl = "pkg:generic/tea6-pub-" + t + "@1.0.0";
		World pub = componentReleaseWithPurl(org, pubPurl,
				List.of(new RearmIdentifier(RearmIdentifierType.COMPLIANCE_DOCUMENT, "https://example.com/doc-" + t)));
		saveComponentProfile(pub.component(), TeaPublishing.ENABLED, TeaVisibility.PUBLIC, TeaRawArtifacts.NONE);
		publish(pub.release(), TeaPublishMode.PUBLISH);
		String privPurl = "pkg:generic/tea6-priv-" + t + "@1.0.0";
		World priv = componentReleaseWithPurl(org, privPurl, List.of());
		publish(priv.release(), TeaPublishMode.PUBLISH);
		World hid = componentReleaseWithPurl(org, "pkg:generic/tea6-hid-" + t + "@1.0.0", List.of());
		saveComponentProfile(hid.component(), TeaPublishing.ENABLED, TeaVisibility.PUBLIC, TeaRawArtifacts.NONE);
		publish(hid.release(), TeaPublishMode.PUBLISH);
		teaPublicationService.hide(hid.release(), null, WU);
		World low = componentReleaseWithPurl(org, "pkg:generic/tea6-low-" + t + "@1.0.0", List.of());
		saveComponentProfile(low.component(), TeaPublishing.ENABLED, TeaVisibility.PUBLIC, TeaRawArtifacts.NONE);
		publish(low.release(), TeaPublishMode.PUBLISH);
		ossReleaseService.updateReleaseLifecycle(low.release(), ReleaseLifecycle.CANCELLED, WU);
		World off = componentReleaseWithPurl(org, "pkg:generic/tea6-off-" + t + "@1.0.0", List.of());
		saveComponentProfile(off.component(), TeaPublishing.ENABLED, TeaVisibility.PUBLIC, TeaRawArtifacts.NONE);
		publish(off.release(), TeaPublishMode.PUBLISH);
		saveComponentProfile(off.component(), TeaPublishing.DISABLED, TeaVisibility.PUBLIC, TeaRawArtifacts.NONE);
		String prodPurl = "pkg:generic/tea6-prod-" + t + "@2.0.0";
		UUID prodRelease = productWithPurl(List.of(pub.release(), priv.release()), prodPurl);
		publish(prodRelease, TeaPublishMode.PUBLISH);
		UUID prodComponent = stored(prodRelease).getComponent();
		World unp = componentReleaseWithPurl(org, null, List.of());

		Organization foreign = testInitializer.obtainOrganization();
		teaProfileService.saveProfile(foreign.getUuid(), TeaProfileScope.ORGANIZATION, null,
				profileInput(TeaPublishing.ENABLED, TeaVisibility.PUBLIC, TeaRawArtifacts.NONE,
						TeaProductComponents.PUBLISH_WITH_PRODUCT).build(), TeaCaller.ORG_ADMIN, WU);
		World far = componentReleaseWithPurl(foreign, "pkg:generic/tea6-foreign-" + t + "@1.0.0", List.of());
		publish(far.release(), TeaPublishMode.PUBLISH);
		UUID foreignHead = teaPublicationService.getPublicationOfRelease(far.release()).orElseThrow().getUuid();

		ApiKey coverKey = externalApiKeyService.create(org.getUuid(), "cover-" + t, null, WU);
		externalApiKeyService.setGrants(coverKey.getUuid(), List.of(
				new PermissionDto(org.getUuid(), PermissionScope.COMPONENT, priv.component(), PermissionType.READ_ONLY,
						Set.of(PermissionFunction.TEA_READ), List.of()),
				new PermissionDto(org.getUuid(), PermissionScope.COMPONENT, prodComponent, PermissionType.READ_ONLY,
						Set.of(PermissionFunction.TEA_READ), List.of())), WU);
		ApiKey otherKey = externalApiKeyService.create(org.getUuid(), "other-" + t, null, WU);
		String teaOrg = getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow().getTeaUuid().toString();
		String apiBase = teaPublicationService.apiBaseOf(org.getUuid());
		Obj prod = obj(new World(prodComponent, null, prodRelease, null), prodPurl);
		return new TeaWorld(teaOrg, root() + "/tea/" + teaOrg + "/v1.0.0", apiBase, obj(pub, pubPurl), obj(priv, privPurl),
				obj(hid, null), obj(low, null), obj(off, null), prod, unp.release(), foreignHead, coverKey.getUuid(),
				token(coverKey), token(otherKey));
	}

	/** Every caller of the matrix, by name. */
	static Map<String, String> callers(TeaWorld w) {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("anonymous", null);
		m.put("bad", BAD);
		m.put("other", w.other());
		m.put("cover", w.cover());
		return m;
	}

	static void assertNoStore401(HttpResponse<String> r, boolean invalidToken, String what) {
		assertEquals(401, r.statusCode(), what + ": " + r.body());
		String challenge = r.headers().firstValue("WWW-Authenticate").orElse("");
		assertTrue(invalidToken ? challenge.contains("invalid_token") : "Bearer realm=\"tea\"".equals(challenge), what + ": " + challenge);
		assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null), what);
		assertTrue(r.body().isEmpty(), what + ": a 401 carries no body");
	}

	static void assertObjectUnknown(HttpResponse<String> r, String what) {
		assertEquals(404, r.statusCode(), what + ": " + r.body());
		assertEquals("{\"error\":\"OBJECT_UNKNOWN\"}", r.body(), what);
		assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null), what);
		io.reliza.service.tea.TeaOpenApiSchemas.assertConforms(r.body(), "error-response");
	}

	static final String NOT_FOUND_BODY = "{\"message\":\"Not Found\"}";

	/** The TEA chain's answer to an error dispatch (decision 16): not a TEA error, no challenge, no cookie. */
	static void assertNotTeaError(HttpResponse<String> r, int status, String reason, String what) {
		assertEquals(status, r.statusCode(), what + ": " + r.body());
		assertEquals("{\"message\":\"" + reason + "\"}", r.body(), what);
		assertEquals(Set.of("message"), json(r).keySet(), what + ": no error key");
		assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), what);
		assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null), what);
		assertTrue(r.headers().firstValue("WWW-Authenticate").isEmpty(), what + ": no challenge");
		assertTrue(r.headers().firstValue("Set-Cookie").isEmpty(), what + ": no cookie");
	}

	static void assertServedHeaders(HttpResponse<?> r, boolean keyed, String what) {
		assertEquals(keyed ? "private, no-cache" : "no-cache", r.headers().firstValue("Cache-Control").orElse(null), what);
		assertEquals("Authorization", r.headers().firstValue("Vary").orElse(null), what);
		assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), what);
	}
}
