/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.AgentActor.ActorKind;
import io.reliza.model.AgentBoardData.BoardEvent;
import io.reliza.model.AgentBoardData.BoardEventKind;
import io.reliza.model.AgentBoardData.BoardPause;
import io.reliza.model.AgentBoardData.BoardPauseLevel;

/**
 * Reading actors, old and new.
 *
 * <p>The old form matters more than the new one here: these four fields live in JSONB, so every
 * board, hold and sign-off written before the record exists still holds a bare string. If that
 * string does not deserialize, reading the row throws and the board becomes unreadable -- a
 * failure that no amount of testing the NEW shape would catch.
 */
class AgentActorTest {

	@Test
	void aCoordinatorSessionStringBecomesASessionActor() {
		UUID session = UUID.randomUUID();
		AgentActor a = AgentActor.fromLegacy("coordinator-session:" + session);
		assertEquals(ActorKind.SESSION, a.kind());
		assertEquals(session, a.uuid(), "the uuid stops being text behind a prefix");
		assertNull(a.name());
	}

	@Test
	void aSystemStringKeepsItsDetail() {
		AgentActor a = AgentActor.fromLegacy("system:humanGate");
		assertEquals(ActorKind.SYSTEM, a.kind());
		assertEquals("humanGate", a.name());
		assertNull(a.uuid());
	}

	@Test
	void anythingElseWasAHuman() {
		AgentActor email = AgentActor.fromLegacy("reviewer@example.com");
		assertEquals(ActorKind.USER, email.kind());
		assertEquals("reviewer@example.com", email.name());
		assertNull(email.uuid(), "an old row never recorded which user row that was");

		// The operator pause wrote this literal and nothing else, which is the hole this change
		// closes going forward; read back, it is still a human with no identity.
		AgentActor operator = AgentActor.fromLegacy("operator");
		assertEquals(ActorKind.USER, operator.kind());
		assertEquals("operator", operator.name());
	}

	@Test
	void aPrefixWithoutAUuidKeepsTheTextRatherThanLosingTheRecord() {
		AgentActor a = AgentActor.fromLegacy("coordinator-session:test");
		assertEquals(ActorKind.SESSION, a.kind());
		assertNull(a.uuid());
		assertEquals("test", a.name(), "unparseable is not the same as absent");
	}

	@Test
	void blankReadsAsAbsent() {
		assertNull(AgentActor.fromLegacy(null));
		assertNull(AgentActor.fromLegacy("   "));
	}

	@Test
	void aBoardPauseWrittenBeforeThisChangeStillDeserializes() {
		// The exact JSON a pre-change row holds: pausedBy as a string.
		String legacy = """
				{"level":"OPERATOR","reason":"operator hold","pausedBy":"operator",
				 "pausedAt":"2026-09-01T10:00:00Z"}""";
		BoardPause pause = Utils.OM.readValue(legacy, BoardPause.class);
		assertEquals(BoardPauseLevel.OPERATOR, pause.level());
		assertNotNull(pause.pausedBy(), "the row must still read, or the board is unreadable");
		assertEquals(ActorKind.USER, pause.pausedBy().kind());
		assertEquals("operator", pause.pausedBy().name());
	}

	@Test
	void aBoardEventWrittenAfterThisChangeRoundTrips() {
		UUID session = UUID.randomUUID();
		BoardEvent e = new BoardEvent(BoardEventKind.ALERT, "something happened",
				AgentActor.ofSession(session), java.time.ZonedDateTime.now());
		BoardEvent back = Utils.OM.readValue(Utils.OM.writeValueAsString(e), BoardEvent.class);
		assertEquals(ActorKind.SESSION, back.actor().kind());
		assertEquals(session, back.actor().uuid());
	}

	@Test
	void displayPrefersTheNameAndFallsBackToTheIdentity() {
		assertEquals("pm@example.com", AgentActor.ofUser(UUID.randomUUID(), "pm@example.com").display());
		UUID session = UUID.randomUUID();
		assertTrue(AgentActor.ofSession(session).display().contains(session.toString()));
		assertEquals("humanGate", AgentActor.system("humanGate").display());
	}
}
