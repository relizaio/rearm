/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.reliza.common.Utils;
import io.reliza.model.DeviceLifecycle;
import io.reliza.model.DeviceSupportRisk;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportData;
import io.reliza.model.SupportMilestoneFact;
import io.reliza.model.SupportMilestoneType;
import io.reliza.model.SupportState;
import io.reliza.model.SupportStatus;

/**
 * Pure, DB-free injector that weaves per-component support disclosure into a CycloneDX BOM
 * JSON tree at read time (FDA-Readiness-1 PR2a). Two entry points:
 *
 * <ul>
 *   <li>{@link #collectKeys} -- the identity key of every component node, so the caller
 *       (a service with repository access) can resolve them to {@code sbom_components} rows;</li>
 *   <li>{@link #inject} -- append the support properties onto each matched component node.</li>
 * </ul>
 *
 * <p>The split keeps this class pure: the caller does the encoding-aware two-pass match and
 * hands back a {@code Map} keyed by {@link #componentKey}, so PR6's frozen snapshot can reuse
 * the exact same injection with {@code asOf} pinned to a cutoff instead of now.
 *
 * <p>Mutates the tree in place via Jackson (append onto any existing {@code properties} array,
 * never overwrite; never re-serialize through the CycloneDX object model, which would rewrite
 * specVersion and drop unknown fields). NEVER emits the internal {@code notes} field, nor the
 * audit row's {@code reason}; {@code justification} IS emitted, because it is the stated basis
 * for a claim and a claim without its basis is worse than no claim.
 *
 * <p><b>Scope of the anti-spoofing guarantee.</b> {@link #inject} strips every
 * {@code reliza:support:*} and {@code reliza:device:*} property tree-wide before writing its
 * own, so on any egress that routes through it, such a property was provably written here.
 * That is every JSON BOM document this server hands out: the CycloneDX artifact download
 * (injected), and the SPDX-augmented download, the raw download and the JSON merged release
 * SBOM export (swept and marked, not yet injected).
 *
 * <p>The raw download is swept too, and is not the exception it briefly looked like. "Raw"
 * means AS INGESTED -- not enriched, not injected -- and never meant byte-identical: that
 * path is a Jackson round trip through rebom, so byte fidelity was never on offer and no
 * signature survives it. ReARM still serves the document under its own authority, and these
 * namespaces are reserved, so an uploader has no legitimate reason to put one there.
 *
 * <p>The CSV and EXCEL media types of the release export are the one remaining gap, and they
 * are rebom's renderers, not this class's: they read a fixed column list that does not include
 * component properties, so no forged property reaches them today. That is rebom's behaviour,
 * not a guarantee made here -- adding a properties column would reopen the hole silently, and
 * the taxonomy says so where a renderer author would look.
 *
 * <p>The guarantee was previously stated with no boundary at all while three egresses did not
 * strip -- a claim that outran the code, which is the worse half of the defect, because the
 * claim is what makes the properties credible. The structural fix, so that no stored BOM
 * carries a reserved property in the first place, is board task
 * {@code t20260905-155742-30573}; until then these sweeps are the only control.
 */
public final class SupportBomInjector {

	private SupportBomInjector() {}

	static final String PROP_STATUS = "reliza:support:status";
	static final String PROP_SOURCE_PREFIX = "reliza:support:source:";
	static final String PROP_LAST_ASSESSED_PREFIX = "reliza:support:lastAssessed:";
	static final String PROP_PARTY = "reliza:support:party";
	/** The manufacturer's ATTESTED level of support -- see {@link SupportData}. Never emitted
	 * without {@link #PROP_ASSESSED_AT} beside it. */
	static final String PROP_LEVEL = "reliza:support:levelOfSupport";
	/** When the human did the assessment (caller-supplied), NOT when the row was written. */
	static final String PROP_ASSESSED_AT = "reliza:support:assessedAt";
	/** The manufacturer's stated basis for the claim. See the emit site for why it ships. */
	static final String PROP_JUSTIFICATION = "reliza:support:justification";
	static final String PROP_EOGS = "cdx:lifecycle:milestone:endOfGuaranteedSupport";
	static final String PROP_EOS = "cdx:lifecycle:milestone:endOfSupport";
	static final String PROP_EOL = "cdx:lifecycle:milestone:endOfLife";
	static final String PROP_DEVICE_SUPPORT_RISK = "reliza:support:deviceSupportRisk";
	static final String PROP_DEVICE_EOS = "reliza:device:endOfSupport";
	static final String PROP_DEVICE_EOL = "reliza:device:endOfLife";
	static final String PROP_DISCLOSURE = "reliza:support:disclosure";
	static final String DISCLOSURE_CURRENT_STATE = "derived-non-attested-current-state";
	/**
	 * The marker for a document we swept but did not inject into. A distinct value because
	 * the two say different things and a reader acts on the difference: CURRENT_STATE means
	 * "the support properties here were produced by current-state derivation", so an absent
	 * property means we hold no fact for that component. Stamping that on an egress that
	 * injects nothing would turn "this document carries no disclosure" into "we looked and
	 * there is nothing to report" -- across every component at once, including ones we DO
	 * hold an end-of-support date for. Same reasoning as applyToComponent's: absence is not
	 * a benign "supported".
	 */
	static final String DISCLOSURE_STRIPPED_ONLY = "provenance-stripped-no-disclosure";

