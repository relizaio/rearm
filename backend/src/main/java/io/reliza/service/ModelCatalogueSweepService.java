/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.ModelOntologyData;
import io.reliza.model.ModelOntologyData.ModelCardSpecVersion;
import io.reliza.model.WhoUpdated;
import lombok.extern.slf4j.Slf4j;

/**
 * One-time repair of the model catalogue: fold the rows that are the same model written
 * differently, then mark every card with the spec version it is written in.
 *
 * <p>Under the old rules a model landed as several rows whenever agents declared it differently --
 * {@code claude-opus} / {@code 4.7} beside {@code claude-opus-4-7} / {@code unknown} beside
 * {@code opus4.7-1m}. Nothing was wrong with any of them; there was simply no normalisation, so
 * each string minted a row. Usage attributed across those rows would be three models' worth of
 * cost for one model's work.
 *
 * <p>Runs once per installation, claimed through a flag in {@code system_info} the same way the
 * API-token pepper is, so two pods starting together cannot both fold the same rows.
 */
@Slf4j
@Service
public class ModelCatalogueSweepService {

	@Autowired private ModelOntologyService modelOntologyService;
	@Autowired private SystemInfoService systemInfoService;
	@Autowired private OrganizationService organizationService;
	@Autowired private io.reliza.repositories.ModelOntologyRepository repository;
	@Autowired private io.reliza.repositories.AgentRepository agentRepository;
	@Autowired private io.reliza.repositories.AgentSessionRepository agentSessionRepository;
	@Autowired private io.reliza.repositories.AgentSessionUsageRepository usageRepository;
	@Autowired private ModelCatalogueBundle catalogueBundle;

	@EventListener(ApplicationReadyEvent.class)
	public void sweepOnStartup() {
		try {
			if (!systemInfoService.claimModelCatalogueSweep()) {
				log.debug("Model catalogue sweep already done on this installation");
				return;
			}
			int foldedTotal = 0;
			int markedTotal = 0;
			for (var org : organizationService.listAllOrganizationData()) {
				// Through the proxy, as dedupAllOrgs does: a call on this is not transactional, and
				// the fold's repoint requires a transaction (propagation MANDATORY), so the sweep
				// failed at startup on any installation that had duplicates to fold.
				foldedTotal += self().foldDuplicates(org.getUuid());
				markedTotal += self().markCardSpecVersions(org.getUuid());
			}
			log.info("Model catalogue sweep complete: {} row(s) folded, {} card(s) marked",
					foldedTotal, markedTotal);
		} catch (Exception e) {
			// A failed sweep must not stop the application: the catalogue still works, duplicates
			// just remain until somebody merges them by hand. Logged at error because it needs one.
			log.error("Model catalogue sweep failed", e);
			try {
				// Hand the claim back so the next start retries. Without this a sweep that died
				// halfway stayed marked done and its remaining duplicates were never folded.
				systemInfoService.releaseModelCatalogueSweep();
			} catch (Exception releaseFailure) {
				log.error("Could not release the model catalogue sweep claim after a failed sweep; "
						+ "it will not be retried automatically", releaseFailure);
			}
		}
	}

