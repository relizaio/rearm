/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static io.reliza.ws.OrientationText.orientation;
import static io.reliza.ws.OrientationText.section;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The orientation's {@code install-cli} section.
 *
 * <p>One file per orientation section: add your assertion to the file for the section you changed, never at the end
 * of a shared class (RD4-15).
 */
class OrientationInstallCliContractTest {

	/**
	 * The CLI floor of the whole orientation: rearm_cli_min, rearm_cli_recommended and the board verbs' minimum are all
	 * this version (operator decision on the 26.10.2 bump). Change it here, in core.md's front-matter and in
	 * install-cli.md together.
	 */
	static final String RD5_CLI_MIN = "26.10.2";

	/**
	 * Task RD5-9: the board sections name the RD5 verbs, so install-cli names the CLI they need, and how to tell an
	 * older one: an older CLI answers {@code task verify --help} with the parent's help, not an error, so the probe is
	 * whether {@code task --help} lists it.
	 */
	@Test
	void theBoardVerbsNameTheirMinimumCli() throws IOException {
		String doc = section("install-cli").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(doc.contains("**The board verbs need CLI `" + RD5_CLI_MIN + "` or later.** `task verify`, `task push`,"
				+ " `agent git commit` and `agent git merge`, `session open`, `session close --final` and `session current`,"
				+ " `task signoff --pr`, the brief's `next <TYPE>: <path>` lines and the checks in `doc publish --json`,"
				+ " which the board sections name, are not in older CLIs."), "the minimum version and the verbs");
		assertTrue(doc.contains("If `rearm agent task --help` does not list `verify`, your CLI predates them: tell the"
				+ " operator what `rearm version` prints."), "how to tell an older CLI");
	}

	/**
	 * The CLI bump to 26.10.2: the front-matter's {@code rearm_cli_min} and {@code rearm_cli_recommended} are the same
	 * version, the board verbs' floor; install-cli installs exactly it in every place it names one (heading, asset table,
	 * snippet, release link, hashes, container); and no section names any other CLI version, as a floor or otherwise. A
	 * bump that misses one place leaves an agent downloading one version and checking it against another's hashes, or
	 * told a CLI the server no longer serves is enough.
	 */
	@Test
	void theInstallSectionInstallsTheRecommendedVersion() throws IOException {
		String rec = frontMatter("rearm_cli_recommended");
		String min = frontMatter("rearm_cli_min");
		String raw = section("install-cli");
		String doc = raw.replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(raw.contains("### 1.2 Install the CLI — `" + rec + "` exactly"), "the heading names " + rec);
		assertEquals(RD5_CLI_MIN, min, "rearm_cli_min is the orientation's floor");
		assertEquals(min, rec, "rearm_cli_min == rearm_cli_recommended");
		assertTrue(doc.contains("Use **`" + rec + "`**: it is both `rearm_cli_min` and `rearm_cli_recommended` in the"
				+ " front-matter, and the oldest CLI this doc supports."), "the text names " + rec + " as both");
		assertTrue(raw.contains("\nVERSION=" + rec + "\n"), "the snippet installs " + rec);
		assertTrue(raw.contains("<https://github.com/relizaio/rearm-cli/releases/tag/" + rec + ">"), "the release link");
		assertTrue(raw.contains("https://cdn.rearmhq.com/rearm-download/" + rec + "/sha256sums.txt"), "the hashes' source");
		assertTrue(Pattern.compile("\nregistry\\.relizahub\\.com/library/rearm-cli:" + Pattern.quote(rec) + "@sha256:[0-9a-f]{64}\n")
				.matcher(raw).find(), "the container is " + rec + " pinned by digest");

		Matcher asset = Pattern.compile("rearm-(\\d+\\.\\d+\\.\\d+)-").matcher(raw);
		while (asset.find()) assertEquals(rec, asset.group(1), "every asset name is " + rec);

		Pattern hashLine = Pattern.compile("[0-9a-f]{64}  rearm-" + Pattern.quote(rec) + "-[a-z0-9]+-[a-z0-9]+\\.zip");
		List<String> hashes = raw.lines().filter(l -> l.matches("[0-9a-f]{64} .*")).toList();
		assertFalse(hashes.isEmpty(), "the hashes are listed");
		for (String h : hashes) assertTrue(hashLine.matcher(h).matches(), "a sha256sums.txt line for " + rec + ": " + h);
		Matcher tableAsset = Pattern.compile("\\| `(rearm-[^`{]+\\.zip)` +\\|").matcher(raw);
		int rows = 0;
		while (tableAsset.find()) {
			rows++;
			String name = tableAsset.group(1);
			assertTrue(hashes.stream().anyMatch(h -> h.endsWith("  " + name)), "the table's asset has a hash: " + name);
		}
		assertTrue(rows > 0, "the asset table is read");

		Matcher pinned = Pattern.compile("`(\\d+\\.\\d+\\.\\d+)` pinned in §1\\.2").matcher(orientation());
		int pins = 0;
		while (pinned.find()) {
			pins++;
			assertEquals(rec, pinned.group(1), "a section's 'pinned in §1.2' names " + rec);
		}
		assertTrue(pins > 0, "the CLI-version notes are read");
		Matcher any = Pattern.compile("(?<![\\d.])\\d{2}\\.\\d{2}\\.\\d+(?![\\d.])").matcher(orientation());
		while (any.find()) assertEquals(rec, any.group(), "every CLI version the orientation names is " + rec);
	}

	private static String frontMatter(String key) throws IOException {
		return section("core").lines().takeWhile(l -> !l.isEmpty()).filter(l -> l.startsWith(key + ": ")).findFirst()
				.orElseThrow(() -> new AssertionError("no front-matter " + key)).substring(key.length() + 2).trim();
	}
}
