/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.StrengthScale;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ModelOntology;
import io.reliza.model.AgentSessionData;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.ModelVariants;
import io.reliza.model.ModelOntologyData.ModelResolution;
import io.reliza.model.ModelOntologyData.ModelCardSpecVersion;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentRepository;
import io.reliza.repositories.AgentSessionRepository;
import io.reliza.repositories.AgentSessionUsageRepository;
import io.reliza.repositories.ModelOntologyRepository;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import io.reliza.model.SessionUsageHosting;
import io.reliza.model.PricingEntry;
import io.reliza.model.PricingEntry.PricingUnit;
import java.time.ZonedDateTime;

/**
 * CRUD + auto-upsert for {@link ModelOntology}. The hot path is
 * {@link #findOrRegisterModel} — invoked during session initialize
 * to resolve / create the model row the calling agent is running.
 * The user can attach a fuller CycloneDX ML-BOM model card later via
 * {@link #setModelCard} (driven by the
 * {@code setModelOntologyModelCardProgrammatic} GraphQL mutation).
 */
@Service
@Slf4j
public class ModelOntologyService {

	@Autowired
	private AuditService auditService;

	/**
	 * Self-injection so {@link #findOrRegisterModel} can call the
	 * REQUIRES_NEW {@link #registerModelIsolated} through Spring's proxy.
	 * A direct {@code this.*} call bypasses AOP, so the insert would join
	 * the caller's transaction — which is exactly the poisoned-transaction
	 * trap this split avoids.
	 */
	@Autowired
	@Lazy
	private ModelOntologyService self;

	private final ModelOntologyRepository repository;

	ModelOntologyService(ModelOntologyRepository repository) {
		this.repository = repository;
	}


	/**
	 * The per-org alias index: normalised string to row uuid. Tens of rows per org, read on every
	 * session init and every usage report, written rarely -- so a map behind a read lock beats an
	 * index in the database (D7). Invalidated wholesale on any write to the org's rows, because a
	 * catalogue write is rare and a stale alias is a mis-attributed cost.
	 */
	private final Map<UUID, Map<String, UUID>> aliasCache = new ConcurrentHashMap<>();

	@Autowired
	private ModelCatalogueBundle catalogueBundle;

	// Needed to re-point references before a model row is removed; see repointModelReferences.
	@Autowired
	private AgentRepository agentRepository;

	@Autowired
	private AgentSessionRepository agentSessionRepository;

	@Autowired
	private AgentSessionUsageRepository usageRepository;

	/** Lazy: the board service validates overrides against the catalogue, so this edge would close a cycle. */
	@Autowired
	@Lazy
	private AgentBoardService agentBoardService;

	/**
	 * What a declared model string resolved to, and what was hiding inside it.
	 *
	 * @param model the catalogue row
	 * @param variants peeled attributes -- context variant, snapshot, service tier
	 * @param hosting where the string said it ran, or null when it did not say
	 */
	public record ResolvedModel(ModelOntologyData model, ModelVariants variants,
			SessionUsageHosting hosting) {}

	/**
	 * How strong the model a session runs is, for role eligibility.
	 *
	 * <p>The declared model's rating, unless the session's usage recorded a different model -- in
	 * which case the weaker of the two, for the rest of the session. An agent that declares Opus
	 * and runs Haiku is usually misconfigured rather than dishonest, but either way the work is
	 * being done by the weaker model and a role with a floor should see that.
	 *
	 * <p>Null when nothing is known: unrated is not weak, it is unknown, and a role that asks for
	 * a floor declines it rather than guessing.
	 */
	public Double effectiveStrength(AgentSessionData session) {
		return effectiveStrength(session, null);
	}

	/**
	 * As above, for work of one role: the role's override for the model, else the model's value
	 * for the role's category, else its base strength (see {@link #strengthFor}). The weakest-
	 * observed rule applies per role, so a session that ran Haiku is judged on Haiku's CODER
	 * strength for a coder task and its ARCHITECT strength for an architect one.
	 */
	public Double effectiveStrength(AgentSessionData session, AgentTaskRoleConfigData role) {
		if (null == session) return null;
		Double declared = strengthFor(session.getModel(), role);
		if (!session.isModelMismatch() || null == session.getUsageTotals()
				|| null == session.getUsageTotals().byModel()) {
			return declared;
		}
		// The rollup already recorded that what ran was not what was declared. Take the weakest
		// model that actually did work: a session that declared Opus and spent most of its tokens
		// on Haiku is doing Haiku-grade work, whatever it said at init.
		Double weakest = declared;
		for (var row : session.getUsageTotals().byModel()) {
			Double observed = strengthFor(row.model(), role);
			if (null == observed) continue;
			weakest = null == weakest ? observed : Math.min(weakest, observed);
		}
		return weakest;
	}

	/**
	 * One model's strength for one role. Most specific first: the role's own override for this
	 * model, then the model's strength for the role's category, then the model's base strength.
	 * Null role reads the base; null when nothing rates the model, since unrated is unknown.
	 */
	public Double strengthFor(UUID modelUuid, AgentTaskRoleConfigData role) {
		if (null == modelUuid) return null;
		if (null != role) {
			Double override = role.modelStrengthOverride(modelUuid);
			if (null != override) return override;
		}
		return getModelOntologyData(modelUuid)
				.map(m -> m.strengthFor(null == role ? null : role.getStrengthCategory()))
				.orElse(null);
	}

