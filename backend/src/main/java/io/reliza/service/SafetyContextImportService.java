/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalysisScope;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ImportMode;
import io.reliza.model.IssuerClass;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.RelizaObject;
import io.reliza.model.SafetyContextData.GateRule;
import io.reliza.model.SafetyContextData.MatchStatus;
import io.reliza.model.SafetyContextData.Note;
import io.reliza.model.SafetyContextProposalData;
import io.reliza.model.SafetyLevelScale;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.cdx.v20.Cdx20Asset;
import io.reliza.model.cdx.v20.Cdx20Blueprint;
import io.reliza.model.cdx.v20.Cdx20Component;
import io.reliza.model.cdx.v20.Cdx20Document;
import io.reliza.model.cdx.v20.Cdx20DocumentReader;
import io.reliza.model.cdx.v20.Cdx20SafetyIntegrityLevel;
import io.reliza.model.cdx.v20.Cdx20SafetyScheme;
import io.reliza.model.cdx.v20.Cdx20Scheme;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ImportSummary;
import io.reliza.model.dto.MatchCandidate;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports the safety integrity levels a CycloneDX 2.0 SAFETY_CONTEXT document declares for the
 * components of the release it is attached to.
 *
 * <p>Phase A (this class, no writes): bounds, parse with a depth-limited reader, validate each
 * level, match its component purl against the release inventory, resolve the scope target and the
 * effective issuer. Phase B ({@link SafetyContextImportWriter}, one new transaction): no-op on
 * re-import, trust gate, stage or accept. Any phase-B failure stores nothing.
 *
 * <p>Summary invariant: statementsTotal = proposalsCreated + proposalsAutoAccepted +
 * proposalsAutoRejected + statementsUnchanged + statementsErrored, with statementsUnmatched a
 * subset of proposalsCreated. A statement is one level for one scope target. A refused or
 * aborted import reports total 1, errored 1.
 */
@Slf4j
@Service
public class SafetyContextImportService implements ArtifactImportHandler {

	static final int MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;
	static final int MAX_NESTING_DEPTH = 64;
	static final int MAX_BLUEPRINTS = 50;
	static final int MAX_ASSETS = 5000;
	static final int MAX_LEVELS_PER_ASSET = 8;
	static final int MAX_CANDIDATES = 5000;
	static final int MAX_TARGETS = 50;
	static final int MAX_PENDING_PER_RELEASE = 10000;
	static final int MESSAGE_VALUE_CHARS = 40;
	static final int MAX_LEVEL_CHARS = 64;
	static final int MAX_REF_CHARS = 256;
	static final int MAX_PURL_CHARS = 1024;
	/** Each asset's JSON is stored on every row it yields; larger assets refuse the import. */
	static final int MAX_ASSET_JSON_CHARS = 64 * 1024;

	private static final Pattern CUSTOM_SCHEME_NAME = Pattern.compile("[A-Za-z0-9 ._-]{1,64}");

	/** Untrusted uploads are parsed with tighter stream limits than {@link Utils#OM}. */
	static final ObjectMapper BOUNDED_MAPPER = JsonMapper.builder(JsonFactory.builder()
			.streamReadConstraints(StreamReadConstraints.builder()
				.maxNestingDepth(MAX_NESTING_DEPTH)
				.maxDocumentLength(MAX_DOCUMENT_BYTES)
				.build())
			.build())
		.changeDefaultVisibility(vc -> vc.withCreatorVisibility(JsonAutoDetect.Visibility.NON_PRIVATE))
		.build();

	@Autowired private SbomComponentService sbomComponentService;
	@Autowired private ScopeTargetResolver scopeTargetResolver;
	@Autowired private SafetyContextService safetyContextService;
	@Autowired private SafetyContextImportWriter writer;
	@Autowired private UserService userService;
	@Autowired private AuthorizationService authorizationService;
	@Autowired private GetOrganizationService getOrganizationService;

	@Override
	public ArtifactType type() {
		return ArtifactType.SAFETY_CONTEXT;
	}

	@Override
	public void verifyPreconditions(ReleaseData rd) throws RelizaException {
		if (rd == null) {
			throw new RelizaException("A SAFETY_CONTEXT artifact must be attached to a release, a deliverable "
				+ "or a source code entry of a release");
		}
		if (!sbomComponentService.releaseHasSbomComponents(rd.getUuid(), rd.getOrg())) {
			throw new RelizaException("Cannot import SAFETY_CONTEXT: release has no SBOM components yet. "
				+ "Upload an SBOM/BOM artifact for this release first.");
		}
	}

