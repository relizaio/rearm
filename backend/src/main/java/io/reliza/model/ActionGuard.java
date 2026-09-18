/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;

import io.reliza.common.CommonVariables;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One named rule withholding an action until its expression holds.
 *
 * <p>Guards accumulate rather than override: every guard that applies to an action is evaluated,
 * any one of them can refuse, and the refusal names the guard that did it. That is deliberate --
 * with override semantics, adding a rule on a component would silently switch off the
 * organization's, which is the last thing anyone wants from a control they turned on on purpose.
 *
 * <p>{@code namePattern} is used only where a guard is declared organization-wide: a Java regex
 * matched against the component name, mirroring how an organization's approval-policy rules pick
 * the components they apply to. Guards written on a component itself leave it null.
 */
public record ActionGuard(
		@JsonProperty(CommonVariables.NAME_FIELD) String name,
		@JsonProperty GuardedAction action,
		@JsonProperty String cel,
		@JsonProperty GuardMode mode,
		@JsonProperty String namePattern) implements Serializable {

	/** A guard with nothing to say: unnamed, expressionless, or switched off. */
	public boolean idle() {
		return cel == null || cel.isBlank() || mode == null || mode == GuardMode.OFF;
	}

	public String label() {
		return name == null || name.isBlank() ? "unnamed guard" : name;
	}
}