	/**
	 * Resolve a declared model string to the org's catalogue row, creating it when needed.
	 *
	 * <p>Four steps, in order: normalise and peel ({@link ModelIdNormalizer}), match the org's
	 * names and aliases, match the bundled catalogue and pre-fill a row from it, else create an
	 * unresolved row carrying the normalised form as its first alias.
	 *
	 * <p>An unresolved row is not an error: it is a model nobody has mapped yet. It records usage
	 * and produces no cost, and the catalogue UI offers to merge it into a known row.
	 */
	@Transactional
	public ResolvedModel resolve(UUID orgUuid, String declaredName, String declaredVersion, WhoUpdated wu)
			throws RelizaException {
		if (orgUuid == null) throw new RelizaException("ModelOntology requires an org");
		// A placeholder is no model (task RD2-26): its usage goes to the organization's one synthetic
		// pseudo-model instead of minting a row per spelling.
		if (ModelIdNormalizer.isPlaceholder(declaredName)) {
			return new ResolvedModel(syntheticModel(orgUuid, wu), ModelVariants.NONE, null);
		}
		ModelIdNormalizer.Normalized n = ModelIdNormalizer.normalize(declaredName, declaredVersion);
		if (StringUtils.isBlank(n.base())) {
			throw new RelizaException("ModelOntology requires a model name to resolve");
		}

		// Each form the declaration may be known by, in order -- the join, then the bare name -- first
		// among the org's rows, then in the bundle (task RD2-26). A declaration that repeats its version
		// in its name, or a canonical name with a stray version, lands on the one row the model has.
		List<String> candidates = ModelIdNormalizer.candidates(declaredName, declaredVersion);
		for (String candidate : candidates) {
			UUID hit = aliasIndex(orgUuid).get(candidate);
			if (hit != null) {
				Optional<ModelOntologyData> row = getModelOntologyData(hit);
				if (row.isPresent()) {
					return new ResolvedModel(noteDeclaration(row.get(), declaredName, declaredVersion, wu),
							n.variants(), n.hosting());
				}
				// The cache outlived the row; drop it and fall through to a fresh resolution.
				aliasCache.remove(orgUuid);
			}
			Optional<ModelCatalogueBundle.BundledModel> bundled = catalogueBundle.find(candidate);
			if (bundled.isPresent()) {
				// Identity by canonical id: a row the org already has for this model is the model's row.
				Optional<ModelOntologyData> sameModel = byCanonicalId(orgUuid, bundled.get().canonicalId());
				ModelOntologyData row = sameModel.isPresent()
						? noteDeclaration(sameModel.get(), declaredName, declaredVersion, wu)
						: registerFromBundle(orgUuid, bundled.get(), n.base(), declaredVersion, wu);
				aliasCache.remove(orgUuid);
				return new ResolvedModel(row, n.variants(), n.hosting());
			}
		}
		ModelOntologyData created = registerUnresolved(orgUuid, declaredName, declaredVersion, n.base(), wu);
		aliasCache.remove(orgUuid);
		return new ResolvedModel(created, n.variants(), n.hosting());
	}

	/** The org's row carrying this canonical id, if any. */
	private Optional<ModelOntologyData> byCanonicalId(UUID orgUuid, String canonicalId) {
		if (StringUtils.isBlank(canonicalId)) return Optional.empty();
		return listByOrg(orgUuid).stream()
				.filter(m -> canonicalId.equalsIgnoreCase(StringUtils.defaultString(m.getCanonicalId())))
				.findFirst();
	}

	/**
	 * Record a declaration on the row it resolved onto: its full form -- name joined with version --
	 * as an alias, so the next one hits the index at once, and the version it gave as information.
	 * A declaration with no version records nothing: its form is the bare name, which must never be
	 * stored as one version's alias (see mergeModelOntology). Written only when new.
	 */
	private ModelOntologyData noteDeclaration(ModelOntologyData row, String declaredName, String declaredVersion,
			WhoUpdated wu) {
		String version = StringUtils.isBlank(declaredVersion)
				|| ModelOntologyData.UNKNOWN_VERSION.equalsIgnoreCase(declaredVersion.strip()) ? null : declaredVersion.strip();
		String joined = null == version ? null : ModelIdNormalizer.normalize(declaredName, version).base();
		boolean newAlias = StringUtils.isNotBlank(joined) && !joined.equals(ModelIdNormalizer.normalize(declaredName).base())
				&& (null == row.getAliases() || !row.getAliases().contains(joined));
		boolean newVersion = null != version && !version.equals(row.getDeclaredVersion());
		if (!newAlias && !newVersion) return row;
		if (newAlias) {
			List<String> aliases = new ArrayList<>(null == row.getAliases() ? List.of() : row.getAliases());
			aliases.add(joined);
			row.setAliases(aliases);
		}
		if (newVersion) row.setDeclaredVersion(version);
		return saveData(row, wu);
	}

	/** The name the synthetic pseudo-model is stored under; one per organization. */
	public static final String SYNTHETIC_MODEL = "synthetic";

	/**
	 * The organization's pseudo-model for placeholders (task RD2-26): created on first use, never
	 * priced, and not counted as unresolved.
	 */
	public ModelOntologyData syntheticModel(UUID orgUuid, WhoUpdated wu) throws RelizaException {
		Optional<ModelOntologyData> existing = listByOrg(orgUuid).stream()
				.filter(m -> m.getResolution() == ModelResolution.SYNTHETIC).findFirst();
		if (existing.isPresent()) return existing.get();
		ModelOntologyData seed = new ModelOntologyData();
		seed.setOrg(orgUuid);
		seed.setName(SYNTHETIC_MODEL);
		seed.setVersion(ModelOntologyData.UNKNOWN_VERSION);
		seed.setResolution(ModelResolution.SYNTHETIC);
		seed.setDescription("Placeholder model: usage reported with no real model (<synthetic>, blank or unknown)");
		seed.setAliases(new ArrayList<>());
		seed.setModelCard(new HashMap<>());
		seed.setModelCardSpecVersion(ModelCardSpecVersion.V2_0);
		ModelOntologyData created = insertResolved(seed, wu);
		aliasCache.remove(orgUuid);
		return created;
	}

	/**
	 * The org's catalogue row a written reference names, without creating one.
	 *
	 * <p>For configuration, where a model the organization does not have is a mistake in the file
	 * rather than something new to record. Matches exactly what {@link #resolve} matches first:
	 * names, name with version, canonical ids and aliases, and a bare name only when one row owns
	 * it -- so an ambiguous bare name finds nothing, and the caller says so.
	 */
	public Optional<ModelOntologyData> findByReference(UUID orgUuid, String reference) {
		if (null == orgUuid || StringUtils.isBlank(reference)) return Optional.empty();
		String key = ModelIdNormalizer.normalize(reference).base();
		if (StringUtils.isBlank(key)) return Optional.empty();
		UUID hit = aliasIndex(orgUuid).get(key);
		return null == hit ? Optional.empty() : getModelOntologyData(hit);
	}

	/**
	 * How configuration should write a reference to this row so {@link #findByReference} finds it
	 * again: the canonical id where there is one, otherwise the name joined with its version the
	 * way the index joins them.
	 */
	public static String referenceOf(ModelOntologyData m) {
		if (StringUtils.isNotBlank(m.getCanonicalId())) return m.getCanonicalId();
		String v = m.getVersion();
		return StringUtils.isNotBlank(v) && !"unknown".equalsIgnoreCase(v.trim())
				? m.getName() + "-" + v.trim() : m.getName();
	}

