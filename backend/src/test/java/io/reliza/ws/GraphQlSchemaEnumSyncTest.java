/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;

import graphql.language.EnumTypeDefinition;
import graphql.language.EnumTypeExtensionDefinition;
import graphql.language.EnumValueDefinition;
import graphql.parser.ParserOptions;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.reliza.common.CommonVariables;
import io.reliza.common.oss.LicensingConstants;
import io.reliza.dto.ChangelogRecords;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskInput;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.ArtifactData;
import io.reliza.model.AttestationData;
import io.reliza.model.CliSession;
import io.reliza.model.ComponentLock;
import io.reliza.model.DeliverableData;
import io.reliza.model.ElementCheckReport;
import io.reliza.model.EmailDigestPolicy;
import io.reliza.model.FederatedGrant;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.ModelFacts;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.VersionAssignment;
import io.reliza.model.dto.notifications.EvaluationMode;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.model.tea.TeaCollectionUpdateReasonType;
import io.reliza.service.BoardAgentsService;
import io.reliza.service.DeclarativeConfigService;
import io.reliza.service.SyntheticEventTemplates;

/**
 * One data-driven guard for GraphQL-schema vs Java enum drift, replacing the per-enum
 * copy-paste sync tests that each carried their own regex.
 *
 * <p>Why it matters: DGS binds a GraphQL enum to a Java enum by value NAME. A value Java can
 * produce but the schema lacks fails on READ, and fails the whole query, not just the field
 * (ReleaseUpdateScope / SUPPORT_WINDOW took the release page down that way). A value the
 * schema accepts but Java lacks passes schema validation and then fails at coercion -- or,
 * where the name is used as a SQL literal, silently returns nothing.
 *
 * <p>How it works:
 * <ol>
 *   <li>Every {@code schema/**}{@code /*.graphqls} file on the classpath is parsed with
 *       graphql-java (no regex) and merged, enum extensions included.</li>
 *   <li>Every enum under {@code io.reliza} on the main classpath (nested ones included) is
 *       discovered from class metadata; test-only enums are ignored.</li>
 *   <li>Each schema enum is resolved to a Java enum: {@link #OVERRIDES} or
 *       {@link #PRO_ONLY_OVERRIDES} first, then the exact
 *       simple name, then the name minus an {@code Enum} suffix. More than one candidate is
 *       ambiguous and must be resolved with an override.</li>
 *   <li>Value sets must be equal, apart from named {@link #INTERNAL_JAVA_VALUES}, unless the
 *       pair is in {@link #ONE_DIRECTIONAL} or its exact
 *       drift is pinned in {@link #KNOWN_DRIFT}.</li>
 * </ol>
 *
 * <p>A schema enum that resolves to nothing and is not in {@link #NO_JAVA_COUNTERPART} fails,
 * so a NEW schema enum has to be mapped or allowlisted deliberately. All problems are
 * collected and reported in one message.
 *
 * <p>Shared with CE via copy-src.sh, so it must compile and pass in both editions. CE gets the
 * same schema files but not the saas/ Java, so Pro-only targets are class-name strings, and --
 * only when {@link LicensingConstants#isOssEdition()} -- a schema enum whose Java side is
 * saas/-only is tolerated. The Pro run is the gate for those.
 *
 * <p>Plain JUnit, no Spring context and no database. {@code NotificationSchemaEnumSyncTest}
 * stays separate: it derives NotificationChannelTypeEnum from a curated set rather than an
 * enum's values, and also pins a type's field names.
 */
class GraphQlSchemaEnumSyncTest {

	private static final String SCHEMA_PATTERN = "classpath*:schema/**/*.graphqls";
	private static final String CLASS_PATTERN = "classpath*:io/reliza/**/*.class";
	private static final String ENUM_SUFFIX = "Enum";
	private static final String TEST_CLASSES_DIR = "/test-classes/";

	/**
	 * graphql-java caps a parse at 15,000 tokens as a defence against hostile input; our own
	 * schema is far larger (see SchemaParsesTest), so the ceiling is raised for this read.
	 */
	private static final int MAX_TOKENS = 1_000_000;

	/** How a resolved schema/Java pair must agree. */
	private enum Agreement {
		/** Same value set both ways (the default). */
		EXACT,
		/** Every schema value must exist in Java; Java may carry values the schema hides. */
		SCHEMA_SUBSET_OF_JAVA
	}

