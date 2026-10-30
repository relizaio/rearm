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
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.PermissionTemplate;
import io.reliza.model.PermissionTemplateData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreatePermissionTemplateDto;
import io.reliza.model.dto.UpdatePermissionTemplateDto;
import io.reliza.model.dto.UserGroupPermissionDto;
import io.reliza.repositories.PermissionTemplateRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Permission templates (TPF-87, TPF-112): a user's named, saved permission sets, owned per user per
 * organization. A template grants nothing; the UI applies it by value and approveCliLogin clamps what is
 * approved to the approver. This service holds the shape bounds, the name rule and the cap; ownership,
 * membership, grant objects and approvals are checked by the fetcher, which holds the caller and the
 * organization. A delete is a delete, with an audit row written first: nothing references a template.
 */
@Slf4j
@Service
@Transactional
public class PermissionTemplateService {

	/** The longest name after trim, counted as String.length (the ApiKeyDeclarativeService.MAX_NAME precedent). */
	public static final int MAX_NAME = 128;
	/** The longest description after trim, counted in code points (the ApiKeyService.MAX_NOTES_LENGTH precedent). */
	public static final int MAX_DESCRIPTION = 512;
	/** The most permission rows one template holds. */
	public static final int MAX_ROWS = 200;
	/** The most templates one user holds in one organization: a guard, not a product limit. */
	public static final int MAX_TEMPLATES_PER_ORG = 100;

	@Autowired
	private PermissionTemplateRepository permissionTemplateRepository;

	@Autowired
	private AuditService auditService;

	@PersistenceContext
	private EntityManager entityManager;

	public Optional<PermissionTemplate> getPermissionTemplate(UUID uuid) {
		return null == uuid ? Optional.empty() : permissionTemplateRepository.findById(uuid);
	}

	public Optional<PermissionTemplateData> getPermissionTemplateData(UUID uuid) {
		return getPermissionTemplate(uuid).map(PermissionTemplateData::dataFromRecord);
	}

	/** The user's templates in the organization, by name; every organization when org is null. */
	@Transactional(readOnly = true)
	public List<PermissionTemplateData> listForUser(UUID user, UUID org) {
		List<PermissionTemplate> rows = null == org
				? permissionTemplateRepository.findByUser(user.toString())
				: permissionTemplateRepository.findByUserAndOrg(user.toString(), org.toString());
		return readable(rows, user);
	}

	/**
	 * Creates a template owned by {@code user}. The bounds run first, in the design's order (name required,
	 * name length, description length, row count), then the (user, org, name) rule, then the cap; nothing
	 * is stored when any refuses.
	 */
	public PermissionTemplateData createPermissionTemplate(CreatePermissionTemplateDto dto, UUID user, WhoUpdated wu)
			throws RelizaException {
		checkName(dto.getName());
		checkDescription(dto.getDescription());
		checkRows(dto.getPermissions());
		String name = dto.getName().strip();
		List<PermissionTemplateData> existing = listForUser(user, dto.getOrg());
		if (existing.stream().anyMatch(t -> Objects.equals(t.getName(), name))) {
			throw new RelizaException("A template with the name '" + name + "' already exists.");
		}
		if (existing.size() >= MAX_TEMPLATES_PER_ORG) {
			throw new RelizaException("You already have " + MAX_TEMPLATES_PER_ORG
					+ " templates in this organization; delete one first.");
		}
		PermissionTemplateData ptd = PermissionTemplateData.permissionTemplateDataFactory(dto, user);
		PermissionTemplate saved = savePermissionTemplate(new PermissionTemplate(), Utils.dataToRecord(ptd), wu);
		return PermissionTemplateData.dataFromRecord(saved);
	}

