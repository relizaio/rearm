/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.reliza.common.CliSessionCodes;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.DeclarativeProvenance;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.DeclarativePruneMode;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.DeclarativeConfigService.Action;
import io.reliza.service.DeclarativeConfigService.ApplyResult;
import io.reliza.service.DeclarativeConfigService.Change;
import io.reliza.service.DeclarativeConfigService.DeclarativeKind;
import io.reliza.service.DeclarativeConfigService.SourceDto;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * API keys as declarative configuration (task RD3-11): kind API_KEYS, each key keyed by the name the file gives
 * it. A file declares a FREEFORM key's identity and settings -- permissions, notes, status, how long a minted
 * secret lives, the device-session bound -- and never a secret: a key a file creates
 * has none, the export carries none, and a secret is minted apart ({@link #mintSecret}), returned once and
 * stored hashed. A caller writes, mints for and archives only keys no stronger than itself.
 */
@Service
@Slf4j
public class ApiKeyDeclarativeService {

	/**
	 * The key types a file declares: FREEFORM only, the key with its own permission set. ORGANIZATION and
	 * ORGANIZATION_RW keys are deprecated; component, instance and cluster keys stay with their objects; personal
	 * (USER) keys are a person's, not configuration; federated identities follow their trust rules (operator,
	 * 2026-09-29).
	 */
	public static final Set<ApiTypeEnum> DECLARABLE = EnumSet.of(ApiTypeEnum.FREEFORM);

	/** The longest declared name. */
	static final int MAX_NAME = 128;

	/** The longest life a declared secret expiry gives: ten years. */
	static final int MAX_SECRET_EXPIRES_DAYS = 3650;

	@Autowired private ApiKeyService apiKeyService;
	@Autowired private DeclarativeConfigService declarativeConfigService;
	@Autowired private GetOrganizationService getOrganizationService;

	/**
	 * Who declares, mints or archives (task RD3-11), as a check of what it may give a key: {@link #excess} lists
	 * what a key's permissions would have to lose to fit under the caller's -- empty when they fit -- and
	 * {@link #orgAdmin} says whether the caller administers the organization.
	 */
	public interface Caller {
		List<String> excess(List<PermissionDto> demand);
		boolean orgAdmin();
	}

	// ------------------------------------------------------------------ spec DTOs

	@Data public static class ApiKeysSpecDto {
		private DeclarativeKind kind = DeclarativeKind.API_KEYS;
		private Integer version = 1;
		/** A file claims the organization's declared keys only when it says so; the provider sends one key, not authoritative. */
		private Boolean authoritative = false;
		private List<ApiKeySpecDto> keys = new LinkedList<>();
	}

	@Data public static class ApiKeySpecDto {
		private String name;
		/** FREEFORM, the one type a file declares; left out, FREEFORM. */
		private ApiTypeEnum type;
		private KeyPermissionsSpecDto permissions;
		private String notes;
		private ApiKeyStatus status;
		private Integer secretExpiresDays;
		private Integer sessionMaxMinutes;
		private DeclarativeProvenance provenance;
		/**
		 * The fields the file wrote, null or not, when it was read from the argument map: a field sent as null
		 * clears the setting and one left out leaves it (as board files, D15). Null when the spec was built in
		 * code: then a field counts when it is set.
		 */
		@JsonIgnore
		private Set<String> declared;
	}

	/** A FREEFORM key's permissions: the organization-wide level and functions, and grants on single objects. */
	@Data public static class KeyPermissionsSpecDto {
		private PermissionType type;
		private List<PermissionFunction> functions;
		private List<String> approvals;
		private List<KeyObjectPermissionSpecDto> objects;
	}

	/** A grant on one object -- a component, a product, a board, a perspective, an instance -- by uuid. */
	@Data public static class KeyObjectPermissionSpecDto {
		private PermissionScope scope;
		private UUID object;
		private PermissionType type;
		private List<PermissionFunction> functions;
		private List<String> approvals;
	}

	/**
	 * A spec from the argument map a client sends, remembering which fields each key wrote: only the map still
	 * knows a field sent as null from one left out.
	 */
	@SuppressWarnings("unchecked")
	public static ApiKeysSpecDto apiKeysSpecFromInput(Map<String, Object> raw) throws RelizaException {
		ApiKeysSpecDto spec;
		try {
			spec = Utils.OM.convertValue(raw, ApiKeysSpecDto.class);
		} catch (IllegalArgumentException e) {
			throw new RelizaException("spec: " + e.getMessage());
		}
		Object keys = null == raw ? null : raw.get("keys");
		if (keys instanceof List<?> list && null != spec.getKeys()) {
			for (int i = 0; i < list.size() && i < spec.getKeys().size(); i++) {
				if (list.get(i) instanceof Map<?, ?> m && null != spec.getKeys().get(i)) {
					spec.getKeys().get(i).setDeclared(new HashSet<>(((Map<String, Object>) m).keySet()));
				}
			}
		}
		return spec;
	}

	private static boolean has(ApiKeySpecDto k, String field, Object value) {
		return null != k.getDeclared() ? k.getDeclared().contains(field) : null != value;
	}

	// ------------------------------------------------------------------ apply

	@Transactional(rollbackFor = RelizaException.class)
	public ApplyResult applyApiKeys(UUID orgUuid, ApiKeysSpecDto spec, boolean dryRun, SourceDto source, Caller caller,
			WhoUpdated wu) throws RelizaException {
		if (spec.getKind() != null && spec.getKind() != DeclarativeKind.API_KEYS) {
			throw new RelizaException("spec kind " + spec.getKind() + " cannot be applied as API_KEYS");
		}
		ApplyResult result = new ApplyResult();
		result.setKind(DeclarativeKind.API_KEYS);
		result.setDryRun(dryRun);
		result.setSpecHash(DeclarativeConfigService.specHash(spec));
		DeclarativeProvenance prov = DeclarativeConfigService.provenance(result.getSpecHash(), source);
		Set<String> declared = new HashSet<>();
		for (ApiKeySpecDto k : null == spec.getKeys() ? List.<ApiKeySpecDto>of() : spec.getKeys()) {
			String name = null == k ? null : k.getName();
			if (StringUtils.isBlank(name)) { result.add(error("", "a key needs a name")); continue; }
			if (!declared.add(name)) { result.add(error(name, "declared more than once")); continue; }
			try {
				String nameProblem = nameProblem(name);
				if (null != nameProblem) { result.add(error(name, nameProblem)); continue; }
				Optional<ApiKey> cur = apiKeyService.findByDeclaredName(orgUuid, name);
				result.add(declarativeConfigService.entry(dryRun, () -> cur.isEmpty()
						? createKey(orgUuid, k, dryRun, prov, caller, wu)
						: updateKey(orgUuid, cur.get(), k, dryRun, prov, caller, wu)));
			} catch (RelizaException | RuntimeException e) {
				log.error("Declarative API key apply failed for key {}", name, e);
				result.add(error(name, e.getMessage()));
			}
		}
		if (Boolean.TRUE.equals(spec.getAuthoritative())) {
			DeclarativePruneMode prune = getOrganizationService.getOrganizationData(orgUuid)
					.map(OrganizationData::getSettings).map(OrganizationData.Settings::getDeclarativePrune)
					.orElse(DeclarativePruneMode.LEAVE);
			for (ApiKey ak : declaredKeys(orgUuid)) {
				ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
				if (declared.contains(akd.getDeclaredName())) continue;
				result.add(prune(ak, akd, prune, dryRun, caller, wu));
			}
		}
		return result;
	}

	private Change prune(ApiKey ak, ApiKeyData akd, DeclarativePruneMode prune, boolean dryRun, Caller caller,
			WhoUpdated wu) {
		String name = akd.getDeclaredName();
		if (prune != DeclarativePruneMode.ARCHIVE) {
			return Change.of(DeclarativeKind.API_KEYS, name, Action.UNCHANGED, null, "absent from authoritative spec; declarativePrune=LEAVE");
		}
		if (akd.getStatus() == ApiKeyStatus.INACTIVE) {
			return Change.of(DeclarativeKind.API_KEYS, name, Action.UNCHANGED, null, "absent from authoritative spec; already inactive");
		}
		List<String> excess = caller.excess(currentPermissions(ak, akd));
		if (!excess.isEmpty()) return error(name, strongerThanCaller("archive", excess));
		try {
			if (!dryRun) apiKeyService.setApiKeyStatus(ak.getUuid(), ApiKeyStatus.INACTIVE, false, wu);
		} catch (RelizaException e) {
			log.error("Declarative API key prune failed for key {}", name, e);
			return error(name, e.getMessage());
		}
		return Change.of(DeclarativeKind.API_KEYS, name, Action.ARCHIVE, List.of("status"),
				"absent from authoritative spec: deactivated, not deleted");
	}

	private Change createKey(UUID orgUuid, ApiKeySpecDto k, boolean dryRun, DeclarativeProvenance prov, Caller caller,
			WhoUpdated wu) throws RelizaException {
		String name = k.getName();
		if (null != k.getType() && !DECLARABLE.contains(k.getType())) return error(name, typeRefusal(k.getType()));
		String problem = settingsProblem(k);
		if (null != problem) return error(name, problem);
		List<PermissionDto> perms = has(k, "permissions", k.getPermissions()) ? permissionsOf(orgUuid, k.getPermissions()) : List.of();
		List<String> excess = caller.excess(perms);
		if (!excess.isEmpty()) return error(name, strongerThanCaller("declare", excess));
		List<String> fields = declaredFields(k);
		if (dryRun) return Change.of(DeclarativeKind.API_KEYS, name, Action.CREATE, fields, null);
		ApiKey ak = apiKeyService.createObjectApiKey(orgUuid, ApiTypeEnum.FREEFORM, orgUuid, UUID.randomUUID().toString(), null, wu);
		apiKeyService.writeDeclaredSettings(ak.getUuid(), settings(k, prov, perms), wu);
		return Change.of(DeclarativeKind.API_KEYS, name, Action.CREATE, fields, null);
	}

	private Change updateKey(UUID orgUuid, ApiKey ak, ApiKeySpecDto k, boolean dryRun, DeclarativeProvenance prov,
			Caller caller, WhoUpdated wu) throws RelizaException {
		String name = k.getName();
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		if (null != k.getType() && !DECLARABLE.contains(k.getType())) return error(name, typeRefusal(k.getType()));
		String problem = settingsProblem(k);
		if (null != problem) return error(name, problem);
		List<PermissionDto> current = currentPermissions(ak, akd);
		List<PermissionDto> perms = current;
		List<String> fields = new ArrayList<>();
		if (has(k, "permissions", k.getPermissions())) {
			perms = permissionsOf(orgUuid, k.getPermissions());
			if (!permissionKeys(perms).equals(permissionKeys(current))) fields.add("permissions");
		}
		if (has(k, "notes", k.getNotes()) && !Objects.equals(StringUtils.isBlank(k.getNotes()) ? null : k.getNotes(),
				StringUtils.isBlank(akd.getNotes()) ? null : akd.getNotes())) fields.add("notes");
		if (has(k, "status", k.getStatus()) && null != k.getStatus() && k.getStatus() != akd.getStatus()) {
			if (k.getStatus() == ApiKeyStatus.ACTIVE && akd.isAdminDisabled() && !caller.orgAdmin()) {
				return error(name, "this key was disabled by an organization administrator; only an administrator can re-enable it");
			}
			fields.add("status");
		}
		if (has(k, "secretExpiresDays", k.getSecretExpiresDays()) && !Objects.equals(k.getSecretExpiresDays(), akd.getSecretExpiresDays())) {
			fields.add("secretExpiresDays");
		}
		if (has(k, "sessionMaxMinutes", k.getSessionMaxMinutes()) && !Objects.equals(k.getSessionMaxMinutes(), akd.getSessionMaxMinutes())) {
			fields.add("sessionMaxMinutes");
		}
		if (fields.isEmpty()) return Change.of(DeclarativeKind.API_KEYS, name, Action.UNCHANGED, null, null);
		List<String> excess = caller.excess(perms);
		if (!excess.isEmpty()) return error(name, strongerThanCaller("change", excess));
		if (dryRun) return Change.of(DeclarativeKind.API_KEYS, name, Action.UPDATE, fields, null);
		apiKeyService.writeDeclaredSettings(ak.getUuid(), settings(k, prov, perms), wu);
		return Change.of(DeclarativeKind.API_KEYS, name, Action.UPDATE, fields, null);
	}

	private ApiKeyService.DeclaredSettings settings(ApiKeySpecDto k, DeclarativeProvenance prov, List<PermissionDto> perms) {
		Set<String> set = new HashSet<>();
		for (String f : List.of("notes", "status", "permissions", "secretExpiresDays", "sessionMaxMinutes")) {
			if (has(k, f, valueOf(k, f))) set.add(f);
		}
		return new ApiKeyService.DeclaredSettings(k.getName(), prov, set, k.getNotes(), k.getStatus(), perms,
				k.getSecretExpiresDays(), k.getSessionMaxMinutes());
	}

	private static Object valueOf(ApiKeySpecDto k, String field) {
		return switch (field) {
			case "notes" -> k.getNotes();
			case "status" -> k.getStatus();
			case "permissions" -> k.getPermissions();
			case "secretExpiresDays" -> k.getSecretExpiresDays();
			case "sessionMaxMinutes" -> k.getSessionMaxMinutes();
			case "type" -> k.getType();
			default -> null;
		};
	}

	private static List<String> declaredFields(ApiKeySpecDto k) {
		List<String> out = new ArrayList<>();
		for (String f : List.of("type", "permissions", "notes", "status", "secretExpiresDays", "sessionMaxMinutes")) {
			if (null != valueOf(k, f)) out.add(f);
		}
		return out;
	}

	/** Why a key's settings cannot be declared as written, or null. */
	static String settingsProblem(ApiKeySpecDto k) {
		if (null != k.getSessionMaxMinutes() && !CliSessionCodes.isValidSessionMinutes(k.getSessionMaxMinutes())) {
			return "sessionMaxMinutes is 1 to " + CliSessionCodes.MAX_SESSION_MINUTES + " minutes, or empty for no bound";
		}
		if (null != k.getSecretExpiresDays() && (k.getSecretExpiresDays() < 1 || k.getSecretExpiresDays() > MAX_SECRET_EXPIRES_DAYS)) {
			return "secretExpiresDays is 1 to " + MAX_SECRET_EXPIRES_DAYS + " days, or empty for secrets that do not expire";
		}
		if (null != k.getStatus() && k.getStatus() != ApiKeyStatus.ACTIVE && k.getStatus() != ApiKeyStatus.INACTIVE) {
			return "status is ACTIVE or INACTIVE";
		}
		return null;
	}

	/** Why a declared name cannot be used, or null. */
	static String nameProblem(String name) {
		if (StringUtils.isBlank(name)) return "a key needs a name";
		if (!name.equals(name.strip())) return "a key's name has no leading or trailing spaces: '" + name + "'";
		if (name.length() > MAX_NAME) return "a key's name is at most " + MAX_NAME + " characters";
		if (name.chars().anyMatch(Character::isISOControl)) return "a key's name has no control characters";
		return null;
	}

	static String typeRefusal(ApiTypeEnum type) {
		return "a " + type + " key cannot be declared; a file declares FREEFORM keys only";
	}

	private static String strongerThanCaller(String what, List<String> excess) {
		return "cannot " + what + " a key stronger than the caller: " + String.join("; ", excess);
	}

	private static Change error(String name, String message) {
		return Change.of(DeclarativeKind.API_KEYS, name, Action.ERROR, null, message);
	}

	// ------------------------------------------------------------------ objects and permissions

	private static UUID parseUuid(String s) {
		try {
			return UUID.fromString(s.strip());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** The permission list a FREEFORM key's declared permissions stand for. */
	static List<PermissionDto> permissionsOf(UUID orgUuid, KeyPermissionsSpecDto p) throws RelizaException {
		List<PermissionDto> out = new ArrayList<>();
		if (null == p) return out;
		PermissionType orgType = null == p.getType() ? PermissionType.NONE : p.getType();
		boolean orgExtras = (null != p.getFunctions() && !p.getFunctions().isEmpty()) || (null != p.getApprovals() && !p.getApprovals().isEmpty());
		if (orgType == PermissionType.NONE && orgExtras) {
			throw new RelizaException("permissions.functions and permissions.approvals need an organization-wide permissions.type");
		}
		if (orgType != PermissionType.NONE) {
			out.add(new PermissionDto(orgUuid, PermissionScope.ORGANIZATION, orgUuid, orgType, functionSet(p.getFunctions()),
					approvalSet(p.getApprovals())));
		}
		Set<String> seen = new HashSet<>();
		int i = 0;
		for (KeyObjectPermissionSpecDto o : null == p.getObjects() ? List.<KeyObjectPermissionSpecDto>of() : p.getObjects()) {
			String at = "permissions.objects[" + i++ + "]";
			if (null == o || null == o.getScope()) throw new RelizaException(at + " needs a scope");
			if (o.getScope() == PermissionScope.ORGANIZATION) {
				throw new RelizaException(at + ": the organization-wide level is permissions.type, not an object grant");
			}
			if (null == o.getObject()) throw new RelizaException(at + " needs the object's uuid");
			if (null == o.getType() || o.getType() == PermissionType.NONE) throw new RelizaException(at + " needs a type other than NONE");
			if (!seen.add(o.getScope() + "|" + o.getObject())) throw new RelizaException(at + ": " + o.getScope() + " " + o.getObject() + " is granted twice");
			out.add(new PermissionDto(orgUuid, o.getScope(), o.getObject(), o.getType(), functionSet(o.getFunctions()),
					approvalSet(o.getApprovals())));
		}
		return out;
	}

	private static Set<PermissionFunction> functionSet(List<PermissionFunction> fs) {
		return null == fs ? new LinkedHashSet<>() : new LinkedHashSet<>(fs);
	}

	private static Set<String> approvalSet(List<String> as) {
		return null == as ? new LinkedHashSet<>() : new LinkedHashSet<>(as);
	}

	/** A key's stored permissions in its organization, as the list a file would declare. */
	private static List<PermissionDto> currentPermissions(ApiKey ak, ApiKeyData akd) {
		List<PermissionDto> out = new ArrayList<>();
		for (UserPermission up : akd.getPermissions(ak.getOrg()).getOrgPermissionsAsSet(ak.getOrg())) {
			if (up.getType() == PermissionType.NONE && (null == up.getFunctions() || up.getFunctions().isEmpty())
					&& (null == up.getApprovals() || up.getApprovals().isEmpty())) continue;
			out.add(new PermissionDto(ak.getOrg(), up.getScope(), up.getObject(), up.getType(),
					null == up.getFunctions() ? Set.of() : up.getFunctions(), null == up.getApprovals() ? Set.of() : up.getApprovals()));
		}
		return out;
	}

	/** Permissions compared as values: scope, object, level, and functions and approvals in any order. */
	static Set<String> permissionKeys(List<PermissionDto> perms) {
		Set<String> out = new TreeSet<>();
		for (PermissionDto p : perms) {
			out.add(p.scope() + "|" + p.object() + "|" + p.type() + "|"
					+ new TreeSet<>(null == p.functions() ? Set.of() : p.functions().stream().map(Enum::name).collect(Collectors.toSet())) + "|"
					+ new TreeSet<>(null == p.approvals() ? Set.<String>of() : Set.copyOf(p.approvals())));
		}
		return out;
	}

	// ------------------------------------------------------------------ export and read

	private List<ApiKey> declaredKeys(UUID orgUuid) {
		return apiKeyService.listApiKeyByOrg(orgUuid).stream().filter(ak -> {
			ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
			return akd.getStatus() != ApiKeyStatus.REVOKED && StringUtils.isNotBlank(akd.getDeclaredName());
		}).sorted(Comparator.comparing(ak -> ApiKeyData.dataFromRecord(ak).getDeclaredName().toLowerCase())).toList();
	}

	/** The organization's declared keys as a file; with names, only those, and the file is not authoritative. */
	public ApiKeysSpecDto exportApiKeys(UUID orgUuid, List<String> names) {
		ApiKeysSpecDto spec = new ApiKeysSpecDto();
		spec.setAuthoritative(null == names || names.isEmpty());
		for (ApiKey ak : declaredKeys(orgUuid)) {
			ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
			if (null != names && !names.isEmpty() && !names.contains(akd.getDeclaredName())) continue;
			spec.getKeys().add(specOf(ak, akd));
		}
		return spec;
	}

	private ApiKeySpecDto specOf(ApiKey ak, ApiKeyData akd) {
		ApiKeySpecDto k = new ApiKeySpecDto();
		k.setName(akd.getDeclaredName());
		k.setType(ak.getObjectType());
		k.setPermissions(permissionsSpecOf(ak.getOrg(), currentPermissions(ak, akd)));
		k.setNotes(StringUtils.isBlank(akd.getNotes()) ? null : akd.getNotes());
		k.setStatus(akd.getStatus());
		k.setSecretExpiresDays(akd.getSecretExpiresDays());
		k.setSessionMaxMinutes(akd.getSessionMaxMinutes());
		k.setProvenance(akd.getDeclarative());
		return k;
	}

	/** The spec block for a permission list; null when there is nothing to write. */
	static KeyPermissionsSpecDto permissionsSpecOf(UUID orgUuid, List<PermissionDto> perms) {
		if (perms.isEmpty()) return null;
		KeyPermissionsSpecDto p = new KeyPermissionsSpecDto();
		List<KeyObjectPermissionSpecDto> objects = new ArrayList<>();
		for (PermissionDto d : perms) {
			List<PermissionFunction> fs = null == d.functions() || d.functions().isEmpty() ? null
					: d.functions().stream().sorted(Comparator.comparing(Enum::name)).toList();
			List<String> as = null == d.approvals() || d.approvals().isEmpty() ? null : d.approvals().stream().sorted().toList();
			if (d.scope() == PermissionScope.ORGANIZATION && orgUuid.equals(d.object())) {
				p.setType(d.type());
				p.setFunctions(fs);
				p.setApprovals(as);
				continue;
			}
			KeyObjectPermissionSpecDto o = new KeyObjectPermissionSpecDto();
			o.setScope(d.scope());
			o.setObject(d.object());
			o.setType(d.type());
			o.setFunctions(fs);
			o.setApprovals(as);
			objects.add(o);
		}
		objects.sort(Comparator.comparing((KeyObjectPermissionSpecDto o) -> o.getScope().name()).thenComparing(o -> o.getObject().toString()));
		p.setObjects(objects.isEmpty() ? null : objects);
		return p;
	}

	/** A secret's metadata: never its value, never its hash. */
	public record SecretSlot(int slot, boolean active, ZonedDateTime createdDate, ZonedDateTime expiresDate,
			ZonedDateTime lastUsedDate) {}

	/** A key as the provider and `rearm apikey list` read it: its declaration, its identity and its secrets' metadata. */
	public record KeyView(UUID uuid, String keyId, String name, ApiTypeEnum type, ApiKeyStatus status, boolean adminDisabled, String notes, KeyPermissionsSpecDto permissions,
			Integer secretExpiresDays, Integer sessionMaxMinutes, List<SecretSlot> secretSlots,
			DeclarativeProvenance declarative) {}

	/** With names, the keys declared under them; without, every live key of the organization, declared or not. */
	public List<KeyView> keys(UUID orgUuid, List<String> names) {
		List<KeyView> out = new ArrayList<>();
		for (ApiKey ak : apiKeyService.listApiKeyByOrg(orgUuid)) {
			ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
			if (akd.getStatus() == ApiKeyStatus.REVOKED) continue;
			if (null != names && !names.isEmpty() && !names.contains(akd.getDeclaredName())) continue;
			out.add(viewOf(ak, akd));
		}
		out.sort(Comparator.comparing((KeyView v) -> null == v.name() ? 1 : 0)
				.thenComparing(v -> null == v.name() ? "" : v.name().toLowerCase()).thenComparing(KeyView::keyId));
		return out;
	}

	private KeyView viewOf(ApiKey ak, ApiKeyData akd) {
		List<SecretSlot> slots = akd.effectiveSecrets(ak.getApiKey()).stream()
				.map(x -> new SecretSlot(x.getSlot(), x.isActive(), x.getCreatedDate(), x.getExpiresDate(), x.getLastUsedDate()))
				.sorted(Comparator.comparingInt(SecretSlot::slot)).toList();
		return new KeyView(ak.getUuid(), ApiKeyService.keyIdOf(ak), akd.getDeclaredName(), ak.getObjectType(), akd.getStatus(), akd.isAdminDisabled(), akd.getNotes(),
				ak.getObjectType() == ApiTypeEnum.FREEFORM || ak.getObjectType() == ApiTypeEnum.USER
						? permissionsSpecOf(ak.getOrg(), currentPermissions(ak, akd)) : null,
				akd.getSecretExpiresDays(), akd.getSessionMaxMinutes(), slots, akd.getDeclarative());
	}

	// ------------------------------------------------------------------ archive, mint, declare

	/**
	 * Deactivate a declared key (task RD3-11): what deleting it from Terraform does. The row, its name and its
	 * secrets stay, so declaring the name again takes the key back; a deactivated key refuses every secret.
	 */
	public KeyView archiveDeclaredKey(UUID orgUuid, String name, Caller caller, WhoUpdated wu) throws RelizaException {
		ApiKey ak = apiKeyService.findByDeclaredName(orgUuid, name)
				.orElseThrow(() -> new RelizaException("API key declared as " + name + " not found in this organization"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		List<String> excess = caller.excess(currentPermissions(ak, akd));
		if (!excess.isEmpty()) throw new RelizaException(strongerThanCaller("archive", excess));
		if (akd.getStatus() != ApiKeyStatus.INACTIVE) apiKeyService.setApiKeyStatus(ak.getUuid(), ApiKeyStatus.INACTIVE, false, wu);
		ApiKey saved = apiKeyService.getApiKey(ak.getUuid()).orElseThrow();
		return viewOf(saved, ApiKeyData.dataFromRecord(saved));
	}

	/**
	 * A secret minted, or not: {@code minted} false with no value when the slot already held one and no rotation
	 * was asked -- the value exists only hashed, so it cannot be returned again.
	 */
	public record MintedSecret(String keyId, String name, int slot, boolean minted, String secret, ZonedDateTime expiresDate) {}

	/**
	 * Mint a secret in a slot of a declared key (task RD3-11), for the provider's ephemeral secret and `rearm apikey mint`:
	 * an empty slot is minted; a slot that holds one is left alone, or regenerated when {@code rotate} asks.
	 * The value is returned here once and stored hashed. The key is named by its declared name, its key id or its
	 * uuid, must be one a file may declare, and no stronger than the caller; the expiry follows the key's
	 * secretExpiresDays.
	 */
	public MintedSecret mintSecret(UUID orgUuid, String key, int slot, boolean rotate, Caller caller, WhoUpdated wu)
			throws RelizaException {
		if (slot < 1 || slot > ApiKeyData.MAX_SECRETS) throw new RelizaException("A key's secret slot is 1 or " + ApiKeyData.MAX_SECRETS);
		ApiKey ak = resolveKey(orgUuid, key);
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		if (!DECLARABLE.contains(ak.getObjectType())) {
			throw new RelizaException("secrets are minted here for FREEFORM keys; this is a " + ak.getObjectType() + " key");
		}
		if (akd.getStatus() != ApiKeyStatus.ACTIVE && akd.getStatus() != ApiKeyStatus.INACTIVE) {
			throw new RelizaException("this key is " + akd.getStatus() + "; it takes no secret");
		}
		// Only keys under configuration (RD3-11 architecture-3 §1): a hand-made key comes under it when an org admin
		// declares it; until then the keys page's own rule -- an org admin -- is the only way to mint for it.
		if (StringUtils.isBlank(akd.getDeclaredName())) {
			throw new RelizaException("this key is not under configuration; an organization admin can declare it first");
		}
		// As on the keys page: a held key's secrets are its holder's to mint.
		if (null != akd.getHolder()) throw new RelizaException("this key is held by a person; only its holder mints its secrets, on the keys page");
		List<String> excess = caller.excess(currentPermissions(ak, akd));
		if (!excess.isEmpty()) throw new RelizaException("cannot mint a secret for a key stronger than the caller: " + String.join("; ", excess));
		ZonedDateTime expires = null == akd.getSecretExpiresDays() ? null : ZonedDateTime.now().plusDays(akd.getSecretExpiresDays());
		Optional<ApiKeySecret> held = akd.effectiveSecrets(ak.getApiKey()).stream().filter(x -> x.getSlot() == slot).findFirst();
		String keyId = ApiKeyService.keyIdOf(ak);
		if (held.isPresent() && !rotate) {
			return new MintedSecret(keyId, akd.getDeclaredName(), slot, false, null, held.get().getExpiresDate());
		}
		String secret = held.isPresent()
				? apiKeyService.regenerateApiKeySecret(ak.getUuid(), slot, expires, wu)
				: apiKeyService.addApiKeySecret(ak.getUuid(), slot, expires, wu);
		log.info("API key secret {} for key {} ({}) slot {}", held.isPresent() ? "rotated" : "minted", ak.getUuid(),
				akd.getDeclaredName(), slot);
		return new MintedSecret(keyId, akd.getDeclaredName(), slot, true, secret, expires);
	}

	/** A key of the organization by declared name, key id (TYPE__object[__ord__order]) or uuid. */
	ApiKey resolveKey(UUID orgUuid, String key) throws RelizaException {
		if (StringUtils.isBlank(key)) throw new RelizaException("Name the key: its declared name, key id or uuid");
		Optional<ApiKey> byName = apiKeyService.findByDeclaredName(orgUuid, key);
		if (byName.isPresent()) return byName.get();
		Optional<ApiKey> found = Optional.empty();
		String[] parts = key.split("__");
		if (parts.length == 2 || (parts.length == 4 && "ord".equals(parts[2]))) {
			try {
				ApiTypeEnum type = ApiTypeEnum.valueOf(parts[0]);
				UUID object = UUID.fromString(parts[1]);
				found = apiKeyService.getApiKeyByObjUuidTypeOrder(object, type, parts.length == 4 ? parts[3] : null, orgUuid);
			} catch (IllegalArgumentException e) {
				found = Optional.empty();
			}
		} else {
			UUID uuid = parseUuid(key);
			if (null != uuid) found = apiKeyService.getApiKey(uuid);
		}
		return found.filter(ak -> orgUuid.equals(ak.getOrg()) && ApiKeyData.dataFromRecord(ak).getStatus() != ApiKeyStatus.REVOKED)
				.orElseThrow(() -> new RelizaException("API key " + key + " not found in this organization"));
	}

	/**
	 * Declare a key made by hand under a name, or release a key's name with null (task RD3-11): a person puts an
	 * existing key under a file this way, and the next apply of that name updates it rather than creating another.
	 */
	public ApiKey declare(UUID orgUuid, UUID keyUuid, String name, WhoUpdated wu) throws RelizaException {
		ApiKey ak = apiKeyService.getApiKey(keyUuid).filter(k -> orgUuid.equals(k.getOrg()))
				.orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		if (StringUtils.isBlank(name)) return apiKeyService.setDeclaredName(keyUuid, null, wu);
		if (!DECLARABLE.contains(ak.getObjectType())) throw new RelizaException(typeRefusal(ak.getObjectType()));
		if (akd.getStatus() != ApiKeyStatus.ACTIVE && akd.getStatus() != ApiKeyStatus.INACTIVE) {
			throw new RelizaException("a " + akd.getStatus() + " key cannot be declared");
		}
		String problem = nameProblem(name);
		if (null != problem) throw new RelizaException(problem);
		Optional<ApiKey> other = apiKeyService.findByDeclaredName(orgUuid, name).filter(k -> !k.getUuid().equals(keyUuid));
		if (other.isPresent()) throw new RelizaException("another key is declared as " + name);
		return apiKeyService.setDeclaredName(keyUuid, name, wu);
	}
}