	/** Server-owned namespaces: any reliza:support:* / reliza:device:* an uploader baked into the
	 * BOM is stripped before we write our own, so their presence provably came from HERE. This
	 * also covers the per-milestone reliza:support:source: and reliza:support:lastAssessed:
	 * properties below -- only the CDX standard milestone keys live outside this namespace and
	 * need an explicit strip. */
	static final String RELIZA_SUPPORT_PREFIX = "reliza:support:";
	static final String RELIZA_DEVICE_PREFIX = "reliza:device:";
	/** Standard milestone keys we replace (never duplicate) on a component we attest. */
	private static final Set<String> MILESTONE_KEYS = Set.of(PROP_EOGS, PROP_EOS, PROP_EOL);

	/**
	 * Bundle of a component's identity row plus its stored attestation, keyed for the
	 * injector's {@code factsByKey}. {@code support} is null when the component resolved
	 * but was never assessed.
	 */
	public record ComponentSupportFacts(SbomComponent component, SupportData support) {

		/** Milestones asserted by this attestation; empty when none (or when unassessed). */
		public Map<SupportMilestoneType, SupportMilestoneFact> milestones() {
			return null == support ? Map.of() : support.milestones();
		}

		/**
		 * Most recent assessment instant across the attestation and its milestones, or null.
		 * Used only to break a tie between two encoding-variant rows for the same identity,
		 * so it must not throw on an unparseable stored value -- an unusable timestamp simply
		 * does not participate in the comparison.
		 */
		public ZonedDateTime mostRecentlyAssessed() {
			if (null == support) return null;
			return Stream.concat(
							Stream.of(support.assessedAt()),
							support.milestones().values().stream().map(SupportMilestoneFact::lastAssessed))
					.filter(Objects::nonNull)
					.map(SupportBomInjector::parseInstantOrNull)
					.filter(Objects::nonNull)
					.max(ZonedDateTime::compareTo)
					.orElse(null);
		}
	}

