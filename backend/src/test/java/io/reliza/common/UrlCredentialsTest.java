/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.reliza.service.BomLinkIndex;

/**
 * FDAW-34 row 34: the Java port of FDAW-6's rule S10. The vectors are the parent's and rebom's own
 * ({@code test/unit/stripPersonalDataAndLocalPaths.test.ts}); the same input must give rebom's output byte
 * for byte, and an input with nothing to strip must come back as the same instance.
 */
class UrlCredentialsTest {

	private static final String TILDE_PURL_HEAD = "pkg:generic/x@1.0?arch=amd64&download_url=https%3A%2F%2F";
	private static final String TILDE_PURL_TAIL = "h.example%2F~x%2Fa%20b.tgz&vcs_url=git%2Bssh%3A%2F%2Fgit%40github.com%2Fo%2Fr.git";

	@ParameterizedTest
	@CsvSource({
			"https://user:pass@host/x, https://host/x",
			"https://ghp_abc@github.com/org/repo.git, https://github.com/org/repo.git",
			"git+https://tok@github.com/o/r.git, git+https://github.com/o/r.git",
			// rebom's vectors
			"https://u:p@ss@h.example/x, https://h.example/x",
			"ssh://git@ghp_tok@github.com/org/private.git, ssh://github.com/org/private.git",
			"see https://u:p@h.example/x for details, see https://h.example/x for details",
			"registry=https://tok@r.example/x, registry=https://r.example/x",
			"https://u:p@registry.example.com, https://registry.example.com",
			"HTTPS://tok@h.example/x, HTTPS://h.example/x",
	})
	void stripUserinfoRemovesACredential(String in, String out) {
		assertEquals(out, UrlCredentials.stripUserinfo(in));
	}

