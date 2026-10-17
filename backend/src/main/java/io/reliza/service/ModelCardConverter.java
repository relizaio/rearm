/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import io.reliza.common.Utils;

/**
 * Converts a CycloneDX 1.6 {@code modelCard} into the 2.0
 * {@code machine-learning-model} component shape, once, in code.
 *
 * <p>Pure and static: the mapping is a data transformation with no repository, clock or org in it,
 * so it is unit-tested directly rather than through the sweep that calls it.
 *
 * <p><b>Nothing is dropped.</b> 2.0 moved several 1.6 areas out of the component -- considerations
 * became part of the document risk model, inline datasets became first-class {@code data}
 * components, graphics went away -- and a component cannot carry any of them. Rather than lose that
 * content, each unmapped fragment is written to a {@code rearm:legacy*} key as JSON text. It is
 * inert to a 2.0 consumer and recoverable by hand, which is the right trade for a one-way migration
 * of a field an operator typed.
 *
 * <p>Idempotent by construction: the sweep skips rows already marked {@code 2.0}, and a card that is
 * already in the 2.0 shape (no {@code modelParameters} or {@code quantitativeAnalysis}) converts to
 * itself.
 */
public final class ModelCardConverter {

	private ModelCardConverter() {}

	/** 1.6 keys that 2.0 has no component-level home for; each is preserved under this prefix. */
	static final String LEGACY_PREFIX = "rearm:legacy";

	/**
	 * Modality inference for 1.6 {@code inputs[]/outputs[]}, which carried a free-text
	 * {@code format} where 2.0 wants a {@code dataType} plus a {@code modality}. Only the
	 * unambiguous prefixes are mapped; anything else becomes {@code custom} rather than a guess,
	 * because a wrong modality on a model card is worse than an honest "unclassified".
	 */
	private static String inferModality(String format) {
		if (StringUtils.isBlank(format)) return "custom";
		String f = format.toLowerCase();
		if (f.startsWith("image/")) return "image";
		if (f.startsWith("audio/")) return "audio";
		if (f.startsWith("video/")) return "video";
		if (f.startsWith("text/") || f.contains("json") || f.contains("string")) return "text";
		return "custom";
	}

