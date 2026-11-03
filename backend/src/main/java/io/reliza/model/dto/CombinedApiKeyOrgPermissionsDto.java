/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;

/**
 * A key's effective permissions in one organization with the source of every row (TPF-118/TPF-123),
 * the answer of the browser query {@code combinedApiKeyOrgPermissions}. The rows are the set
 * {@code AuthorizationService.effectiveStoredKeyPermissions} returns, the set every check enforces;
 * this class only attaches sources and an order to them, it never merges grants itself.
 */
public record CombinedApiKeyOrgPermissionsDto(UUID apiKeyUuid, UUID org, List<SourcedPermission> permissions) {

	public enum PermissionSourceKind { OWN, GROUP }   // exact simple name = the schema enum (GraphQlSchemaEnumSyncTest)

	/** What one source contributes to a row: the key's own grant (no group) or an ACTIVE group's. */
	public record PermissionSource(PermissionSourceKind kind, UUID groupUuid, String groupName,
			PermissionType type, Set<PermissionFunction> functions, Set<String> approvals) {}

	/** One row of the effective set: the UserPermission fields plus its sources. */
	public record SourcedPermission(UUID org, PermissionScope scope, UUID object, PermissionType type,
			Set<PermissionFunction> functions, String meta, Set<String> approvals, List<PermissionSource> sources) {}

	/** Organization-wide first (descending PermissionScope ordinal), then the object uuid text. */
	private static final Comparator<SourcedPermission> ROW_ORDER =
			Comparator.<SourcedPermission>comparingInt(r -> -r.scope().ordinal())
					.thenComparing(r -> String.valueOf(r.object()));

	/**
	 * The rows of the effective set with their sources (TPF-123): pure, no Spring. A row's sources are
	 * OWN when the key's own set holds the (org, scope, object) triple, then one GROUP per group whose
	 * set holds it, each with what that source contributes. Rows sorted organization-wide first
	 * (descending PermissionScope ordinal: ORGANIZATION, INSTANCE, PERSPECTIVE, BOARD, COMPONENT, BRANCH,
	 * RELEASE), then object uuid text (the effective set is a HashSet). Null functions and approvals
	 * become empty sets.
	 */
	public static CombinedApiKeyOrgPermissionsDto of(UUID apiKeyUuid, UUID org, Set<UserPermission> effective,
			ApiKeyData akd, List<ApiKeyGroupData> groups) {
		List<SourcedPermission> rows = new ArrayList<>();
		for (UserPermission up : null == effective ? Set.<UserPermission>of() : effective) {
			List<PermissionSource> sources = new ArrayList<>();
			Optional<UserPermission> own = null == akd ? Optional.empty()
					: akd.getPermission(org, up.getScope(), up.getObject());
			own.ifPresent(o -> sources.add(new PermissionSource(PermissionSourceKind.OWN, null, null,
					o.getType(), copy(o.getFunctions()), copy(o.getApprovals()))));
			for (ApiKeyGroupData g : null == groups ? List.<ApiKeyGroupData>of() : groups) {
				g.getPermission(up.getScope(), up.getObject()).ifPresent(gp -> sources.add(new PermissionSource(
						PermissionSourceKind.GROUP, g.getUuid(), g.getName(), gp.getType(),
						copy(gp.getFunctions()), copy(gp.getApprovals()))));
			}
			rows.add(new SourcedPermission(up.getOrg(), up.getScope(), up.getObject(), up.getType(),
					copy(up.getFunctions()), up.getMeta(), copy(up.getApprovals()), sources));
		}
		rows.sort(ROW_ORDER);
		return new CombinedApiKeyOrgPermissionsDto(apiKeyUuid, org, rows);
	}

	private static <T> Set<T> copy(Collection<T> values) {
		return null == values ? new LinkedHashSet<>() : new LinkedHashSet<>(values);
	}
}
