/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.StrengthScale;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.common.Utils;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

/**
 * Board-scoped role definition for the agent task pipeline. Roles are
 * configuration, not agent properties — any agent can assume any
 * role; the role is a served prompt plus an advisory position in the
 * board's workflow. The coordinator role is implicit on the board and
 * never a row here. Full design:
 * backend/ai-plans/agentic/task-boards.md.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentTaskRoleConfigData extends RelizaDataParent implements RelizaObject {

	/**
	 * The verbs an assignee must be able to perform — deliberately
	 * small v1 vocabulary. Declared on roles, implicit/unverified on
	 * agents in v1 (verification arrives with the GitHub token
	 * broker); the board alerts when its active roles do not cover
	 * the delivery minimum (CODE_PUSH + PR_MERGE).
	 */
	public enum AgentCapability { TRACKER_READ, TRACKER_WRITE, CODE_PUSH, PR_MERGE }

	/**
	 * Who works this role. AGENTIC (default): agents poll, assume the
	 * served prompt and sign off through sessions. HUMAN: a deliberate
	 * human workflow stage — never offered to agent polls, never
	 * assignable; any org-admin signs off directly from the queue (no
	 * claim step in v1; humans have no sessions to bind). The agent
	 * concepts (wipLimit, requireDistinctAgent, requiredCapabilities)
	 * do not apply and are rejected on input.
	 */
	public enum RoleKind { AGENTIC, HUMAN }

	/**
	 * Whether the coordinator may skip this role. OPTIONAL (default):
	 * routing through it is coordinator judgment. REQUIRED: a task
	 * cannot be COMPLETED unless its most recent sign-off in this role
	 * is PASSED — enforced at completion, so routing stays free but
	 * "done" needs the stamp. Cancel and split parents (whose children
	 * are each individually enforced) are exempt.
	 */
	public enum RoleNecessity { OPTIONAL, REQUIRED }

	/**
	 * Conditional human review of this role's output. Fires only when
	 * the role actually signs off — a task never routed through the
	 * role never hits the gate. NONE (default): no gate. ON_PASS: a
	 * PASSED sign-off parks the task ON_HOLD (operator-level,
	 * HUMAN_GATE kind) until a human accepts or rejects. ON_ANY_SIGNOFF:
	 * same, but rejections also gate (human arbitration of bounces).
	 */
	public enum HumanGate { NONE, ON_PASS, ON_ANY_SIGNOFF }

	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;

	/**
	 * Owning board; role names are unique per board (case-insensitive).
	 * NULL board = an org-level role PRESET (operator-curated library
	 * a new board can seed from; unique per org name).
	 */
	@JsonProperty
	private UUID board;

	/** Denormalized org for authorization checks. */
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;

	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;

	/**
	 * The prompt an agent assumes when working this role. Served on
	 * poll/assignment; its content hash is pinned on every assignment
	 * and sign-off so a prompt edit never silently changes what a
	 * historical sign-off meant.
	 */
	@JsonProperty
	private String prompt;

	/**
	 * Advisory routing order for the coordinator (ascending). Routing
	 * is a per-hop coordinator decision, not an automatic pipeline.
	 */
	@JsonProperty
	private int orderIndex;

	/**
	 * Optional cap on concurrently ASSIGNED tasks in this role on this
	 * board (e.g. architect = 1). Null/0 = uncapped.
	 */
	@JsonProperty
	private Integer wipLimit;

	/**
	 * When true, the assigned agent must differ from the agent of the
	 * task's most recent sign-off — separation of duties for review
	 * roles.
	 */
	@JsonProperty
	private boolean requireDistinctAgent;

	@JsonProperty
	private boolean active = true;

	/** Capabilities an assignee of this role must hold (declared, unverified in v1). */
	@JsonProperty
	private List<AgentCapability> requiredCapabilities = new ArrayList<>();

	/** Operator-only governance: who works this role. */
	@JsonProperty
	private RoleKind kind = RoleKind.AGENTIC;

	/** Operator-only governance: may the coordinator skip this role. */
	@JsonProperty
	private RoleNecessity necessity = RoleNecessity.OPTIONAL;

	/** Operator-only governance: conditional human review of this role's sign-offs. */
	@JsonProperty
	private HumanGate humanGate = HumanGate.NONE;

	/**
	 * What every task in this role must be able to read before it starts. Unioned with the
	 * task's own requirements at eligibility time; a task may add, never weaken.
	 */
	@JsonProperty
	private List<RequiredInput> requiredInputs = new ArrayList<>();

	/**
	 * What a hop in this role must leave behind: the mirror of {@link #requiredInputs}.
	 *
	 * <p>Inputs say what a hop reads, outputs say what the next hop gets. Without the second half
	 * a review ends in a sign-off note, which is fine while a person reads every note and useless
	 * to a coder/tester/fixer loop nobody reads: a coordinator cannot count three review items against
	 * thirty and a fixer cannot iterate over a paragraph.
	 *
	 * <p>Enforced at sign-off, not at assignment — the hop has to run before it can produce
	 * anything, so the failure has to land on the hop that can fix it.
	 */
	@JsonProperty
	private List<ProducedOutput> producesOutputs = new ArrayList<>();

	/**
	 * On a preset only: the presets file that last wrote it. Presets have no board to post an
	 * event on, so the row carries the record (declarative-boards D9). Null on board roles.
	 */
	@JsonProperty
	private DeclarativeProvenance declarative;

	/**
	 * Allowance for one assignment of this role, in USD micros: the projection's estimate when the
	 * board has no history for the role, told to the worker at assignment, and flagged on the hop
	 * and the board when a hop exceeds it. Not enforced mid-hop -- agents pull, and the server learns
	 * the cost when it is reported. Null sets no allowance.
	 */
	@JsonProperty
	private Long hopBudgetMicros;

	/**
	 * A blind role reads its task without the earlier hops' account of the work (task 0192a587,
	 * gaps §1.13): for the session assigned in it, earlier sign-offs' and returns' note, session,
	 * agent and return description read as null. Documents and their indexes are untouched -- the
	 * reviewer reads the work, not the worker's account of it. Everyone else sees everything.
	 * Operator-only, default off: a reviewer often benefits from the producer's note.
	 */
	@JsonProperty
	private boolean blindReview;

	/**
	 * Model strength this role needs, on the catalogue's scale; null admits every model.
	 * A capability floor, not a preference: a model below it is ineligible, never merely sorted last.
	 * Compared through {@link StrengthScale}, so within its tolerance counts as meeting it.
	 */
	@JsonProperty
	private Double requiredStrength;

	/**
	 * How far above {@link #requiredStrength} a model may be and still take the role. Zero means an
	 * exact match, which is the default because the point of a floor is to stop a frontier model
	 * doing work a cheap one does correctly.
	 */
	@JsonProperty
	private double strengthHeadroom;

	/**
	 * Which of a model's per-category strengths this role reads. Board roles are free-form, so a
	 * board PLANNER maps to ARCHITECT here. Null reads the model's base strength.
	 */
	@JsonProperty
	private ModelOntologyData.RoleCategory strengthCategory;

	/**
	 * One model's strength for this role, overriding everything the catalogue says about it.
	 *
	 * @param model the catalogue row uuid; re-pointed when that row is merged into another
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record ModelStrength(UUID model, Double strength) implements Serializable {}

	/**
	 * Per-model overrides for this role, the first thing consulted: override, then the model's
	 * value for {@link #strengthCategory}, then its base strength.
	 */
	@JsonProperty
	private List<ModelStrength> modelStrengths = new ArrayList<>();

	/** The override this role holds for a model, or null. */
	public Double modelStrengthOverride(UUID model) {
		if (null == model || null == modelStrengths) return null;
		for (ModelStrength ms : modelStrengths) {
			if (model.equals(ms.model())) return ms.strength();
		}
		return null;
	}

	/** Whether a model of this strength may take this role. Null strength fails a real requirement. */
	public boolean admitsStrength(Double strength) {
		return StrengthScale.admits(strength, requiredStrength, strengthHeadroom);
	}

	/**
	 * A document a role is expected to publish during its hop.
	 *
	 * @param specification which document type
	 * @param scope TASK for a per-task round (review items, test reports), COMPONENT for a document
	 *        series that belongs to the thing rather than the task
	 * @param required false makes it advisory, for roles that sometimes have nothing to write
	 */
	public static record ProducedOutput(RearmSpecificationType specification, InputScope scope,
			boolean required) implements Serializable {

		private static final long serialVersionUID = 20260920L;

		/** Required unless said otherwise: a declared output that is usually absent is not a contract. */
		public ProducedOutput(RearmSpecificationType specification, InputScope scope) {
			this(specification, scope, true);
		}
	}

	/** How a commission from this role enters the board (task RD4-12). */
	public enum CommissionIntake {
		/** Queued for the investigating role at once. */
		AUTO,
		/** PENDING_INTAKE, authorised by the coordinator like any registered task. */
		COORDINATOR
	}

	/**
	 * Which roles this role may commission an investigation from, and how (task RD4-12). Every role named must
	 * produce BOARD_INVESTIGATION_REPORT at TASK scope on the same board (or in the same presets), which the apply and
	 * the role upsert check.
	 *
	 * @param roles the investigating roles, by name
	 * @param intake AUTO (the default) or COORDINATOR
	 * @param defaultBudgetMicros the investigation's budget when the commission names none, in USD micros;
	 *        capped by the board's budget. Null sets none.
	 * @param review the role that reviews the report before it returns, when the commission names none; null
	 *        for no review
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static record Commissions(List<String> roles, CommissionIntake intake, Long defaultBudgetMicros,
			String review) implements Serializable {
		private static final long serialVersionUID = 20260930L;

		public CommissionIntake effectiveIntake() {
			return null == intake ? CommissionIntake.AUTO : intake;
		}

		/** Whether this role may commission the named role, case-insensitively. */
		public boolean allows(String role) {
			return null != roles && null != role && roles.stream().anyMatch(r -> null != r && r.strip().equalsIgnoreCase(role.strip()));
		}
	}

	/** Who this role may commission for a report (task RD4-12); null when it commissions nobody. */
	@JsonProperty
	private Commissions commissions;

	/** Whether this role may be asked for an investigation: it produces BOARD_INVESTIGATION_REPORT at TASK scope. */
	@JsonIgnore
	public boolean producesInvestigationReport() {
		return null != producesOutputs && producesOutputs.stream().anyMatch(po -> null != po
				&& RearmSpecificationType.BOARD_INVESTIGATION_REPORT == po.specification() && InputScope.TASK == po.scope());
	}

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		return null;
	}

	public static AgentTaskRoleConfigData dataFromRecord(AgentTaskRoleConfig rc) {
		if (rc.getSchemaVersion() != 0) {
			throw new IllegalStateException("AgentTaskRoleConfig schema version is " + rc.getSchemaVersion()
					+ ", which is not currently supported");
		}
		Map<String, Object> recordData = rc.getRecordData();
		AgentTaskRoleConfigData rcd = Utils.OM.convertValue(recordData, AgentTaskRoleConfigData.class);
		rcd.setUuid(rc.getUuid());
		rcd.setCreatedDate(rc.getCreatedDate());
		return rcd;
	}
}