	/**
	 * Runs outside the upload's transaction (phase A only reads, so a failure here cannot mark the
	 * upload rollback-only); the writer opens its own, so a failed write is reported, not thrown.
	 */
	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public ImportSummary dispatch(UUID artifactUuid, ArtifactDto artDto, ReleaseData rd,
			ArtifactBelongsTo belongsTo, byte[] content, WhoUpdated wu) throws RelizaException {
		verifyPreconditions(rd);
		if (content == null || content.length > MAX_DOCUMENT_BYTES) {
			return refused("SAFETY_CONTEXT document is empty or larger than " + (MAX_DOCUMENT_BYTES / (1024 * 1024))
				+ " MB; nothing was imported");
		}
		String json = new String(content, StandardCharsets.UTF_8);
		JsonNode root;
		Cdx20Document doc;
		try {
			root = BOUNDED_MAPPER.readTree(json);
			doc = Cdx20DocumentReader.read(json, BOUNDED_MAPPER);
		} catch (JacksonException e) {
			return refused("SAFETY_CONTEXT document is not readable JSON or exceeds the import limits");
		} catch (RelizaException e) {
			// The reader's message can quote the document's specVersion; do not echo it.
			return refused("SAFETY_CONTEXT document is not a CycloneDX 2.x JSON document");
		}

		ImportMode importMode = artDto.getImportMode() != null ? artDto.getImportMode() : ImportMode.AUTO_ACCEPT;
		AnalysisScope importScope = artDto.getImportScope() != null ? artDto.getImportScope() : AnalysisScope.COMPONENT;
		IssuerClass derived = ArtifactImportHandler.deriveIssuerClass(belongsTo);
		IssuerClass override = artDto.getUserIssuerClassOverride();
		UUID actor = SafetyContextService.actorOf(wu);
		// Scopes wider than the release's own component apply org- or branch-wide, so, like the
		// issuer override, they take Finding Analysis write; without it every level stages.
		boolean wideScope = importScope == AnalysisScope.ORG || importScope == AnalysisScope.BRANCH;
		boolean holdsWrite = (override != null || wideScope) && actorHoldsWrite(actor, rd.getOrg());
		SafetyContextTrustPolicy.Issuer issuer = SafetyContextTrustPolicy.effectiveIssuer(derived, override, holdsWrite);
		boolean stageForScope = wideScope && !holdsWrite;
		List<String> messages = new ArrayList<>();
		if (stageForScope) {
			messages.add("Import scope " + importScope + " needs Finding Analysis write permission; all levels were staged for review");
		}
		if (issuer.overrideIgnored()) {
			messages.add("Issuer override " + override + " ignored: it needs Finding Analysis write permission; "
				+ "the upload's own class " + derived + " was used");
		}
		if (issuer.overrideHonoured()) {
			log.warn("SAFETY_CONTEXT issuer override honoured: org={} release={} artifact={} actor={} derived={} effective={}",
				rd.getOrg(), rd.getUuid(), artifactUuid, actor, derived, issuer.effective());
		}

		List<MatchCandidate> targets;
		try {
			targets = scopeTargetResolver.targets(rd.getOrg(), importScope, Set.of(rd.getUuid()), rd.getUuid());
		} catch (RuntimeException e) {
			return refused("Import scope " + importScope + " is not supported for SAFETY_CONTEXT");
		}
		if (targets.isEmpty() || targets.size() > MAX_TARGETS) {
			return refused("Import scope " + importScope + " resolved to " + targets.size() + " targets; nothing was imported");
		}

		Base base = new Base(rd, artifactUuid, belongsTo, derived, override, issuer, importMode, actor, stageForScope,
			Utils.sha256Hex(json), doc,
			Utils.sha256Hex(String.join("|", rd.getOrg().toString(), rd.getUuid().toString(),
				String.valueOf(belongsTo), issuer.effective().name(), String.valueOf(actor))));

		List<SafetyContextProposalData> candidates = new ArrayList<>();
		int[] errored = {0};
		String refusal = collectCandidates(doc, root, base, targets, candidates, errored, messages);
		if (refusal != null) return refused(refusal);
		if (candidates.isEmpty() && errored[0] == 0) {
			messages.add("The document declares no safety integrity levels; nothing was imported");
		}
		if (safetyContextService.countPending(rd.getOrg(), rd.getUuid()) + candidates.size() > MAX_PENDING_PER_RELEASE) {
			return refused("This release already has too many pending safety context proposals; review them first");
		}

		SafetyContextImportWriter.Counts counts;
		try {
			counts = candidates.isEmpty()
				? new SafetyContextImportWriter.Counts(0, 0, 0, 0, 0)
				: writer.importAll(candidates, importMode, wu);
		} catch (RuntimeException e) {
			log.error("SAFETY_CONTEXT import aborted for artifact {}", artifactUuid, e);
			return refused("SAFETY_CONTEXT import aborted; nothing was stored. Retry the upload.");
		}
		ImportSummary summary = new ImportSummary();
		summary.setProposalsCreated(counts.created());
		summary.setProposalsAutoAccepted(counts.autoAccepted());
		summary.setProposalsAutoRejected(counts.autoRejected());
		summary.setStatementsUnchanged(counts.unchanged());
		summary.setStatementsUnmatched(counts.unmatched());
		summary.setStatementsErrored(errored[0]);
		summary.setStatementsTotal(counts.created() + counts.autoAccepted() + counts.autoRejected()
			+ counts.unchanged() + errored[0]);
		setMessages(summary, messages);
		return summary;
	}