	/** A drift that is known, reported and awaiting a decision -- pinned exactly. */
	private record KnownDrift(Set<String> javaOnly, Set<String> schemaOnly, String reason) {}

	/**
	 * Schema enums whose Java counterpart is not found by name, or whose name is ambiguous or
	 * collides with an unrelated Java enum.
	 */
	private static final Map<String, Class<?>> OVERRIDES = Map.ofEntries(
			entry("AgentActorKind", AgentActor.ActorKind.class),
			entry("AgentBoardEventKind", AgentBoardData.BoardEventKind.class),
			entry("AgentBoardPauseLevel", AgentBoardData.BoardPauseLevel.class),
			entry("AgentBoardPriorityType", AgentBoardData.PriorityType.class),
			entry("AgentBoardStatus", AgentBoardData.BoardStatus.class),
			entry("AgentCommissionIntake", AgentTaskRoleConfigData.CommissionIntake.class),
			entry("AgentDeliveryMode", AgentBoardData.DeliveryMode.class),
			entry("AgentDeliveryOutcome", AgentTaskData.DeliveryOutcome.class),
			entry("AgentHumanGate", AgentTaskRoleConfigData.HumanGate.class),
			entry("AgentInputKind", AgentTaskInput.InputKind.class),
			entry("AgentInputResolution", AgentTaskInput.InputResolution.class),
			entry("AgentInputScope", AgentTaskInput.InputScope.class),
			entry("AgentInvestigationReturnTo", AgentTaskData.ReturnTo.class),
			entry("AgentMergeMethod", AgentBoardData.MergeMethod.class),
			entry("AgentMergeOrder", AgentBoardData.MergeOrder.class),
			entry("AgentRoleKind", AgentTaskRoleConfigData.RoleKind.class),
			entry("AgentRoleNecessity", AgentTaskRoleConfigData.RoleNecessity.class),
			entry("AgentSignOffOutcome", AgentTaskData.SignOffOutcome.class),
			entry("AgentStatusTrigger", AgentTaskData.StatusTrigger.class),
			entry("AgentTaskGroupStatus", AgentBoardData.GroupStatus.class),
			entry("AgentTaskHoldKind", AgentTaskData.HoldKind.class),
			entry("AgentTaskHoldLevel", AgentTaskData.HoldLevel.class),
			entry("AgentTaskHoldStop", AgentTaskData.HoldStop.class),
			entry("AgentTaskKind", AgentTaskData.TaskKind.class),
			entry("AgentTaskReturnReason", AgentTaskData.TaskReturnReason.class),
			entry("AgentTaskStatus", AgentTaskData.TaskStatus.class),
			entry("ArtifactBelonging", DeliverableData.BelongsToOrganization.class),
			entry("ArtifactStoredIn", ArtifactData.StoredIn.class),
			entry("AttestationActorType", AttestationData.ActorType.class),
			entry("AttestationRecordStatus", AttestationData.RecordStatus.class),
			entry("AttestationSubjectType", AttestationData.SubjectType.class),
			entry("AttestationType", AttestationData.Type.class),
			entry("AttestationVerdict", AttestationData.Verdict.class),
			entry("BoardAgentStateKind", BoardAgentsService.StateKind.class),
			// Ambiguous: VulnerabilityRecordUpdatedPayload.ChangeType is a notification payload enum.
			entry("ChangeType", ChangelogRecords.ChangeType.class),
			entry("CliSessionStatus", CliSession.Status.class),
			entry("CollectionUpdateReasonType", TeaCollectionUpdateReasonType.class),
			entry("DeclarativeAction", DeclarativeConfigService.Action.class),
			entry("ElementCheckOutcome", ElementCheckReport.Result.class),
			entry("FederatedGrantType", FederatedGrant.GrantType.class),
			entry("FederatedProvider", FederatedTrustRule.Provider.class),
			entry("FederatedTrustRuleStatus", FederatedTrustRule.Status.class),
			entry("IdentifierType", RearmIdentifierType.class),
			entry("LockOrigin", ComponentLock.Origin.class),
			entry("LockScope", ComponentLock.Scope.class),
			entry("LockStatus", ComponentLock.Status.class),
			entry("LockSubjectType", ComponentLock.SubjectType.class),
			entry("ModelHostingKind", ModelFacts.HostingKind.class),
			entry("ModelRoleCategory", ModelOntologyData.RoleCategory.class),
			entry("NotificationEmailDigestModeEnum", EmailDigestPolicy.EmailDigestMode.class),
			entry("NotificationEvaluationModeEnum", EvaluationMode.class),
			entry("OAuthType", CommonVariables.OauthType.class),
			entry("RemvovableType", CommonVariables.Removable.class),
			entry("SessionAuthMethod", AgentSessionData.AuthMethod.class),
			entry("SessionOwnerSource", AgentSessionData.OwnerSource.class),
			entry("SessionUsageCompleteness", AgentSessionData.UsageCompleteness.class),
			entry("SpecificationType", RearmSpecificationType.class),
			// Ambiguous: CliSession / ComponentLock / FederatedTrustRule each have a Status.
			entry("Status", CommonVariables.StatusEnum.class),
			entry("SyntheticEventTemplateEnum", SyntheticEventTemplates.Template.class),
			entry("TeaArtifactChecksumType", TeaChecksumType.class),
			// Name collides with io.reliza.versioning.VersionType (version schemes, unrelated).
			entry("VersionType", VersionAssignment.VersionTypeEnum.class));

