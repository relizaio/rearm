/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.parser.Parser;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.StripBom;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactVersionSnapshot;
import io.reliza.model.SystemInfo;
import io.reliza.model.SystemInfoData;
import io.reliza.model.dto.ArtifactWebDto;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomMeta;

/**
 * SCORE-52 T-16, T-21, T-23 and T-25, without a database: what rearm-saas removed stays removed and
 * the copy mark is never caller input or output. Every schema file is parsed as SDL: no
 * {@code sbomLineageOwner} query, {@code reassignSbomLineageOwner} mutation or
 * {@code SbomLineageOwner} type, no {@code ownerComponent} or {@code bomCopy} field on any type or
 * input, and {@code ReleaseInput} is the only input type carrying an artifact uuid list. The options
 * serialise no owner, stored records written before this change still load, and a source scan of
 * {@code src/main/java} finds neither the lineage contract nor a second use of
 * {@code SbomWriteAuthority.NO_PRINCIPAL}.
 */
class SbomUpdateGuardMetaNotExposedTest {

	private static final Path MAIN_SOURCES = Paths.get("src/main/java");

	private static List<Document> schema() throws Exception {
		List<Document> docs = new ArrayList<>();
		try (Stream<Path> files = Files.list(Paths.get("src/main/resources/schema"))) {
			for (Path p : files.filter(f -> f.toString().endsWith(".graphqls")).sorted().toList()) {
				docs.add(Parser.parse(Files.readString(p)));
			}
		}
		assertTrue(docs.size() >= 3, "schema, user and programmatic files");
		return docs;
	}

	@SuppressWarnings("rawtypes")
	private static <T extends Definition> List<T> definitions(List<Document> docs, Class<T> kind) {
		return docs.stream().flatMap(d -> d.getDefinitionsOfType(kind).stream()).toList();
	}

	private static Type<?> unwrapNonNull(Type<?> t) {
		return t instanceof NonNullType nn ? nn.getType() : t;
	}

	private static boolean isListOfId(Type<?> t) {
		return unwrapNonNull(t) instanceof ListType lt && unwrapNonNull(lt.getType()) instanceof TypeName tn
				&& "ID".equals(tn.getName());
	}

	/** The names of every object type (extensions included) and input type with a field named {@code field}. */
	private static TreeSet<String> typesWithField(List<Document> docs, String field) {
		TreeSet<String> names = new TreeSet<>();
		for (ObjectTypeDefinition o : definitions(docs, ObjectTypeDefinition.class)) {
			if (o.getFieldDefinitions().stream().anyMatch(f -> field.equals(f.getName()))) names.add(o.getName());
		}
		for (InputObjectTypeDefinition i : definitions(docs, InputObjectTypeDefinition.class)) {
			if (i.getInputValueDefinitions().stream().anyMatch(f -> field.equals(f.getName()))) names.add(i.getName());
		}
		return names;
	}

	/** Every query and mutation field name in the schema, extensions included. */
	private static TreeSet<String> operations(List<Document> docs) {
		TreeSet<String> names = new TreeSet<>();
		for (ObjectTypeDefinition o : definitions(docs, ObjectTypeDefinition.class)) {
			if ("Query".equals(o.getName()) || "Mutation".equals(o.getName())
					|| o instanceof ObjectTypeExtensionDefinition) {
				o.getFieldDefinitions().stream().map(FieldDefinition::getName).forEach(names::add);
			}
		}
		return names;
	}