	/**
	 * Build the org's normalised-string index from its rows: every row's name, its name joined
	 * with its version, its canonical id and its stored aliases.
	 */
	private Map<String, UUID> aliasIndex(UUID orgUuid) {
		return aliasCache.computeIfAbsent(orgUuid, org -> {
			List<ModelOntologyData> rows = listByOrg(org);
			Map<String, UUID> index = new HashMap<>();
			for (ModelOntologyData mod : rows) {
				putAlias(index, ModelIdNormalizer.normalize(mod.getName(), mod.getVersion()).base(), mod);
				if (StringUtils.isNotBlank(mod.getCanonicalId())) {
					putAlias(index, ModelIdNormalizer.normalize(mod.getCanonicalId()).base(), mod);
				}
				if (null != mod.getAliases()) {
					mod.getAliases().forEach(a -> putAlias(index, ModelIdNormalizer.normalize(a).base(), mod));
				}
			}

			// The bare name -- the row's name with its version dropped -- is added only where
			// exactly one row claims it.
			//
			// Indexing it unconditionally was a false alarm generator: an org running two versions
			// of one model (claude-opus 4.5 and 4.7) has two rows whose bare name is the same
			// string, so every cache rebuild logged a collision at error level on a completely
			// healthy catalogue. Error level is paged on here, and an alert that fires on the
			// normal case trains people to ignore the one that matters.
			//
			// Dropping it outright would have cost something real, though: with a single row for
			// a model, an agent declaring just "claude-opus" should still resolve to it. So the
			// bare name is indexed when it is unambiguous and skipped when it is not -- an
			// ambiguous bare name resolves to a new unresolved row, which is the honest answer,
			// because nothing in the declaration says which version ran.
			Map<String, List<UUID>> bareNames = new HashMap<>();
			for (ModelOntologyData mod : rows) {
				String bare = ModelIdNormalizer.normalize(mod.getName()).base();
				if (StringUtils.isBlank(bare)) continue;
				bareNames.computeIfAbsent(bare, k -> new ArrayList<>()).add(mod.getUuid());
			}
			bareNames.forEach((bare, owners) -> {
				if (owners.size() == 1) index.putIfAbsent(bare, owners.get(0));
			});
			return index;
		});
	}

	private void putAlias(Map<String, UUID> index, String key, ModelOntologyData mod) {
		if (StringUtils.isBlank(key)) return;
		UUID prior = index.putIfAbsent(key, mod.getUuid());
		if (prior != null && !prior.equals(mod.getUuid())) {
			// Two rows claiming one string. Resolution keeps the first deterministically rather
			// than picking at random, and says so: this is what the fold sweep exists to clear.
			log.error("Model alias '{}' claimed by both {} and {} in org {}; keeping {}",
					key, prior, mod.getUuid(), mod.getOrg(), prior);
		}
	}

	private ModelOntologyData registerFromBundle(UUID orgUuid, ModelCatalogueBundle.BundledModel bm,
			String normalisedBase, String declaredVersion, WhoUpdated wu) throws RelizaException {
		ModelOntologyData seed = new ModelOntologyData();
		seed.setOrg(orgUuid);
		seed.setName(bm.name());
		seed.setVersion(StringUtils.isNotBlank(bm.version()) ? bm.version() : ModelOntologyData.UNKNOWN_VERSION);
		seed.setPublisher(bm.publisher());
		seed.setCanonicalId(bm.canonicalId());
		seed.setTier(bm.tier());
		seed.setStrength(bm.strength());
		seed.setFacts(bm.facts());
		seed.setResolution(ModelResolution.RESOLVED);
		Set<String> aliases = new LinkedHashSet<>(bm.aliases());
		aliases.add(normalisedBase);
		seed.setAliases(new ArrayList<>(aliases));
		seed.setDeclaredVersion(StringUtils.isBlank(declaredVersion) ? null : declaredVersion.strip());
		seed.setModelCard(new HashMap<>());
		seed.setModelCardSpecVersion(ModelCardSpecVersion.V2_0);
		return insertResolved(seed, wu);
	}

	private ModelOntologyData registerUnresolved(UUID orgUuid, String declaredName, String declaredVersion,
			String normalisedBase, WhoUpdated wu) throws RelizaException {
		ModelOntologyData seed = new ModelOntologyData();
		seed.setOrg(orgUuid);
		seed.setName(StringUtils.isNotBlank(declaredName) ? declaredName.trim() : normalisedBase);
		seed.setVersion(StringUtils.isNotBlank(declaredVersion)
				? declaredVersion.trim().toLowerCase(Locale.ROOT) : ModelOntologyData.UNKNOWN_VERSION);
		seed.setResolution(ModelResolution.UNRESOLVED);
		seed.setAliases(new ArrayList<>(List.of(normalisedBase)));
		seed.setModelCard(new HashMap<>());
		seed.setModelCardSpecVersion(ModelCardSpecVersion.V2_0);
		return insertResolved(seed, wu);
	}

