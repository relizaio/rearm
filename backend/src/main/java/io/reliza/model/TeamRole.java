/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * The role a member plays on a Team (the {@link UserGroup} primitive).
 *
 * <p><strong>A role is an addressing LABEL and never grants access.</strong> It
 * answers "who on this team do I escalate a KEV finding to?", not "who may write
 * this component". Membership stays decoupled from RBAC (RFC sec. 4.1): a team
 * may additionally be granted a permission, but that is a separate, explicit
 * choice that no role here implies. Nothing in the authorization path reads this
 * enum -- it feeds notification/escalation targeting only.
 *
 * <p>{@code CUSTOM} is the escape hatch for org-defined roles: the operator's
 * label travels alongside in {@code customRole} rather than widening this enum,
 * so the known set stays a closed vocabulary (house rule: enums over magic
 * strings) while orgs are not boxed in.
 */
public enum TeamRole {
	TEAM_LEAD,
	SECURITY_SPECIALIST,
	DEVELOPER,
	QA,
	PROJECT_MANAGER,
	PRODUCT_MANAGER,
	CUSTOM;
}
