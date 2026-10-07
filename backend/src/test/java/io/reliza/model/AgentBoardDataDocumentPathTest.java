/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.AgentTaskInput.InputScope;

/**
 * Which template a document type takes on a board (gaps §1.18): the board's override, else the
 * index type's default, else the default for the scope the board produces the type at.
 */
class AgentBoardDataDocumentPathTest {

	private static AgentBoardData board(Map<RearmSpecificationType, String> overrides) {
		AgentBoardData bd = new AgentBoardData();
		bd.setDocumentPaths(new java.util.LinkedHashMap<>(overrides));
		return bd;
	}

	@Test
	void theDefaultsByTypeAndScope() {
		AgentBoardData bd = board(Map.of());
		// The index types are per task whatever scope is asked for.
		for (InputScope scope : InputScope.values()) {
			assertEquals("review-items/{key}/round-{round}.md", bd.documentPathTemplate(RearmSpecificationType.BOARD_REVIEW_ITEMS, scope));
			assertEquals("tests/{key}/run-{round}.md", bd.documentPathTemplate(RearmSpecificationType.BOARD_TEST_REPORT, scope));
			assertEquals("questions/{key}/round-{round}.md", bd.documentPathTemplate(RearmSpecificationType.BOARD_QUESTIONS, scope));
		}
		// The two prose types every board has land where the handoff repository keeps them.
		assertEquals("impl/{key}/notes-{round}.md",
				bd.documentPathTemplate(RearmSpecificationType.DETAILED_DESIGN, InputScope.TASK));
		assertEquals("design/{key}/architecture-{round}.md",
				bd.documentPathTemplate(RearmSpecificationType.ARCHITECTURE, InputScope.TASK));
		assertEquals("design/{key}/{type}-{round}.md",
				bd.documentPathTemplate(RearmSpecificationType.GLOSSARY, InputScope.TASK));
		assertEquals("docs/{type}/{component}.md",
				bd.documentPathTemplate(RearmSpecificationType.DETAILED_DESIGN, InputScope.COMPONENT));
	}

	@Test
	void anOverrideWinsForEveryKindOfType() {
		AgentBoardData bd = board(Map.of(
				RearmSpecificationType.BOARD_REVIEW_ITEMS, "rev/{key}-{round}.md",
				RearmSpecificationType.BOARD_QUESTIONS, "q/{key}.md",
				RearmSpecificationType.DETAILED_DESIGN, "work/{key}/notes-{round}.md",
				RearmSpecificationType.ARCHITECTURE, "design/{component}.md"));
		assertEquals("rev/{key}-{round}.md", bd.documentPathTemplate(RearmSpecificationType.BOARD_REVIEW_ITEMS, InputScope.TASK));
		assertEquals("q/{key}.md", bd.documentPathTemplate(RearmSpecificationType.BOARD_QUESTIONS, InputScope.TASK));
		assertEquals("work/{key}/notes-{round}.md",
				bd.documentPathTemplate(RearmSpecificationType.DETAILED_DESIGN, InputScope.COMPONENT),
				"an override applies at either scope");
		assertEquals("design/{component}.md", bd.documentPathTemplate(RearmSpecificationType.ARCHITECTURE, InputScope.TASK));
	}

	@Test
	void aSlugIsLowerCaseHyphenatedAndTrimmed() {
		assertEquals("platform-api-v2", Utils.slug("Platform API v2"));
		assertEquals("cafe-creme", Utils.slug("Café Crème"), "accents are dropped, not the letters");
		assertEquals("payments", Utils.slug("  --Payments!! "), "leading and trailing punctuation trimmed");
		assertEquals("a-b", Utils.slug("a__//b"), "a run of anything else is one hyphen");
		assertEquals("", Utils.slug("日本"), "nothing usable left is empty");
		assertEquals("", Utils.slug(null));
	}

	@Test
	void aStoredTaskPlaceholderReadsAsKey() {
		Map<RearmSpecificationType, String> stored = new java.util.LinkedHashMap<>();
		stored.put(RearmSpecificationType.BOARD_REVIEW_ITEMS, "reviews/{task}/round-{round}.md");
		stored.put(RearmSpecificationType.ARCHITECTURE, "docs/{component}.md");
		assertEquals(Map.of(RearmSpecificationType.BOARD_REVIEW_ITEMS, "reviews/{key}/round-{round}.md",
				RearmSpecificationType.ARCHITECTURE, "docs/{component}.md"),
				AgentBoardData.normaliseDocumentPaths(stored));
		assertEquals(null, AgentBoardData.normaliseDocumentPaths(null));
	}

	@Test
	void theDocumentsRootIsSharedsDefaultOrTheExplicitOne() {
		AgentBoardData bd = new AgentBoardData();
		bd.setName("ReARM Dogfood");
		assertEquals("", bd.documentsRoot(), "no documents block: the repository root");
		bd.setDocuments(new AgentBoardData.DocumentsConfig(null, true, null));
		assertEquals("boards/rearm-dogfood/", bd.documentsRoot());
		bd.setDocuments(new AgentBoardData.DocumentsConfig(null, true, "team/{board}"));
		assertEquals("team/rearm-dogfood/", bd.documentsRoot(), "an explicit root wins and gets its slash");
		bd.setDocuments(new AgentBoardData.DocumentsConfig(null, true, ""));
		assertEquals("", bd.documentsRoot(), "an explicit empty root is the bare repository");
		bd.setDocuments(new AgentBoardData.DocumentsConfig(null, false, "/mine/"));
		assertEquals("mine/", bd.documentsRoot(), "a root without shared is honoured, its leading slash gone");
	}
}