	/** The main source files whose text matches {@code pattern}, as paths relative to src/main/java. */
	private static List<String> mainSourcesMatching(Pattern pattern) throws IOException {
		List<String> hits = new ArrayList<>();
		try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
			for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
				if (pattern.matcher(Files.readString(p)).find()) hits.add(MAIN_SOURCES.relativize(p).toString());
			}
		}
		return hits;
	}

	// T-16
	@Test
	void theLineageOwnerApiIsGoneFromEverySchemaFile() throws Exception {
		List<Document> docs = schema();
		TreeSet<String> ops = operations(docs);
		assertFalse(ops.contains("sbomLineageOwner"), ops.toString());
		assertFalse(ops.contains("reassignSbomLineageOwner"), ops.toString());
		assertTrue(ops.contains("addArtifactManual"), "the scan reads the user schema");
		assertFalse(definitions(docs, ObjectTypeDefinition.class).stream()
				.anyMatch(o -> "SbomLineageOwner".equals(o.getName())));
		assertEquals(new TreeSet<>(), typesWithField(docs, "ownerComponent"));
	}

	// T-16, round 3
	@Test
	void theCopyMarkIsNeverCallerInputOrOutput() throws Exception {
		assertEquals(new TreeSet<>(), typesWithField(schema(), "bomCopy"));
		assertFalse(Arrays.stream(ArtifactVersionSnapshot.class.getRecordComponents())
				.anyMatch(c -> "bomCopy".equals(c.getName())));
		ArtifactData copy = ArtifactData.dataFromRecord(artifact(Map.of("bomCopy", true)));
		assertTrue(copy.isCopyRow());
		assertFalse(Utils.OM.valueToTree(ArtifactWebDto.fromData(copy, UUID.randomUUID())).has("bomCopy"));
	}

	// T-16, round 3
	@Test
	void theCopyMarkRoundTripsThroughTheRecordAndAbsentReadsFalse() {
		ArtifactData copy = ArtifactData.dataFromRecord(artifact(Map.of("bomCopy", true)));
		assertTrue(copy.isCopyRow());
		Map<String, Object> written = Utils.dataToRecord(copy);
		assertEquals(Boolean.TRUE, written.get("bomCopy"));
		assertTrue(ArtifactData.dataFromRecord(artifact(written)).isCopyRow());

		ArtifactData legacy = ArtifactData.dataFromRecord(artifact(Map.of()));
		assertFalse(legacy.isCopyRow());
		assertNull(legacy.getBomCopy());
	}

	private static Artifact artifact(Map<String, Object> recordData) {
		Map<String, Object> rd = new HashMap<>(recordData);
		rd.putIfAbsent("org", UUID.randomUUID().toString());
		rd.putIfAbsent("type", "BOM");
		Artifact a = new Artifact();
		a.setRecordData(rd);
		return a;
	}

	// T-16
	@Test
	void aSystemInfoRecordWrittenByTheBackfillStillLoads() {
		SystemInfo si = new SystemInfo();
		Map<String, Object> data = new HashMap<>();
		data.put("sbomLineageOwnerBackfill", Map.of("startedAt", "2026-10-15T00:00:00Z", "orgsDone", List.of(),
				"claimed", 3, "ambiguous", 0, "conflicts", 0, "noSuchLineage", 1));
		data.put("systemSealed", false);
		si.setData(data);
		SystemInfoData sid = SystemInfoData.dataFromRecord(si);
		assertFalse(Utils.OM.valueToTree(sid).has("sbomLineageOwnerBackfill"));
	}

	// T-16
	@Test
	void theBackfillAndTheLineageResolverAreGone() {
		assertThrows(ClassNotFoundException.class, () -> Class.forName("io.reliza.service.SbomLineageOwnerBackfill"));
		assertThrows(ClassNotFoundException.class, () -> Class.forName("io.reliza.service.SbomLineageOwnerService"));
		assertThrows(ClassNotFoundException.class, () -> Class.forName("io.reliza.ws.SbomLineageDataFetcher"));
	}

	// T-16, T-25
	@Test
	void theOptionsSerialiseNoOwner() {
		RebomOptions options = new RebomOptions("api", "Example Org", "1.0.0", ArtifactBelongsTo.RELEASE, null,
				StripBom.FALSE, null);
		assertFalse(Utils.OM.valueToTree(options).has("ownerComponent"));
		assertFalse(Arrays.stream(RebomOptions.class.getRecordComponents())
				.anyMatch(c -> "ownerComponent".equals(c.getName())));
		assertFalse(Arrays.stream(BomMeta.class.getRecordComponents()).anyMatch(c -> "ownerComponent".equals(c.getName())));
	}

	// T-25
	@Test
	void noMainSourceNamesTheLineageContract() throws Exception {
		assertEquals(List.of(), mainSourcesMatching(
				Pattern.compile("bomLineage|setBomLineageOwners|ownerComponent|BOM_LINEAGE_OWNER_MISMATCH")));
	}

	// T-21
	@Test
	void onlyTheVdrTriggerUsesNoPrincipalAndNoAuthorityIsConstantlyOrgWide() throws Exception {
		// CE: Pro's only NO_PRINCIPAL caller is saas/ReleaseTriggerService (the VDR trigger), which CE
		// does not have, so on CE nothing but its declaration names it (SCORE-55 3.6).
		List<String> users = mainSourcesMatching(Pattern.compile("NO_PRINCIPAL"));
		assertEquals(List.of("io/reliza/service/SbomWriteAuthority.java"), users);
		assertEquals(List.of(), mainSourcesMatching(Pattern.compile(
				"boolean\\s+orgWideWrite\\s*\\(\\s*\\)\\s*\\{\\s*return\\s+true\\s*;")));
	}

	// T-23
	@Test
	void releaseInputIsTheOnlyInputCarryingAnArtifactUuidList() throws Exception {
		TreeSet<String> withIdList = new TreeSet<>();
		for (InputObjectTypeDefinition i : definitions(schema(), InputObjectTypeDefinition.class)) {
			for (InputValueDefinition f : i.getInputValueDefinitions()) {
				if ("artifacts".equals(f.getName()) && isListOfId(f.getType())) withIdList.add(i.getName());
			}
		}
		assertEquals(new TreeSet<>(List.of("ReleaseInput")), withIdList);
	}
}
