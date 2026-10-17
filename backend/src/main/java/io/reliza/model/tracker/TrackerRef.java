/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.tracker;

import java.io.Serializable;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.exceptions.RelizaException;

/**
 * One work item on a tracker: {@code source#key}.
 *
 * <p>The key is a string, not a number. Only repository hosts number their issues; a Jira key is
 * {@code PLATFORM-1234}. Assuming a number is what the old {@code startsWith(source + "#")} match
 * baked in, and it is why this is worth typing before a project tracker arrives rather than after.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrackerRef(
		@JsonProperty("source") TrackerSource source,
		@JsonProperty("key") String key) implements Serializable {

	private static final long serialVersionUID = 20260920L;

	/** Comfortably past any real issue number, and short of what overflows a long. */
	private static final int MAX_NUMERIC_KEY_DIGITS = 15;

	public static TrackerRef parse(String raw) throws RelizaException {
		if (StringUtils.isBlank(raw)) throw new RelizaException("A tracker reference cannot be blank");
		String s = raw.trim();
		int hash = s.lastIndexOf('#');
		if (hash < 0) {
			throw new RelizaException("Tracker reference " + raw + " names no item."
					+ " The form is provider:[host/]project#key, e.g. github:acme/widget#42");
		}
		TrackerSource source = TrackerSource.parse(s.substring(0, hash));
		String key = s.substring(hash + 1).trim();
		if (StringUtils.isBlank(key)) {
			throw new RelizaException("Tracker reference " + raw + " ends in '#' with no item key");
		}
		return new TrackerRef(source, renderKey(source, key, raw));
	}

	/**
	 * The string form, rendered from the record: what gets stored, and therefore what the
	 * idempotent-registration key compares. Spelling is fixed by the grammar and case by this.
	 */
	public String canonical() {
		return source.canonical() + "#" + key;
	}

	/** Whether this item belongs to a source a board is wired to. */
	public boolean belongsTo(TrackerSource wired) {
		return source.sameAs(wired);
	}

	private static String renderKey(TrackerSource source, String key, String raw)
			throws RelizaException {
		return switch (source.provider()) {
			case GITHUB, GITLAB, BITBUCKET, CODEBERG, GIT, TRELLO -> numericKey(source, key, raw);
			case JIRA -> jiraKey(source, key, raw);
		};
	}

	private static String numericKey(TrackerSource source, String key, String raw)
			throws RelizaException {
		if (!key.chars().allMatch(Character::isDigit)) {
			throw new RelizaException("Tracker reference " + raw + " has key '" + key + "'; "
					+ source.provider().token() + " numbers its items, so the key is digits");
		}
		// "#042" and "#42" are one item, so the rendering drops leading zeros. Bounded first:
		// a long digit string would otherwise leave parseLong to throw NumberFormatException,
		// which escapes as an internal error rather than a refusal naming the field.
		if (key.length() > MAX_NUMERIC_KEY_DIGITS) {
			throw new RelizaException("Tracker reference " + raw + " has a " + key.length()
					+ "-digit key; no tracker numbers items that far");
		}
		return Long.toString(Long.parseLong(key));
	}

	private static String jiraKey(TrackerSource source, String key, String raw)
			throws RelizaException {
		String rendered = key.toUpperCase(Locale.ROOT);
		String prefix = source.project() + "-";
		if (!rendered.startsWith(prefix)) {
			throw new RelizaException("Tracker reference " + raw + " has key '" + key + "', which is"
					+ " not an issue of project " + source.project() + " (expected " + prefix + "N)");
		}
		String number = rendered.substring(prefix.length());
		if (number.length() > MAX_NUMERIC_KEY_DIGITS
				|| number.isEmpty() || !number.chars().allMatch(Character::isDigit)) {
			throw new RelizaException("Tracker reference " + raw + " has key '" + key
					+ "'; a Jira issue key is " + prefix + "N");
		}
		return prefix + Long.parseLong(number);
	}
}