	/**
	 * Updates a template: a null field keeps the stored value, a non-null permissions list replaces the
	 * set. The bounds apply to every non-null field, and the name rule on a rename, before anything is
	 * written. The fetcher has already checked that the caller owns the template.
	 */
	public PermissionTemplateData updatePermissionTemplate(UpdatePermissionTemplateDto dto, WhoUpdated wu)
			throws RelizaException {
		PermissionTemplate pt = lockedTemplate(dto.getUuid());
		if (null != dto.getName()) checkName(dto.getName());
		if (null != dto.getDescription()) checkDescription(dto.getDescription());
		if (null != dto.getPermissions()) checkRows(dto.getPermissions());
		PermissionTemplateData ptd = PermissionTemplateData.dataFromRecord(pt);
		PermissionTemplateData updated = PermissionTemplateData.updatePermissionTemplateData(ptd, dto);
		if (!Objects.equals(updated.getName(), ptd.getName())) {
			boolean taken = listForUser(ptd.getUser(), ptd.getOrg()).stream()
					.filter(t -> !t.getUuid().equals(ptd.getUuid()))
					.anyMatch(t -> Objects.equals(t.getName(), updated.getName()));
			if (taken) {
				throw new RelizaException("A template with the name '" + updated.getName() + "' already exists.");
			}
		}
		PermissionTemplate saved = savePermissionTemplate(pt, Utils.dataToRecord(updated), wu);
		return PermissionTemplateData.dataFromRecord(saved);
	}

	/**
	 * Deletes a template: the audit row is written first, so its last content stays readable in
	 * rearm.audit, then the row is removed. Sessions and keys approved from it are unchanged (applied by
	 * value). No restore.
	 */
	public void deletePermissionTemplate(UUID uuid, WhoUpdated wu) throws RelizaException {
		PermissionTemplate pt = lockedTemplate(uuid);
		auditService.createAndSaveAuditRecord(TableName.PERMISSION_TEMPLATES, pt);
		permissionTemplateRepository.delete(pt);
	}

	private static void checkName(String name) throws RelizaException {
		if (StringUtils.isBlank(name)) throw new RelizaException("Template name is required");
		if (name.strip().length() > MAX_NAME) {
			throw new RelizaException("A template's name is at most " + MAX_NAME + " characters");
		}
	}

	/** Refused, never cut: counted in code points after trim, so a surrogate pair counts once. */
	private static void checkDescription(String description) throws RelizaException {
		if (null == description) return;
		String d = description.strip();
		if (d.codePointCount(0, d.length()) > MAX_DESCRIPTION) {
			throw new RelizaException("A template's description is at most " + MAX_DESCRIPTION + " characters");
		}
	}

	private static void checkRows(Collection<UserGroupPermissionDto> rows) throws RelizaException {
		if (null != rows && rows.size() > MAX_ROWS) {
			throw new RelizaException("A template holds at most " + MAX_ROWS + " permission rows");
		}
	}

	/** The template row write-locked and re-read from the locked row (a pre-lock copy is discarded). */
	private PermissionTemplate lockedTemplate(UUID uuid) throws RelizaException {
		Optional<PermissionTemplate> opt = null == uuid ? Optional.empty() : permissionTemplateRepository.findByIdWriteLocked(uuid);
		if (opt.isEmpty()) {
			throw new RelizaException("Permission template not found: " + uuid);
		}
		PermissionTemplate pt = opt.get();
		entityManager.refresh(pt);
		return pt;
	}

	/**
	 * The save with audit and revision bump, the {@code saveApiKeyGroup} shape. A template save is CONTENT,
	 * not bookkeeping, for TPF-71: it changes what a user will propose. It must stay an audited
	 * whole-record save.
	 */
	private PermissionTemplate savePermissionTemplate(PermissionTemplate pt, Map<String, Object> recordData, WhoUpdated wu) {
		if (null == recordData || recordData.isEmpty()) {
			throw new IllegalStateException("PermissionTemplate must have record data");
		}
		Optional<PermissionTemplate> existing = permissionTemplateRepository.findById(pt.getUuid());
		if (existing.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.PERMISSION_TEMPLATES, pt);
			pt.setRevision(pt.getRevision() + 1);
			pt.setLastUpdatedDate(ZonedDateTime.now());
		}
		pt.setRecordData(recordData);
		pt = (PermissionTemplate) WhoUpdated.injectWhoUpdatedData(pt, wu);
		return permissionTemplateRepository.save(pt);
	}

	/** One unreadable row costs only that row; it never marks a caller's transaction rollback-only. */
	private List<PermissionTemplateData> readable(List<PermissionTemplate> rows, UUID user) {
		List<PermissionTemplateData> out = new ArrayList<>();
		for (PermissionTemplate pt : rows) {
			try {
				out.add(PermissionTemplateData.dataFromRecord(pt));
			} catch (RuntimeException e) {
				log.error("Skipping unreadable permission template {} of user {}", pt.getUuid(), user, e);
			}
		}
		return out;
	}
}