	/** Upload-wide values every candidate shares. */
	private record Base(ReleaseData rd, UUID artifactUuid, ArtifactBelongsTo belongsTo, IssuerClass derived,
			IssuerClass override, SafetyContextTrustPolicy.Issuer issuer, ImportMode importMode, UUID actor,
			boolean stageForScope, String documentHash, Cdx20Document doc, String lineageKey) {}

	/** Fills {@code out}; returns a refusal message when a bound is exceeded (never truncates). */
	private String collectCandidates(Cdx20Document doc, JsonNode root, Base base, List<MatchCandidate> targets,
			List<SafetyContextProposalData> out, int[] errored, List<String> messages) {
		List<Cdx20Blueprint> blueprints = doc.getBlueprints() == null ? List.of() : doc.getBlueprints();
		if (blueprints.size() > MAX_BLUEPRINTS) return "Too many blueprints (limit " + MAX_BLUEPRINTS + ")";
		int assetCount = blueprints.stream().mapToInt(b -> b == null || b.getAssets() == null ? 0 : b.getAssets().size()).sum();
		if (assetCount > MAX_ASSETS) return "Too many assets (limit " + MAX_ASSETS + ")";

		Map<String, List<Cdx20Component>> componentsByRef = new HashMap<>();
		for (Cdx20Component c : doc.allComponents()) {
			if (c.getRefId() != null) componentsByRef.computeIfAbsent(c.getRefId(), k -> new ArrayList<>()).add(c);
		}
		SbomComponentService.ReleaseComponentPurls release = sbomComponentService.resolveReleaseComponentPurls(base.rd().getUuid());
		Map<String, Set<UUID>> releaseComponentsByPurl = new LinkedHashMap<>();
		release.canonicalByComponent().forEach((componentUuid, purl) -> {
			String canonical = canonical(purl);
			if (canonical != null) releaseComponentsByPurl.computeIfAbsent(canonical, k -> new LinkedHashSet<>()).add(componentUuid);
		});

		Set<String> seenIdentities = new HashSet<>();
		JsonNode blueprintNodes = root.path("blueprints");
		for (int b = 0; b < blueprints.size(); b++) {
			Cdx20Blueprint blueprint = blueprints.get(b);
			if (blueprint == null || blueprint.getAssets() == null) continue;
			JsonNode assetNodes = blueprintNodes.path(b).path("assets");
			for (int a = 0; a < blueprint.getAssets().size(); a++) {
				Cdx20Asset asset = blueprint.getAssets().get(a);
				if (asset == null || asset.getClassification() == null) continue;
				List<Cdx20SafetyIntegrityLevel> levels = asset.getClassification().getSafetyIntegrityLevels();
				if (levels == null || levels.isEmpty()) continue;
				if (levels.size() > MAX_LEVELS_PER_ASSET) {
					return "Asset " + clip(asset.getRefId()) + " has more than " + MAX_LEVELS_PER_ASSET + " safety integrity levels";
				}
				String assetJson = assetNodes.path(a).toString();
				if (assetJson.length() > MAX_ASSET_JSON_CHARS) {
					return "Asset " + clip(asset.getRefId()) + " is larger than " + (MAX_ASSET_JSON_CHARS / 1024) + " KB";
				}
				if (tooLong(blueprint.getRefId(), MAX_REF_CHARS) || tooLong(asset.getRefId(), MAX_REF_CHARS)) {
					errored[0] += levels.size() * targets.size();
					messages.add("Asset " + clip(asset.getRefId()) + " has a reference id longer than " + MAX_REF_CHARS
						+ " characters; ignored");
					continue;
				}
				// One proposal per scheme; an asset declaring several levels for one scheme becomes
				// a single staged conflict carrying all of them.
				Map<String, SafetyContextProposalData> byScheme = new LinkedHashMap<>();
				for (Cdx20SafetyIntegrityLevel level : levels) {
					SafetyContextProposalData proto = new SafetyContextProposalData();
					String error = describeLevel(level, proto);
					if (error == null) error = describeComponent(asset, componentsByRef, proto);
					if (error != null) {
						errored[0] += targets.size();
						messages.add(error);
						continue;
					}
					SafetyContextProposalData first = byScheme.get(proto.getSchemeKey());
					if (first != null && List.of(first.getLevelRaw().split(" \\| ")).contains(proto.getLevelRaw())) {
						continue; // the same level stated twice is one level
					}
					if (first != null) {
						first.setLevelRaw(first.getLevelRaw() + " | " + proto.getLevelRaw());
						first.setLevelNormalized(null);
						first.setLevelRank(null);
						first.setGateRule(GateRule.INTRA_ASSET_CONFLICT);
						first.setStatusReason("The asset declares more than one level for this scheme");
						continue;
					}
					proto.setBlueprintRefId(blueprint.getRefId());
					proto.setAssetRefId(asset.getRefId());
					proto.setSourceAssertionJson(assetJson);
					matchRelease(proto, release, releaseComponentsByPurl);
					byScheme.put(proto.getSchemeKey(), proto);
				}
				List<SafetyContextProposalData> assetCandidates = new ArrayList<>(byScheme.values());
				for (SafetyContextProposalData proto : assetCandidates) {
					proto.setSourceAssertionHash(Utils.sha256Hex(proto.getSchemeKey() + "|" + proto.getLevelRaw() + "|" + assetJson));
					for (MatchCandidate target : targets) {
						String identity = String.join("|", target.scope().name(), String.valueOf(target.scopeUuid()),
							proto.getComponentPurl(), proto.getSchemeKey(), String.valueOf(proto.getBlueprintRefId()),
							String.valueOf(proto.getAssetRefId()));
						if (!seenIdentities.add(identity)) {
							errored[0]++;
							messages.add("Duplicate safety level for asset " + clip(proto.getAssetRefId()) + "; later entry ignored");
							continue;
						}
						if (out.size() >= MAX_CANDIDATES) return "Too many safety levels to import (limit " + MAX_CANDIDATES + ")";
						out.add(candidate(proto, target, base));
					}
				}
			}
		}
		return null;
	}