	/** Lenient parse of a stored RFC-3339 instant string; null when absent or malformed. */
	static ZonedDateTime parseInstantOrNull(String value) {
		if (null == value || value.isBlank()) return null;
		try {
			return ZonedDateTime.parse(value);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static String cdxMilestoneKey(SupportMilestoneType type) {
		return switch (type) {
			case END_OF_GUARANTEED_SUPPORT -> PROP_EOGS;
			case END_OF_SUPPORT -> PROP_EOS;
			case END_OF_LIFE -> PROP_EOL;
		};
	}

	private static String milestoneSuffix(SupportMilestoneType type) {
		return switch (type) {
			case END_OF_GUARANTEED_SUPPORT -> "endOfGuaranteedSupport";
			case END_OF_SUPPORT -> "endOfSupport";
			case END_OF_LIFE -> "endOfLife";
		};
	}

	/**
	 * Identity key of a component node: the preserving-canonical purl (byte-compatible with
	 * how rebom persists {@code sbom_components.canonical_purl}), else the raw cpe, else null
	 * (a component with neither cannot match a stored row).
	 */
	public static String componentKey(JsonNode component) {
		if (component == null || !component.isObject()) {
			return null;
		}
		JsonNode purl = component.get("purl");
		if (purl != null && purl.isTextual() && !purl.asText().isBlank()) {
			try {
				String canonical = Utils.canonicalizePurlPreservingEncoding(purl.asText());
				if (canonical != null) {
					return canonical;
				}
				// A non-pkg / unparseable purl yields null -- fall through to cpe.
			} catch (RuntimeException malformed) {
				// fall through to cpe
			}
		}
		JsonNode cpe = component.get("cpe");
		if (cpe != null && cpe.isTextual() && !cpe.asText().isBlank()) {
			return cpe.asText();
		}
		return null;
	}

	/**
	 * Every resolvable component identity key in the BOM. Recurses nested
	 * {@code components[].components[]} and includes {@code metadata.component} (the root
	 * self-component) -- the flat extraction loop used elsewhere would miss nested children.
	 */
	public static Set<String> collectKeys(JsonNode bom) {
		Set<String> keys = new LinkedHashSet<>();
		if (bom == null || !bom.isObject()) {
			return keys;
		}
		collectFrom(bom.get("components"), keys);
		JsonNode metadata = bom.get("metadata");
		if (metadata != null && metadata.isObject()) {
			JsonNode root = metadata.get("component");
			addKey(root, keys);
			if (root != null) {
				collectFrom(root.get("components"), keys);
			}
		}
		return keys;
	}

	private static void collectFrom(JsonNode components, Set<String> keys) {
		if (components == null || !components.isArray()) {
			return;
		}
		for (JsonNode c : components) {
			addKey(c, keys);
			collectFrom(c.get("components"), keys);
		}
	}

	private static void addKey(JsonNode component, Set<String> keys) {
		String key = componentKey(component);
		if (key != null) {
			keys.add(key);
		}
	}

	/**
	 * Append per-component support properties onto every matched component node, and a
	 * document-level disclosure marker on {@code metadata.properties} labelling this as the
	 * derived, non-attested, current-state view (not the frozen attested record). Mutates and
	 * returns the same tree. {@code factsByKey} is keyed by {@link #componentKey}; a component
	 * with no entry, or whose facts carry no milestones, gets nothing (absence is not a benign
	 * "supported"). {@code asOf} is the derivation clock (now for the live view).
	 * No device context: neither the device-support-risk verdict nor the device-anchor
	 * properties are emitted.
	 */
	public static JsonNode inject(JsonNode bom, Map<String, ComponentSupportFacts> factsByKey, LocalDate asOf) {
		return inject(bom, factsByKey, asOf, null);
	}

	/**
	 * As {@link #inject(JsonNode, Map, LocalDate)}, but for a PRODUCT (device) download also
	 * (a) stamps the discriminated {@code reliza:support:deviceSupportRisk} verdict
	 * ({@link DeviceSupportRisk}) on every assessed component, and (b) stamps the device's own
	 * support window ({@code reliza:device:endOfSupport}/{@code endOfLife}) on
	 * {@code metadata.component} so each per-component verdict is independently re-checkable from
	 * the file alone. {@code device} is null on a non-PRODUCT release or when the download has no
	 * unambiguous device release, in which case neither is emitted (the raw component milestone
	 * dates are still injected).
	 */
	public static JsonNode inject(JsonNode bom, Map<String, ComponentSupportFacts> factsByKey, LocalDate asOf,
			DeviceLifecycle device) {
		if (bom == null || !bom.isObject() || factsByKey == null) {
			return bom;
		}
		// reliza:support:* and reliza:device:* are server-owned namespaces. Strip EVERY occurrence
		// anywhere in the tree BEFORE we write our own -- not just the component array but pedigree,
		// services, metadata.tools.components, vulnerabilities, etc. -- so an uploader cannot spoof
		// our provenance on any document that passes through here. See the class javadoc for the
		// exact scope of that guarantee: it is not every served document.
		stripReservedEverywhere(bom);
		injectInto(bom.get("components"), factsByKey, asOf, device);
		// metadata.component is the root self-component (injected too if matched). The
		// document-level disclosure marker is ALWAYS stamped so the download is labelled
		// non-attested current-state -- creating metadata if the BOM somehow lacks it.
		JsonNode metadata = bom.get("metadata");
		ObjectNode metaObj;
		if (metadata != null && metadata.isObject()) {
			metaObj = (ObjectNode) metadata;
			JsonNode root = metaObj.get("component");
			if (root != null && root.isObject()) {
				applyToComponent((ObjectNode) root, factsByKey, asOf, device);
				injectInto(root.get("components"), factsByKey, asOf, device);
			}
		} else {
			metaObj = ((ObjectNode) bom).putObject("metadata");
		}
		// Stamp the device's own support window on metadata.component (the device self-component)
		// so a reader can recompute every deviceSupportRisk verdict from the file alone.
		if (device != null && device.declaresAnyDate()) {
			JsonNode root = metaObj.get("component");
			if (root != null && root.isObject()) {
				ArrayNode rootProps = properties((ObjectNode) root);
				if (device.endOfSupport() != null) {
					addProperty(rootProps, PROP_DEVICE_EOS, device.endOfSupport().toString());
				}
				if (device.endOfLife() != null) {
					addProperty(rootProps, PROP_DEVICE_EOL, device.endOfLife().toString());
				}
			}
		}
		// Exactly one disclosure marker (the global sweep already removed any forged one).
		addProperty(properties(metaObj), PROP_DISCLOSURE, DISCLOSURE_CURRENT_STATE);
		return bom;
	}

	/**
	 * Strip the server-owned namespaces and mark the document as swept, injecting NOTHING.
	 * For egresses that must not carry an uploader's forged support claim but do not (yet)
	 * carry ours either: the merged release SBOM export and the SPDX-augmented download.
	 *
	 * <p>Marked with {@link #DISCLOSURE_STRIPPED_ONLY}, never the current-state value -- see
	 * that constant for why the distinction is load-bearing rather than cosmetic.
	 */
	public static JsonNode stripOnly(JsonNode bom) {
		if (bom == null || !bom.isObject()) {
			return bom;
		}
		stripReservedEverywhere(bom);
		JsonNode metadata = bom.get("metadata");
		ObjectNode metaObj = (metadata != null && metadata.isObject())
				? (ObjectNode) metadata
				: ((ObjectNode) bom).putObject("metadata");
		addProperty(properties(metaObj), PROP_DISCLOSURE, DISCLOSURE_STRIPPED_ONLY);
		return bom;
	}

	/**
	 * Recursively remove every reliza:support:* and reliza:device:* property from every node's
	 * {@code properties} array, everywhere in the tree -- the server-owned-namespace guarantee must
	 * hold document wide, not just on the primary component surface.
	 */
	private static void stripReservedEverywhere(JsonNode node) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			stripByPrefix((ObjectNode) node, RELIZA_SUPPORT_PREFIX);
			stripByPrefix((ObjectNode) node, RELIZA_DEVICE_PREFIX);
		}
		// Iterating a value node yields nothing, so this safely recurses only containers.
		for (JsonNode child : node) {
			stripReservedEverywhere(child);
		}
	}