	/*
	 * ---- PRO_ONLY ----------------------------------------------------------------------------
	 * Overrides whose Java target lives in a saas/ package. This file is mirrored into the CE
	 * repo by copy-src.sh, which does not copy saas/, so these are class-NAME strings resolved
	 * with Class.forName rather than imports. A missing target is a stale entry in the Pro
	 * edition and is skipped in the OSS edition.
	 */
	private static final Map<String, String> PRO_ONLY_OVERRIDES = Map.ofEntries(
			entry("DeviceWindowSource", "io.reliza.service.saas.DeviceLifecycleResolver$WindowSource"),
			// Ambiguous: CommonVariables.IntegrateType (FOLLOW/TARGET/NONE) backs MarketingRelease,
			// which shares this schema enum; the full set lives on the instance plan DTO.
			entry("IntegrateType", "io.reliza.model.dto.saas.InstanceProductMapPlanDto$IntegrateType"));
	/* ---- end PRO_ONLY ------------------------------------------------------------------------ */

	/** Schema enums that intentionally have no Java enum to compare against. */
	private static final Map<String, String> NO_JAVA_COUNTERPART = Map.ofEntries(
			entry("AgentInboxEventKind", "built as string literals in AgentDataFetcher, no Java enum"),
			entry("AgentInboxSource", "built as string literals in AgentDataFetcher, no Java enum"),
			entry("ApprovalMappingClass", "legacy: ApprovalMapping.mappingClass has no Java field"),
			entry("DependencySource", "built as a string literal in BranchDataFetcher, no Java enum"),
			entry("NotificationChannelStatusEnum",
					"mapped from IntegrationData.isEnabled via string constants in NotificationDataFetcher"),
			entry("NotificationChannelTypeEnum",
					"derived from IntegrationType via toChannelTypeName; NotificationSchemaEnumSyncTest"),
			entry("OrganizationType", "legacy: OrganizationData has no type field"),
			entry("ReleaseStatus", "unreferenced schema type; ReleaseData.ReleaseStatus is unrelated"),
			entry("TicketStatus", "legacy: Ticket has no Java entity"));

	/**
	 * NO_JAVA_COUNTERPART entries that DO resolve by name, to an unrelated Java enum. Every other
	 * entry must keep resolving to nothing: once someone adds a matching Java enum, it has to be
	 * compared, not skipped.
	 */
	private static final Set<String> UNRELATED_NAMESAKES = Set.of("ReleaseStatus");

	/**
	 * Named Java values that are internal on purpose and must stay off the wire. Only these are
	 * excused: any OTHER Java-only value still fails, which matters for enums returned on outputs
	 * (a Java-only value there breaks the whole query). A listed value that stops being Java-only
	 * fails too, so the entry cannot outlive its reason.
	 */
	private static final Map<String, Set<String>> INTERNAL_JAVA_VALUES = Map.ofEntries(
			// Agent-session artifacts are attached by AgentSessionService, never by a client. The
			// schema enum is input-only (CreateArtifactInput, the SBOM export inputs);
			// DownloadConfig.belongsTo only echoes the export input. Exposing the value would let
			// clients send one no input handler supports.
			entry("ArtifactBelongsToEnum", Set.of("AGENT_SESSION")),
			// A "match any status" filter value used inside MarketingReleaseService; no field
			// returns it and no input should accept it.
			entry("Status", Set.of("ANY")));