	/** Scheme key and level ranking; returns an error message, or null. */
	private static String describeLevel(Cdx20SafetyIntegrityLevel level, SafetyContextProposalData proto) {
		Cdx20Scheme scheme = level.getScheme();
		String raw = level.getLevel();
		if (scheme == null || raw == null || raw.isBlank()) return "A safety integrity level without scheme or level was ignored";
		if (raw.length() > MAX_LEVEL_CHARS) return "A safety integrity level text longer than " + MAX_LEVEL_CHARS + " characters was ignored";
		Cdx20SafetyScheme predefined = level.predefinedScheme();
		if (predefined != null) {
			proto.setSchemeKey(predefined.value());
		} else if (scheme.getCustomName() != null && CUSTOM_SCHEME_NAME.matcher(scheme.getCustomName()).matches()) {
			proto.setSchemeKey("CUSTOM:" + scheme.getCustomName());
		} else {
			return "Safety integrity scheme " + clip(scheme.getPredefined() != null ? scheme.getPredefined() : scheme.getCustomName())
				+ " is not recognised; the level was ignored";
		}
		SafetyLevelScale.Level ranked = SafetyLevelScale.classify(predefined, raw);
		proto.setLevelRaw(raw);
		proto.setLevelNormalized(ranked.normalized());
		proto.setLevelRank(ranked.rank());
		proto.setLevelNote(ranked.note());
		return null;
	}

	/** Resolves the asset's component purl from the document; returns an error message, or null. */
	private static String describeComponent(Cdx20Asset asset, Map<String, List<Cdx20Component>> componentsByRef,
			SafetyContextProposalData proto) {
		List<Cdx20Component> found = asset.getComponentRef() == null ? null : componentsByRef.get(asset.getComponentRef());
		if (found == null || found.size() != 1) {
			return "Asset " + clip(asset.getRefId()) + " does not reference exactly one component in the document; ignored";
		}
		List<String> purls = found.get(0).purls();
		String canonical = purls.isEmpty() ? null : canonical(purls.get(0));
		if (canonical == null) return "Component " + clip(asset.getComponentRef()) + " has no usable purl; its levels were ignored";
		if (canonical.length() > MAX_PURL_CHARS || tooLong(asset.getComponentRef(), MAX_REF_CHARS)) {
			return "Component " + clip(asset.getComponentRef()) + " has a purl or reference that is too long; ignored";
		}
		proto.setComponentRefId(asset.getComponentRef());
		proto.setComponentPurl(canonical);
		return null;
	}