	private static void injectInto(JsonNode components, Map<String, ComponentSupportFacts> factsByKey, LocalDate asOf,
			DeviceLifecycle device) {
		if (components == null || !components.isArray()) {
			return;
		}
		for (JsonNode c : components) {
			if (c.isObject()) {
				applyToComponent((ObjectNode) c, factsByKey, asOf, device);
			}
			injectInto(c.get("components"), factsByKey, asOf, device);
		}
	}

	private static void applyToComponent(ObjectNode component, Map<String, ComponentSupportFacts> factsByKey, LocalDate asOf,
			DeviceLifecycle device) {
		// reliza:support:* was already stripped tree-wide by stripReservedEverywhere; here we
		// only (re)write on a component we actually attest.
		String key = componentKey(component);
		ComponentSupportFacts facts = key == null ? null : factsByKey.get(key);
		if (facts == null || facts.support() == null) {
			return;
		}
		SupportData support = facts.support();
		// A WITHDRAWN attestation is retracted: it must not be re-emitted as a live claim.
		// The row and its history stay on record -- withdrawal supersedes, it does not erase --
		// but the served BOM says nothing, which reads as "not assessed". That is the honest
		// degradation: the alternative is publishing a claim the manufacturer has taken back.
		if (SupportState.WITHDRAWN == support.state()) {
			return;
		}
		// Emit for an attestation carrying NO dates and NO level -- "assessed, nothing
		// published" -- which the previous shape could not even store. The row's existence
		// with an assessment instant IS the diligence record, and it is the case this whole
		// redesign exists for.
		//
		// Keep this in step with countAttestedNonRootByOrg. An earlier revision required a
		// milestone or a level here while the coverage query counted any MANUAL row, so the
		// flagship case was counted as covered and then emitted into nothing: the readiness
		// gauge reported coverage the served BOM did not contain -- the same gauge-vs-artifact
		// divergence the WITHDRAWN exclusion was added to prevent, through another door.
		//
		// They agree TODAY, and only because MANUAL is the sole writer. This condition is not
		// the mirror of that query and must not be read as one: it ignores assessmentSource
		// entirely, while coverage deliberately counts MANUAL only. The first SUPPLIER or
		// ENRICHED writer diverges immediately and in the opposite direction -- emitted but
		// not counted -- which is arguably correct (a machine-sourced fact is a disclosure but
		// not a manufacturer attestation) and must be decided deliberately rather than
		// discovered.
		boolean hasAnythingToSay = !support.milestones().isEmpty()
				|| support.levelOfSupport() != null
				|| support.assessedAt() != null;
		if (!hasAnythingToSay) {
			return;
		}
		SupportMilestoneFact eogsM = support.milestones().get(SupportMilestoneType.END_OF_GUARANTEED_SUPPORT);
		SupportMilestoneFact eosM = support.milestones().get(SupportMilestoneType.END_OF_SUPPORT);
		SupportMilestoneFact eolM = support.milestones().get(SupportMilestoneType.END_OF_LIFE);
		LocalDate eogs = eogsM == null ? null : eogsM.dateValue();
		LocalDate eos = eosM == null ? null : eosM.dateValue();
		// Dates only: derive() no longer takes a source. The reserved ABANDONED-inference
		// parameter was withdrawn with D5 -- a machine inferring abandonment about a named
		// third party and publishing it under the manufacturer's name is what section 3
		// forbids. End-of-life is absent too: since D5 it means end of SALE.
		SupportStatus status = SupportStatus.derive(eogs, eos, asOf);
		// We own the standard milestone keys for a component we attest: replace any existing
		// (incl. an upstream-supplied one) so there is never a duplicate/conflict. Provenance is
		// ALWAYS co-emitted per milestone as reliza:support:source:<milestone>, independently of
		// the other milestones on the same component -- this is what makes "EOS from a supplier
		// BOM, EOL manually asserted" representable.
		stripByNames(component, MILESTONE_KEYS);
		ArrayNode props = properties(component);
		emitMilestone(props, SupportMilestoneType.END_OF_GUARANTEED_SUPPORT, eogsM);
		emitMilestone(props, SupportMilestoneType.END_OF_SUPPORT, eosM);
		emitMilestone(props, SupportMilestoneType.END_OF_LIFE, eolM);
		addProperty(props, PROP_STATUS, status.name());
		// The ATTESTED level is emitted ALONGSIDE the derived status, never instead of it, and
		// the two are deliberately NOT reconciled here. A maintainer can publish a far-future
		// end-of-life and have abandoned the library in practice; when the human says so and
		// the dates disagree, a reader must be able to see both and judge. Collapsing them in
		// code would reintroduce, in the opposite direction, the defect this replaces.
		//
		// Never emit a level bare: assessedAt goes with it, so the claim carries its own date
		// and a reader can weigh its age. An undated claim cannot be weighed at all.
		// The VALUE is FDA's phrase verbatim (LevelOfSupport.getWireValue), not the constant
		// name: a consumer checking against the guidance's words -- which CycloneDX taxonomy
		// PR #186 says to use verbatim -- must match, and an enum name would not.
		//
		// Attribution is deliberately NOT emitted here: the attester is an internal user uuid,
		// which is meaningless to a BOM consumer and a needless disclosure in a customer-facing
		// file. Named attribution belongs in the attestation history and the labeling document.
		if (support.levelOfSupport() != null && support.assessedAt() != null) {
			addProperty(props, PROP_LEVEL, support.levelOfSupport().getWireValue());
			addProperty(props, PROP_ASSESSED_AT, support.assessedAt());
		} else if (support.assessedAt() != null) {
			// Assessed, with no level and possibly no dates. The instant alone is the
			// disclosure: it says a human looked and when, which is exactly what the
			// manufacturer is claiming. Without this the row is countable and invisible.
			addProperty(props, PROP_ASSESSED_AT, support.assessedAt());
		}
		// THE BASIS SHIPS WITH THE CLAIM. FDA L1021-1022 asks for a justification when the
		// information cannot be provided, and NO_LONGER_MAINTAINED / ABANDONED are factual
		// assertions about a named third party's project that the write path refuses to
		// record without one. Exporting the claim while keeping its basis internal would
		// publish the accusation and withhold the evidence -- and for the commonest case
		// here, "assessed, upstream publishes nothing", the basis IS the entire disclosure.
		//
		// This is `justification`, never the audit row's `reason` (why an edit happened) and
		// never `notes` (internal). They are separate fields precisely so a milestone removal
		// note can never surface here as the stated reason a project was called abandoned.
		if (support.justification() != null && !support.justification().isBlank()) {
			addProperty(props, PROP_JUSTIFICATION, support.justification());
		}
		if (support.party() != null) {
			addProperty(props, PROP_PARTY, support.party().name());
		}
		// Device-support risk (the Feb-2026 FDA signal): emit the DISCRIMINATED verdict on every
		// ASSESSED component (OK / EOS_BEFORE_DEVICE) so absence means only
		// "not assessed", never a false-benign read. Same derive() as the UI/API surface, so the
		// file and the app can never disagree. UNKNOWN (no device horizon here) emits nothing.
		// The device axis compares END-OF-SUPPORT only. EOGS is out of its scope, and since D5
		// end-of-life is out too: it means end of sale, so a component leaving the price list
		// before the device horizon carries no support risk to disclose.
		DeviceSupportRisk deviceSupportRisk = DeviceSupportRisk.derive(eos, device);
		if (deviceSupportRisk != DeviceSupportRisk.UNKNOWN) {
			addProperty(props, PROP_DEVICE_SUPPORT_RISK, deviceSupportRisk.name());
		}
		// notes is internal-only and is DELIBERATELY never emitted, for any milestone.
	}

