/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ModelOntology;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.ModelOntologyRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * CRUD + auto-upsert for {@link ModelOntology}. The hot path is
 * {@link #findOrRegisterModel} — invoked during session initialize
 * to resolve / create the model row the calling agent is running.
 * The user can attach a fuller CycloneDX ML-BOM model card later via
 * {@link #setModelCard} (driven by the
 * {@code setModelOntologyModelCardProgrammatic} GraphQL mutation).
 */
@Service
@Slf4j
public class ModelOntologyService {

	@Autowired
	private AuditService auditService;

	private final ModelOntologyRepository repository;

	ModelOntologyService(ModelOntologyRepository repository) {
		this.repository = repository;
	}

	public Optional<ModelOntology> getModelOntology(UUID uuid) {
		if (uuid == null) return Optional.empty();
		return repository.findById(uuid);
	}

	public Optional<ModelOntologyData> getModelOntologyData(UUID uuid) {
		return getModelOntology(uuid).map(ModelOntologyData::dataFromRecord);
	}

	public List<ModelOntologyData> listByOrg(UUID orgUuid) {
		if (orgUuid == null) return List.of();
		return repository.findByOrg(orgUuid.toString()).stream()
				.map(ModelOntologyData::dataFromRecord)
				.collect(Collectors.toList());
	}

	/**
	 * Find-or-create a ModelOntology row for {@code (org, name,
	 * version)}. Idempotent under the V38 unique index — concurrent
	 * first-init calls race; the loser catches the violation and
	 * re-reads the winner. Subsequent calls do not mutate refinable
	 * fields the user may have edited (publisher, description, etc.).
	 */
	@Transactional
	public ModelOntologyData findOrRegisterModel(UUID orgUuid, String name, String version,
			String publisher, WhoUpdated wu) throws RelizaException {
		if (orgUuid == null) throw new RelizaException("ModelOntology requires an org");
		if (StringUtils.isBlank(name)) {
			throw new RelizaException("ModelOntology requires a name on auto-registration");
		}
		String effectiveVersion = StringUtils.isNotBlank(version) ? version : ModelOntologyData.UNKNOWN_VERSION;

		Optional<ModelOntology> existing = repository.findByOrgNameVersion(
				orgUuid.toString(), name, effectiveVersion);
		if (existing.isPresent()) {
			return ModelOntologyData.dataFromRecord(existing.get());
		}
		ModelOntologyData seed = new ModelOntologyData();
		seed.setOrg(orgUuid);
		seed.setName(name);
		seed.setVersion(effectiveVersion);
		seed.setPublisher(publisher);
		seed.setModelCard(new HashMap<>());

		ModelOntology m = new ModelOntology();
		Map<String, Object> recordData = Utils.dataToRecord(seed);
		try {
			ModelOntology saved = save(m, recordData, wu);
			log.info("Auto-registered ModelOntology uuid={} name='{}' version='{}' org={}",
					saved.getUuid(), name, effectiveVersion, orgUuid);
			return ModelOntologyData.dataFromRecord(saved);
		} catch (DataIntegrityViolationException e) {
			log.info("Concurrent ModelOntology auto-registration race for (org={}, name='{}', version='{}') — re-reading winner",
					orgUuid, name, effectiveVersion);
			return repository.findByOrgNameVersion(orgUuid.toString(), name, effectiveVersion)
					.map(ModelOntologyData::dataFromRecord)
					.orElseThrow(() -> new RelizaException(
							"ModelOntology auto-registration race detected but no winning row found"));
		}
	}

	/**
	 * Attach a CycloneDX ML-BOM model card to an existing ontology row.
	 * Replaces any prior model card. The card is stored opaquely — ReARM
	 * does not validate its CycloneDX shape beyond JSON parsing.
	 */
	@Transactional
	public ModelOntologyData setModelCard(UUID ontologyUuid, Map<String, Object> modelCard, WhoUpdated wu)
			throws RelizaException {
		ModelOntology m = repository.findByIdWriteLocked(ontologyUuid)
				.orElseThrow(() -> new RelizaException("ModelOntology not found: " + ontologyUuid));
		ModelOntologyData mod = ModelOntologyData.dataFromRecord(m);
		mod.setModelCard(modelCard != null ? modelCard : new HashMap<>());
		return saveData(mod, wu);
	}

	/**
	 * Refine display fields. Only non-null arguments are applied so a
	 * partial edit can't blank unrelated fields.
	 */
	@Transactional
	public ModelOntologyData updateModelOntology(UUID ontologyUuid, String publisher, String description,
			String purl, String notes, WhoUpdated wu) throws RelizaException {
		ModelOntology m = repository.findByIdWriteLocked(ontologyUuid)
				.orElseThrow(() -> new RelizaException("ModelOntology not found: " + ontologyUuid));
		ModelOntologyData mod = ModelOntologyData.dataFromRecord(m);
		if (publisher != null) mod.setPublisher(publisher);
		if (description != null) mod.setDescription(description);
		if (purl != null) mod.setPurl(purl);
		if (notes != null) mod.setNotes(notes);
		return saveData(mod, wu);
	}

	@Transactional
	public ModelOntologyData saveData(ModelOntologyData mod, WhoUpdated wu) {
		ModelOntology m = repository.findById(mod.getUuid())
				.orElseGet(() -> {
					ModelOntology fresh = new ModelOntology();
					fresh.setUuid(mod.getUuid() != null ? mod.getUuid() : UUID.randomUUID());
					return fresh;
				});
		Map<String, Object> recordData = Utils.dataToRecord(mod);
		ModelOntology saved = save(m, recordData, wu);
		return ModelOntologyData.dataFromRecord(saved);
	}

	private ModelOntology save(ModelOntology m, Map<String, Object> recordData, WhoUpdated wu) {
		Optional<ModelOntology> existing = repository.findById(m.getUuid());
		if (existing.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.MODEL_ONTOLOGIES, m);
			m.setRevision(m.getRevision() + 1);
			m.setLastUpdatedDate(ZonedDateTime.now());
		}
		m.setRecordData(recordData);
		m = (ModelOntology) WhoUpdated.injectWhoUpdatedData(m, wu);
		return repository.save(m);
	}
}