	/**
	 * Insert through the proxy so a unique-key collision rolls back only this attempt, then
	 * re-read the winner -- the same race handling {@link #findOrRegisterModel} uses.
	 */
	private ModelOntologyData insertResolved(ModelOntologyData seed, WhoUpdated wu) throws RelizaException {
		try {
			return self.insertIsolated(seed, wu);
		} catch (DataIntegrityViolationException e) {
			log.info("Concurrent catalogue registration for (org={}, name='{}', version='{}') -- re-reading winner",
					seed.getOrg(), seed.getName(), seed.getVersion());
			return repository.findByOrgNameVersion(seed.getOrg().toString(), seed.getName(), seed.getVersion())
					.map(ModelOntologyData::dataFromRecord)
					.orElseThrow(() -> new RelizaException(
							"Catalogue registration race detected but no winning row found"));
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ModelOntologyData insertIsolated(ModelOntologyData seed, WhoUpdated wu) {
		ModelOntology saved = save(new ModelOntology(), Utils.dataToRecord(seed), wu);
		log.info("Registered ModelOntology uuid={} name='{}' version='{}' resolution={} org={}",
				saved.getUuid(), seed.getName(), seed.getVersion(), seed.getResolution(), seed.getOrg());
		return ModelOntologyData.dataFromRecord(saved);
	}

	/** Drop an org's alias index; called by every path that writes a catalogue row. */
	public void invalidateAliasCache(UUID orgUuid) {
		aliasCache.remove(orgUuid);
	}


	/**
	 * Top-level keys that only a CycloneDX 1.6 card has. {@code modelCard} is the 1.6 component
	 * wrapper; the other three are the containers of the card body, which is the shape ReARM
	 * itself stored before 2.0 and therefore the likelier thing for a caller to resend. The brief
	 * names only the wrapper, but refusing the wrapper alone would let a bare 1.6 body through and
	 * be stamped 2.0 by {@link #setModelCard} -- marked converted while still being 1.6, which the
	 * sweep would then never look at again.
	 */
	private static final java.util.List<String> LEGACY_CARD_KEYS = java.util.List.of(
			"modelCard", "modelParameters", "quantitativeAnalysis", "considerations");



	/**
	 * Add a dated rate.
	 *
	 * <p>Entries are append-only: nothing here edits an existing one, because a rollup records
	 * the entry uuid it priced under and that reference has to keep meaning the same numbers. A
	 * correction is a new entry plus {@link #expireModelPricing} on the old one.
	 */
	@Transactional
	public ModelOntologyData addModelPricing(UUID ontologyUuid, PricingEntry entry, WhoUpdated wu)
			throws RelizaException {
		if (null == entry || null == entry.effectiveFrom()) {
			throw new RelizaException("A pricing entry requires effectiveFrom");
		}
		ModelOntologyData mod = getModelOntologyData(ontologyUuid)
				.orElseThrow(() -> new RelizaException("ModelOntology not found: " + ontologyUuid));
		PricingEntry stored = new PricingEntry(UUID.randomUUID(), entry.effectiveFrom(),
				entry.effectiveTo(),
				StringUtils.defaultIfBlank(entry.currency(), PricingEntry.CURRENCY_USD),
				null != entry.unit() ? entry.unit() : PricingUnit.PER_MILLION_TOKENS,
				entry.inputMicros(), entry.outputMicros(), entry.cacheReadMicros(),
				entry.cacheWriteMicros(), entry.reasoningMicros(), entry.appliesTo(),
				entry.source(), entry.note(), null != wu ? wu.getLastUpdatedBy() : null,
				ZonedDateTime.now());
		List<PricingEntry> pricing = new ArrayList<>(
				null != mod.getPricing() ? mod.getPricing() : List.of());
		pricing.add(stored);
		mod.setPricing(pricing);
		return saveData(mod, wu);
	}

	/** Close an open rate. The entry itself is untouched apart from its end date. */
	@Transactional
	public ModelOntologyData expireModelPricing(UUID ontologyUuid, UUID entryUuid,
			ZonedDateTime effectiveTo, WhoUpdated wu) throws RelizaException {
		ModelOntologyData mod = getModelOntologyData(ontologyUuid)
				.orElseThrow(() -> new RelizaException("ModelOntology not found: " + ontologyUuid));
		List<PricingEntry> pricing = new ArrayList<>(
				null != mod.getPricing() ? mod.getPricing() : List.of());
		boolean found = false;
		for (int i = 0; i < pricing.size(); i++) {
			PricingEntry e = pricing.get(i);
			if (e.uuid().equals(entryUuid)) {
				pricing.set(i, new PricingEntry(e.uuid(), e.effectiveFrom(), effectiveTo, e.currency(),
						e.unit(), e.inputMicros(), e.outputMicros(), e.cacheReadMicros(),
						e.cacheWriteMicros(), e.reasoningMicros(), e.appliesTo(), e.source(),
						e.note(), e.createdBy(), e.createdAt()));
				found = true;
			}
		}
		if (!found) throw new RelizaException("Pricing entry not found: " + entryUuid);
		mod.setPricing(pricing);
		return saveData(mod, wu);
	}

	/**
	 * Create or refresh an org's row from the bundled catalogue.
	 *
	 * <p>Facts, aliases, tier and canonical id only: pricing and anything an admin typed are left
	 * exactly as they were, because the bundle knows what a model IS and the org knows what it
	 * costs and what it is called locally.
	 */
	@Transactional
	public ModelOntologyData applyModelCataloguePreset(UUID orgUuid, String canonicalId, WhoUpdated wu)
			throws RelizaException {
		ModelCatalogueBundle.BundledModel bm = catalogueBundle
				.find(ModelIdNormalizer.normalize(canonicalId).base())
				.orElseThrow(() -> new RelizaException("No bundled model with canonical id " + canonicalId));
		ResolvedModel resolved = resolve(orgUuid, bm.name(), bm.version(), wu);
		ModelOntologyData mod = resolved.model();
		mod.setCanonicalId(bm.canonicalId());
		mod.setPublisher(StringUtils.defaultIfBlank(bm.publisher(), mod.getPublisher()));
		if (null != bm.tier()) mod.setTier(bm.tier());
		// Strength is the operator's call once they have made one: a preset refresh fills it in
		// when the row has none and leaves an edited value alone, the same rule pricing follows.
		if (null == mod.getStrength()) mod.setStrength(bm.strength());
		mod.setFacts(bm.facts());
		mod.setResolution(ModelResolution.RESOLVED);
		Set<String> aliases = new LinkedHashSet<>(
				null != mod.getAliases() ? mod.getAliases() : List.of());
		aliases.addAll(bm.aliases());
		mod.setAliases(new ArrayList<>(aliases));
		return saveData(mod, wu);
	}

	/**
	 * Fold one catalogue row into another: the folded row's name and aliases become aliases of
	 * the target, and the row is removed.
	 *
	 * <p>Re-pointing agents, sessions and usage rows is the caller's job in the sweep; this is
	 * the admin-driven single merge, which refuses when the two rows are not in the same org.
	 */
	@Transactional
	public ModelOntologyData mergeModelOntology(UUID from, UUID into, WhoUpdated wu) throws RelizaException {
		if (from.equals(into)) throw new RelizaException("Cannot merge a model into itself");
		ModelOntologyData source = getModelOntologyData(from)
				.orElseThrow(() -> new RelizaException("ModelOntology not found: " + from));
		ModelOntologyData target = getModelOntologyData(into)
				.orElseThrow(() -> new RelizaException("ModelOntology not found: " + into));
		if (!source.getOrg().equals(target.getOrg())) {
			throw new RelizaException("Cannot merge models across organizations");
		}
		Set<String> aliases = new LinkedHashSet<>(
				null != target.getAliases() ? target.getAliases() : List.of());
		// The folded row's full identity -- name AND version -- becomes an alias. Its BARE name
		// deliberately does not: storing that would make "claude-opus" resolve to whichever version
		// happened to be merged first, permanently, even after a second version exists as its own
		// row. The index already offers a bare name when exactly one row claims it, and withdraws
		// it the moment that stops being true, which is the behaviour a stored alias cannot have.
		aliases.add(ModelIdNormalizer.normalize(source.getName(), source.getVersion()).base());
		if (null != source.getAliases()) aliases.addAll(source.getAliases());
		aliases.removeIf(StringUtils::isBlank);
		target.setAliases(new ArrayList<>(aliases));
		ModelOntologyData saved = saveData(target, wu);
		// Re-point BEFORE the delete, in this same transaction. Skipping it leaves every agent,
		// session and usage row that named the folded model pointing at a uuid that no longer
		// exists: their model lookups return null and their cost silently becomes null, which
		// looks like a pricing gap rather than the dangling reference it is. This used to live
		// only in the sweep, so the sweep was safe and this admin path was not.
		repointModelReferences(from, into);
		repository.deleteById(from);
		invalidateAliasCache(source.getOrg());
		log.info("Merged ModelOntology {} into {} for org {}", from, into, source.getOrg());
		return saved;
	}

	/**
	 * Re-point everything that names a model at another one.
	 *
	 * <p>Lives here rather than in the sweep because both callers need it and only one of them had
	 * it: the fold re-pointed correctly while the admin merge deleted the row out from under its
	 * references. Anything that removes a model row must call this first.
	 *
	 * @return the number of rows moved, for the caller's log
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int repointModelReferences(UUID from, UUID into) {
		int agents = agentRepository.repointModel(from.toString(), into.toString());
		int sessions = agentSessionRepository.repointModel(from.toString(), into.toString());
		int usage = repointUsage(from, into);
		// Role configs and presets key their strength overrides by model uuid. Left alone, an
		// override for the folded row silently stops applying -- nothing errors, the role just
		// starts reading the survivor's catalogue strength instead of what the operator set.
		int roles = agentBoardService.repointModelStrengths(from, into);
		if (agents + sessions + usage + roles > 0) {
			log.info("Re-pointed {} agent(s), {} session(s), {} usage row(s) and {} role override(s)"
					+ " from model {} to {}", agents, sessions, usage, roles, from, into);
		}
		return agents + sessions + usage + roles;
	}

	/**
	 * Re-point a folded model's usage rows at the survivor without losing a report (task RD2-26).
	 * A row with no twin on the survivor is re-keyed in bulk. A row whose twin exists -- the same
	 * session, client seq, hosting and context band, once re-pointed -- is summed into it: tokens by
	 * class, requests, turns, tool calls, wall time and the client's reported cost; the widest and
	 * narrowest request contexts; the earliest reportedAt. The duplicate is then deleted.
	 *
	 * @return the rows moved or summed
	 */
	int repointUsage(UUID from, UUID into) {
		int moved = usageRepository.repointModelWithoutCollision(from, into);
		int summed = 0;
		for (io.reliza.model.AgentSessionUsage row : usageRepository.findByModel(from)) {
			io.reliza.model.AgentSessionUsage twin = usageRepository
					.findBySessionAndClientSeqAndModelAndHostingAndContextBand(row.getSession(), row.getClientSeq(),
							into, row.getHosting(), row.getContextBand())
					.orElse(null);
			if (null == twin) {
				row.setModel(into);
				usageRepository.save(row);
				moved++;
				continue;
			}
			sumInto(twin, row);
			usageRepository.save(twin);
			usageRepository.delete(row);
			summed++;
		}
		if (summed > 0) {
			log.info("Summed {} colliding usage row(s) of model {} into their twins on {}", summed, from, into);
		}
		return moved + summed;
	}

	/** Adds one report into its twin, so no reported number is lost when the two are re-keyed onto one row. */
	static void sumInto(io.reliza.model.AgentSessionUsage twin, io.reliza.model.AgentSessionUsage row) {
		twin.setRequests(twin.getRequests() + row.getRequests());
		twin.setInputTokens(twin.getInputTokens() + row.getInputTokens());
		twin.setOutputTokens(twin.getOutputTokens() + row.getOutputTokens());
		twin.setCacheReadTokens(twin.getCacheReadTokens() + row.getCacheReadTokens());
		twin.setCacheWriteTokens(twin.getCacheWriteTokens() + row.getCacheWriteTokens());
		twin.setTurns(twin.getTurns() + row.getTurns());
		twin.setToolCalls(twin.getToolCalls() + row.getToolCalls());
		twin.setWallSeconds(twin.getWallSeconds() + row.getWallSeconds());
		twin.setMaxRequestContextTokens(Math.max(twin.getMaxRequestContextTokens(), row.getMaxRequestContextTokens()));
		long lo = twin.getMinRequestContextTokens();
		long other = row.getMinRequestContextTokens();
		twin.setMinRequestContextTokens(lo == 0 ? other : other == 0 ? lo : Math.min(lo, other));
		if (null != row.getReportedCostMicros()) {
			twin.setReportedCostMicros((null == twin.getReportedCostMicros() ? 0L : twin.getReportedCostMicros())
					+ row.getReportedCostMicros());
		}
		if (row.getReportedAt().isBefore(twin.getReportedAt())) twin.setReportedAt(row.getReportedAt());
		if (null == twin.getTask()) twin.setTask(row.getTask());
		if (null == twin.getBoard()) twin.setBoard(row.getBoard());
		twin.setRevision(twin.getRevision() + 1);
		twin.setLastUpdatedDate(ZonedDateTime.now());
	}

	/**
	 * Other rows of the org ranked by how close their normalised strings are to this one.
	 *
	 * <p>Suggestion only -- the UI offers the top few for a one-click merge, and an automatic
	 * merge happens on an exact normalised match and never on a score. A similarity that picked
	 * wrong would fold two models into one, which is not recoverable from the usage rows.
	 */
	public List<ModelOntologyData> mergeCandidates(ModelOntologyData mod, int limit) {
		String base = ModelIdNormalizer.normalize(mod.getName(), mod.getVersion()).base();
		Set<String> mine = tokenize(base);
		String canonical = StringUtils.defaultString(mod.getCanonicalId());
		// Resolved rows too (task RD2-27): a row with this one's canonical id is the same model and
		// leads, then the rest by similarity. The synthetic pseudo-model is never a candidate.
		java.util.function.ToIntFunction<ModelOntologyData> sameModel = o -> !canonical.isEmpty()
				&& canonical.equalsIgnoreCase(StringUtils.defaultString(o.getCanonicalId())) ? 0 : 1;
		return listByOrg(mod.getOrg()).stream()
				.filter(other -> !other.getUuid().equals(mod.getUuid()))
				.filter(other -> ModelResolution.SYNTHETIC != other.getResolution())
				.sorted(java.util.Comparator.comparingInt(sameModel).thenComparing((a, b) -> Double.compare(
						similarity(mine, ModelIdNormalizer.normalize(b.getName(), b.getVersion()).base()),
						similarity(mine, ModelIdNormalizer.normalize(a.getName(), a.getVersion()).base()))))
				.limit(limit)
				.toList();
	}

	private static double similarity(Set<String> mine, String otherBase) {
		Set<String> theirs = tokenize(otherBase);
		if (mine.isEmpty() || theirs.isEmpty()) return 0;
		Set<String> shared = new LinkedHashSet<>(mine);
		shared.retainAll(theirs);
		return (double) shared.size() / Math.max(mine.size(), theirs.size());
	}

	/** Split on digit / letter boundaries so "opus47" and "claudeopus47" share tokens. */
	private static Set<String> tokenize(String base) {
		Set<String> tokens = new LinkedHashSet<>();
		for (String t : base.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")) {
			if (StringUtils.isNotBlank(t)) tokens.add(t);
		}
		return tokens;
	}

	public Optional<ModelOntology> getModelOntology(UUID uuid) {
		if (uuid == null) return Optional.empty();
		return repository.findById(uuid);
	}

	public Optional<ModelOntologyData> getModelOntologyData(UUID uuid) {
		return getModelOntology(uuid).map(ModelOntologyData::dataFromRecord);
	}

	public List<ModelOntologyData> listByOrg(UUID orgUuid) {
		if (orgUuid == null) return List.of();
		return repository.findByOrg(orgUuid.toString()).stream()
				.map(ModelOntologyData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/**
	 * Find-or-create a ModelOntology row for {@code (org, name,
	 * version)}. Idempotent under the V38 unique index — concurrent
	 * first-init calls race; the loser catches the violation and
	 * re-reads the winner. Subsequent calls do not mutate refinable
	 * fields the user may have edited (publisher, description, etc.).
	 *
	 * <p>This runs as plain REQUIRED, so it joins any caller transaction
	 * (e.g. session initialize). The actual INSERT is delegated to
	 * {@link #registerModelIsolated} in its own REQUIRES_NEW transaction:
	 * on a unique-constraint collision Postgres aborts the <em>inner</em>
	 * transaction only, the caller's transaction stays clean, and the
	 * recovery re-read below succeeds. Doing the insert inline would abort
	 * the caller's transaction, leaving the re-read to fail with "current
	 * transaction is aborted".
	 */
	@Transactional
	public ModelOntologyData findOrRegisterModel(UUID orgUuid, String name, String version,
			String publisher, WhoUpdated wu) throws RelizaException {
		if (orgUuid == null) throw new RelizaException("ModelOntology requires an org");
		if (StringUtils.isBlank(name)) {
			throw new RelizaException("ModelOntology requires a name on auto-registration");
		}
		// Normalize the version at this single chokepoint. The V38 unique
		// index keys on (org, lower(name), version) — name is already
		// case-folded by the index and the lookup query, but version is
		// verbatim in both, so "1m" vs "1M" minted duplicate rows. Trim +
		// lowercase here so new rows collapse onto one key. Forward-only:
		// it stops new dups but does not merge rows written before this.
		String effectiveVersion = StringUtils.isNotBlank(version)
				? version.trim().toLowerCase(Locale.ROOT)
				: ModelOntologyData.UNKNOWN_VERSION;

		Optional<ModelOntology> existing = repository.findByOrgNameVersion(
				orgUuid.toString(), name, effectiveVersion);
		if (existing.isPresent()) {
			return ModelOntologyData.dataFromRecord(existing.get());
		}
		try {
			return self.registerModelIsolated(orgUuid, name, effectiveVersion, publisher, wu);
		} catch (DataIntegrityViolationException e) {
			log.info("Concurrent ModelOntology auto-registration race for (org={}, name='{}', version='{}') — re-reading winner",
					orgUuid, name, effectiveVersion);
			return repository.findByOrgNameVersion(orgUuid.toString(), name, effectiveVersion)
					.map(ModelOntologyData::dataFromRecord)
					.orElseThrow(() -> new RelizaException(
							"ModelOntology auto-registration race detected but no winning row found"));
		}
	}

	/**
	 * Insert a fresh ontology row in its <em>own</em> transaction.
	 * REQUIRES_NEW so a unique-constraint collision on the V38 index rolls
	 * back only this attempt — the caller's transaction is suspended and
	 * untouched, letting {@link #findOrRegisterModel} recover via re-read.
	 * Always invoked through {@code self} (the Spring proxy); a direct
	 * {@code this.*} call would silently degrade to the caller's
	 * transaction. {@code version} must already be normalized by the
	 * caller — this method does not re-apply the single-chokepoint rule.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ModelOntologyData registerModelIsolated(UUID orgUuid, String name, String effectiveVersion,
			String publisher, WhoUpdated wu) throws RelizaException {
		ModelOntologyData seed = new ModelOntologyData();
		seed.setOrg(orgUuid);
		seed.setName(name);
		seed.setVersion(effectiveVersion);
		seed.setPublisher(publisher);
		seed.setModelCard(new HashMap<>());
		// Born 2.0. An absent marker means 1.6 by definition, so leaving this null would have the
		// converter treat every freshly auto-registered row as a legacy card.
		seed.setModelCardSpecVersion(ModelCardSpecVersion.V2_0);

		ModelOntology m = new ModelOntology();
		Map<String, Object> recordData = Utils.dataToRecord(seed);
		ModelOntology saved = save(m, recordData, wu);
		log.info("Auto-registered ModelOntology uuid={} name='{}' version='{}' org={}",
				saved.getUuid(), name, effectiveVersion, orgUuid);
		return ModelOntologyData.dataFromRecord(saved);
	}

	/**
	 * Attach a CycloneDX ML-BOM model card to an existing ontology row.
	 * Replaces any prior model card. The card is stored opaquely — ReARM
	 * does not validate its CycloneDX shape beyond JSON parsing and the
	 * 2.0 check below.
	 *
	 * <p><b>2.0 only.</b> A payload with a top-level {@code modelCard} key is a 1.6 card being
	 * sent as-is, and is refused rather than stored: the one-time converter runs over the rows
	 * present at its sweep, so a 1.6 card written afterwards would sit marked 2.0 and never be
	 * converted. Refusing at the boundary is the only place that stays true over time. The message
	 * names the 2.0 shape because the caller is a script and needs to know what to send.
	 */
	@Transactional
	public ModelOntologyData setModelCard(UUID ontologyUuid, Map<String, Object> modelCard, WhoUpdated wu)
			throws RelizaException {
		String legacyKey = null == modelCard ? null
				: LEGACY_CARD_KEYS.stream().filter(modelCard::containsKey).findFirst().orElse(null);
		if (null != legacyKey) {
			throw new RelizaException("Model card must be sent in the CycloneDX 2.0 machine-learning-model "
					+ "component shape (modelProperties, measurements, parties, identifiers); the payload "
					+ "carries the 1.6 key '" + legacyKey + "'");
		}
		ModelOntology m = repository.findByIdWriteLocked(ontologyUuid)
				.orElseThrow(() -> new RelizaException("ModelOntology not found: " + ontologyUuid));
		ModelOntologyData mod = ModelOntologyData.dataFromRecord(m);
		mod.setModelCard(modelCard != null ? modelCard : new HashMap<>());
		// Written through this method the card is 2.0 by the guard above, so the marker is set
		// here too -- otherwise a row created before the sweep and given a card after it would
		// keep whatever marker the sweep left.
		mod.setModelCardSpecVersion(ModelCardSpecVersion.V2_0);
		return saveData(mod, wu);
	}

	/**
	 * Refine display fields. Only non-null arguments are applied so a
	 * partial edit can't blank unrelated fields.
	 */
	@Transactional
	public ModelOntologyData updateModelOntology(UUID ontologyUuid, String publisher, String description,
			String purl, String notes, WhoUpdated wu) throws RelizaException {
		return updateModelOntology(ontologyUuid, publisher, description, purl, notes, null, wu);
	}

	/**
	 * An operator's edit to a model's strength. Each part null means leave it: {@code strength}
	 * set with {@code clearStrength} false rates the model, {@code clearStrength} unrates it, and
	 * {@code strengthByRole} replaces the per-category list (empty clears it).
	 */
	public static record StrengthUpdate(Double strength, boolean clearStrength,
			List<ModelOntologyData.RoleStrength> strengthByRole) {}

	@Transactional
	public ModelOntologyData updateModelOntology(UUID ontologyUuid, String publisher, String description,
			String purl, String notes, StrengthUpdate strength, WhoUpdated wu) throws RelizaException {
		return updateModelOntology(ontologyUuid, publisher, description, purl, notes, strength, null, wu);
	}

	/**
	 * An operator's edit to what a row is (task RD2-27): its name and version, each null meaning leave
	 * it, and its canonical id when {@code canonicalIdSet} -- blank clears it and the row resolves
	 * from its name again; a bundled id links the row to that entry; any other id is the operator's
	 * confirmation, kept as typed.
	 */
	public static record IdentityUpdate(String name, String version, String canonicalId, boolean canonicalIdSet) {
		boolean touches() {
			return null != name || null != version || canonicalIdSet;
		}
	}

	@Transactional
	public ModelOntologyData updateModelOntology(UUID ontologyUuid, String publisher, String description,
			String purl, String notes, StrengthUpdate strength, IdentityUpdate identity, WhoUpdated wu)
			throws RelizaException {
		ModelOntology m = repository.findByIdWriteLocked(ontologyUuid)
				.orElseThrow(() -> new RelizaException("ModelOntology not found: " + ontologyUuid));
		ModelOntologyData mod = ModelOntologyData.dataFromRecord(m);
		if (null != identity && identity.touches()) applyIdentity(mod, identity);
		if (publisher != null) mod.setPublisher(publisher);
		if (description != null) mod.setDescription(description);
		if (purl != null) mod.setPurl(purl);
		if (notes != null) mod.setNotes(notes);
		if (null != strength) {
			if (strength.clearStrength()) {
				mod.setStrength(null);
			} else if (null != strength.strength()) {
				mod.setStrength(StrengthScale.validate("strength", strength.strength()));
			}
			if (null != strength.strengthByRole()) {
				mod.setStrengthByRole(validRoleStrengths(strength.strengthByRole()));
			}
		}
		return saveData(mod, wu);
	}

	/**
	 * Name, version and canonical id from an edit, and the resolution that follows from them. Refused:
	 * the synthetic pseudo-model; a blank name; a name and version another row of the org has; a
	 * canonical id another row carries -- each of those is a merge, which says what happens to the
	 * other row's usage, not an edit.
	 */
	private void applyIdentity(ModelOntologyData mod, IdentityUpdate identity) throws RelizaException {
		if (ModelResolution.SYNTHETIC == mod.getResolution()) {
			throw new RelizaException("The synthetic pseudo-model's name, version and canonical id are fixed");
		}
		String name = null == identity.name() ? mod.getName() : identity.name().strip();
		if (StringUtils.isBlank(name)) throw new RelizaException("A model needs a name");
		String version = null == identity.version() ? mod.getVersion()
				: StringUtils.isBlank(identity.version()) ? ModelOntologyData.UNKNOWN_VERSION : identity.version().strip();
		boolean renamed = !name.equalsIgnoreCase(StringUtils.defaultString(mod.getName()))
				|| !version.equals(mod.getVersion());
		List<ModelOntologyData> others = listByOrg(mod.getOrg()).stream()
				.filter(o -> !o.getUuid().equals(mod.getUuid())).toList();
		if (renamed) {
			// The resolver's rule, not the spelling's (RD2-27 T-1): the new name and version, and the
			// bare name, must not be a key another row already answers to -- its normalised name and
			// version, its canonical id, or an alias, the old identity a rename keeps included.
			// Otherwise two rows claim one key, and a declaration of it lands on either.
			for (String key : List.of(ModelIdNormalizer.normalize(name, version).base(), ModelIdNormalizer.normalize(name).base())) {
				Optional<ModelOntologyData> holder = holderOf(others, key);
				if (holder.isPresent()) {
					throw new RelizaException("Model " + holder.get().getName() + " " + holder.get().getVersion()
							+ " is already a row of this organization answering to '" + key
							+ "'; merge this row into it instead");
				}
			}
		}
		mod.setName(name);
		mod.setVersion(version);
		Set<String> aliases = new LinkedHashSet<>(null != mod.getAliases() ? mod.getAliases() : List.of());
		aliases.add(ModelIdNormalizer.normalize(name, version).base());

		Optional<ModelCatalogueBundle.BundledModel> bundled = Optional.empty();
		String canonical = mod.getCanonicalId();
		ModelResolution resolution = mod.getResolution();
		if (identity.canonicalIdSet() && StringUtils.isNotBlank(identity.canonicalId())) {
			String typed = identity.canonicalId().strip();
			bundled = catalogueBundle.find(ModelIdNormalizer.normalize(typed).base())
					.filter(bm -> bm.canonicalId().equalsIgnoreCase(typed));
			canonical = bundled.map(ModelCatalogueBundle.BundledModel::canonicalId).orElse(typed);
			// Typed by an operator: confirmation, whether or not the bundle knows it (it prices only what it knows).
			resolution = ModelResolution.RESOLVED;
		} else if (identity.canonicalIdSet() || (renamed && ModelResolution.RESOLVED != resolution)) {
			// Cleared, or an unresolved row renamed: resolve from the name as a declaration would.
			bundled = Optional.empty();
			for (String candidate : ModelIdNormalizer.candidates(name, version)) {
				bundled = catalogueBundle.find(candidate);
				if (bundled.isPresent()) break;
			}
			canonical = bundled.map(ModelCatalogueBundle.BundledModel::canonicalId).orElse(null);
			resolution = bundled.isPresent() ? ModelResolution.RESOLVED : ModelResolution.UNRESOLVED;
		}
		if (StringUtils.isNotBlank(canonical) && !canonical.equalsIgnoreCase(StringUtils.defaultString(mod.getCanonicalId()))) {
			final String c = canonical;
			Optional<ModelOntologyData> holder = others.stream()
					.filter(o -> c.equalsIgnoreCase(StringUtils.defaultString(o.getCanonicalId())))
					.findFirst()
					// Another row's alias or name is as much its key as its canonical id is.
					.or(() -> holderOf(others, ModelIdNormalizer.normalize(c).base()));
			if (holder.isPresent()) {
				throw new RelizaException("Model " + holder.get().getName() + " " + holder.get().getVersion()
						+ " already carries canonical id " + c + "; merge this row into it instead");
			}
		}
		if (bundled.isPresent()) {
			ModelCatalogueBundle.BundledModel bm = bundled.get();
			if (StringUtils.isBlank(mod.getPublisher())) mod.setPublisher(bm.publisher());
			if (null == mod.getTier()) mod.setTier(bm.tier());
			if (null == mod.getStrength()) mod.setStrength(bm.strength());
			if (null == mod.getFacts()) mod.setFacts(bm.facts());
			aliases.addAll(bm.aliases());
		}
		aliases.removeIf(StringUtils::isBlank);
		mod.setAliases(new ArrayList<>(aliases));
		mod.setCanonicalId(canonical);
		mod.setResolution(resolution);
		invalidateAliasCache(mod.getOrg());
	}

	/**
	 * The row among {@code rows} that answers to an index key: by its normalised name and version, its
	 * canonical id or an alias -- the keys {@link #aliasIndex} indexes for every row.
	 */
	private static Optional<ModelOntologyData> holderOf(List<ModelOntologyData> rows, String key) {
		if (StringUtils.isBlank(key)) return Optional.empty();
		for (ModelOntologyData o : rows) {
			if (key.equals(ModelIdNormalizer.normalize(o.getName(), o.getVersion()).base())) return Optional.of(o);
			if (StringUtils.isNotBlank(o.getCanonicalId()) && key.equals(ModelIdNormalizer.normalize(o.getCanonicalId()).base())) {
				return Optional.of(o);
			}
			if (null != o.getAliases()) {
				for (String a : o.getAliases()) {
					if (key.equals(ModelIdNormalizer.normalize(a).base())) return Optional.of(o);
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * How much a model has been used lately (task RD2-27): distinct sessions and usage lines reported
	 * on it in the last {@code days} days, so an admin can tell which of two duplicates is live.
	 */
	public ModelUsageCount usage(UUID model, int days) {
		if (null == model) return new ModelUsageCount(0, 0, days);
		int d = Math.max(1, days);
		List<Object[]> rows = usageRepository.countSessionsAndLinesSince(model, ZonedDateTime.now().minusDays(d));
		Object[] r = rows.isEmpty() ? new Object[] { 0L, 0L } : rows.get(0);
		return new ModelUsageCount(((Number) r[0]).intValue(), ((Number) r[1]).intValue(), d);
	}

	public static record ModelUsageCount(int sessions, int lines, int days) {}

	/** The bundled canonical id a row's name and version resolve to, as a declaration would find it. */
	public Optional<String> suggestedCanonicalId(ModelOntologyData mod) {
		if (null == mod || ModelResolution.SYNTHETIC == mod.getResolution()) return Optional.empty();
		for (String candidate : ModelIdNormalizer.candidates(mod.getName(), mod.getVersion())) {
			Optional<ModelCatalogueBundle.BundledModel> bm = catalogueBundle.find(candidate);
			if (bm.isPresent()) return Optional.of(bm.get().canonicalId());
		}
		return Optional.empty();
	}

	/** The bundled catalogue's entries, for the edit form's canonical id select. */
	public List<ModelCatalogueBundle.BundledModel> bundledModels() {
		return catalogueBundle.all();
	}

	/** An operator's provenance for a model (task RD2-27). */
	@Transactional
	public ModelOntologyData setTier(UUID ontologyUuid, ModelOntologyData.ModelTier tier, WhoUpdated wu)
			throws RelizaException {
		ModelOntology m = repository.findByIdWriteLocked(ontologyUuid)
				.orElseThrow(() -> new RelizaException("ModelOntology not found: " + ontologyUuid));
		ModelOntologyData mod = ModelOntologyData.dataFromRecord(m);
		mod.setTier(tier);
		return saveData(mod, wu);
	}

	/** One value per category, each on the scale. */
	static List<ModelOntologyData.RoleStrength> validRoleStrengths(List<ModelOntologyData.RoleStrength> in)
			throws RelizaException {
		Set<ModelOntologyData.RoleCategory> seen = new java.util.HashSet<>();
		List<ModelOntologyData.RoleStrength> out = new ArrayList<>();
		for (ModelOntologyData.RoleStrength rs : in) {
			if (null == rs.category() || null == rs.strength()) {
				throw new RelizaException("A per-role strength needs both a category and a strength");
			}
			if (!seen.add(rs.category())) {
				throw new RelizaException("Strength for " + rs.category() + " is given more than once");
			}
			out.add(new ModelOntologyData.RoleStrength(rs.category(),
					StrengthScale.validate("strength for " + rs.category(), rs.strength())));
		}
		return out;
	}

	@Transactional
	public ModelOntologyData saveData(ModelOntologyData mod, WhoUpdated wu) {
		ModelOntology m = repository.findById(mod.getUuid())
				.orElseGet(() -> {
					ModelOntology fresh = new ModelOntology();
					fresh.setUuid(mod.getUuid() != null ? mod.getUuid() : UUID.randomUUID());
					return fresh;
				});
		Map<String, Object> recordData = Utils.dataToRecord(mod);
		ModelOntology saved = save(m, recordData, wu);
		return ModelOntologyData.dataFromRecord(saved);
	}

	private ModelOntology save(ModelOntology m, Map<String, Object> recordData, WhoUpdated wu) {
		Optional<ModelOntology> existing = repository.findById(m.getUuid());
		if (existing.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.MODEL_ONTOLOGIES, m);
			m.setRevision(m.getRevision() + 1);
			m.setLastUpdatedDate(ZonedDateTime.now());
		}
		m.setRecordData(recordData);
		m = (ModelOntology) WhoUpdated.injectWhoUpdatedData(m, wu);
		ModelOntology saved = repository.save(m);
		// Every catalogue write passes here, so this is the one place the alias index has to be
		// dropped. Wholesale rather than per-alias: catalogue writes are rare and a stale alias
		// misattributes cost, which is far more expensive than rebuilding tens of rows.
		ModelOntologyData sd = ModelOntologyData.dataFromRecord(saved);
		if (null != sd.getOrg()) invalidateAliasCache(sd.getOrg());
		return saved;
	}
}
