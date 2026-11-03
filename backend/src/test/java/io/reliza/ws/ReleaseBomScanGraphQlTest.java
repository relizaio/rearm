/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLContext;
import graphql.GraphQLError;
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
import graphql.validation.ValidationError;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.ArtifactNotScannable;
import io.reliza.model.NoMatchableComponentsReason;
import io.reliza.model.ReleaseData;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.BomScanTally;
import io.reliza.service.ArtifactService;

/**
 * TPF-132 architecture-4 section 6 test 1: DependencyTrackMetrics.bomScan serves the persisted tally of
 * Release.metrics (state, the two counts, the reasons) over the three schema files merged as the server
 * merges them, with the real Release.metrics and Artifact.metrics fetchers and the two
 * ReleaseBomScanDataFetcher methods wired on ReleaseBomScan; null without a tally and on artifact metrics.
 */
class ReleaseBomScanGraphQlTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final Set<String> BUILT_IN_SCALARS = Set.of("Int", "Float", "String", "Boolean", "ID");
	private static final ApplicationContext NO_CONTEXT = mock(ApplicationContext.class);
	private static final String BOM_SCAN_QUERY = "{ release(releaseUuid: \"" + UUID.randomUUID()
			+ "\") { metrics { bomScan { state scannableBoms notScannableBoms reasons } } } }";

	private ReleaseData release;
	private GraphQLSchema schema;

	@BeforeEach
	void setUp() throws Exception {
		ArtifactData marked = new ArtifactData();
		ReflectionTestUtils.setField(marked, "uuid", UUID.randomUUID());
		marked.setOrg(ORG);
		DependencyTrackIntegration markedMetrics = new DependencyTrackIntegration();
		markedMetrics.setNotScannable(new ArtifactNotScannable(NoMatchableComponentsReason.ONLY_ROOT,
				ZonedDateTime.of(2026, 10, 29, 1, 0, 0, 0, ZoneOffset.UTC), 1, 1, 0, 0));
		marked.setMetrics(markedMetrics);

		ArtifactData scanned = new ArtifactData();
		ReflectionTestUtils.setField(scanned, "uuid", UUID.randomUUID());
		scanned.setOrg(ORG);
		DependencyTrackIntegration scannedMetrics = new DependencyTrackIntegration();
		scannedMetrics.setFirstScanned(ZonedDateTime.of(2026, 10, 28, 1, 0, 0, 0, ZoneOffset.UTC));
		scanned.setMetrics(scannedMetrics);

		release = new ReleaseData();
		ReflectionTestUtils.setField(release, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(release, "org", ORG);
		ReflectionTestUtils.setField(release, "artifacts", List.of(marked.getUuid(), scanned.getUuid()));
		release.setMetrics(new ReleaseMetricsDto());

		ArtifactService artifactService = mock(ArtifactService.class);
		when(artifactService.getArtifactDataList(anyCollection())).thenReturn(List.of(marked, scanned));
		when(artifactService.getArtifactDataListLight(anyCollection())).thenReturn(List.of(marked, scanned));
		ReleaseDatafetcher releaseFetcher = new ReleaseDatafetcher();
		ReflectionTestUtils.setField(releaseFetcher, "artifactService", artifactService);
		ArtifactDataFetcher artifactFetcher = new ArtifactDataFetcher();
		ReleaseBomScanDataFetcher bomScanFetcher = new ReleaseBomScanDataFetcher();

		TypeDefinitionRegistry registry = new TypeDefinitionRegistry();
		for (String name : new String[] {"schema.graphqls", "user.graphqls", "programmatic.graphqls"}) {
			registry.merge(new SchemaParser().parse(Files.readString(Path.of("src/main/resources/schema/" + name))));
		}
		RuntimeWiring.Builder wiring = RuntimeWiring.newRuntimeWiring();
		registry.scalars().keySet().stream()
				.filter(n -> !BUILT_IN_SCALARS.contains(n))
				.forEach(n -> wiring.scalar(passThroughScalar(n)));
		registry.types().values().stream()
				.filter(t -> t instanceof InterfaceTypeDefinition || t instanceof UnionTypeDefinition)
				.forEach(t -> wiring.type(TypeRuntimeWiring.newTypeWiring(t.getName()).typeResolver(env -> null)));
		wiring.type("Query", t -> t.dataFetcher("release", env -> release));
		wiring.type("Release", t -> t
				.dataFetcher("metrics", env -> releaseFetcher.metricsOfRelease(dgs(env)))
				.dataFetcher("artifactDetails", env -> releaseFetcher.artifactsOfReleaseWithDep(dgs(env))));
		wiring.type("Artifact", t -> t.dataFetcher("metrics", env -> artifactFetcher.metricsOfArtifact(dgs(env))));
		wiring.type("ReleaseBomScan", t -> t
				.dataFetcher("state", env -> bomScanFetcher.stateOfTally(dgs(env)))
				.dataFetcher("reasons", env -> bomScanFetcher.reasonsOfTally(dgs(env))));
		schema = new SchemaGenerator().makeExecutableSchema(registry, wiring.build());
	}

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

	private ExecutionResult execute(String query) {
		return GraphQL.newGraphQL(schema).build().execute(ExecutionInput.newExecutionInput(query).build());
	}

	/** The served bomScan of the release, asserting the response carries no error. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> servedBomScan(BomScanTally tally) {
		release.getMetrics().setBomScan(tally);
		ExecutionResult result = execute(BOM_SCAN_QUERY);
		assertTrue(result.getErrors().isEmpty(), () -> "errors: " + result.getErrors());
		Map<String, Object> rel = (Map<String, Object>) ((Map<String, Object>) result.getData()).get("release");
		return (Map<String, Object>) ((Map<String, Object>) rel.get("metrics")).get("bomScan");
	}

	private static void assertServed(Map<String, Object> served, String state, int scannable, int notScannable,
			List<String> reasons) {
		assertNotNull(served, "a stored tally is served");
		assertEquals(state, served.get("state"));
		assertEquals(scannable, served.get("scannableBoms"));
		assertEquals(notScannable, served.get("notScannableBoms"));
		assertEquals(reasons, served.get("reasons"));
	}

	/** (a) */
	@Test
	void noBomsTallyServesNoBoms() {
		assertServed(servedBomScan(new BomScanTally(0, 0, List.of())), "NO_BOMS", 0, 0, List.of());
	}

	/** (b) */
	@Test
	void scannableOnlyTallyServesAllScannable() {
		assertServed(servedBomScan(new BomScanTally(2, 0, List.of())), "ALL_SCANNABLE", 2, 0, List.of());
	}

	/** (c) */
	@Test
	void mixedTallyServesSomeNotScannableWithItsReason() {
		assertServed(servedBomScan(new BomScanTally(1, 1, List.of(NoMatchableComponentsReason.NO_PURL_OR_CPE))),
				"SOME_NOT_SCANNABLE", 1, 1, List.of("NO_PURL_OR_CPE"));
	}

	/** (d) */
	@Test
	void markedOnlyTallyServesAllNotScannableWithReasonsInEnumOrder() {
		assertServed(servedBomScan(new BomScanTally(0, 2,
				List.of(NoMatchableComponentsReason.ONLY_ROOT, NoMatchableComponentsReason.NO_PURL_OR_CPE))),
				"ALL_NOT_SCANNABLE", 0, 2, List.of("ONLY_ROOT", "NO_PURL_OR_CPE"));
	}

	/** (e) */
	@Test
	void metricsWithoutATallyServeNull() {
		assertNull(servedBomScan(null), "a legacy row says nothing");
	}

	/** (f) */
	@Test
	void aTallyWithoutItsReasonsListServesAnEmptyList() {
		assertServed(servedBomScan(new BomScanTally(1, 0, null)), "ALL_SCANNABLE", 1, 0, List.of());
	}

	/** (h) */
	@Test
	void theRecordComponentNameIsNotAServedField() {
		release.getMetrics().setBomScan(new BomScanTally(0, 1, List.of(NoMatchableComponentsReason.ONLY_ROOT)));
		ExecutionResult result = execute("{ release(releaseUuid: \"" + UUID.randomUUID()
				+ "\") { metrics { bomScan { notScannableReasons } } } }");
		List<GraphQLError> errors = result.getErrors();
		assertEquals(1, errors.size(), errors.toString());
		assertTrue(errors.get(0) instanceof ValidationError, errors.toString());
		assertTrue(errors.get(0).getMessage().contains("notScannableReasons"), errors.get(0).getMessage());
		assertNull(result.getData(), "a document that does not validate returns no data");
	}

	/** (g) */
	@SuppressWarnings("unchecked")
	@Test
	void artifactMetricsNeverServeATally() {
		release.getMetrics().setBomScan(new BomScanTally(1, 1, List.of(NoMatchableComponentsReason.ONLY_ROOT)));
		ExecutionResult result = execute("{ release(releaseUuid: \"" + UUID.randomUUID() + "\") { "
				+ "metrics { bomScan { state } } "
				+ "artifactDetails { metrics { notScannable { reason } bomScan { state scannableBoms notScannableBoms reasons } } } } }");
		assertTrue(result.getErrors().isEmpty(), () -> "errors: " + result.getErrors());

		Map<String, Object> rel = (Map<String, Object>) ((Map<String, Object>) result.getData()).get("release");
		assertNotNull(((Map<String, Object>) rel.get("metrics")).get("bomScan"), "the release serves its tally");
		List<Map<String, Object>> arts = (List<Map<String, Object>>) rel.get("artifactDetails");
		assertEquals(2, arts.size());
		Map<String, Object> markedMetrics = (Map<String, Object>) arts.get(0).get("metrics");
		assertNotNull(markedMetrics.get("notScannable"), "the marked artifact carries its marker");
		assertNull(markedMetrics.get("bomScan"), "and no tally: the two scan-state fields are independent");
		assertNull(((Map<String, Object>) arts.get(1).get("metrics")).get("bomScan"));
	}
}