	/** Pairs that legitimately agree in one direction only. */
	private static final Map<String, Agreement> ONE_DIRECTIONAL = Map.ofEntries(
			// EMAIL / WEBHOOK / SENTINEL are notification-only destinations, exposed through
			// NotificationChannelTypeEnum instead; every schema value must still be real Java.
			entry("IntegrationType", Agreement.SCHEMA_SUBSET_OF_JAVA));

	/**
	 * Real drift awaiting a decision, pinned EXACTLY: fixing it, or drifting further, fails until
	 * the entry is updated or removed. Empty: every drift found when this guard was introduced
	 * has been decided (AggregationType, OAuthType, Status).
	 */
	private static final Map<String, KnownDrift> KNOWN_DRIFT = Map.of();

	private static Map<String, Set<String>> schemaEnums;
	private static Map<String, List<Class<?>>> javaEnumsBySimpleName;

	@BeforeAll
	static void load() throws IOException {
		schemaEnums = readSchemaEnums();
		javaEnumsBySimpleName = scanJavaEnums();
	}

	@Test
	void everySchemaEnumMatchesItsJavaEnum() {
		List<String> problems = new ArrayList<>();
		int compared = 0;
		for (Map.Entry<String, Set<String>> e : schemaEnums.entrySet()) {
			String name = e.getKey();
			Set<String> schemaValues = e.getValue();
			if (NO_JAVA_COUNTERPART.containsKey(name)) continue;
			List<Class<?>> candidates;
			if (PRO_ONLY_OVERRIDES.containsKey(name)) {
				Class<?> proTarget = loadProOnly(PRO_ONLY_OVERRIDES.get(name));
				if (null == proTarget) {
					if (!LicensingConstants.isOssEdition()) {
						problems.add(name + ": PRO_ONLY override target " + PRO_ONLY_OVERRIDES.get(name)
								+ " does not exist; update or remove the entry");
					}
					continue;
				}
				candidates = List.of(proTarget);
			} else {
				candidates = resolve(name);
			}
			if (candidates.isEmpty()) {
				// CE mirrors the full schema but not the saas/ Java behind Pro-only types, so an
				// unresolved enum is expected there. The Pro run, on the same schema, is the gate.
				if (LicensingConstants.isOssEdition()) continue;
				problems.add(name + ": no Java enum found (tried " + name
						+ (name.endsWith(ENUM_SUFFIX) ? " and " + stripSuffix(name) : "")
						+ "); map it in OVERRIDES or allowlist it in NO_JAVA_COUNTERPART");
				continue;
			}
			if (candidates.size() > 1) {
				problems.add(name + ": ambiguous, Java enums " + candidates.stream().map(Class::getName)
						.toList() + "; pick one in OVERRIDES");
				continue;
			}
			compared++;
			Class<?> javaEnum = candidates.get(0);
			Set<String> javaValues = valuesOf(javaEnum);
			Set<String> javaOnly = minus(javaValues, schemaValues);
			Set<String> schemaOnly = minus(schemaValues, javaValues);
			KnownDrift known = KNOWN_DRIFT.get(name);
			if (null != known) {
				if (!known.javaOnly().equals(javaOnly) || !known.schemaOnly().equals(schemaOnly)) {
					problems.add(describe(name, javaEnum, javaOnly, schemaOnly)
							+ " -- differs from the pinned KNOWN_DRIFT entry (java-only "
							+ new TreeSet<>(known.javaOnly()) + ", schema-only "
							+ new TreeSet<>(known.schemaOnly()) + ": " + known.reason() + "); update or remove it");
				}
				continue;
			}
			Set<String> internal = INTERNAL_JAVA_VALUES.getOrDefault(name, Set.of());
			if (!javaOnly.containsAll(internal)) {
				problems.add(name + ": INTERNAL_JAVA_VALUES lists " + minus(internal, javaOnly)
						+ " but they are no longer Java-only; update the entry");
			}
			javaOnly = minus(javaOnly, internal);
			Agreement agreement = ONE_DIRECTIONAL.getOrDefault(name, Agreement.EXACT);
			if (Agreement.SCHEMA_SUBSET_OF_JAVA == agreement) javaOnly = Set.of();
			if (!javaOnly.isEmpty() || !schemaOnly.isEmpty()) {
				problems.add(describe(name, javaEnum, javaOnly, schemaOnly));
			}
		}
		assertTrue(compared > 0, "compared no enums at all -- the classpath scan found nothing");
		if (!problems.isEmpty()) {
			fail(problems.size() + " GraphQL/Java enum problem(s) across " + schemaEnums.size()
					+ " schema enums. A Java-only value breaks the whole query that reads it; a"
					+ " schema-only value breaks every request that sends it.\n  "
					+ String.join("\n  ", problems));
		}
	}