	/**
	 * Fold an org's rows that normalise to the same base string.
	 *
	 * <p>The survivor is the row with a card, else one the bundled catalogue recognises, else the
	 * oldest -- in that order because a card is the thing that took work to write. Every other
	 * row's name and aliases become the survivor's aliases, references are re-pointed, and the
	 * folded rows are deleted in the same transaction.
	 *
	 * <p>Groups keyed on the normalised base only, never on similarity: {@code 4.5} and
	 * {@code 4.7} never fold, and neither do two models that merely look alike.
	 */
	@Transactional
	public int foldDuplicates(UUID orgUuid) {
		Map<String, List<ModelOntologyData>> groups = new LinkedHashMap<>();
		for (ModelOntologyData mod : modelOntologyService.listByOrg(orgUuid)) {
			String base = ModelIdNormalizer.normalize(mod.getName(), mod.getVersion()).base();
			if (StringUtils.isBlank(base)) continue;
			groups.computeIfAbsent(base, k -> new ArrayList<>()).add(mod);
		}
		int folded = 0;
		for (Map.Entry<String, List<ModelOntologyData>> e : groups.entrySet()) {
			List<ModelOntologyData> rows = e.getValue();
			if (rows.size() < 2) continue;
			ModelOntologyData survivor = pickSurvivor(rows);
			Set<String> aliases = new LinkedHashSet<>(
					null != survivor.getAliases() ? survivor.getAliases() : List.of());
			aliases.add(e.getKey());
			List<Map<String, Object>> foldedCards = new ArrayList<>();
			for (ModelOntologyData row : rows) {
				if (row.getUuid().equals(survivor.getUuid())) continue;
				// Full identity only. The bare name is deliberately NOT stored: it would make
				// "claude-opus" resolve to whichever version was folded first, for good, even once
				// a second version exists as its own row. The alias index offers a bare name while
				// exactly one row claims it and withdraws it when that stops holding -- which a
				// stored alias cannot do.
				aliases.add(ModelIdNormalizer.normalize(row.getName(), row.getVersion()).base());
				if (null != row.getAliases()) aliases.addAll(row.getAliases());
				if (null != row.getModelCard() && !row.getModelCard().isEmpty()) {
					foldedCards.add(row.getModelCard());
				}
				repointReferences(row.getUuid(), survivor.getUuid());
				repository.deleteById(row.getUuid());
				folded++;
			}
			aliases.removeIf(StringUtils::isBlank);
			survivor.setAliases(new ArrayList<>(aliases));
			if ((null == survivor.getModelCard() || survivor.getModelCard().isEmpty())
					&& foldedCards.size() == 1) {
				survivor.setModelCard(foldedCards.get(0));
			} else if (!foldedCards.isEmpty()) {
				// Two cards for one model is a judgement nobody asked this sweep to make. Both are
				// kept where an admin can see them rather than one being chosen silently.
				Map<String, Object> card = null != survivor.getModelCard()
						? new LinkedHashMap<>(survivor.getModelCard()) : new LinkedHashMap<>();
				card.put("rearm:legacyFoldedCards", foldedCards);
				survivor.setModelCard(card);
				log.error("Folded rows for '{}' in org {} carried {} non-empty model cards; kept as "
						+ "rearm:legacyFoldedCards on {}", e.getKey(), orgUuid, foldedCards.size(),
						survivor.getUuid());
			}
			modelOntologyService.saveData(survivor, WhoUpdated.getAutoWhoUpdated());
			log.info("Folded {} row(s) into {} for '{}' in org {}",
					rows.size() - 1, survivor.getUuid(), e.getKey(), orgUuid);
		}
		if (folded > 0) modelOntologyService.invalidateAliasCache(orgUuid);
		return folded;
	}

	// ---------- one row per model (task RD2-26) ----------

	/**
	 * Every organization's catalogue, re-resolved and folded by canonical id. Run on a schedule under
	 * an advisory lock (SchedulingService), so it repeats as the bundle learns models and as older
	 * declarations drift in. One organization failing is logged and the others go on.
	 */
	public int dedupAllOrgs() {
		int total = 0;
		for (var org : organizationService.listAllOrganizationData()) {
			try {
				total += self().dedupCatalogue(org.getUuid());
			} catch (Exception e) {
				log.error("Model catalogue dedup failed for org {}", org.getUuid(), e);
			}
		}
		if (total > 0) log.info("Model catalogue dedup: {} row(s) re-resolved or folded", total);
		return total;
	}

	@Autowired @org.springframework.context.annotation.Lazy private ModelCatalogueSweepService selfRef;

	private ModelCatalogueSweepService self() {
		return null != selfRef ? selfRef : this;
	}

	/** Re-resolve the org's unresolved rows against the bundle, then fold the rows sharing a canonical id. */
	@Transactional(rollbackFor = io.reliza.exceptions.RelizaException.class)
	public int dedupCatalogue(UUID orgUuid) throws io.reliza.exceptions.RelizaException {
		return reResolve(orgUuid) + foldByCanonicalId(orgUuid);
	}