	@Test
	void stripUserinfoEditsEveryTokenOfAString() {
		assertEquals("a https://h.example/x b https://i.example/y",
				UrlCredentials.stripUserinfo("a https://u:p@h.example/x b https://t@i.example/y"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "git+ssh://git@github.com/org/repo.git", "ssh://git@github.com:22/org/repo.git",
			"ftp://anonymous@ftp.example.org/pub/x", "https://h.example/x?u=a@b", "no url at all", "",
			// an empty userinfo (the authority starts with @) is not a credential, in any scheme
			"https://@h.example/x", "git+https://@h.example/x" })
	void stripUserinfoLeavesTheRestAlone(String in) {
		assertSame(in, UrlCredentials.stripUserinfo(in));
	}

	/** F-4: the token ends at U+00A0, as JavaScript's whitespace class ends it; Java's own would run on. */
	@Test
	void theAuthorityEndsAtJavaScriptWhitespace() {
		String in = "https://h.example\u00A0u:p@x/y";
		assertSame(in, UrlCredentials.stripUserinfo(in));
		String ideographic = "https://h.example\u3000u:p@x/y";
		assertSame(ideographic, UrlCredentials.stripUserinfo(ideographic));
		String bom = "https://h.example\uFEFFu:p@x/y";
		assertSame(bom, UrlCredentials.stripUserinfo(bom));
	}

	/** U+0085 is whitespace to UNICODE_CHARACTER_CLASS and not to JavaScript, so the authority runs through it. */
	@Test
	void nextLineIsNotJavaScriptWhitespace() {
		assertEquals("https://x/y", UrlCredentials.stripUserinfo("https://h.example\u0085u:p@x/y"));
	}

	@ParameterizedTest
	@CsvSource({
			"pkg:npm/x@1.0.0?download_url=https%3A%2F%2Fuser%3Atok%40reg.example%2Fx.tgz, pkg:npm/x@1.0.0?download_url=https%3A%2F%2Freg.example%2Fx.tgz",
			"pkg:npm/x@1.0.0?repository_url=https%3A%2F%2Fuser%3Atok%40reg.example, pkg:npm/x@1.0.0?repository_url=https%3A%2F%2Freg.example",
			"pkg:npm/x@1.0.0?vcs_url=https%3A%2F%2Fuser%3Atok%40reg.example%2Fx.git, pkg:npm/x@1.0.0?vcs_url=https%3A%2F%2Freg.example%2Fx.git",
			"pkg:deb/debian/x@1:2.5.2-3?download_url=https%3A%2F%2Fu%3Ap%40h.example%2Fx.deb, pkg:deb/debian/x@1:2.5.2-3?download_url=https%3A%2F%2Fh.example%2Fx.deb",
			// rebom's vectors
			"pkg:generic/x@1.0?download_url=https%3A%2F%2Fu%3Ap%40h.example%2Fx.tgz, pkg:generic/x@1.0?download_url=https%3A%2F%2Fh.example%2Fx.tgz",
			"pkg:generic/r@1.0?vcs_url=git+https://u:p@h.example/r.git&checksum=sha256:ab, pkg:generic/r@1.0?vcs_url=git+https://h.example/r.git&checksum=sha256:ab",
			"pkg:pypi/foo@1.0%2Blocal?repository_url=https://tok@pypi.example/simple#sub/path, pkg:pypi/foo@1.0%2Blocal?repository_url=https://pypi.example/simple#sub/path",
			"pkg:generic/x@1?download_url=https%3A%2F%2Fu%3Ap%40ss%40h%2Fx, pkg:generic/x@1?download_url=https%3A%2F%2Fh%2Fx",
			// (iii) the key's case, and a malformed escape: the raw value is tested and cut
			"pkg:generic/x@1?DOWNLOAD_URL=https%3A%2F%2Fu%3Ap%40h.example%2Fx, pkg:generic/x@1?DOWNLOAD_URL=https%3A%2F%2Fh.example%2Fx",
			"pkg:generic/x@1?download_url=https://u:p@h.example/%ZZ, pkg:generic/x@1?download_url=https://h.example/%ZZ",
			"pkg:generic/x@1?download_url=https%3a%2f%2fu%3ap%40h.example%2fx, pkg:generic/x@1?download_url=https%3a%2f%2fh.example%2fx",
	})
	void stripPurlCutsTheUserinfoBytes(String in, String out) {
		assertEquals(out, UrlCredentials.stripPurl(in));
	}

	/** (i) every other byte is the input's: no re-encoding, so the tilde and the %20 stay, and arch and vcs_url in place. */
	@Test
	void stripPurlNeverReencodes() {
		assertEquals(TILDE_PURL_HEAD + TILDE_PURL_TAIL, UrlCredentials.stripPurl(TILDE_PURL_HEAD + "u%3Ap%40" + TILDE_PURL_TAIL));
	}

	@ParameterizedTest
	@ValueSource(strings = { "pkg:generic/x@1?vcs_url=git+ssh://git@github.com/o/r.git",
			"pkg:generic/x@1?other=https://u:p@h/x", "pkg:npm/a@1.0.0", "pkg:generic/x@1?download_url=file:///x",
			"pkg:npm/a@1.0.0?arch=amd64", "not a purl at all",
			// (vi) %FF is no UTF-8: the decode reports, the encoded string is tested and holds no ://
			"pkg:generic/x@1?download_url=https%3A%2F%2Fu%3Ap%40h%FF",
			// a trailing lone % and an encoded surrogate are malformed the same way
			"pkg:generic/x@1?download_url=https%3A%2F%2Fu%3Ap%40h%",
			"pkg:generic/x@1?download_url=https%3A%2F%2Fu%3Ap%40h%ED%A0%80" })
	void stripPurlLeavesTheRestAlone(String in) {
		assertSame(in, UrlCredentials.stripPurl(in));
	}

	@Test
	void nullStaysNull() {
		assertEquals(null, UrlCredentials.stripPurl(null));
		assertEquals(null, UrlCredentials.stripUserinfo(null));
		assertFalse(UrlCredentials.carriesCredential(null));
	}

	@ParameterizedTest
	@ValueSource(strings = { "pkg:npm/x@1.0.0?download_url=https%3A%2F%2Fuser%3Atok%40reg.example%2Fx.tgz&package-id=abc",
			"input-2#pkg:npm/x@1.0.0?download_url=https%3A%2F%2Fuser%3Atok%40reg.example%2Fx.tgz",
			"https://user:tok@h.example/x",
			// (v) F-33: a percent-encoded URL token in a segment that is not a purl
			"comp-1?src=https%3A%2F%2Fuser%3Atok%40h.example%2Fx" })
	void carriesCredentialIsTrue(String value) {
		assertTrue(UrlCredentials.carriesCredential(value));
	}

	@ParameterizedTest
	@ValueSource(strings = { "pkg:npm/x@1.0.0?package-id=abc", "git+ssh://git@github.com/o/r.git",
			"0b0f7e4e-4c5e-4b64-9d7a-5a3a9d3c8a10", "", "comp-1?src=https%3A%2F%2Fh.example%2Fx" })
	void carriesCredentialIsFalse(String value) {
		assertFalse(UrlCredentials.carriesCredential(value));
	}

	/** (iv) F-32: the exact inverse of the fragment encoding, a plus included. */
	@Test
	void decodeFragmentInvertsTheEncoding() {
		String ref = "pkg:npm/x+y@1.0.0#a%b\u00E9";
		String link = BomLinkIndex.elementLink(UUID.randomUUID(), 1, ref);
		assertEquals(ref, BomLinkIndex.decodeFragment(link.substring(link.indexOf('#') + 1)));
		assertEquals("a+b", BomLinkIndex.decodeFragment("a+b"));
		assertEquals("100%", BomLinkIndex.decodeFragment("100%"));
		assertEquals("\u00E9%ZZ", BomLinkIndex.decodeFragment("\u00E9%ZZ"));
		// a %-escape truncated to one hex digit at the very end of the string: no second digit to read,
		// so the '%' and what follows it are literal, not a thrown exception
		assertEquals("a%4", BomLinkIndex.decodeFragment("a%4"));
		assertEquals("%4", BomLinkIndex.decodeFragment("%4"));
	}
}
