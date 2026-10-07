/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.service.AgentTaskService;

/**
 * Every field on an agent board type has something behind it.
 *
 * <p>DGS resolves a field by asking the returned object for it, so a field declared in the schema
 * with no accessor on the object that answers it does not fail anywhere: it resolves to null, for
 * every caller, forever. That is how {@code roleUuid} came to be declared on
 * {@code AgentTaskAssignment} -- the poll result, backed by a record that had no such component --
 * while the stored assignment that actually carried the value had no field at all. Two types, both
 * reachable, both parsing, and the value unreachable in one direction and absent in the other.
 *
 * <p>{@code SchemaParsesTest.everyDefinedTypeIsReachable} cannot see this. The types are reachable;
 * it is the fields inside them that answer with nothing.
 *
 * <p>The map below is explicit because DGS resolution is not statically knowable -- a field is
 * backed by whatever object the datafetcher happened to return. An explicit map goes stale, so it
 * covers the boards surface where this class of mistake keeps happening rather than pretending to
 * be exhaustive.
 */
class AgentSchemaFieldsAreBackedTest {

	/** GraphQL type -> the Java type DGS resolves its fields against. */
	private static final Map<String, Class<?>> BACKED_BY = backedByMap();

	private static Map<String, Class<?>> backedByMap() {
		Map<String, Class<?>> m = new LinkedHashMap<>();
		m.put("AgentTaskAssignment", AgentTaskService.WorkerAssignment.class);
		m.put("AgentTaskWorkAssignment", AgentTaskData.TaskAssignment.class);
		m.put("AgentTaskSignOff", AgentTaskData.SignOff.class);
		m.put("AgentTaskReturn", AgentTaskData.TaskReturn.class);
		m.put("AgentTaskStatusChange", AgentTaskData.StatusChange.class);
		m.put("AgentTaskHold", AgentTaskData.TaskHold.class);
		m.put("AgentActor", AgentActor.class);
		m.put("AgentBoardPause", AgentBoardData.BoardPause.class);
		m.put("AgentBoardEvent", AgentBoardData.BoardEvent.class);
		// The export spec. producesOutputs was declared on BoardRoleSpec and the DTO had no such
		// field, so it answered null for every caller -- the same shape of gap as roleUuid, one
		// layer out.
		m.put("BoardRoleSpec", io.reliza.service.AgentBoardService.BoardRoleSpecDto.class);
		m.put("BoardRequiredInputSpec",
				io.reliza.service.AgentBoardService.BoardRequiredInputSpecDto.class);
		m.put("BoardProducedOutputSpec",
				io.reliza.service.AgentBoardService.BoardProducedOutputSpecDto.class);
		m.put("ProviderSession", io.reliza.model.AgentSessionData.ProviderSession.class);
		// SessionOrigin resolves to the redacted view, never the stored record, so a field added to
		// one and not the other shows up here.
		m.put("SessionOrigin", io.reliza.service.AgentSessionOriginService.SessionOriginView.class);
		m.put("SessionDevice", io.reliza.model.AgentSessionData.SessionDevice.class);
		m.put("SessionFederation", io.reliza.model.AgentSessionData.SessionFederation.class);
		m.put("ModelRoleStrength", io.reliza.model.ModelOntologyData.RoleStrength.class);
		m.put("RoleModelStrength", io.reliza.model.AgentTaskRoleConfigData.ModelStrength.class);
		m.put("BoardReviewItem", io.reliza.model.BoardReviewItemIndex.BoardReviewItem.class);
		m.put("BoardSpec", io.reliza.service.AgentBoardService.BoardSpecDto.class);
		m.put("BoardSettingsSpec", io.reliza.service.AgentBoardService.BoardSettingsSpecDto.class);
		m.put("BoardStrengthSpec", io.reliza.service.AgentBoardService.BoardStrengthSpecDto.class);
		m.put("BoardModelStrengthSpec", io.reliza.service.AgentBoardService.BoardModelStrengthSpecDto.class);
		m.put("RolePresetsSpec", io.reliza.service.AgentBoardService.RolePresetsSpecDto.class);
		m.put("AgentTaskReviewedPrerequisite", AgentTaskDataFetcher.ReviewedInput.class);
		m.put("AgentTaskRefusedPromotion", AgentTaskDataFetcher.RefusedPromotion.class);
		return m;
	}

