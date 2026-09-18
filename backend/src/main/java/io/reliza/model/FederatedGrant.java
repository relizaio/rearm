/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import lombok.Data;

/**
 * What a matching identity may do. {@code TEMPLATE}: a scope evaluated against the token on every
 * call (the calling repository's components, optionally creating them, plus optional static
 * extras) through an identity row materialised per repository. {@code KEY}: act as one Free Form
 * key of the org, with that key's permissions and attribution.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FederatedGrant implements Serializable {
	private static final long serialVersionUID = 1L;

	public enum GrantType { TEMPLATE, KEY }

	/** One explicit permission the template adds beyond the calling repository. */
	@Data
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class StaticPermission implements Serializable {
		private static final long serialVersionUID = 1L;
		private PermissionScope scope;
		private UUID object;
		private PermissionType type;
		private Set<PermissionFunction> functions = new LinkedHashSet<>();
		public Set<PermissionFunction> getFunctions() { return functions == null ? new LinkedHashSet<>() : functions; }
	}

	private GrantType type = GrantType.TEMPLATE;
	/** KEY: the Free Form key to act as. */
	private UUID keyUuid;
	/** TEMPLATE: org-wide level, usually READ_ONLY so names resolve and lists work; NONE for nothing. */
	private PermissionType orgPermission = PermissionType.NONE;
	/** TEMPLATE: level on components, branches and releases whose VCS repository is the calling repository. */
	private PermissionType vcsPermission = PermissionType.READ_WRITE;
	/** TEMPLATE: may create components (and the VCS repository record) bound to the calling repository. */
	private boolean createComponents = true;
	/** TEMPLATE: functions carried by the org-wide and repository-scoped permissions; RESOURCE is implicit. */
	private Set<PermissionFunction> functions = new LinkedHashSet<>();
	/** TEMPLATE: extra static scope, same shape as Free Form key permissions. */
	private List<StaticPermission> permissions = new LinkedList<>();

	public GrantType getType() { return type == null ? GrantType.TEMPLATE : type; }
	public PermissionType getOrgPermission() { return orgPermission == null ? PermissionType.NONE : orgPermission; }
	public PermissionType getVcsPermission() { return vcsPermission == null ? PermissionType.NONE : vcsPermission; }
	public Set<PermissionFunction> getFunctions() { return functions == null ? new LinkedHashSet<>() : functions; }
	public List<StaticPermission> getPermissions() { return permissions == null ? List.of() : permissions; }
}
