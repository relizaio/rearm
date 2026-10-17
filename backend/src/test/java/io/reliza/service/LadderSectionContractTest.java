/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentBoardData;

/**
 * The level ladder section a ladder board serves after every prompt ({@code static/agents/ladder.md}), and the
 * board's rendering of it (task RD3-6).
 *
 * <p>One file per template: add your assertion to the file for the template you changed, never at the end of a
 * shared class. A rule that spans several templates gets a class of its own, named after the rule. Task RD4-15 split
 * the old {@code AgentRoleTemplatesContractTest} this way, because every task that appended to its end made the next
 * PR conflict at the closing brace; the methods kept their names.
 */
class LadderSectionContractTest {

	/**
	 * Task RD3-6: the ladder section a ladder board serves after every prompt says what the levels mean there,
	 * that the coordinator sets a work level at authorize, that a level descends from the level above it, that a gate
	 * confirms a level before its children, and names the board's levels where it renders them.
	 */
	@Test
	void ladderSectionExplainsLevels() throws IOException {
		String doc = Files.readString(Path.of("src/main/resources/static/agents/ladder.md"), StandardCharsets.UTF_8)
				.replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(doc.startsWith("## This board's level ladder"), doc);
		assertTrue(doc.contains("{{levels}}"), "the board's levels are rendered in");
		assertTrue(doc.contains("Levels are 0 to {{last}} on this board."), doc);
		assertTrue(doc.contains("the board offers lower levels first"), doc);
		assertTrue(doc.contains("**The coordinator sets the work level at authorize** (`rearm agent task authorize <task> --role <role>"
				+ " --work-level <n>`"), doc);
		assertTrue(doc.contains("say so in the order note when you rely on the default"), doc);
		assertTrue(doc.contains("**A task at level n descends from the level n-1 decision it refines.**"), doc);
		assertTrue(doc.contains("**A gate confirms a level before its children are authorized.**"), doc);

		AgentBoardData board = new AgentBoardData();
		assertNull(AgentBoardService.ladderSection(board), "a board without a ladder serves no section");
		board.setLadder(new AgentBoardData.Ladder(List.of(new AgentBoardData.LadderLevel(0, "requirements", null),
				new AgentBoardData.LadderLevel(1, "solution", "the decisions")), null));
		String served = AgentBoardService.ladderSection(board);
		assertTrue(served.contains("- 0 · requirements\n- 1 · solution — the decisions"), served);
		assertTrue(served.contains("Levels are 0 to 1 on this board."), served);
		assertFalse(served.contains("{{"), served);
	}
}
