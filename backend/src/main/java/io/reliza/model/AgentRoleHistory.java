/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.UUID;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * How often an agent has signed a role off on a board, for poll ordering.
 *
 * <p>A table rather than a map on the board row, for two reasons that are both about the board
 * row. It is written on every sign-off, and the sign-off path holds the TASK lock -- writing the
 * board there inverts the lock order the document path depends on. And it grows with every
 * agent-role pair the board has ever seen, inside a jsonb column that is read whole on every board
 * read, including the poll that would be consulting it.
 *
 * <p>Advisory data: it orders a poll and never decides eligibility, so a row that is late or
 * missing costs a worse choice of task, never a wrong one.
 */
@Entity
@Getter
@Setter
@IdClass(AgentRoleHistory.Key.class)
@Table(schema = ModelProperties.DB_SCHEMA, name = "agent_role_history")
public class AgentRoleHistory {

	/** The composite key, one row per board, agent and role. */
	public static class Key implements java.io.Serializable {
		private static final long serialVersionUID = 20260921L;
		private UUID board;
		private UUID agent;
		private UUID role;

		public Key() {}

		public Key(UUID board, UUID agent, UUID role) {
			this.board = board;
			this.agent = agent;
			this.role = role;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Key k)) return false;
			return java.util.Objects.equals(board, k.board) && java.util.Objects.equals(agent, k.agent)
					&& java.util.Objects.equals(role, k.role);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(board, agent, role);
		}
	}

	@Id
	@Column(name = "board")
	private UUID board;

	@Id
	@Column(name = "agent")
	private UUID agent;

	@Id
	@Column(name = "role")
	private UUID role;

	@Column(name = "last_signed_off_at")
	private ZonedDateTime lastSignedOffAt;

	@Column(name = "sign_offs")
	private int signOffs;
}