	/**
	 * @param card a 1.6 model card, or null/empty
	 * @return the 2.0 shape; an empty card converts to an empty card
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> convert(Map<String, Object> card) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (null == card || card.isEmpty()) return out;

		// Anything the 1.6 card carried that is not one of the areas below (bom-ref, name,
		// arbitrary operator keys) is copied through untouched: 2.0 did not move it.
		card.forEach((k, v) -> {
			if (!"modelParameters".equals(k) && !"quantitativeAnalysis".equals(k)
					&& !"considerations".equals(k) && !"properties".equals(k)) {
				out.put(k, v);
			}
		});

		Map<String, Object> modelProperties = new LinkedHashMap<>();

		Object mpRaw = card.get("modelParameters");
		if (mpRaw instanceof Map<?, ?> mpMap) {
			Map<String, Object> mp = (Map<String, Object>) mpMap;

			// approach.type is a single string in 1.6; 2.0 takes a list.
			if (mp.get("approach") instanceof Map<?, ?> approach) {
				Object type = ((Map<String, Object>) approach).get("type");
				if (type instanceof String s && StringUtils.isNotBlank(s)) {
					modelProperties.put("learningTypes", List.of(s));
				}
			}
			// Same single-to-list widening for task.
			if (mp.get("task") instanceof String task && StringUtils.isNotBlank(task)) {
				modelProperties.put("tasks", List.of(task));
			}

			Map<String, Object> architecture = new LinkedHashMap<>();
			if (mp.get("architectureFamily") instanceof String fam && StringUtils.isNotBlank(fam)) {
				architecture.put("family", fam);
			}
			if (mp.get("modelArchitecture") instanceof String arch && StringUtils.isNotBlank(arch)) {
				architecture.put("name", arch);
			}
			if (!architecture.isEmpty()) modelProperties.put("architecture", architecture);

			convertIo(mp, "inputs", modelProperties);
			convertIo(mp, "outputs", modelProperties);

			if (mp.get("datasets") instanceof List<?> datasets && !datasets.isEmpty()) {
				// A bom-ref reference carries over as-is. An INLINE dataset object cannot: 2.0
				// makes datasets their own `data` components, which a single component has no
				// way to hold, so those are preserved as legacy text instead of being silently
				// flattened into something that looks like a reference but is not one.
				List<Object> refs = new ArrayList<>();
				List<Object> inline = new ArrayList<>();
				for (Object d : datasets) {
					if (d instanceof Map<?, ?> dm && dm.containsKey("ref")) {
						refs.add(d);
					} else {
						inline.add(d);
					}
				}
				if (!refs.isEmpty()) {
					Map<String, Object> training = new LinkedHashMap<>();
					training.put("datasets", refs);
					modelProperties.put("training", training);
				}
				if (!inline.isEmpty()) putLegacy(out, "Datasets", inline);
			}
		}

		Object qaRaw = card.get("quantitativeAnalysis");
		if (qaRaw instanceof Map<?, ?> qaMap) {
			Map<String, Object> qa = (Map<String, Object>) qaMap;
			if (qa.get("performanceMetrics") instanceof List<?> metrics && !metrics.isEmpty()) {
				List<Object> measurements = new ArrayList<>();
				for (Object m : metrics) {
					if (!(m instanceof Map<?, ?> mm)) continue;
					Map<String, Object> metric = (Map<String, Object>) mm;
					Map<String, Object> measurement = new LinkedHashMap<>();
					if (null != metric.get("type")) measurement.put("type", metric.get("type"));
					// 1.6 put the number in `value`; 2.0 nests it under `measure`.
					if (null != metric.get("value")) {
						measurement.put("measure", Map.of("value", metric.get("value")));
					}
					if (null != metric.get("slice")) measurement.put("slice", metric.get("slice"));
					if (null != metric.get("confidenceInterval")) {
						measurement.put("confidenceInterval", metric.get("confidenceInterval"));
					}
					if (!measurement.isEmpty()) measurements.add(measurement);
				}
				if (!measurements.isEmpty()) out.put("measurements", measurements);
			}
			if (null != qa.get("graphics")) putLegacy(out, "Graphics", qa.get("graphics"));
		}

		if (card.get("properties") instanceof List<?> props && !props.isEmpty()) {
			modelProperties.put("properties", props);
		}

		if (null != card.get("considerations")) {
			putLegacy(out, "Considerations", card.get("considerations"));
		}

		if (!modelProperties.isEmpty()) out.put("modelProperties", modelProperties);
		return out;
	}

	/**
	 * 1.6 {@code inputs[]/outputs[]} entries carry {@code format}; 2.0 wants {@code dataType} and a
	 * {@code modality}. The format string is kept verbatim as the dataType so nothing is lost to
	 * the inference.
	 */
	@SuppressWarnings("unchecked")
	private static void convertIo(Map<String, Object> mp, String key, Map<String, Object> target) {
		if (!(mp.get(key) instanceof List<?> list) || list.isEmpty()) return;
		List<Object> converted = new ArrayList<>();
		for (Object io : list) {
			if (io instanceof Map<?, ?> iom) {
				Map<String, Object> entry = new LinkedHashMap<>((Map<String, Object>) iom);
				Object format = entry.remove("format");
				if (format instanceof String fs) {
					entry.put("dataType", fs);
					entry.put("modality", inferModality(fs));
				}
				converted.add(entry);
			} else {
				converted.add(io);
			}
		}
		target.put(key, converted);
	}

	/**
	 * Preserved as JSON TEXT rather than as a nested object: these keys are not part of the 2.0
	 * component schema, and a validator that walks unknown objects is likelier to object to a
	 * structure than to a string. Serialization failure is not fatal -- losing the legacy fragment
	 * is bad, refusing to convert the card at all is worse -- so the fragment falls back to
	 * toString().
	 */
	private static void putLegacy(Map<String, Object> out, String name, Object value) {
		String text;
		try {
			text = Utils.OM.writeValueAsString(value);
		} catch (Exception e) {
			text = String.valueOf(value);
		}
		out.put(LEGACY_PREFIX + name, text);
	}
}
