/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import org.yaml.snakeyaml.Yaml;

import io.reliza.common.Utils;
import io.reliza.model.ModelFacts;
import io.reliza.model.ModelOntologyData.ModelTier;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * The catalogue that ships with the code: model facts and aliases, no prices.
 *
 * <p>Read once at startup into an index of normalised string to entry. Its only job is to
 * pre-fill an org's row the first time somebody's agent declares a model we already know about,
 * so that a fresh org gets a context window and a tier without an admin typing them.
 *
 * <p>No prices here on purpose (D2). A rate that ships with the product is a rate that is wrong
 * in six months, and quietly wrong: costs would look plausible and be false. A model with no
 * price entry produces no cost and says so.
 */
@Slf4j
@Component
public class ModelCatalogueBundle {

	private static final String RESOURCE = "/model-catalogue.yaml";

	/**
	 * @param canonicalId the provider's own model id
	 * @param aliases normalised strings that resolve here, including the canonical id and name
	 */
	public record BundledModel(String canonicalId, String publisher, String name, String version,
			List<String> aliases, ModelTier tier, Double strength, ModelFacts facts) {}

	private final Map<String, BundledModel> byAlias = new LinkedHashMap<>();
	private final List<BundledModel> models = new ArrayList<>();

	/**
	 * Everything the bundle loaded. Empty means it failed to parse, which the loader catches by
	 * design -- so this is what a test reads to make that failure loud.
	 */
	public List<BundledModel> models() {
		return List.copyOf(models);
	}
	private int version;

	@PostConstruct
	@SuppressWarnings("unchecked")
	void load() {
		try (InputStream in = ModelCatalogueBundle.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				log.warn("Bundled model catalogue {} not found; resolution falls back to unresolved rows", RESOURCE);
				return;
			}
			Map<String, Object> root = new Yaml().load(in);
			if (root == null) return;
			version = root.get("version") instanceof Number n ? n.intValue() : 0;
			for (Map<String, Object> m : (List<Map<String, Object>>) root.getOrDefault("models", List.of())) {
				String canonicalId = (String) m.get("canonicalId");
				List<String> declared = (List<String>) m.getOrDefault("aliases", List.of());
				BundledModel bm = new BundledModel(canonicalId, (String) m.get("publisher"),
						(String) m.get("name"), String.valueOf(m.getOrDefault("version", "")),
						List.copyOf(declared),
						// Parsed here rather than carried as a string: a typo'd tier in the bundle
						// should fail the load loudly at startup, not become a silent null that a
						// routing floor later reads as "no tier".
						null != m.get("tier")
								? ModelTier.valueOf(String.valueOf(m.get("tier")).toUpperCase(Locale.ROOT))
								: null,
						// A starting point for an operator to edit, never a claim an agent makes.
						m.get("strength") instanceof Number sn ? sn.doubleValue() : null,
						Utils.OM.convertValue(m.getOrDefault("facts", Map.of()), ModelFacts.class));
				models.add(bm);
				// Index every form we can derive, so a declaration matches whether it arrives as
				// the canonical id, the name, the name with version, or a listed short form.
				index(bm, canonicalId);
				index(bm, bm.name());
				index(bm, bm.name() + "-" + bm.version());
				declared.forEach(a -> index(bm, a));
			}
			log.info("Bundled model catalogue v{} loaded: {} models, {} aliases",
					version, models.size(), byAlias.size());
		} catch (Exception e) {
			// A malformed bundle must not stop the application: resolution degrades to creating
			// unresolved rows, which is the pre-catalogue behaviour. But it degrades SILENTLY --
			// every model resolves as unresolved and prices nothing -- so the message has to say
			// what the consequence is, not just that a file failed to parse. A mis-cased enum in
			// the yaml cost the whole catalogue exactly this way.
			log.error("Failed to load bundled model catalogue {}; EVERY model will resolve as "
					+ "UNRESOLVED and price nothing until this is fixed", RESOURCE, e);
		}
	}

	private void index(BundledModel bm, String form) {
		String key = ModelIdNormalizer.normalize(form).base();
		if (key.isEmpty()) return;
		BundledModel prior = byAlias.putIfAbsent(key, bm);
		if (prior != null && prior != bm) {
			log.error("Bundled catalogue: '{}' normalises to '{}' which already maps to {}; ignoring for {}",
					form, key, prior.canonicalId(), bm.canonicalId());
		}
	}

	/** @param normalisedBase the output of {@link ModelIdNormalizer} */
	public Optional<BundledModel> find(String normalisedBase) {
		return Optional.ofNullable(byAlias.get(normalisedBase));
	}

	public int getVersion() {
		return version;
	}

	public List<BundledModel> all() {
		return List.copyOf(models);
	}
}
