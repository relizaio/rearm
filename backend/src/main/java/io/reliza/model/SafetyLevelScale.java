/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import io.reliza.model.SafetyContextData.LevelNote;
import io.reliza.model.cdx.v20.Cdx20SafetyScheme;

/**
 * Ordered safety-integrity scales for the predefined CycloneDX 2.0 schemes, used to rank an
 * imported level text so the trust gate can spot a scheme's bottom level and a downgrade.
 * Ranks compare only within one scheme. Rank 0 is the scheme's bottom.
 *
 * <ul>
 * <li>ISO-26262: QM &lt; ASIL A &lt; B &lt; C &lt; D. A decomposed level such as {@code B(D)} is
 * not a plain level: unranked, noted DECOMPOSITION.</li>
 * <li>IEC-61508: SIL 0 (non-SIL, below the standard's SIL 1) &lt; SIL 1 &lt; 2 &lt; 3 &lt; 4.</li>
 * <li>DO-178C: DAL E &lt; D &lt; C &lt; B &lt; A (letters run opposite to the rank).</li>
 * <li>IEC-62304: Class A &lt; B &lt; C. Class A is still a safety class; it is the bottom here so
 * a VENDOR Class A stages.</li>
 * <li>EN-50128: SIL 0 (basic integrity) &lt; SIL 1 &lt; 2 &lt; 3 &lt; 4.</li>
 * </ul>
 *
 * Level text is matched case-insensitively after dropping the scheme's prefix word (ASIL, SIL,
 * DAL, LEVEL, CLASS) and separators, so {@code ASIL-B}, {@code asil b} and {@code B} agree.
 * Anything else is unranked (UNMAPPED); custom schemes are unranked (CUSTOM_SCHEME).
 */
public final class SafetyLevelScale {

	/** The outcome of ranking one level text. {@code rank} is null when unranked. */
	public record Level(String normalized, Integer rank, LevelNote note) {}

	private static final Pattern DECOMPOSED = Pattern.compile("^[A-D]\\([A-D]\\)$");
	private static final Pattern SEPARATORS = Pattern.compile("[\\s_\\-.:]+");

	private static final List<String> ISO_26262 = List.of("QM", "A", "B", "C", "D");
	private static final List<String> SIL_0_TO_4 = List.of("0", "1", "2", "3", "4");
	private static final List<String> DO_178C = List.of("E", "D", "C", "B", "A");
	private static final List<String> IEC_62304 = List.of("A", "B", "C");

	private SafetyLevelScale() {}

	public static Level classify(Cdx20SafetyScheme scheme, String levelRaw) {
		if (scheme == null) return new Level(null, null, LevelNote.CUSTOM_SCHEME);
		if (levelRaw == null || levelRaw.isBlank()) return new Level(null, null, LevelNote.UNMAPPED);
		String token = token(levelRaw);
		return switch (scheme) {
			case ISO_26262 -> {
				String t = strip(token, "ASIL");
				if (DECOMPOSED.matcher(t).matches()) yield new Level("ASIL " + t, null, LevelNote.DECOMPOSITION);
				yield ranked(ISO_26262, t, v -> "QM".equals(v) ? "QM" : "ASIL " + v);
			}
			case IEC_61508 -> {
				String t = strip(token, "SIL");
				if ("NONE".equals(t) || "NON".equals(t) || "NOSIL".equals(t) || "NONSIL".equals(t)) t = "0";
				yield ranked(SIL_0_TO_4, t, v -> "SIL " + v);
			}
			case DO_178C -> ranked(DO_178C, strip(strip(token, "DAL"), "LEVEL"), v -> "DAL " + v);
			case IEC_62304 -> ranked(IEC_62304, strip(token, "CLASS"), v -> "Class " + v);
			case EN_50128 -> ranked(SIL_0_TO_4, strip(token, "SIL"), v -> "SIL " + v);
		};
	}

	private static String token(String levelRaw) {
		return SEPARATORS.matcher(levelRaw.trim().toUpperCase(Locale.ROOT)).replaceAll("");
	}

	private static String strip(String token, String prefix) {
		return token.startsWith(prefix) && token.length() > prefix.length()
			? token.substring(prefix.length()) : token;
	}

	private static Level ranked(List<String> order, String value, UnaryOperator<String> label) {
		int rank = order.indexOf(value);
		if (rank < 0) return new Level(null, null, LevelNote.UNMAPPED);
		return new Level(label.apply(value), rank, LevelNote.OK);
	}
}
