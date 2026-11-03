/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.ApiKeyGroupStatus;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyGroup;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.UserPermission;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateApiKeyGroupDto;
import io.reliza.model.dto.UpdateApiKeyGroupDto;
import io.reliza.repositories.ApiKeyGroupRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * API key groups (TPF-91, child of TPF-86), the twin of {@link UserGroupService} for FREEFORM keys.
 * A group belongs to one organization; its members are FREEFORM keys of that organization; a member
 * key's effective permissions are its own grants union every ACTIVE group holding it, read per call
 * through {@link #permissionSetsForKey} (no cache). A group is never deleted: it is deactivated and
 * restored through its status, as user groups are. Member validation, names and existence live here;
 * grant objects and approvals are validated by the fetcher, which holds the organization.
 * The key-side mutation {@link #setGroupsOfKey} (TPF-123) sets one FREEFORM key's groups as a desired
 * set under the key's row lock (key, then groups in sorted uuid order, the order
 * {@link #removeKeyFromAllGroups} takes too), one audit row per changed group.
 */
@Slf4j
@Service
@Transactional
public class ApiKeyGroupService {

	@Autowired
	private ApiKeyGroupRepository apiKeyGroupRepository;

	@Autowired
	private AuditService auditService;

	@Autowired
	@Lazy
	private ApiKeyService apiKeyService;

	@PersistenceContext
	private EntityManager entityManager;

	public Optional<ApiKeyGroup> getApiKeyGroup(UUID groupUuid) {
		return apiKeyGroupRepository.findById(groupUuid);
	}

	public Optional<ApiKeyGroupData> getApiKeyGroupData(UUID groupUuid) {
		return getApiKeyGroup(groupUuid).map(ApiKeyGroupData::dataFromRecord);
	}

	/**
	 * The save with audit and revision bump, the {@code saveUserGroup} shape. A membership, permission
	 * or status change is CONTENT, not bookkeeping: it changes what a key may do. TPF-71 (the audited
	 * save sweep) must leave this an audited whole-record save. The key rows are never written by a
	 * group change; this group's audit chain names who changed it through WhoUpdated.
	 */
	private ApiKeyGroup saveApiKeyGroup(ApiKeyGroup akg, Map<String, Object> recordData, WhoUpdated wu) {
		if (null == recordData || recordData.isEmpty()) {
			throw new IllegalStateException("ApiKeyGroup must have record data");
		}
		Optional<ApiKeyGroup> existing = apiKeyGroupRepository.findById(akg.getUuid());
		if (existing.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.API_KEY_GROUPS, akg);
			akg.setRevision(akg.getRevision() + 1);
			akg.setLastUpdatedDate(ZonedDateTime.now());
		}
		akg.setRecordData(recordData);
		akg = (ApiKeyGroup) WhoUpdated.injectWhoUpdatedData(akg, wu);
		return apiKeyGroupRepository.save(akg);
	}

	/**
	 * Creates a group. The name is unique over the organization's groups, active and inactive.
	 */
	public ApiKeyGroupData createApiKeyGroup(CreateApiKeyGroupDto createDto, WhoUpdated wu) throws RelizaException {
		Optional<ApiKeyGroupData> nameConflict = getAllApiKeyGroupsByOrganization(createDto.getOrg()).stream()
				.filter(g -> Objects.equals(g.getName(), createDto.getName()))
				.findFirst();
		if (nameConflict.isPresent()) {
			if (nameConflict.get().getStatus() == ApiKeyGroupStatus.INACTIVE) {
				throw new RelizaException(
					"An inactive group with the name '" + createDto.getName() + "' already exists. "
					+ "Please restore it from the inactive groups list instead of creating a new one.");
			} else {
				throw new RelizaException(
					"A group with the name '" + createDto.getName() + "' already exists.");
			}
		}
		ApiKeyGroupData akgd = ApiKeyGroupData.apiKeyGroupDataFactory(createDto);
		ApiKeyGroup saved = saveApiKeyGroup(new ApiKeyGroup(), Utils.dataToRecord(akgd), wu);
		return ApiKeyGroupData.dataFromRecord(saved);
	}

	/**
	 * Updates a group: a null field keeps the stored value, a non-null apiKeys replaces the member list,
	 * non-null permissions replace the group's set. The name rule applies on rename or restore. Every
	 * member is validated before anything is written: a refused list leaves the group unchanged.
	 */
	public ApiKeyGroupData updateApiKeyGroup(UpdateApiKeyGroupDto dto, WhoUpdated wu) throws RelizaException {
		ApiKeyGroup akg = lockedGroup(dto.getGroupId());
		ApiKeyGroupData akgd = ApiKeyGroupData.dataFromRecord(akg);

		String effectiveName = null != dto.getName() ? dto.getName() : akgd.getName();
		boolean nameChanging = !Objects.equals(effectiveName, akgd.getName());
		boolean restoring = dto.getStatus() == ApiKeyGroupStatus.ACTIVE
				&& akgd.getStatus() == ApiKeyGroupStatus.INACTIVE;
		if (nameChanging || restoring) {
			Optional<ApiKeyGroupData> nameConflict = getAllApiKeyGroupsByOrganization(akgd.getOrg()).stream()
					.filter(g -> !g.getUuid().equals(akgd.getUuid()))
					.filter(g -> Objects.equals(g.getName(), effectiveName))
					.findFirst();
			if (nameConflict.isPresent()) {
				throw new RelizaException("A group with the name '" + effectiveName + "' already exists.");
			}
		}

		if (null != dto.getApiKeys()) validateMembers(dto.getApiKeys(), akgd.getOrg());

		ApiKeyGroupData updated = ApiKeyGroupData.updateApiKeyGroupData(akgd, dto);
		ApiKeyGroup saved = saveApiKeyGroup(akg, Utils.dataToRecord(updated), wu);
		return ApiKeyGroupData.dataFromRecord(saved);
	}

	/**
	 * Members are FREEFORM keys of the group's organization only, checked over the whole list before
	 * any write. An unknown uuid (a deleted key's tombstone included) and a key of another organization
	 * get the same message, checked before the type, so membership cannot probe another organization's
	 * key uuids or key types. Key status is not checked: a key that is not ACTIVE cannot authenticate.
	 */
	private void validateMembers(Collection<UUID> keyUuids, UUID org) throws RelizaException {
		for (UUID keyUuid : keyUuids) {
			Optional<ApiKey> oak = null == keyUuid ? Optional.empty() : apiKeyService.getApiKey(keyUuid);
			if (oak.isEmpty() || !org.equals(oak.get().getOrg())) {
				throw new RelizaException("API key not found: " + keyUuid);
			}
			if (oak.get().getObjectType() != ApiTypeEnum.FREEFORM) {
				throw new RelizaException("Only FREEFORM keys can join an API key group: " + keyUuid);
			}
		}
	}

	/**
	 * Deactivates a group (status INACTIVE, audited): its members lose its permissions on their next
	 * call. The row is kept, so the audit chain stays readable; an inactive group is restored with
	 * updateApiKeyGroup status ACTIVE. Deactivating an inactive group saves again.
	 */
	public ApiKeyGroupData deactivateApiKeyGroup(UUID groupUuid, WhoUpdated wu) throws RelizaException {
		ApiKeyGroup akg = lockedGroup(groupUuid);
		ApiKeyGroupData akgd = ApiKeyGroupData.dataFromRecord(akg);
		akgd.setStatus(ApiKeyGroupStatus.INACTIVE);
		ApiKeyGroup saved = saveApiKeyGroup(akg, Utils.dataToRecord(akgd), wu);
		return ApiKeyGroupData.dataFromRecord(saved);
	}

	/** The group row write-locked and re-read from the locked row (a pre-lock copy is discarded). */
	private ApiKeyGroup lockedGroup(UUID groupUuid) throws RelizaException {
		Optional<ApiKeyGroup> oakg = null == groupUuid ? Optional.empty() : apiKeyGroupRepository.findByIdWriteLocked(groupUuid);
		if (oakg.isEmpty()) {
			throw new RelizaException("API key group not found: " + groupUuid);
		}
		ApiKeyGroup akg = oakg.get();
		entityManager.refresh(akg);
		return akg;
	}

	/**
	 * The ACTIVE groups of the organization. One unreadable row costs only that row: the exception is
	 * contained here, inside the transactional method, so it never marks a caller's transaction
	 * rollback-only (the {@code UserGroupService.getUserGroupsByOrganization} containment).
	 */
	@Transactional(readOnly = true)
	public List<ApiKeyGroupData> getApiKeyGroupsByOrganization(UUID orgUuid) {
		return readable(apiKeyGroupRepository.findAllByOrganization(orgUuid.toString()), orgUuid).stream()
				.filter(g -> g.getStatus() == ApiKeyGroupStatus.ACTIVE)
				.toList();
	}

	/** Every group of the organization, active and inactive (unreadable rows skipped). */
	@Transactional(readOnly = true)
	public List<ApiKeyGroupData> getAllApiKeyGroupsByOrganization(UUID orgUuid) {
		return readable(apiKeyGroupRepository.findAllByOrganization(orgUuid.toString()), orgUuid);
	}

	/**
	 * The permission sets, in {@code org}, of the ACTIVE groups of {@code org} holding the key: what
	 * {@code OrganizationService.combinePermissions} unions into the key's own grants. Read per call,
	 * no cache; joins a surrounding transaction when there is one. An unreadable row is skipped, never
	 * thrown into an authorization.
	 */
	@Transactional(readOnly = true)
	public List<Set<UserPermission>> permissionSetsForKey(UUID keyUuid, UUID org) {
		return activeGroupsHoldingKey(keyUuid, org).stream().map(g -> g.getOrgPermissions(org)).toList();
	}

	/**
	 * The ACTIVE groups of org holding the key, the rows permissionSetsForKey takes its sets from (TPF-123:
	 * the sources of the combined read come from the same query as the union, so a group in one is in the
	 * other). Read per call, no cache; an unreadable row is skipped.
	 */
	@Transactional(readOnly = true)
	public List<ApiKeyGroupData> activeGroupsHoldingKey(UUID keyUuid, UUID org) {
		if (null == keyUuid || null == org) return List.of();
		List<ApiKeyGroupData> out = new ArrayList<>();
		for (ApiKeyGroupData g : readable(apiKeyGroupRepository.findByApiKeyAndOrg(keyUuid.toString(), org.toString()), org)) {
			// the query filters org, membership and status; the same three are re-checked on the parsed row
			if (org.equals(g.getOrg()) && g.getStatus() == ApiKeyGroupStatus.ACTIVE && g.hasApiKey(keyUuid)) {
				out.add(g);
			}
		}
		return out;
	}

	/**
	 * Sets the groups of one key (TPF-118/TPF-123): added to each desired group it is not in, removed from
	 * each holding group not desired, in this one transaction, each changed group taken write-locked and
	 * re-read, saved once, audited; a group whose membership does not change is not written. The key row is
	 * taken write-locked FIRST (getApiKeyWriteLocked, never written): calls on the same key serialize on it,
	 * so the later desired set wins whole, and a deleter (deleteApiKey, which locks the key and then its
	 * groups in the same sorted order, removeKeyFromAllGroups) waits for this call and then removes the key
	 * from every group. The key is a FREEFORM key of org (validateMembers). Every desired uuid is a group of
	 * org, checked over the whole list before any group lock: a foreign or unknown uuid answers "API key
	 * group not found". A key is never added to an INACTIVE group (a restore would grant silently); it may
	 * be removed from one. Lock order: the key row, then group rows in sorted uuid order, the one order
	 * every path that locks several groups uses (removeKeyFromAllGroups sorts its holding list, TPF-123
	 * round 3).
	 * @return the groups of org holding the key after the write, any status, by name
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public List<ApiKeyGroupData> setGroupsOfKey(UUID keyUuid, UUID org, Collection<UUID> desired, WhoUpdated wu) throws RelizaException {
		// TPF-123 round 2 (F-1): the key row first, locked until this transaction ends (MANDATORY propagation,
		// satisfied by this method's own transaction); a tombstone or a missing row is empty under the lock
		if (apiKeyService.getApiKeyWriteLocked(keyUuid).isEmpty()) {
			throw new RelizaException("API key not found: " + keyUuid);
		}
		// foreign "API key not found", non-FREEFORM "Only FREEFORM keys ..."; current, read under the key lock
		validateMembers(List.of(keyUuid), org);
		Set<UUID> want = new LinkedHashSet<>(null == desired ? List.of() : desired);
		Map<UUID, ApiKeyGroupData> all = new HashMap<>();
		for (ApiKeyGroupData g : getAllApiKeyGroupsByOrganization(org)) all.put(g.getUuid(), g);
		for (UUID g : want) {   // the whole list before any lock or write
			ApiKeyGroupData gd = all.get(g);
			if (null == gd) throw new RelizaException("API key group not found: " + g);
			if (gd.getStatus() == ApiKeyGroupStatus.INACTIVE && !gd.hasApiKey(keyUuid)) {
				throw new RelizaException("API key group is inactive: " + g);
			}
		}
		// the group order after the key: sorted uuids, the one order removeKeyFromAllGroups also locks in (its .sorted(), round 3)
		List<UUID> toTouch = all.values().stream()
				.filter(gd -> gd.hasApiKey(keyUuid) != want.contains(gd.getUuid()))
				.map(ApiKeyGroupData::getUuid).sorted().toList();
		for (UUID groupUuid : toTouch) {
			ApiKeyGroup akg = lockedGroup(groupUuid);   // lock + refresh; gone meanwhile = "API key group not found"
			ApiKeyGroupData gd = ApiKeyGroupData.dataFromRecord(akg);
			boolean changed;
			if (want.contains(groupUuid)) {
				if (gd.getStatus() == ApiKeyGroupStatus.INACTIVE && !gd.hasApiKey(keyUuid)) {
					// deactivated between the read and the lock: the whole call rolls back
					throw new RelizaException("API key group is inactive: " + groupUuid);
				}
				changed = gd.addApiKey(keyUuid);
			} else {
				changed = gd.removeApiKey(keyUuid);
			}
			if (changed) saveApiKeyGroup(akg, Utils.dataToRecord(gd), wu);
		}
		return getAllApiKeyGroupsByOrganization(org).stream()
				.filter(g -> g.hasApiKey(keyUuid))
				.sorted(Comparator.comparing(ApiKeyGroupData::getName, String.CASE_INSENSITIVE_ORDER))
				.toList();
	}

	/**
	 * A deleted key leaves every group of its organization, ACTIVE or INACTIVE: the groups are read
	 * with {@code findAllByOrganization} and filtered in memory (the by-key query filters ACTIVE), then
	 * each holding group is taken write-locked, re-read from the locked row and saved once, audited.
	 * A uuid removed meanwhile is a no-op. The holding groups are locked in sorted uuid order, the order
	 * {@link #setGroupsOfKey} locks groups in (TPF-123), so the two paths never lock two groups in
	 * opposite orders.
	 */
	public void removeKeyFromAllGroups(UUID keyUuid, UUID org, WhoUpdated wu) {
		if (null == keyUuid || null == org) return;
		List<UUID> holding = readable(apiKeyGroupRepository.findAllByOrganization(org.toString()), org).stream()
				.filter(g -> g.hasApiKey(keyUuid))
				.map(ApiKeyGroupData::getUuid)
				.sorted()   // TPF-123 round 3 (F-4): sorted uuid order, the order setGroupsOfKey locks groups in
				.toList();
		for (UUID groupUuid : holding) {
			Optional<ApiKeyGroup> oakg = apiKeyGroupRepository.findByIdWriteLocked(groupUuid);
			if (oakg.isEmpty()) continue;
			ApiKeyGroup akg = oakg.get();
			entityManager.refresh(akg);
			ApiKeyGroupData akgd = ApiKeyGroupData.dataFromRecord(akg);
			if (akgd.removeApiKey(keyUuid)) {
				saveApiKeyGroup(akg, Utils.dataToRecord(akgd), wu);
			}
		}
	}

	private List<ApiKeyGroupData> readable(List<ApiKeyGroup> groups, UUID orgUuid) {
		List<ApiKeyGroupData> out = new ArrayList<>();
		for (ApiKeyGroup akg : groups) {
			try {
				out.add(ApiKeyGroupData.dataFromRecord(akg));
			} catch (RuntimeException e) {
				log.error("Skipping unreadable API key group {} of org {}", akg.getUuid(), orgUuid, e);
			}
		}
		return out;
	}
}