	/**
	 * Fields whose value is computed by a datafetcher rather than read off the object, so the
	 * backing type has no accessor by design. Each needs the resolver it names to exist.
	 */
	private static final Map<String, String> RESOLVED_ELSEWHERE = Map.of(
			"AgentTaskAssignment.task", "the task is the record's own component, renamed in schema",
			"AgentTaskSignOff.usage", "HopUsage is a snapshot record on the sign-off",
			"BoardReviewItem.decidedAt", "AgentTaskDataFetcher.reviewItemDecidedAt, from decidedIn's release",
			"BoardReviewItem.element", "AgentTaskDataFetcher.reviewItemElement, from location.element and the round's about release",
			"AgentTaskSignOff.overAllowanceMicros", "AgentTaskDataFetcher.signOffOverAllowance, computed from usage",
			"AgentTaskReturn.overAllowanceMicros", "AgentTaskDataFetcher.returnOverAllowance, computed from usage",
			"AgentTaskSignOff.reviewedInputs", "AgentTaskDataFetcher.signOffReviewedInputs, the stored uuids resolved per read",
			"AgentTaskSignOff.refusedPromotions", "AgentTaskDataFetcher.signOffRefusedPromotions, from the sign-off's promotions");

	@Test
	void everyDeclaredFieldHasAnAccessor() {
		String schema = readSchema();
		List<String> unbacked = new ArrayList<>();
		for (Map.Entry<String, Class<?>> e : BACKED_BY.entrySet()) {
			for (String field : fieldsOf(schema, e.getKey())) {
				String qualified = e.getKey() + "." + field;
				if (RESOLVED_ELSEWHERE.containsKey(qualified)) continue;
				if (!hasAccessor(e.getValue(), field)) {
					unbacked.add(qualified + " -> " + e.getValue().getSimpleName()
							+ " has no accessor for it");
				}
			}
		}
		if (!unbacked.isEmpty()) {
			fail("Declared in the schema, answered by nothing:\n  " + String.join("\n  ", unbacked)
					+ "\n\nA field DGS cannot resolve does not fail: it returns null to every caller."
					+ " Either add the accessor, or move the field to the type that can answer it.");
		}
	}

	/**
	 * The types whose fields are mostly computed by datafetchers, so an explicit exemption list
	 * would go stale at once: each field needs an accessor on the object or a {@code @DgsData}
	 * resolver for it on some data fetcher. {@code AgentBoard.targetDetails} and
	 * {@code AgentTask.producesComponentDetails} were declared with neither and read null for every
	 * row (task RD2-4); {@link #BACKED_BY} could not see them, since neither type was in the map.
	 */
	private static final Map<String, Class<?>> RESOLVER_BACKED = Map.of(
			"AgentBoard", AgentBoardData.class, "AgentTask", AgentTaskData.class,
			"Session", io.reliza.model.AgentSessionData.class, "Agent", io.reliza.model.AgentData.class);

	@Test
	void everyFieldOfAResolverBackedTypeHasAnAccessorOrAResolver() {
		String schema = readSchema();
		java.util.Set<String> resolved = dgsResolvedFields();
		List<String> unbacked = new ArrayList<>();
		for (Map.Entry<String, Class<?>> e : RESOLVER_BACKED.entrySet()) {
			for (String field : fieldsOf(schema, e.getKey())) {
				String qualified = e.getKey() + "." + field;
				if (!hasAccessor(e.getValue(), field) && !resolved.contains(qualified)) unbacked.add(qualified);
			}
		}
		assertTrue(resolved.contains("AgentBoard.documentSeries"), "the resolver scan finds nothing: " + resolved.size());
		if (!unbacked.isEmpty()) {
			fail("Declared in the schema, answered by nothing (no accessor, no @DgsData):\n  "
					+ String.join("\n  ", unbacked));
		}
	}