	/**
	 * The allowlists are only trustworthy while every key still names a schema enum and no
	 * schema enum is in two of them at once.
	 */
	@Test
	void allowlistsAreNotStale() {
		List<String> problems = new ArrayList<>();
		// Both editions carry the same schema files, so a missing key is stale in either.
		for (String key : allListKeys()) {
			if (!schemaEnums.containsKey(key)) problems.add(key + " is listed but is not a schema enum");
		}
		for (String key : NO_JAVA_COUNTERPART.keySet()) {
			boolean resolves = !resolve(key).isEmpty();
			if (resolves && !UNRELATED_NAMESAKES.contains(key)) {
				problems.add(key + " is in NO_JAVA_COUNTERPART but now resolves to " + resolve(key)
						+ "; compare it (remove the entry, or OVERRIDES / KNOWN_DRIFT)");
			}
			if (!resolves && UNRELATED_NAMESAKES.contains(key)) {
				problems.add(key + " is in UNRELATED_NAMESAKES but resolves to nothing; drop it there");
			}
		}
		for (String key : PRO_ONLY_OVERRIDES.keySet()) {
			if (OVERRIDES.containsKey(key)) problems.add(key + " is in both OVERRIDES and PRO_ONLY_OVERRIDES");
		}
		for (String key : NO_JAVA_COUNTERPART.keySet()) {
			if (OVERRIDES.containsKey(key) || PRO_ONLY_OVERRIDES.containsKey(key)
					|| ONE_DIRECTIONAL.containsKey(key) || KNOWN_DRIFT.containsKey(key)
					|| INTERNAL_JAVA_VALUES.containsKey(key)) {
				problems.add(key + " is in NO_JAVA_COUNTERPART and also mapped/compared");
			}
		}
		for (String key : KNOWN_DRIFT.keySet()) {
			if (ONE_DIRECTIONAL.containsKey(key)) problems.add(key + " is in both KNOWN_DRIFT and ONE_DIRECTIONAL");
		}
		for (Map.Entry<String, Class<?>> e : OVERRIDES.entrySet()) {
			if (!e.getValue().isEnum()) problems.add(e.getKey() + " overrides to non-enum " + e.getValue().getName());
		}
		if (!problems.isEmpty()) fail(String.join("\n", problems));
	}

	/**
	 * Kept from the retired IntegrationTypeSchemaEnumSyncTest. The subset rule above allows Java
	 * values the schema hides, so it cannot catch the KEV sources going missing -- yet
	 * enableKevSource / disableKevSource take them as input and configuredBaseIntegrations
	 * returns them (every org has a CISA_KEV row since V54).
	 */
	@Test
	void kevSourceTypesAreDeclaredInSchema() {
		Set<String> schemaValues = schemaEnums.getOrDefault("IntegrationType", Set.of());
		for (IntegrationType kev : List.of(IntegrationType.CISA_KEV, IntegrationType.VULNCHECK_KEV)) {
			assertTrue(schemaValues.contains(kev.name()), "GraphQL enum IntegrationType is missing "
					+ kev.name() + " -- enableKevSource input and configuredBaseIntegrations output"
					+ " will fail enum validation");
		}
	}

	private static List<Class<?>> resolve(String schemaName) {
		Class<?> override = OVERRIDES.get(schemaName);
		if (null != override) return List.of(override);
		List<Class<?>> exact = javaEnumsBySimpleName.getOrDefault(schemaName, List.of());
		if (!exact.isEmpty() || !schemaName.endsWith(ENUM_SUFFIX)) return exact;
		return javaEnumsBySimpleName.getOrDefault(stripSuffix(schemaName), List.of());
	}

	/** The named enum, or null when this edition does not ship it. */
	private static Class<?> loadProOnly(String className) {
		try {
			Class<?> c = Class.forName(className, false, GraphQlSchemaEnumSyncTest.class.getClassLoader());
			return c.isEnum() ? c : null;
		} catch (ClassNotFoundException e) {
			return null;
		}
	}

