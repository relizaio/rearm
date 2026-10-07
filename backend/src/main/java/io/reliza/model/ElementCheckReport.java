/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The evidence a document's element index was checked (gaps §2.A, task 2e0fffa6): which named checks
 * ran over which releases, and what each found. The board cuts it as a BOARD_ELEMENT_CHECK_REPORT round on the task
 * whenever an element-bearing document is published or a check is re-run; no agent writes one.
 *
 * <p>Every check is pure over the scope, so the report is deterministic and its {@code digest} (sha256
 * of the canonical JSON with the digest left out) is its identity: the same scope checked twice is the
 * same report, and a changed input release is a different one.
 *
 * @param catalogueVersion the catalogue the checks came from; bumped on any change to a check's meaning
 * @param grammarVersion the element grammar of the checked document's index
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ElementCheckReport(String catalogueVersion, String grammarVersion, Scope scope,
		List<ElementCheckResult> results, String digest) implements Serializable {

	private static final long serialVersionUID = 20260925L;

	public ElementCheckReport {
		if (null == results) results = List.of();
	}

	/** The same report with its digest set. */
	public ElementCheckReport withDigest(String d) {
		return new ElementCheckReport(catalogueVersion, grammarVersion, scope, results, d);
	}

	/** Whether any check that the board blocks on failed. */
	public boolean blocks() {
		return results.stream().anyMatch(r -> Result.FAIL == r.result() && r.blocking());
	}

	/**
	 * @param task the task the report was cut on
	 * @param checked the release the report is about
	 * @param releases every release whose elements the checks could see, the checked one first
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Scope(UUID task, UUID checked, List<ScopedRelease> releases) implements Serializable {
		private static final long serialVersionUID = 20260925L;

		public Scope {
			if (null == releases) releases = List.of();
		}
	}

	/** One release in scope, as it stood when the checks ran. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ScopedRelease(UUID release, RearmSpecificationType specification, String elementsDigest,
			String lifecycle) implements Serializable {
		private static final long serialVersionUID = 20260925L;
	}

	/**
	 * @param blocking whether the board blocked hand-over on this check when it ran
	 * @param reason why a check was skipped, or a one-line summary of a failure
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ElementCheckResult(String check, Result result, boolean blocking, String reason,
			List<Offence> offences) implements Serializable {
		private static final long serialVersionUID = 20260925L;

		public ElementCheckResult {
			if (null == offences) offences = List.of();
		}
	}

	/** SKIP is a result, never silence: a check that cannot run says why. */
	public enum Result { PASS, FAIL, SKIP }

	/** One element a check failed on, and the release it is in. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Offence(String elementId, UUID release, String message) implements Serializable {
		private static final long serialVersionUID = 20260925L;
	}
}
