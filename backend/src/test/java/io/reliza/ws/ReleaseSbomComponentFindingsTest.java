/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.dataloader.DataLoaderFactory;
import org.dataloader.DataLoaderRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.UnionTypeDefinition;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.TypeRuntimeWiring;
import io.reliza.model.AnalysisState;
import io.reliza.model.ArtifactData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseSbomComponent;
import io.reliza.model.SbomComponent;
import io.reliza.model.UserData;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.model.VulnerabilityRecordData.AffectedIdentityType;
import io.reliza.model.VulnerabilityRecordData.AffectedRange;
import io.reliza.model.VulnerabilityRecordData.AffectedRangeType;
import io.reliza.model.VulnerabilityRecordData.UpstreamSource;
import io.reliza.model.VulnerabilityRecordData.VulnScore;
import io.reliza.model.VulnerabilityRecordData.VulnScoreType;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.DeviceLifecycleHook;
import io.reliza.service.FindingPurlBridge;
import io.reliza.service.GetComponentService;
import io.reliza.service.SbomComponentService;
import io.reliza.service.SbomComponentService.LatestVersion;
import io.reliza.service.SbomComponentService.ReleaseComponentPurls;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.VulnerabilityRecordService;

/**
 * The findings fields of {@code ReleaseSbomComponent} and
 * {@code Vulnerability.sbomMatch}, executed over the real SDL with the real
 * resolvers, bridge and batch loaders (the services behind them are mocks):
 * a release's findings land on the component their purl names whatever its
 * encoding, their scores and fix resolve as under {@code Release.metrics},
 * a page of components costs one record read and no inventory read (a
 * finding listed under a row is on that row), release findings one inventory read, and
 * outside a release the fields answer empty or null instead of failing.
 */
class ReleaseSbomComponentFindingsTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID RELEASE = UUID.randomUUID();
	private static final UUID GLIBC = UUID.randomUUID();
	private static final UUID NPM_A = UUID.randomUUID();
	private static final UUID CLEAN = UUID.randomUUID();
	private static final Map<UUID, String> CANONICALS = Map.of(
			GLIBC, "pkg:deb/debian/glibc@2.36-9+deb12u14?distro=debian-12",
			NPM_A, "pkg:npm/a@1.4.1",
			CLEAN, "pkg:npm/clean@1.0.0");
	private static final Set<String> BUILT_IN_SCALARS = Set.of("Int", "Float", "String", "Boolean", "ID");

	private VulnerabilityRecordService recordService;
	private SbomComponentService sbomComponentService;
	private GraphQLSchema schema;
	private ReleaseData release;

	@BeforeEach
	void setUp() throws Exception {
		recordService = mock(VulnerabilityRecordService.class);
		VulnerabilityRecordData cve = new VulnerabilityRecordData();
		cve.setPrimaryVulnId("CVE-2026-0001");
		cve.setScores(List.of(VulnScore.of(VulnScoreType.CVSS_V3, 9.8, "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H")));
		AffectedRange range = new AffectedRange();
		range.setIdentityType(AffectedIdentityType.PURL);
		range.setIdentity("pkg:npm/a");
		range.setRangeType(AffectedRangeType.RANGE);
		range.setVersionEndExcluding("1.4.2");
		range.setSources(List.of(UpstreamSource.GITHUB));
		cve.setAffectedRanges(List.of(range));
		Map<String, VulnerabilityRecordData> records = new HashMap<>();
		records.put("CVE-2026-0001", cve);
		when(recordService.getDataByOrgAndVulnIds(eq(ORG), anyCollection())).thenReturn(records);

		ReleaseMetricsDto metrics = new ReleaseMetricsDto();
		metrics.setVulnerabilityDetails(new LinkedList<>(List.of(
				// Dependency-Track's spelling of the stored raw '+'
				finding("pkg:deb/debian/glibc@2.36-9%2Bdeb12u14?distro=debian-12", "CVE-2025-4802",
						VulnerabilitySeverity.CRITICAL, null, true),
				finding("pkg:npm/a@1.4.1", "CVE-2026-0001", VulnerabilitySeverity.HIGH, null, false),
				finding("pkg:npm/a@1.4.1", "CVE-2026-0002", VulnerabilitySeverity.MEDIUM, AnalysisState.NOT_AFFECTED, false),
				// carried forward from a BOM the release no longer has
				finding("pkg:maven/org.apache.logging.log4j/log4j-core@2.24.3", "CVE-2025-68161",
						VulnerabilitySeverity.MEDIUM, null, false),
				finding(null, "CVE-2026-0404", VulnerabilitySeverity.LOW, null, false))));
		metrics.setVulnerabilities(4);
		release = new ReleaseData();
		ReflectionTestUtils.setField(release, "uuid", RELEASE);
		ReflectionTestUtils.setField(release, "org", ORG);
		release.setMetrics(metrics);

		sbomComponentService = mock(SbomComponentService.class);
		List<ReleaseSbomComponent> rows = List.of(row(GLIBC), row(NPM_A), row(CLEAN));
		when(sbomComponentService.listReleaseSbomComponents(RELEASE, null, null)).thenReturn(rows);
		Map<UUID, SbomComponent> components = new LinkedHashMap<>();
		for (UUID id : List.of(GLIBC, NPM_A, CLEAN)) components.put(id, component(id));
		// Dependency-Track's latest version of pkg:npm/a (read-only on the entity, set as a load would)
		ReflectionTestUtils.setField(components.get(NPM_A), "latestVersion", "1.5.0");
		ReflectionTestUtils.setField(components.get(NPM_A), "latestVersionChecked",
				ZonedDateTime.of(2026, 9, 29, 6, 0, 0, 0, ZoneOffset.UTC));
		when(sbomComponentService.findSbomComponentsByIds(anyCollection(), eq(ORG))).thenReturn(components);
		when(sbomComponentService.findSupportByComponentIds(eq(ORG), anyCollection())).thenReturn(Map.of());
		// the bridge's own read of the release inventory, for sbomMatch
		when(sbomComponentService.resolveReleaseComponentPurls(RELEASE))
				.thenReturn(new ReleaseComponentPurls(ORG, CANONICALS,
						Map.of(NPM_A, new LatestVersion("1.5.0", "2026-09-29T06:00:00Z")), false));

		SecurityContext security = mock(SecurityContext.class);
		when(security.getAuthentication()).thenReturn(mock(JwtAuthenticationToken.class));
		SecurityContextHolder.setContext(security);

		schema = schema();
	}

	@AfterEach
	void clearSecurity() {
		SecurityContextHolder.clearContext();
	}

	private static VulnerabilityDto finding(String purl, String vulnId, VulnerabilitySeverity severity,
			AnalysisState state, boolean kev) {
		return new VulnerabilityDto(purl, vulnId, severity, Set.of(), Set.of(), Set.of(),
				state, null, null, null, null, null, null, null, kev);
	}

	private static ReleaseSbomComponent row(UUID component) {
		ReleaseSbomComponent r = new ReleaseSbomComponent();
		r.setUuid(UUID.randomUUID());
		r.setReleaseUuid(RELEASE);
		r.setSbomComponentUuid(component);
		return r;
	}

	private static SbomComponent component(UUID id) {
		SbomComponent sc = new SbomComponent();
		sc.setUuid(id);
		sc.setOrg(ORG);
		sc.setCanonicalPurl(CANONICALS.get(id));
		return sc;
	}

	private GraphQLSchema schema() throws Exception {
		TypeDefinitionRegistry registry = new TypeDefinitionRegistry();
		for (String name : new String[] {"schema.graphqls", "user.graphqls", "programmatic.graphqls"}) {
			registry.merge(new SchemaParser().parse(Files.readString(Path.of("src/main/resources/schema/" + name))));
		}
		SbomComponentDataFetcher sbom = new SbomComponentDataFetcher();
		SharedReleaseService sharedReleaseService = mock(SharedReleaseService.class);
		when(sharedReleaseService.getReleaseData(RELEASE)).thenReturn(Optional.of(release));
		UserService userService = mock(UserService.class);
		when(userService.getUserDataByAuth(any(JwtAuthenticationToken.class))).thenReturn(Optional.of(mock(UserData.class)));
		ReflectionTestUtils.setField(sbom, "authorizationService", mock(AuthorizationService.class));
		ReflectionTestUtils.setField(sbom, "userService", userService);
		ReflectionTestUtils.setField(sbom, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(sbom, "sbomComponentService", sbomComponentService);
		ReflectionTestUtils.setField(sbom, "getComponentService", mock(GetComponentService.class));
		ReflectionTestUtils.setField(sbom, "deviceLifecycleHook", mock(DeviceLifecycleHook.class));

		ArtifactData artifact = new ArtifactData();
		artifact.setOrg(ORG);
		ArtifactData.DependencyTrackIntegration artMetrics = new ArtifactData.DependencyTrackIntegration();
		artMetrics.setVulnerabilityDetails(new LinkedList<>(List.of(release.getMetrics().getVulnerabilityDetails().get(1))));
		artifact.setMetrics(artMetrics);
		ReflectionTestUtils.setField(artifact, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(release, "artifacts", List.of(artifact.getUuid()));
		ArtifactService artifactService = mock(ArtifactService.class);
		when(artifactService.getArtifactDataList(anyCollection())).thenReturn(List.of(artifact));
		ReleaseDatafetcher releaseFetcher = new ReleaseDatafetcher();
		ReflectionTestUtils.setField(releaseFetcher, "artifactService", artifactService);
		ArtifactDataFetcher artifactFetcher = new ArtifactDataFetcher();
		VulnerabilityScoreDataFetcher scores = new VulnerabilityScoreDataFetcher();

		RuntimeWiring.Builder wiring = RuntimeWiring.newRuntimeWiring();
		registry.scalars().keySet().stream()
				.filter(n -> !BUILT_IN_SCALARS.contains(n))
				.forEach(n -> wiring.scalar(passThroughScalar(n)));
		registry.types().values().stream()
				.filter(t -> t instanceof InterfaceTypeDefinition || t instanceof UnionTypeDefinition)
				.forEach(t -> wiring.type(TypeRuntimeWiring.newTypeWiring(t.getName()).typeResolver(env -> null)));
		wiring.type("Query", t -> t
				.dataFetcher("release", env -> release)
				.dataFetcher("getReleaseSbomComponents",
						env -> sbom.getReleaseSbomComponents(UUID.fromString(env.getArgument("releaseUuid"))))
				// a ReleaseSbomComponent reached without a release query's context
				.dataFetcher("getReleaseSbomComponentGraph",
						env -> Map.of("uuid", UUID.randomUUID(), "releaseUuid", RELEASE, "sbomComponentUuid", NPM_A)));
		wiring.type("Release", t -> t
				.dataFetcher("metrics", env -> releaseFetcher.metricsOfRelease(dgs(env)))
				.dataFetcher("artifactDetails", env -> releaseFetcher.artifactsOfReleaseWithDep(dgs(env))));
		wiring.type("Artifact", t -> t.dataFetcher("metrics", env -> artifactFetcher.metricsOfArtifact(dgs(env))));
		wiring.type("ReleaseSbomComponent", t -> t
				.dataFetcher("component", env -> sbom.getComponent(dgs(env)))
				.dataFetcher("latestFixes", env -> sbom.getLatestFixes(dgs(env)))
				.dataFetcher("findings", env -> sbom.getFindings(dgs(env)))
				.dataFetcher("riskSummary", env -> sbom.getRiskSummary(dgs(env)))
				.dataFetcher("fixTargets", env -> sbom.getFixTargets(dgs(env))));
		wiring.type("Vulnerability", t -> t
				.dataFetcher("topScore", env -> scores.topScoreOfVulnerability(dgs(env)))
				.dataFetcher("fixedIn", env -> scores.fixedInOfVulnerability(dgs(env)))
				.dataFetcher("sbomMatch", env -> sbom.getSbomMatch(dgs(env)))
				.dataFetcher("latestFix", env -> sbom.getLatestFix(dgs(env))));
		return new SchemaGenerator().makeExecutableSchema(registry, wiring.build());
	}

	// only DgsDataFetchingEnvironment.getDataLoader(Class) reads the context; the resolvers load by name
	private static final ApplicationContext NO_CONTEXT = mock(ApplicationContext.class);

	private static DgsDataFetchingEnvironment dgs(DataFetchingEnvironment env) {
		return new DgsDataFetchingEnvironment(env, NO_CONTEXT);
	}

	private static GraphQLScalarType passThroughScalar(String name) {
		return GraphQLScalarType.newScalar().name(name).coercing(new Coercing<Object, Object>() {
			@Override
			public Object serialize(Object in, GraphQLContext ctx, Locale locale) {
				return in == null ? null : in.toString();
			}

			@Override
			public Object parseValue(Object in, GraphQLContext ctx, Locale locale) {
				return in;
			}

			@Override
			public Object parseLiteral(Value<?> in, CoercedVariables vars, GraphQLContext ctx, Locale locale) {
				return in;
			}
		}).build();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> run(String query) {
		VulnerabilityRecordDataLoader records = new VulnerabilityRecordDataLoader();
		ReflectionTestUtils.setField(records, "vulnerabilityRecordService", recordService);
		FindingPurlBridge bridge = new FindingPurlBridge();
		ReflectionTestUtils.setField(bridge, "sbomComponentService", sbomComponentService);
		ReleaseInventoryDataLoader inventories = new ReleaseInventoryDataLoader();
		ReflectionTestUtils.setField(inventories, "findingPurlBridge", bridge);
		DataLoaderRegistry loaders = new DataLoaderRegistry();
		loaders.register(VulnerabilityRecordDataLoader.NAME, DataLoaderFactory.newDataLoader(records));
		loaders.register(ReleaseInventoryDataLoader.NAME, DataLoaderFactory.newDataLoader(inventories));
		ExecutionResult result = GraphQL.newGraphQL(schema).build()
				.execute(ExecutionInput.newExecutionInput(query).dataLoaderRegistry(loaders).build());
		assertTrue(result.getErrors().isEmpty(), () -> "errors: " + result.getErrors());
		return (Map<String, Object>) result.getData();
	}

	@SuppressWarnings("unchecked")
	private static <T> T at(Object node, Object... path) {
		Object cur = node;
		for (Object p : path) {
			cur = p instanceof Integer i ? ((List<Object>) cur).get(i) : ((Map<String, Object>) cur).get(p);
		}
		return (T) cur;
	}

	private static Map<String, Object> rowOf(List<Object> rows, UUID component) {
		for (Object r : rows) {
			if (component.toString().equals(at(r, "sbomComponentUuid"))) return at(r);
		}
		throw new AssertionError("no row for " + component);
	}

	@Test
	void findingsHangOffTheComponentTheirPurlNames() {
		Map<String, Object> data = run("{ getReleaseSbomComponents(releaseUuid: \"" + RELEASE + "\") {"
				+ " sbomComponentUuid"
				+ " findings { vulnId topScore { score } fixedIn { verdict version } sbomMatch { sbomComponentUuid } }"
				+ " riskSummary { totalFindings kevCount maxCvss severityWeightedScore }"
				+ " fixTargets { purl targets { version fixes } } } }");
		List<Object> rows = at(data, "getReleaseSbomComponents");

		Map<String, Object> glibc = rowOf(rows, GLIBC);
		assertEquals(List.of("CVE-2025-4802"), vulnIds(at(glibc, "findings")));
		assertEquals(GLIBC.toString(), at(glibc, "findings", 0, "sbomMatch", "sbomComponentUuid"));
		assertEquals(Map.of("totalFindings", 1, "kevCount", 1, "severityWeightedScore", 10),
				withoutNulls(at(glibc, "riskSummary")));
		assertEquals(List.of(), at(glibc, "fixTargets"));

		Map<String, Object> npm = rowOf(rows, NPM_A);
		// stored order, the NOT_AFFECTED row included
		assertEquals(List.of("CVE-2026-0001", "CVE-2026-0002"), vulnIds(at(npm, "findings")));
		assertEquals(9.8, (Double) at(npm, "findings", 0, "topScore", "score"));
		assertEquals("FIXED_IN", at(npm, "findings", 0, "fixedIn", "verdict"));
		assertEquals("1.4.2", at(npm, "findings", 0, "fixedIn", "version"));
		// the summary counts the open row only
		assertEquals(1, (Integer) at(npm, "riskSummary", "totalFindings"));
		assertEquals(9.8, (Double) at(npm, "riskSummary", "maxCvss"));
		assertEquals(5, (Integer) at(npm, "riskSummary", "severityWeightedScore"));
		assertEquals(List.of(Map.of("purl", "pkg:npm/a@1.4.1",
				"targets", List.of(Map.of("version", "1.4.2", "fixes", List.of("CVE-2026-0001"))))),
				at(npm, "fixTargets"));

		Map<String, Object> clean = rowOf(rows, CLEAN);
		assertEquals(List.of(), at(clean, "findings"));
		assertEquals(0, (Integer) at(clean, "riskSummary", "totalFindings"));

		verify(recordService, times(1)).getDataByOrgAndVulnIds(eq(ORG), anyCollection());
		// a finding listed under a row is on that row: no read of the release's SBOM
		verify(sbomComponentService, times(0)).resolveReleaseComponentPurls(RELEASE);
	}

	@Test
	void findingsUnderReleaseMetricsSayWhichComponentTheyAreOn() {
		Map<String, Object> data = run("{ release(releaseUuid: \"x\") {"
				+ " metrics { vulnerabilityDetails { vulnId sbomMatch { sbomComponentUuid canonicalPurl missReason } } }"
				+ " artifactDetails { metrics { vulnerabilityDetails { vulnId sbomMatch { sbomComponentUuid } } } } } }");

		List<Object> rows = at(data, "release", "metrics", "vulnerabilityDetails");
		assertEquals(GLIBC.toString(), at(rows, 0, "sbomMatch", "sbomComponentUuid"));
		assertEquals(CANONICALS.get(GLIBC), at(rows, 0, "sbomMatch", "canonicalPurl"));
		assertNull(at(rows, 0, "sbomMatch", "missReason"));
		assertEquals(NPM_A.toString(), at(rows, 2, "sbomMatch", "sbomComponentUuid"));
		assertEquals("NOT_IN_INVENTORY", at(rows, 3, "sbomMatch", "missReason"));
		assertNull(at(rows, 3, "sbomMatch", "sbomComponentUuid"));
		assertEquals("NO_PURL", at(rows, 4, "sbomMatch", "missReason"));
		// an artifact's findings belong to no one release
		assertNull(at(data, "release", "artifactDetails", 0, "metrics", "vulnerabilityDetails", 0, "sbomMatch"));
		verify(sbomComponentService, times(1)).resolveReleaseComponentPurls(RELEASE);
	}

	@Test
	void aFailedInventoryReadAnswersNullNotAMiss() {
		when(sbomComponentService.resolveReleaseComponentPurls(RELEASE)).thenThrow(new IllegalStateException("db down"));

		Map<String, Object> data = run("{ release(releaseUuid: \"x\") { metrics { vulnerabilityDetails {"
				+ " vulnId sbomMatch { missReason } } } } }");

		assertNull(at(data, "release", "metrics", "vulnerabilityDetails", 0, "sbomMatch"));
		assertNull(at(data, "release", "metrics", "vulnerabilityDetails", 3, "sbomMatch"));
	}

	@Test
	void outsideAReleaseQueryTheFieldsAreEmptyNotFailing() {
		Map<String, Object> data = run("{ getReleaseSbomComponentGraph(releaseUuid: \"" + RELEASE
				+ "\", sbomComponentUuid: \"" + NPM_A + "\") {"
				+ " findings { vulnId } riskSummary { totalFindings } fixTargets { purl } } }");

		assertEquals(List.of(), at(data, "getReleaseSbomComponentGraph", "findings"));
		assertNull(at(data, "getReleaseSbomComponentGraph", "riskSummary"));
		assertNull(at(data, "getReleaseSbomComponentGraph", "fixTargets"));
		verify(recordService, times(0)).getDataByOrgAndVulnIds(any(), anyCollection());
	}

	@Test
	void theLatestVersionAndWhatItFixesComeWithTheComponent() {
		Map<String, Object> data = run("{ getReleaseSbomComponents(releaseUuid: \"" + RELEASE + "\") {"
				+ " sbomComponentUuid component { latestVersion latestVersionChecked } latestFixes"
				+ " findings { vulnId latestFix sbomMatch { latestVersion latestVersionChecked } } } }");
		List<Object> rows = at(data, "getReleaseSbomComponents");

		Map<String, Object> npm = rowOf(rows, NPM_A);
		assertEquals("1.5.0", at(npm, "component", "latestVersion"));
		assertEquals("2026-09-29T06:00:00Z", at(npm, "component", "latestVersionChecked"));
		// 1.5.0 is past CVE-2026-0001's range (< 1.4.2); CVE-2026-0002 has no record to place it in
		assertEquals(List.of("CVE-2026-0001"), at(npm, "latestFixes"));
		assertEquals("FIXES", at(npm, "findings", 0, "latestFix"));
		assertEquals("NO_RANGE_DATA", at(npm, "findings", 1, "latestFix"));
		assertEquals("1.5.0", at(npm, "findings", 0, "sbomMatch", "latestVersion"));
		// no latest version known for glibc: nothing to say
		Map<String, Object> glibc = rowOf(rows, GLIBC);
		assertNull(at(glibc, "component", "latestVersion"));
		assertNull(at(glibc, "latestFixes"));
		assertNull(at(glibc, "findings", 0, "latestFix"));
	}

	@Test
	void findingsUnderReleaseMetricsCarryTheirComponentsLatestVersion() {
		Map<String, Object> data = run("{ release(releaseUuid: \"x\") { metrics { vulnerabilityDetails {"
				+ " vulnId latestFix sbomMatch { latestVersion latestVersionChecked } } } } }");
		List<Object> rows = at(data, "release", "metrics", "vulnerabilityDetails");

		assertEquals("1.5.0", at(rows, 1, "sbomMatch", "latestVersion"));
		assertEquals("2026-09-29T06:00:00Z", at(rows, 1, "sbomMatch", "latestVersionChecked"));
		assertEquals("FIXES", at(rows, 1, "latestFix"));
		assertNull(at(rows, 0, "sbomMatch", "latestVersion"));
		assertNull(at(rows, 3, "latestFix"));
		verify(sbomComponentService, times(1)).resolveReleaseComponentPurls(RELEASE);
	}

	private static List<Object> vulnIds(List<Object> findings) {
		return findings.stream().map(f -> (Object) at(f, "vulnId")).toList();
	}

	private static Map<String, Object> withoutNulls(Map<String, Object> m) {
		Map<String, Object> out = new LinkedHashMap<>();
		m.forEach((k, v) -> {
			if (v != null) out.put(k, v);
		});
		return out;
	}
}
