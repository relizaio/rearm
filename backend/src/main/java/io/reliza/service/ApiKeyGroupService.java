/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
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
		if (null == keyUuid || null == org) return List.of();
		List<Set<UserPermission>> sets = new ArrayList<>();
		for (ApiKeyGroupData g : readable(apiKeyGroupRepository.findByApiKeyAndOrg(keyUuid.toString(), org.toString()), org)) {
			// the query filters org, membership and status; the same three are re-checked on the parsed row
			if (org.equals(g.getOrg()) && g.getStatus() == ApiKeyGroupStatus.ACTIVE && g.hasApiKey(keyUuid)) {
				sets.add(g.getOrgPermissions(org));
			}
		}
		return sets;
	}

	/**
	 * A deleted key leaves every group of its organization, ACTIVE or INACTIVE: the groups are read
	 * with {@code findAllByOrganization} and filtered in memory (the by-key query filters ACTIVE), then
	 * each holding group is taken write-locked, re-read from the locked row and saved once, audited.
	 * A uuid removed meanwhile is a no-op.
	 */
	public void removeKeyFromAllGroups(UUID keyUuid, UUID org, WhoUpdated wu) {
		if (null == keyUuid || null == org) return;
		List<UUID> holding = readable(apiKeyGroupRepository.findAllByOrganization(org.toString()), org).stream()
				.filter(g -> g.hasApiKey(keyUuid))
				.map(ApiKeyGroupData::getUuid)
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
