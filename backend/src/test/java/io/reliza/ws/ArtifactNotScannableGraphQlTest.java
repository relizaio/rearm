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
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.ArtifactNotScannable;
import io.reliza.model.NoMatchableComponentsReason;
import io.reliza.model.ReleaseData;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.service.ArtifactService;

/**
 * TPF-125 / TPF-126 architecture 6.3 row 12: Artifact.metrics.notScannable is served through the existing
 * Artifact.metrics fetcher as a nullable object (reason, at as a DateTime, the four counts), null on a
 * scanned artifact and on Release.metrics, over the three schema files merged as the server merges them.
 */
class ArtifactNotScannableGraphQlTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final Set<String> BUILT_IN_SCALARS = Set.of("Int", "Float", "String", "Boolean", "ID");
	private static final ApplicationContext NO_CONTEXT = mock(ApplicationContext.class);

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

	@SuppressWarnings("unchecked")
	@Test
	void artifactMetricsServeTheMarkerAndReleaseMetricsDoNot() {
		ExecutionResult result = GraphQL.newGraphQL(schema).build().execute(ExecutionInput.newExecutionInput(
				"{ release(releaseUuid: \"" + UUID.randomUUID() + "\") { metrics { notScannable { reason } } "
						+ "artifactDetails { metrics { notScannable { reason at components root terminal unmatchable } "
						+ "firstScanned } } } }").build());
		assertTrue(result.getErrors().isEmpty(), () -> "errors: " + result.getErrors());

		Map<String, Object> rel = (Map<String, Object>) ((Map<String, Object>) result.getData()).get("release");
		assertNull(((Map<String, Object>) rel.get("metrics")).get("notScannable"), "Release.metrics never carries it");
		List<Map<String, Object>> arts = (List<Map<String, Object>>) rel.get("artifactDetails");
		assertEquals(2, arts.size());

		Map<String, Object> marked = (Map<String, Object>) ((Map<String, Object>) arts.get(0).get("metrics"))
				.get("notScannable");
		assertNotNull(marked);
		assertEquals("ONLY_ROOT", marked.get("reason"));
		assertTrue(marked.get("at") instanceof String && ((String) marked.get("at")).startsWith("2026-10-29T01:00"),
				"at is served as a DateTime: " + marked.get("at"));
		assertEquals(1, marked.get("components"));
		assertEquals(1, marked.get("root"));
		assertEquals(0, marked.get("terminal"));
		assertEquals(0, marked.get("unmatchable"));

		Map<String, Object> scannedMetrics = (Map<String, Object>) arts.get(1).get("metrics");
		assertNull(scannedMetrics.get("notScannable"), "null on a scannable artifact");
		assertNotNull(scannedMetrics.get("firstScanned"));
	}
}
