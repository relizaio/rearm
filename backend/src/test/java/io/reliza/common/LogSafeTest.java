/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * TPF-24 (architecture-6 decision 9, 6.5 item 18): the bounded rendering every request-derived log argument on a
 * credential path goes through. Plain JUnit, no Spring.
 */
public class LogSafeTest {

	private final List<String> rendered = new ArrayList<>();

	private String text(String s) {
		String r = LogSafe.text(s);
		rendered.add(r);
		return r;
	}

	@Test
	public void honestValuesRenderUnchangedAndEverythingElseIsBounded() {
		assertEquals("-", text(null));
		String key = "FREEFORM__" + UUID.randomUUID() + "__ord__abc";
		for (String honest : List.of("abc", "203.0.113.7", "2001:db8::1", "https://token.actions.githubusercontent.com",
				"/api/programmatic/%67raphql", key)) {
			assertEquals(honest, text(honest), honest);
		}
		assertEquals("a?b", text("a\nb"));
		assertEquals("a?b", text("a\rb"));
		assertEquals("a?b", text("a\tb"));
		assertEquals("a?b", text("a\u0001b"));
		assertEquals("a?b", text("a\u007Fb"));
		assertEquals("?", text("é"));
		assertEquals("??", text(new String(Character.toChars(0x1F600))), "one ? per UTF-16 unit");
		String x300 = "x".repeat(300);
		assertEquals("x".repeat(256) + "...", text(x300));
		assertEquals(259, text(x300).length());
		assertEquals("x".repeat(256), text("x".repeat(256)), "at the bound, unchanged");
		for (String r : rendered) {
			assertTrue(r.chars().allMatch(c -> c >= 0x20 && c <= 0x7E), "printable ASCII only: " + r);
		}
	}
}
