/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.reliza.common.Utils;
import io.reliza.model.ArtifactData.SpecVersion;
import io.reliza.model.DeviceLifecycle;
import io.reliza.model.DeviceSupportRisk;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportData;
import io.reliza.model.SupportMilestoneFact;
import io.reliza.model.SupportMilestoneType;
import io.reliza.model.SupportParty;
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
 * That is every document this server PRODUCES: the CycloneDX artifact download, the
 * SPDX-augmented download (the converted CycloneDX form) and the JSON merged release SBOM
 * export -- each swept and marked always, and injected when the org enables it.
 *
 * <p>The raw download is outside it, deliberately. Raw means AS UPLOADED: it serves the
 * uploader's own document and makes no claim about its contents, so nothing is stripped from
 * it and no marker is stamped on it. A reserved-namespace property found there is the
 * uploader's, and TEA labels that URL "Raw Artifact as Uploaded" so a reader knows which kind
 * of document they hold. (Decided 2026-09-11, reversing a sweep briefly added there.)
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
	/**
	 * The document-level attestation block (CycloneDX 1.6+), server-owned exactly like the
	 * reliza namespaces above -- and NOT reachable by the property sweep, which walks property
	 * KEYS. An uploader-forged block here would otherwise be served back under our attribution.
	 */
	static final String NODE_DECLARATIONS = "declarations";
	private static final String FIELD_BOM_REF = "bom-ref";
	/**
	 * Prefix for a {@code bom-ref} THIS CLASS assigned to a component that arrived without one,
	 * so a claim has something to target. Reserved like the property namespaces, and load-bearing
	 * for two reasons beyond tidiness.
	 *
	 * <p><b>It cannot silently resolve someone else's dangling edge.</b> Assigning the bare
	 * component key would mean a document whose {@code dependencies[].dependsOn} named a purl
	 * that no component carried as a {@code bom-ref} suddenly had that edge resolve -- a
	 * support-disclosure feature quietly changing the dependency-graph semantics of a served
	 * BOM it did not author. A prefixed ref matches nothing that was already there.
	 *
	 * <p><b>It is removable.</b> {@link #stripAssignedBomRefs} takes them back out, so a caller
	 * that injects and then strips (the VDR enrichment path) is left with the document it would
	 * have had, rather than with our scaffolding embedded in it.
	 */
	static final String ASSIGNED_REF_PREFIX = "reliza:bomref:";
	/**
	 * The CycloneDX versions that define {@code declarations} -- 1.6 and everything after it.
	 *
	 * <p>Explicit membership rather than {@code ordinal() >= CYCLONEDX_1_6.ordinal()}, which
	 * would be wrong and quietly so: {@link SpecVersion} declares four unrelated format
	 * families in one enum, so every SPDX, SARIF, OpenVEX and CSAF member outranks
	 * {@code CYCLONEDX_1_6} and an ordinal test would call an SPDX document declarations-capable.
	 */
	private static final Set<SpecVersion> DECLARATIONS_CAPABLE = EnumSet.of(
			SpecVersion.CYCLONEDX_1_6, SpecVersion.CYCLONEDX_1_7, SpecVersion.CYCLONEDX_2_0);
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
	 * The person who recorded an attestation, for {@code evidence[].author} -- DECISION D4.
	 *
	 * <p><b>Name and role, never an email address.</b> A name identifies the attester to a
	 * reviewer, which is the point; an email adds nothing a regulator needs and turns a
	 * redistributable BOM into a harvestable contact list.
	 *
	 * <p>The role does NOT travel in {@code author}. CycloneDX's {@code organizationalContact}
	 * is {@code additionalProperties: false} over {@code bom-ref}/{@code name}/{@code email}/
	 * {@code phone} -- it has no role field, so writing one there produces a document that
	 * fails the 1.6 schema. It goes in the evidence {@code description} instead, which is a
	 * free-text field meant for exactly this. Both halves of D4 still reach the reader; only
	 * the slot differs, because the standard has no better one.
	 */
	public record Attester(String name, String role) {
		public Attester {
			// D4 ENFORCED BY CONSTRUCTION, not by remembering not to read the email field.
			//
			// UserData.name comes from the IdP's `name` claim, and Keycloak/Okta/Entra
			// deployments routinely fall back to the username -- which is an email address.
			// A user provisioned without a display name would therefore have shipped
			// "jane@acme.example" as evidence[].author.name in a redistributable BOM: exactly
			// the harvestable contact list D4 forbids, arriving through the one door D4 did
			// not think to watch. Not emitting ud.getEmail() is not sufficient protection.
			//
			// An unnamed attester is dropped rather than masked. A partially redacted address
			// is still a contact hint, and "no author" is a state this emitter already handles
			// honestly.
			if (null != name && name.contains("@")) {
				name = null;
			}
		}
	}

	/**
	 * The document-scoped inputs to the {@code declarations} block: who the assessing
	 * organization is, and how to name the people behind {@code assertedBy} UUIDs.
	 *
	 * <p>Passed in rather than looked up here because this class is pure and deliberately
	 * repository-free; {@code SupportInjectionService} owns the user lookup D4 requires.
	 *
	 * <p>A null context means EMIT NO DECLARATIONS -- which is what the frozen-snapshot path
	 * wants until it decides what an attested-as-of document should claim, and what the
	 * property-only overloads below give existing callers.
	 */
	public record DeclarationContext(String organizationName, Map<UUID, Attester> attesters) {
		public DeclarationContext {
			attesters = (null == attesters) ? Map.of() : Map.copyOf(attesters);
		}

		Attester attester(UUID assertedBy) {
			return (null == assertedBy) ? null : attesters.get(assertedBy);
		}
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
		return inject(bom, factsByKey, asOf, device, null);
	}

	/**
	 * As above, additionally emitting the document-level {@code declarations} block (Layer B)
	 * when {@code declarationContext} is non-null and the document is CycloneDX 1.6 or later.
	 *
	 * <p>Layer B rides the SAME setting as the properties, because it is the same disclosure
	 * said in the standard's own vocabulary rather than a second, separately-governed one. The
	 * inbound strip does NOT ride it -- see {@link #stripInboundDeclarations}.
	 */
	public static JsonNode inject(JsonNode bom, Map<String, ComponentSupportFacts> factsByKey, LocalDate asOf,
			DeviceLifecycle device, DeclarationContext declarationContext) {
		if (bom == null || !bom.isObject() || factsByKey == null) {
			return bom;
		}
		// reliza:support:* and reliza:device:* are server-owned namespaces. Strip EVERY occurrence
		// anywhere in the tree BEFORE we write our own -- not just the component array but pedigree,
		// services, metadata.tools.components, vulnerabilities, etc. -- so an uploader cannot spoof
		// our provenance on any document that passes through here. See the class javadoc for the
		// exact scope of that guarantee: it is not every served document.
		stripReservedEverywhere(bom);
		stripInboundDeclarations((ObjectNode) bom);
		stripAssignedBomRefs(bom);
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
		emitDeclarations((ObjectNode) bom, factsByKey, declarationContext);
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
		stripInboundDeclarations((ObjectNode) bom);
		stripAssignedBomRefs(bom);
		JsonNode metadata = bom.get("metadata");
		ObjectNode metaObj = (metadata != null && metadata.isObject())
				? (ObjectNode) metadata
				: ((ObjectNode) bom).putObject("metadata");
		addProperty(properties(metaObj), PROP_DISCLOSURE, DISCLOSURE_STRIPPED_ONLY);
		return bom;
	}

	/**
	 * Remove any inbound top-level {@code declarations} block, UNCONDITIONALLY, on every egress
	 * that routes through this class -- injected or strip-only, setting on or off.
	 *
	 * <p>This is the Layer B half of the server-owned-namespace guarantee, and it needed its own
	 * method because {@link #stripReservedEverywhere} cannot reach it: that sweep walks
	 * {@code properties} KEYS, and {@code declarations} is a sibling of {@code components}, not
	 * a property. A forged block would therefore have survived it untouched on the
	 * single-artifact download -- the path the merge never rebuilds -- and been served back
	 * carrying an attacker's "assessor", "claim" and named "author" under this server's
	 * attribution. That is a strictly worse spoof than a forged property, because
	 * {@code declarations} is the standard's vocabulary for attestation.
	 *
	 * <p>THE WHOLE BLOCK GOES, not the reliza-looking parts of it. An upload never legitimately
	 * carries ours, and a filter that kept "someone else's" declarations would have to decide
	 * which claims are foreign from content alone -- exactly the judgement an attacker gets to
	 * influence. Dropping it is the only rule with no such seam.
	 *
	 * <p>Unconditional, unlike the emit: a security control and a content choice are not the
	 * same switch. Turning injection off must never stop us removing a forged attestation.
	 */
	private static void stripInboundDeclarations(ObjectNode bom) {
		bom.remove(NODE_DECLARATIONS);
	}

	/**
	 * Remove every {@code bom-ref} this class assigned (see {@link #ASSIGNED_REF_PREFIX}).
	 *
	 * <p>Runs on BOTH paths, which is what keeps the scaffolding from outliving its purpose.
	 * The case the STRIP path needs is the VDR enrichment path, which fetches a BOM through the
	 * INJECTING read and then strips it back down: without this, an org with injection on would
	 * get a VDR carrying component identifiers that exist only because a declarations block was
	 * briefly emitted and then removed.
	 *
	 * <p>The case the INJECT path needs is a component that STOPS being attested -- withdrawn,
	 * or deleted -- between two injections of the same document. {@link #ensureBomRef} reuses a
	 * ref it finds, so re-injection alone never accumulates; but nothing else would ever remove
	 * a ref belonging to a claim that is no longer emitted, and it would sit there permanently
	 * as an identifier for a component this server no longer says anything about.
	 *
	 * <p>Only OUR prefix is touched. A {@code bom-ref} the uploader wrote is not ours to remove
	 * and is what the strip is protecting, not attacking.
	 */
	private static void stripAssignedBomRefs(JsonNode node) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			ObjectNode obj = (ObjectNode) node;
			JsonNode ref = obj.get(FIELD_BOM_REF);
			if (ref != null && ref.isTextual() && ref.asText().startsWith(ASSIGNED_REF_PREFIX)) {
				obj.remove(FIELD_BOM_REF);
			}
		}
		for (JsonNode child : node) {
			stripAssignedBomRefs(child);
		}
	}

	/**
	 * Whether this document can carry {@code declarations} at all: CycloneDX 1.6 introduced the
	 * block, so writing it into a 1.4 or 1.5 document produces a BOM that fails its OWN declared
	 * schema. We serve BOMs at whatever version was uploaded, so this is reachable in practice
	 * and not a theoretical guard -- and the failure it prevents is the nastiest kind, a
	 * document that validates nowhere while looking richer.
	 *
	 * <p>Parsed to {@link SpecVersion} at this boundary and compared as an enum, which is what
	 * the codebase already does with this field. Unknown, absent or non-CycloneDX versions
	 * parse to null and are treated as incapable, which is the safe direction.
	 */
	private static boolean declarationsFit(ObjectNode bom) {
		JsonNode version = bom.get("specVersion");
		if (version == null || !version.isTextual()) {
			return false;
		}
		return DECLARATIONS_CAPABLE.contains(
				SpecVersion.fromCycloneDxVersionString(version.asText()));
	}

	/**
	 * Emit the Layer B {@code declarations} block: who assessed, what they claimed, and the
	 * evidence behind it, in CycloneDX's own attestation vocabulary rather than ours.
	 *
	 * <p>Emits NOTHING (not even an empty block) when there is nothing to say. An empty
	 * {@code declarations} is not neutral -- it reads as "we ran an attestation process and it
	 * produced no claims", which is a different statement from "this export carries no
	 * attestations" and the one an auditor would act on.
	 *
	 * <p>Two rules are enforced structurally rather than by review:
	 * <ul>
	 *   <li>a WITHDRAWN row yields no claim, ever. Withdrawal supersedes; republishing it as a
	 *       live claim in a second vocabulary would undo Layer A's retraction through a door
	 *       Layer A does not watch;</li>
	 *   <li>{@code levelOfSupport} evidence is never emitted without {@code created} beside it,
	 *       the same Contemporaneous rule Layer A applies to {@code assessedAt} -- and here it
	 *       falls out of the code rather than sitting next to it: an unparseable stored instant
	 *       produces no {@code created}, and no {@code created} produces no level evidence.
	 * </ul>
	 */
	private static void emitDeclarations(ObjectNode bom, Map<String, ComponentSupportFacts> factsByKey,
			DeclarationContext ctx) {
		if (ctx == null || factsByKey.isEmpty() || !declarationsFit(bom)) {
			return;
		}
		// Decide WHAT is emittable before creating any node, so the "emit nothing rather than an
		// empty block" rule cannot be got wrong by an early return leaving a half-built block.
		List<ObjectNode> emittable = new ArrayList<>();
		for (ObjectNode component : collectComponentNodes(bom)) {
			String key = componentKey(component);
			ComponentSupportFacts facts = (key == null) ? null : factsByKey.get(key);
			if (facts == null || facts.support() == null) {
				continue;
			}
			SupportData support = facts.support();
			// Same retraction rule as applyToComponent, deliberately restated rather than shared:
			// the two emit into different vocabularies, and a claim is a stronger statement than
			// a property, so each layer must independently refuse to republish a withdrawal.
			if (SupportState.WITHDRAWN == support.state()) {
				continue;
			}
			if (support.milestones().isEmpty() && support.levelOfSupport() == null
					&& support.assessedAt() == null) {
				continue;
			}
			emittable.add(component);
		}
		if (emittable.isEmpty()) {
			return;
		}
		ObjectNode declarations = bom.putObject(NODE_DECLARATIONS);
		ArrayNode claims = declarations.putArray("claims");
		ArrayNode evidence = declarations.putArray("evidence");
		// Both arrays are pruned below if they end up empty -- see the end of this method.
		// Claim refs grouped by the party that asserted them, so attestations[] can link an
		// assessor to its claims. Ordered so the emitted document is reproducible.
		Map<SupportParty, List<String>> claimsByParty = new LinkedHashMap<>();
		Set<String> assignedRefs = collectExistingRefs(bom);
		int ordinal = 0;
		for (ObjectNode component : emittable) {
			String key = componentKey(component);
			SupportData support = factsByKey.get(key).support();
			String target = ensureBomRef(component, key, assignedRefs);
			Attester attester = ctx.attester(support.assertedBy());
			// P2's Contemporaneous rule, enforced by construction: an absent or unparseable
			// assessment instant leaves created null, and the level evidence below requires it.
			String created = rfc3339OrNull(support.assessedAt());
			List<String> evidenceRefs = new ArrayList<>();
			if (support.levelOfSupport() != null && created != null) {
				evidenceRefs.add(addEvidence(evidence, ++ordinal, target, PROP_LEVEL,
						support.levelOfSupport().getWireValue(), created, attester));
			}
			// The MILESTONE DATES, which are the FDA-relevant facts and were absent from Layer B
			// entirely: a milestone-only attestation produced a claim saying "assessed" and no
			// evidence at all, while Layer A carried the actual end-of-support date. Named with
			// the same cdx:lifecycle:milestone:* keys Layer A writes, so the two layers stay
			// reconcilable. Each milestone carries its OWN assessment instant, which is the
			// whole point of storing provenance per milestone.
			for (Map.Entry<SupportMilestoneType, SupportMilestoneFact> m : support.milestones().entrySet()) {
				SupportMilestoneFact fact = m.getValue();
				if (fact == null || fact.dateValue() == null) {
					continue;
				}
				String milestoneCreated = rfc3339OrNull(fact.lastAssessed());
				evidenceRefs.add(addEvidence(evidence, ++ordinal, target, cdxMilestoneKey(m.getKey()),
						fact.dateValue().toString(),
						milestoneCreated != null ? milestoneCreated : created, attester));
			}
			if (support.party() != null) {
				// name(), NOT getCycloneDxPartyRole(): this evidence entry declares
				// propertyName = reliza:support:party, and Layer A emits FIRST_PARTY/THIRD_PARTY
				// under that exact name. Emitting "supplier" here would give one taxonomy
				// property two value vocabularies depending on which layer a consumer read it
				// from -- which would defeat the whole reason the two layers share names, and
				// contradict what cyclonedx-taxonomy.md documents the property's values to be.
				// The CycloneDX party role still reaches the document, where it has a slot of
				// its own: the assessors[] entry and its bom-ref.
				evidenceRefs.add(addEvidence(evidence, ++ordinal, target, PROP_PARTY,
						support.party().name(), created, attester));
			}
			ObjectNode claim = claims.addObject();
			// Derived from the TARGET, not from the component key. Two component nodes can share
			// one key -- the same purl appearing twice in a merged multi-artifact BOM is the
			// ordinary case -- and keying the claim off it minted ONE ref for TWO claims. The
			// JSON schema does not enforce bom-ref uniqueness, so the validator cannot catch
			// that; the target is already uniquified, so deriving from it cannot recur.
			String claimRef = "reliza:claim:" + target;
			claim.put(FIELD_BOM_REF, claimRef);
			claim.put("target", target);
			if (support.party() != null) {
				claimsByParty.computeIfAbsent(support.party(), k -> new ArrayList<>()).add(claimRef);
			}
			claim.put("predicate", support.levelOfSupport() != null
					? PROP_LEVEL + ": " + support.levelOfSupport().getWireValue()
					// "Assessed, nothing published" IS a claim -- the row's existence is the
					// diligence record, and the reasoning below is what it has to say. The same
					// case applyToComponent exists for; dropping it here would make the two
					// layers disagree about whether the component was assessed at all.
					: "reliza:support:assessed");
			if (support.justification() != null && !support.justification().isBlank()) {
				claim.put("reasoning", support.justification());
			}
			if (!evidenceRefs.isEmpty()) {
				ArrayNode refs = claim.putArray("evidence");
				evidenceRefs.forEach(refs::add);
			}
		}
		// Last, because the set of parties is only known once the claims are built. Key order
		// carries no meaning in JSON, and building it here beats a speculative pre-pass.
		if (!claimsByParty.isEmpty()) {
			ArrayNode assessors = declarations.putArray("assessors");
			for (SupportParty party : claimsByParty.keySet()) {
				ObjectNode assessor = assessors.addObject();
				assessor.put(FIELD_BOM_REF, assessorRef(party));
				assessor.put("thirdParty", party.isThirdParty());
				if (ctx.organizationName() != null && !ctx.organizationName().isBlank()) {
					assessor.putObject("organization").put("name", ctx.organizationName());
				}
			}
			// attestations[] is what JOINS an assessor to the claims it made. Without it the
			// assessor block is decorative: a consumer reading claims[] has no way to tell who
			// asserted them -- the same dangling-reference failure claims[].target was careful
			// to avoid, pointing the other way. `requirement` is optional inside map[], so this
			// needs no invented conformance requirement to be valid; verified against the 1.6
			// schema rather than assumed.
			ArrayNode attestations = declarations.putArray("attestations");
			for (Map.Entry<SupportParty, List<String>> e : claimsByParty.entrySet()) {
				ObjectNode attestation = attestations.addObject();
				attestation.put("summary", "ReARM support disclosure for components assessed as "
						+ e.getKey().getCycloneDxPartyRole());
				attestation.put("assessor", assessorRef(e.getKey()));
				ArrayNode claimRefs = attestation.putArray("map").addObject().putArray("claims");
				e.getValue().forEach(claimRefs::add);
			}
		}
		// An EMPTY array says the same wrong thing an empty block would -- "we ran this and it
		// produced nothing" -- one level further down, where it is easier to miss. A row with a
		// claim but no datable evidence is a real state; it should read as a claim with no
		// evidence, not as an evidence process that came back empty.
		if (evidence.isEmpty()) {
			declarations.remove("evidence");
		}
	}

	/** One {@code evidence[]} entry; returns its bom-ref so the claim can point at it. */
	private static String addEvidence(ArrayNode evidence, int ordinal, String target,
			String propertyName, String value, String created, Attester attester) {
		ObjectNode e = evidence.addObject();
		String ref = "reliza:evidence:" + ordinal + ":" + target;
		e.put(FIELD_BOM_REF, ref);
		// propertyName is DEFINED as a taxonomy-property reference, so the reliza:support:*
		// names double as the taxonomy here -- Layer A and Layer B name the same fact
		// identically, which is what lets a consumer reconcile the two.
		e.put("propertyName", propertyName);
		e.putArray("data").addObject().put("name", propertyName)
				.putObject("contents").putObject("attachment").put("content", value);
		if (created != null) {
			e.put("created", created);
		}
		if (attester != null && attester.name() != null && !attester.name().isBlank()) {
			// NAME ONLY in author -- organizationalContact has no role field and forbids
			// additional ones, so a role written there fails the 1.6 schema. The role rides in
			// description instead. See Attester's javadoc; both halves of D4 still reach a reader.
			e.putObject("author").put("name", attester.name());
			if (attester.role() != null && !attester.role().isBlank()) {
				e.put("description", "Recorded by " + attester.name()
						+ " (organization role: " + attester.role() + ")");
			}
		}
		return ref;
	}

	private static String assessorRef(SupportParty party) {
		return "reliza:assessor:" + party.getCycloneDxPartyRole();
	}

	/**
	 * The bom-ref a claim can target, ASSIGNING one when the component has none.
	 *
	 * <p>Necessary, not incidental: {@code claims[].target} is a {@code refLinkType}, which
	 * resolves against a {@code bom-ref} in the same document, and observed exports carry
	 * {@code bom-ref} on the root self-component ONLY. Without this every claim would dangle,
	 * which is the failure mode a consumer is least likely to notice -- the block parses, and
	 * the claims silently attach to nothing.
	 *
	 * <p>Assignment is additive and is namespaced under {@link #ASSIGNED_REF_PREFIX}, so it can
	 * neither collide with a ref the document already had nor accidentally resolve a
	 * {@code dependsOn} edge that was dangling before we touched it. A collision among our own
	 * assignments (two nodes for one component identity) is suffixed, so the standard's
	 * "unique within the BOM" rule holds for refs we mint as well as for refs we find.
	 */
	private static String ensureBomRef(ObjectNode component, String key, Set<String> assignedRefs) {
		JsonNode existing = component.get(FIELD_BOM_REF);
		if (existing != null && existing.isTextual() && !existing.asText().isBlank()) {
			return existing.asText();
		}
		String ref = ASSIGNED_REF_PREFIX + key;
		int suffix = 2;
		while (assignedRefs.contains(ref)) {
			ref = ASSIGNED_REF_PREFIX + key + "#" + suffix++;
		}
		assignedRefs.add(ref);
		component.put(FIELD_BOM_REF, ref);
		return ref;
	}

	/** Every bom-ref already present anywhere in the tree, so an assigned one cannot collide. */
	private static Set<String> collectExistingRefs(JsonNode node) {
		Set<String> refs = new HashSet<>();
		collectExistingRefs(node, refs);
		return refs;
	}

	private static void collectExistingRefs(JsonNode node, Set<String> refs) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			JsonNode ref = node.get(FIELD_BOM_REF);
			if (ref != null && ref.isTextual()) {
				refs.add(ref.asText());
			}
		}
		for (JsonNode child : node) {
			collectExistingRefs(child, refs);
		}
	}

	/**
	 * Every component node the injector considers, in document order: the component array
	 * (nested included) plus {@code metadata.component} and its children -- the same surface
	 * {@link #inject} walks, so Layer A and Layer B cannot end up describing different sets
	 * of components.
	 */
	private static List<ObjectNode> collectComponentNodes(ObjectNode bom) {
		List<ObjectNode> out = new ArrayList<>();
		collectComponentNodes(bom.get("components"), out);
		JsonNode metadata = bom.get("metadata");
		if (metadata != null && metadata.isObject()) {
			JsonNode root = metadata.get("component");
			if (root != null && root.isObject()) {
				out.add((ObjectNode) root);
				collectComponentNodes(root.get("components"), out);
			}
		}
		return out;
	}

	private static void collectComponentNodes(JsonNode components, List<ObjectNode> out) {
		if (components == null || !components.isArray()) {
			return;
		}
		for (JsonNode c : components) {
			if (c.isObject()) {
				out.add((ObjectNode) c);
			}
			collectComponentNodes(c.get("components"), out);
		}
	}

	/**
	 * A stored assessment instant as an RFC-3339 UTC instant, or null if absent/unparseable.
	 * {@code evidence[].created} is schema-constrained to {@code date-time}, so a value we
	 * cannot normalize must be omitted rather than passed through -- and omitting it also
	 * suppresses the level evidence, which is the rule we want.
	 */
	private static String rfc3339OrNull(String storedInstant) {
		ZonedDateTime parsed = parseInstantOrNull(storedInstant);
		return parsed == null ? null : DateTimeFormatter.ISO_INSTANT.format(parsed.toInstant());
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
		boolean removedAny = false;
		for (int i = arr.size() - 1; i >= 0; i--) {
			JsonNode name = arr.get(i).get("name");
			if (name != null && name.isTextual() && name.asText().startsWith(prefix)) {
				arr.remove(i);
				removedAny = true;
			}
		}
		dropEmptiedProperties(node, arr, removedAny);
	}

	/**
	 * Remove a {@code properties} array THIS SWEEP emptied.
	 *
	 * <p>A component whose only properties were forged {@code reliza:*} ones was left carrying
	 * {@code "properties": []} -- valid, and noise: it says "this component has properties" to
	 * a reader and to any consumer that branches on the key's presence, when what happened is
	 * that it had none of its own. Spotted in a served export during the operator walkthrough
	 * (board t20260909-061338-23148).
	 *
	 * <p>ONLY when we emptied it. An empty array the uploader supplied is left exactly as it
	 * arrived: the strip's remit is the reserved namespaces, not tidying someone else's
	 * document, and a sweep that quietly edits untouched structure is harder to reason about
	 * than one that leaves a little noise.
	 */
	private static void dropEmptiedProperties(ObjectNode node, ArrayNode arr, boolean removedAny) {
		if (removedAny && arr.isEmpty()) {
			node.remove("properties");
		}
	}

	/** Remove every property whose name is in {@code names} from a node's array. */
	private static void stripByNames(ObjectNode node, Set<String> names) {
		JsonNode props = node.get("properties");
		if (props == null || !props.isArray()) {
			return;
		}
		ArrayNode arr = (ArrayNode) props;
		boolean removedAny = false;
		for (int i = arr.size() - 1; i >= 0; i--) {
			JsonNode name = arr.get(i).get("name");
			if (name != null && name.isTextual() && names.contains(name.asText())) {
				arr.remove(i);
				removedAny = true;
			}
		}
		dropEmptiedProperties(node, arr, removedAny);
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