	/** Emit one milestone's CDX date plus its own per-milestone source/lastAssessed provenance. */
	private static void emitMilestone(ArrayNode props, SupportMilestoneType type, SupportMilestoneFact milestone) {
		if (milestone == null || milestone.dateValue() == null) {
			return;
		}
		String suffix = milestoneSuffix(type);
		addProperty(props, cdxMilestoneKey(type), milestone.dateValue().toString());
		if (milestone.source() != null) {
			addProperty(props, PROP_SOURCE_PREFIX + suffix, milestone.source().name());
		}
		if (milestone.lastAssessed() != null) {
			addProperty(props, PROP_LAST_ASSESSED_PREFIX + suffix, milestone.lastAssessed());
		}
	}

	/** Remove every property whose name starts with {@code prefix} from a node's array. */
	private static void stripByPrefix(ObjectNode node, String prefix) {
		JsonNode props = node.get("properties");
		if (props == null || !props.isArray()) {
			return;
		}
		ArrayNode arr = (ArrayNode) props;
		for (int i = arr.size() - 1; i >= 0; i--) {
			JsonNode name = arr.get(i).get("name");
			if (name != null && name.isTextual() && name.asText().startsWith(prefix)) {
				arr.remove(i);
			}
		}
	}

	/** Remove every property whose name is in {@code names} from a node's array. */
	private static void stripByNames(ObjectNode node, Set<String> names) {
		JsonNode props = node.get("properties");
		if (props == null || !props.isArray()) {
			return;
		}
		ArrayNode arr = (ArrayNode) props;
		for (int i = arr.size() - 1; i >= 0; i--) {
			JsonNode name = arr.get(i).get("name");
			if (name != null && name.isTextual() && names.contains(name.asText())) {
				arr.remove(i);
			}
		}
	}

	private static ArrayNode properties(ObjectNode node) {
		JsonNode existing = node.get("properties");
		if (existing != null && existing.isArray()) {
			return (ArrayNode) existing;
		}
		return node.putArray("properties");
	}

	private static void addProperty(ArrayNode props, String name, String value) {
		ObjectNode prop = Utils.OM.createObjectNode();
		prop.put("name", name);
		prop.put("value", value);
		props.add(prop);
	}
}