	/**
	 * Rows written before resolution learned the model, or before it tried the bare name, resolved
	 * again: one the bundle now knows takes its canonical id, facts and aliases (pricing and edited
	 * fields untouched, as a preset refresh); one named by a placeholder is folded into the synthetic
	 * pseudo-model. One nothing knows stays UNRESOLVED.
	 *
	 * @return rows resolved or folded
	 */
	@Transactional(rollbackFor = io.reliza.exceptions.RelizaException.class)
	public int reResolve(UUID orgUuid) throws io.reliza.exceptions.RelizaException {
		int changed = 0;
		for (ModelOntologyData mod : modelOntologyService.listByOrg(orgUuid)) {
			if (mod.getResolution() == ModelOntologyData.ModelResolution.RESOLVED
					|| mod.getResolution() == ModelOntologyData.ModelResolution.SYNTHETIC) {
				continue;
			}
			if (ModelIdNormalizer.isPlaceholder(mod.getName())) {
				ModelOntologyData synthetic = modelOntologyService.syntheticModel(orgUuid, WhoUpdated.getAutoWhoUpdated());
				if (!synthetic.getUuid().equals(mod.getUuid())) {
					modelOntologyService.repointModelReferences(mod.getUuid(), synthetic.getUuid());
					repository.deleteById(mod.getUuid());
					changed++;
				}
				continue;
			}
			for (String candidate : ModelIdNormalizer.candidates(mod.getName(), mod.getVersion())) {
				var bundled = catalogueBundle.find(candidate);
				if (bundled.isEmpty()) continue;
				var bm = bundled.get();
				mod.setCanonicalId(bm.canonicalId());
				if (StringUtils.isBlank(mod.getPublisher())) mod.setPublisher(bm.publisher());
				if (null == mod.getTier()) mod.setTier(bm.tier());
				if (null == mod.getStrength()) mod.setStrength(bm.strength());
				if (null == mod.getFacts()) mod.setFacts(bm.facts());
				mod.setResolution(ModelOntologyData.ModelResolution.RESOLVED);
				Set<String> aliases = new LinkedHashSet<>(null != mod.getAliases() ? mod.getAliases() : List.of());
				aliases.addAll(bm.aliases());
				aliases.add(candidate);
				aliases.removeIf(StringUtils::isBlank);
				mod.setAliases(new ArrayList<>(aliases));
				modelOntologyService.saveData(mod, WhoUpdated.getAutoWhoUpdated());
				changed++;
				break;
			}
		}
		if (changed > 0) modelOntologyService.invalidateAliasCache(orgUuid);
		return changed;
	}

	/**
	 * Fold the org's rows that are one model by canonical id (task RD2-26), the way the admin merge
	 * folds: the survivor takes each folded row's full identity and aliases, its pricing when it has
	 * none, its strengths where unset and its card as foldDuplicates does; agents, sessions, role
	 * overrides and usage are re-pointed (colliding usage rows summed) and the folded row is deleted.
	 * The survivor is the resolved row that carries pricing, else a resolved one, else the oldest.
	 */
	@Transactional
	public int foldByCanonicalId(UUID orgUuid) {
		Map<String, List<ModelOntologyData>> groups = new LinkedHashMap<>();
		for (ModelOntologyData mod : modelOntologyService.listByOrg(orgUuid)) {
			if (StringUtils.isBlank(mod.getCanonicalId())) continue;
			groups.computeIfAbsent(mod.getCanonicalId().toLowerCase(java.util.Locale.ROOT), k -> new ArrayList<>()).add(mod);
		}
		int folded = 0;
		for (Map.Entry<String, List<ModelOntologyData>> e : groups.entrySet()) {
			List<ModelOntologyData> rows = e.getValue();
			if (rows.size() < 2) continue;
			ModelOntologyData survivor = rows.stream().sorted(Comparator
					.comparing((ModelOntologyData m) -> isResolved(m) && hasPricing(m) ? 0 : 1)
					.thenComparing(m -> isResolved(m) ? 0 : 1)
					.thenComparing(m -> null != m.getCreatedDate() ? m.getCreatedDate() : java.time.ZonedDateTime.now()))
					.findFirst().orElseThrow();
			Set<String> aliases = new LinkedHashSet<>(null != survivor.getAliases() ? survivor.getAliases() : List.of());
			List<Map<String, Object>> foldedCards = new ArrayList<>();
			for (ModelOntologyData row : rows) {
				if (row.getUuid().equals(survivor.getUuid())) continue;
				// Full identity only, never the bare name (see foldDuplicates and mergeModelOntology).
				aliases.add(ModelIdNormalizer.normalize(row.getName(), row.getVersion()).base());
				if (null != row.getAliases()) aliases.addAll(row.getAliases());
				if (!hasPricing(survivor) && hasPricing(row)) survivor.setPricing(new ArrayList<>(row.getPricing()));
				if (null == survivor.getStrength()) survivor.setStrength(row.getStrength());
				if ((null == survivor.getStrengthByRole() || survivor.getStrengthByRole().isEmpty())
						&& null != row.getStrengthByRole() && !row.getStrengthByRole().isEmpty()) {
					survivor.setStrengthByRole(new ArrayList<>(row.getStrengthByRole()));
				}
				if (StringUtils.isBlank(survivor.getDeclaredVersion())) survivor.setDeclaredVersion(row.getDeclaredVersion());
				if (null != row.getModelCard() && !row.getModelCard().isEmpty()) foldedCards.add(row.getModelCard());
				repointReferences(row.getUuid(), survivor.getUuid());
				repository.deleteById(row.getUuid());
				folded++;
			}
			aliases.removeIf(StringUtils::isBlank);
			survivor.setAliases(new ArrayList<>(aliases));
			absorbCards(survivor, foldedCards, e.getKey(), orgUuid);
			modelOntologyService.saveData(survivor, WhoUpdated.getAutoWhoUpdated());
			log.info("Folded {} row(s) into {} for canonical id '{}' in org {}", rows.size() - 1, survivor.getUuid(),
					e.getKey(), orgUuid);
		}
		if (folded > 0) modelOntologyService.invalidateAliasCache(orgUuid);
		return folded;
	}