	/** Every parentType.field a {@code @DgsData} in io.reliza.ws resolves. */
	private static java.util.Set<String> dgsResolvedFields() {
		var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(
				com.netflix.graphql.dgs.DgsComponent.class));
		java.util.Set<String> out = new java.util.HashSet<>();
		for (var bd : scanner.findCandidateComponents("io.reliza.ws")) {
			Class<?> c;
			try {
				c = Class.forName(bd.getBeanClassName());
			} catch (ClassNotFoundException ex) {
				throw new IllegalStateException(ex);
			}
			for (Method m : c.getDeclaredMethods()) {
				for (com.netflix.graphql.dgs.DgsData d : m.getAnnotationsByType(com.netflix.graphql.dgs.DgsData.class)) {
					out.add(d.parentType() + "." + (d.field().isEmpty() ? m.getName() : d.field()));
				}
			}
		}
		return out;
	}

	@Test
	void theMapItselfIsHonest() {
		String schema = readSchema();
		for (String type : BACKED_BY.keySet()) {
			assertTrue(!fieldsOf(schema, type).isEmpty(),
					type + " is in the map but not in the schema -- the map has gone stale");
		}
	}

	/** Field names of a type block, ignoring docstrings and comments. */
	private static List<String> fieldsOf(String schema, String typeName) {
		List<String> out = new ArrayList<>();
		Matcher block = Pattern.compile("(?m)^type " + Pattern.quote(typeName) + " \\{(.*?)^\\}",
				Pattern.DOTALL).matcher(schema);
		if (!block.find()) return out;
		String body = block.group(1).replaceAll("(?s)\"\"\".*?\"\"\"", "").replaceAll("(?m)#.*$", "");
		Matcher field = Pattern.compile("(?m)^\\s*(\\w+)\\s*(\\([^)]*\\))?\\s*:").matcher(body);
		while (field.find()) out.add(field.group(1));
		return out;
	}

	/** A record component, a getter, or an is-getter. */
	private static boolean hasAccessor(Class<?> type, String field) {
		String capitalized = Character.toUpperCase(field.charAt(0)) + field.substring(1);
		for (Method m : type.getMethods()) {
			if (m.getParameterCount() != 0) continue;
			if (m.getName().equals(field) || m.getName().equals("get" + capitalized)
					|| m.getName().equals("is" + capitalized)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Every GraphQL enum names exactly the values its Java enum does.
	 *
	 * <p>Two gaps of this shape shipped before the map existed, and they fail in opposite
	 * directions. {@code SpecificationType} was missing {@code BOARD_QUESTIONS}, so the publish mutation
	 * refused the only value that would name a questions round -- an agent could not ask a
	 * question through the API at all. {@code AgentTaskHoldKind} was missing the same word, and
	 * that one broke OUTPUT: the moment a question was escalated the task carried a hold DGS
	 * could not serialise, so every query selecting {@code hold { kind }} came back with the
	 * field null and a SERIALIZATION_ERROR beside it -- and the answer form, which keys on that
	 * exact value, could never render.
	 *
	 * <p>Neither was visible to anything else here. The types are reachable and every field is
	 * backed; it is the VALUES inside the enums that had no parity check, and no integration test
	 * could see them because they build the Java enum and call the service directly. The enum is
	 * the one thing between those tests and a real caller.
	 *
	 * <p>Checked both ways, for every pair. A schema value Java cannot accept is always wrong. A
	 * Java value the schema cannot name is wrong too, for any enum the API both accepts and
	 * returns -- which is all of these.
	 */
	@Test
	public void everyGraphqlEnumMatchesItsJavaEnum() {
		String schema = readSchema();
		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, Class<? extends Enum<?>>> pair : ENUMS.entrySet()) {
			java.util.Set<String> inSchema = enumValues(schema, pair.getKey());
			if (inSchema.isEmpty()) {
				problems.add(pair.getKey() + " is in the map but not in the schema -- stale map");
				continue;
			}
			java.util.Set<String> inJava = new java.util.LinkedHashSet<>();
			for (Enum<?> v : pair.getValue().getEnumConstants()) inJava.add(v.name());

			java.util.Set<String> onlyJava = new java.util.LinkedHashSet<>(inJava);
			onlyJava.removeAll(inSchema);
			if (!onlyJava.isEmpty()) {
				problems.add(pair.getKey() + " cannot name " + onlyJava + ", which "
						+ pair.getValue().getSimpleName() + " has -- no caller can send or read them");
			}
			java.util.Set<String> onlySchema = new java.util.LinkedHashSet<>(inSchema);
			onlySchema.removeAll(inJava);
			if (!onlySchema.isEmpty()) {
				problems.add(pair.getKey() + " offers " + onlySchema + ", which "
						+ pair.getValue().getSimpleName() + " cannot accept");
			}
		}
		if (!problems.isEmpty()) fail(String.join("\n", problems));
	}

	/**
	 * GraphQL enum to the Java enum behind it.
	 *
	 * <p>Explicit, like {@link #BACKED_BY} above and for the same reason: the correspondence is a
	 * naming convention, not something the compiler knows. Adding a GraphQL enum without adding
	 * it here buys nothing, so the pair goes in when the enum does.
	 */
	private static final Map<String, Class<? extends Enum<?>>> ENUMS = enumMap();

	private static Map<String, Class<? extends Enum<?>>> enumMap() {
		Map<String, Class<? extends Enum<?>>> m = new LinkedHashMap<>();
		m.put("AgentTaskStatus", AgentTaskData.TaskStatus.class);
		m.put("AgentSignOffOutcome", AgentTaskData.SignOffOutcome.class);
		m.put("AgentTaskReturnReason", AgentTaskData.TaskReturnReason.class);
		m.put("AgentTaskHoldLevel", AgentTaskData.HoldLevel.class);
		m.put("AgentTaskHoldKind", AgentTaskData.HoldKind.class);
		m.put("AgentStatusTrigger", AgentTaskData.StatusTrigger.class);
		m.put("AgentBoardStatus", AgentBoardData.BoardStatus.class);
		m.put("AgentBoardPriorityType", AgentBoardData.PriorityType.class);
		m.put("AgentBoardPauseLevel", AgentBoardData.BoardPauseLevel.class);
		m.put("AgentBoardEventKind", AgentBoardData.BoardEventKind.class);
		m.put("AgentCapability", io.reliza.model.AgentTaskRoleConfigData.AgentCapability.class);
		m.put("AgentRoleKind", io.reliza.model.AgentTaskRoleConfigData.RoleKind.class);
		m.put("AgentRoleNecessity", io.reliza.model.AgentTaskRoleConfigData.RoleNecessity.class);
		m.put("AgentHumanGate", io.reliza.model.AgentTaskRoleConfigData.HumanGate.class);
		m.put("AgentInputKind", io.reliza.model.AgentTaskInput.InputKind.class);
		m.put("AgentInputScope", io.reliza.model.AgentTaskInput.InputScope.class);
		m.put("AgentInputResolution", io.reliza.model.AgentTaskInput.InputResolution.class);
		m.put("BoardReviewVerdict", io.reliza.model.BoardReviewItemIndex.BoardReviewVerdict.class);
		m.put("BoardReviewItemStatus", io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus.class);
		m.put("BoardReviewItemDecisionAction", io.reliza.service.AgentDocumentService.BoardReviewItemDecisionAction.class);
		m.put("SpecificationType", io.reliza.model.RearmSpecificationType.class);
		m.put("AgentActorKind", AgentActor.ActorKind.class);
		m.put("SessionStatus", io.reliza.model.AgentSessionData.SessionStatus.class);
		m.put("SessionAuthMethod", io.reliza.model.AgentSessionData.AuthMethod.class);
		m.put("SessionOwnerSource", io.reliza.model.AgentSessionData.OwnerSource.class);
		m.put("ModelRoleCategory", io.reliza.model.ModelOntologyData.RoleCategory.class);
		m.put("AgentStatus", io.reliza.model.AgentData.AgentStatus.class);
		m.put("AgentType", io.reliza.model.AgentData.AgentType.class);
		m.put("SessionUsageCompleteness", io.reliza.model.AgentSessionData.UsageCompleteness.class);
		m.put("SessionUsageHosting", io.reliza.model.SessionUsageHosting.class);
		m.put("SessionUsageSource", io.reliza.model.SessionUsageSource.class);
		m.put("SessionUsageAttribution", io.reliza.model.SessionUsageAttribution.class);
		m.put("ModelAssertionState", io.reliza.model.ModelAssertionState.class);
		m.put("ModelTier", io.reliza.model.ModelOntologyData.ModelTier.class);
		m.put("ModelResolution", io.reliza.model.ModelOntologyData.ModelResolution.class);
		m.put("ModelCardSpecVersion", io.reliza.model.ModelOntologyData.ModelCardSpecVersion.class);
		m.put("PricingUnit", io.reliza.model.PricingEntry.PricingUnit.class);
		m.put("ServiceTier", io.reliza.model.PricingEntry.PricingSelector.ServiceTier.class);
		m.put("ReasoningMatch", io.reliza.model.PricingEntry.PricingSelector.ReasoningMatch.class);
		m.put("Modality", io.reliza.model.ModelFacts.Modality.class);
		m.put("ModelHostingKind", io.reliza.model.ModelFacts.HostingKind.class);
		return m;
	}

	/**
	 * The bare values of a GraphQL enum.
	 *
	 * <p>Docstrings are stripped from the WHOLE schema before the block is located, not from the
	 * block afterwards. The block pattern stops at the first closing brace, so a docstring
	 * containing one would cut the enum short and the values past it would read as missing from
	 * the schema. Only {@code SignatureVerificationState} carries a brace in its prose today and
	 * it is not mapped, but the order costs nothing and removes the trap for whoever maps it.
	 */
	private static java.util.Set<String> enumValues(String schema, String name) {
		String stripped = schema.replaceAll("(?s)\"\"\".*?\"\"\"", "");
		Matcher m = Pattern.compile("enum " + Pattern.quote(name) + "\\s*\\{([^}]*)\\}")
				.matcher(stripped);
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		if (!m.find()) return out;
		for (String token : m.group(1).split("[\\s,]+")) {
			String t = token.trim();
			if (t.isEmpty() || t.startsWith("#")) continue;
			out.add(t);
		}
		return out;
	}

	private static String readSchema() {
		try (InputStream in = AgentSchemaFieldsAreBackedTest.class
				.getResourceAsStream("/schema/schema.graphqls")) {
			if (in == null) throw new IllegalStateException("schema.graphqls not on test classpath");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
	}
}