	private static void matchRelease(SafetyContextProposalData proto, SbomComponentService.ReleaseComponentPurls release,
			Map<String, Set<UUID>> byPurl) {
		Set<UUID> matches = byPurl.getOrDefault(proto.getComponentPurl(), Set.of());
		if (release.reconcilePending()) {
			proto.setMatchStatus(MatchStatus.SBOM_PENDING);
			proto.setGateRule(GateRule.SBOM_PENDING);
		} else if (matches.isEmpty()) {
			proto.setMatchStatus(MatchStatus.UNMATCHED);
			proto.setGateRule(GateRule.UNMATCHED);
		} else if (matches.size() > 1) {
			proto.setMatchStatus(MatchStatus.AMBIGUOUS);
			proto.setGateRule(GateRule.AMBIGUOUS);
		} else {
			proto.setMatchStatus(MatchStatus.MATCHED);
			proto.setSbomComponentUuid(matches.iterator().next());
		}
	}

	private static SafetyContextProposalData candidate(SafetyContextProposalData proto, MatchCandidate target, Base base) {
		SafetyContextProposalData c = new SafetyContextProposalData();
		c.copySharedFrom(proto);
		c.setUuid(UUID.randomUUID());
		c.setOrg(base.rd().getOrg());
		c.setReleaseUuid(base.rd().getUuid());
		c.setScope(target.scope());
		c.setScopeUuid(target.scopeUuid());
		c.setSourceArtifact(base.artifactUuid());
		c.setSourceDocumentHash(base.documentHash());
		c.setSpecCommit(Cdx20DocumentReader.CDX_2_0_DEV_PINNED_COMMIT);
		c.setDocSerialNumber(clipStored(base.doc().getSerialNumber()));
		c.setDocVersion(base.doc().getVersion());
		c.setUploadedBy(base.actor());
		c.setOriginBinding(base.belongsTo());
		c.setIssuerClassDerived(base.derived());
		c.setIssuerClassEffective(base.issuer().effective());
		c.setIssuerOverride(base.override());
		c.setOverrideHonoured(base.issuer().overrideHonoured());
		c.setElevatedByOverride(base.issuer().elevatedByOverride());
		if (base.override() != null) c.setOverrideActor(base.actor());
		if (base.issuer().overrideIgnored()) c.addNote(Note.OVERRIDE_IGNORED);
		c.setImportMode(base.importMode());
		c.setLineageKey(base.lineageKey());
		c.setGateRule(proto.getGateRule() == null && base.stageForScope() ? GateRule.WIDE_SCOPE : proto.getGateRule());
		return c;
	}

	private boolean actorHoldsWrite(UUID actor, UUID org) {
		if (actor == null) return false;
		Optional<UserData> oud = userService.getUserData(actor);
		if (oud.isEmpty()) return false;
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(org);
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		return authorizationService.isUserAuthorizedForObject(oud.get(), PermissionFunction.FINDING_ANALYSIS_WRITE,
			PermissionScope.ORGANIZATION, org, Collections.singletonList(ro), CallType.WRITE);
	}

	private static String canonical(String purl) {
		if (purl == null || purl.isBlank()) return null;
		try {
			return Utils.canonicalizePurl(purl);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** A short, control-character-free rendering of an uploaded value for a message. */
	static String clip(String value) {
		return clip(value, MESSAGE_VALUE_CHARS);
	}

	private static String clip(String value, int max) {
		if (value == null) return "<none>";
		String clean = value.replaceAll("\\p{Cntrl}", "");
		return clean.length() > max ? clean.substring(0, max) + "..." : clean;
	}

	private static boolean tooLong(String value, int max) {
		return value != null && value.length() > max;
	}

	private static String clipStored(String value) {
		return value == null || value.length() <= 256 ? value : value.substring(0, 256);
	}

	private static ImportSummary refused(String message) {
		ImportSummary s = new ImportSummary();
		s.setStatementsTotal(1);
		s.setStatementsErrored(1);
		s.setErrorMessages(List.of(message));
		return s;
	}

	private static void setMessages(ImportSummary summary, List<String> messages) {
		if (messages.isEmpty()) return;
		summary.setErrorMessages(List.copyOf(messages.size() > ImportSummary.MAX_ERROR_MESSAGES
			? messages.subList(0, ImportSummary.MAX_ERROR_MESSAGES) : messages));
	}
}