	private static boolean isResolved(ModelOntologyData m) {
		return m.getResolution() == ModelOntologyData.ModelResolution.RESOLVED;
	}

	private static boolean hasPricing(ModelOntologyData m) {
		return null != m.getPricing() && !m.getPricing().isEmpty();
	}

	/**
	 * A folded row's model card onto the survivor: adopted when the survivor has none and one came,
	 * kept beside it as {@code rearm:legacyFoldedCards} when there are more -- a choice between two
	 * cards is nobody's to make silently.
	 */
	private static void absorbCards(ModelOntologyData survivor, List<Map<String, Object>> foldedCards, String what,
			UUID orgUuid) {
		if ((null == survivor.getModelCard() || survivor.getModelCard().isEmpty()) && foldedCards.size() == 1) {
			survivor.setModelCard(foldedCards.get(0));
		} else if (!foldedCards.isEmpty()) {
			Map<String, Object> card = null != survivor.getModelCard()
					? new LinkedHashMap<>(survivor.getModelCard()) : new LinkedHashMap<>();
			card.put("rearm:legacyFoldedCards", foldedCards);
			survivor.setModelCard(card);
			log.error("Folded rows for '{}' in org {} carried {} non-empty model cards; kept as "
					+ "rearm:legacyFoldedCards on {}", what, orgUuid, foldedCards.size(), survivor.getUuid());
		}
	}

	private ModelOntologyData pickSurvivor(List<ModelOntologyData> rows) {
		return rows.stream()
				.sorted(Comparator
						.comparing((ModelOntologyData m) ->
								null != m.getModelCard() && !m.getModelCard().isEmpty() ? 0 : 1)
						.thenComparing(m -> StringUtils.isNotBlank(m.getCanonicalId()) ? 0 : 1)
						.thenComparing(m -> null != m.getCreatedDate() ? m.getCreatedDate()
								: java.time.ZonedDateTime.now()))
				.findFirst().orElseThrow();
	}

	/**
	 * Re-point everything that names the folded row before it is deleted.
	 *
	 * <p>Delegates to the service: the admin merge needs the identical step, and having two copies
	 * is how the merge path came to be missing it.
	 */
	private void repointReferences(UUID from, UUID into) {
		modelOntologyService.repointModelReferences(from, into);
	}

	/**
	 * Convert every unmarked card to the 2.0 shape and mark it.
	 *
	 * <p>An unmarked card predates the converter, so it is 1.6 by definition and is run through
	 * {@link ModelCardConverter}. Empty cards convert to themselves and are marked without a write
	 * to the card, which is also what stops the sweep from looking at them again.
	 *
	 * <p>Runs AFTER the fold, so a card absorbed onto a survivor (or parked as
	 * {@code rearm:legacyFoldedCards}) is converted in this same pass rather than left as 1.6 until
	 * the next deploy. The folded rows themselves are gone by now, so nothing converts twice.
	 *
	 * @return the number of rows marked, of which the converted ones are logged per org
	 */
	@Transactional
	public int markCardSpecVersions(UUID orgUuid) {
		int marked = 0;
		int converted = 0;
		for (ModelOntologyData mod : modelOntologyService.listByOrg(orgUuid)) {
			if (null != mod.getModelCardSpecVersion()) continue;
			boolean empty = null == mod.getModelCard() || mod.getModelCard().isEmpty();
			if (!empty) {
				mod.setModelCard(ModelCardConverter.convert(mod.getModelCard()));
				converted++;
			}
			// Marked 2.0 either way: an empty card has nothing to convert, and a converted card
			// IS 2.0 now. Leaving a converted row marked 1.6 would make the next sweep convert
			// an already-converted card.
			mod.setModelCardSpecVersion(ModelCardSpecVersion.V2_0);
			modelOntologyService.saveData(mod, WhoUpdated.getAutoWhoUpdated());
			marked++;
		}
		if (converted > 0) {
			log.info("Converted {} model card(s) from CycloneDX 1.6 to 2.0 in org {}", converted, orgUuid);
		}
		return marked;
	}
}