	private static String stripSuffix(String name) {
		return name.substring(0, name.length() - ENUM_SUFFIX.length());
	}

	private static Set<String> allListKeys() {
		Set<String> keys = new TreeSet<>(OVERRIDES.keySet());
		keys.addAll(PRO_ONLY_OVERRIDES.keySet());
		keys.addAll(NO_JAVA_COUNTERPART.keySet());
		keys.addAll(ONE_DIRECTIONAL.keySet());
		keys.addAll(INTERNAL_JAVA_VALUES.keySet());
		keys.addAll(KNOWN_DRIFT.keySet());
		return keys;
	}

	private static String describe(String name, Class<?> javaEnum, Set<String> javaOnly, Set<String> schemaOnly) {
		return name + " vs " + javaEnum.getName() + ": java-only " + javaOnly + ", schema-only " + schemaOnly;
	}

	private static Set<String> valuesOf(Class<?> javaEnum) {
		return Arrays.stream(javaEnum.getEnumConstants()).map(c -> ((Enum<?>) c).name())
				.collect(Collectors.toCollection(TreeSet::new));
	}

	private static Set<String> minus(Set<String> a, Set<String> b) {
		Set<String> r = new TreeSet<>(a);
		r.removeAll(b);
		return r;
	}

	private static Map<String, Set<String>> readSchemaEnums() throws IOException {
		ParserOptions options = ParserOptions.newParserOptions()
				.maxTokens(MAX_TOKENS)
				.maxWhitespaceTokens(Integer.MAX_VALUE)
				.maxCharacters(Integer.MAX_VALUE)
				.build();
		Resource[] files = new PathMatchingResourcePatternResolver().getResources(SCHEMA_PATTERN);
		assertTrue(files.length > 0, "no schema files matched " + SCHEMA_PATTERN);
		TypeDefinitionRegistry merged = new TypeDefinitionRegistry();
		for (Resource file : files) {
			// The application's own schema only, like the Java scan: not test resources.
			if (file.getURL().toString().contains(TEST_CLASSES_DIR)) continue;
			try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
				merged.merge(new SchemaParser().parse(reader, options));
			}
		}
		Map<String, Set<String>> out = new TreeMap<>();
		for (EnumTypeDefinition def : merged.getTypes(EnumTypeDefinition.class)) {
			addValues(out, def.getName(), def.getEnumValueDefinitions());
		}
		for (List<EnumTypeExtensionDefinition> extensions : merged.enumTypeExtensions().values()) {
			for (EnumTypeExtensionDefinition ext : extensions) {
				addValues(out, ext.getName(), ext.getEnumValueDefinitions());
			}
		}
		return out;
	}

	private static void addValues(Map<String, Set<String>> out, String name, List<EnumValueDefinition> defs) {
		Set<String> values = out.computeIfAbsent(name, k -> new TreeSet<>());
		for (EnumValueDefinition v : defs) values.add(v.getName());
	}

	/**
	 * Reads class metadata (ASM, no class init) and only loads the enums. Enums compiled from
	 * src/test are skipped -- they are fixtures, not wire types, and would only add ambiguity.
	 */
	private static Map<String, List<Class<?>>> scanJavaEnums() throws IOException {
		PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
		CachingMetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
		ClassLoader loader = GraphQlSchemaEnumSyncTest.class.getClassLoader();
		URL testClasses = location(GraphQlSchemaEnumSyncTest.class);
		Map<String, List<Class<?>>> out = new TreeMap<>();
		for (Resource r : resolver.getResources(CLASS_PATTERN)) {
			ClassMetadata md = readers.getMetadataReader(r).getClassMetadata();
			if (!Enum.class.getName().equals(md.getSuperClassName())) continue;
			Class<?> c;
			try {
				c = Class.forName(md.getClassName(), false, loader);
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException("scanned but could not load " + md.getClassName(), e);
			} catch (LinkageError e) {
				// Needs a class this classpath lacks, so it cannot be compared. If it is the
				// counterpart of a schema enum, that enum reports as unresolved, so nothing hides.
				continue;
			}
			if (Objects.equals(testClasses, location(c))) continue;
			out.computeIfAbsent(c.getSimpleName(), k -> new ArrayList<>()).add(c);
		}
		return out;
	}

	private static URL location(Class<?> c) {
		CodeSource source = c.getProtectionDomain().getCodeSource();
		return null == source ? null : source.getLocation();
	}
}
