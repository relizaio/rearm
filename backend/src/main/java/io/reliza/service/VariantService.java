/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.common.ValidationResult;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ReleaseData;
import io.reliza.model.Variant;
import io.reliza.model.VariantData;
import io.reliza.model.VariantData.VariantType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.dto.VariantDto;
import io.reliza.repositories.VariantRepository;
import lombok.extern.slf4j.Slf4j;



@Slf4j
@Service
public class VariantService {
	
	@Autowired
	private AuditService auditService;
	
	@Lazy
	@Autowired
	private AcollectionService acollectionService;
			
	private final VariantRepository repository;
	
	VariantService(VariantRepository repository) {
		this.repository = repository;
	}
	
	public Optional<Variant> getVariant (UUID uuid) {
		return repository.findById(uuid);
	}

	public Optional<VariantData> getVariantData (UUID uuid) {
		Optional<VariantData> ovd = Optional.empty();
		Optional<Variant> v = getVariant(uuid);
		if (v.isPresent()) {
			ovd = Optional
							.of(
								VariantData
									.dataFromRecord(v
										.get()
								));
		}
		return ovd;
	}
	
	public List<VariantData> getVariantsOfRelease (UUID releaseUuid) {
		return repository.findVariantsOfRelease(releaseUuid.toString()).stream().map(x -> VariantData.dataFromRecord(x)).toList();
	}
	
	@Transactional
	public VariantData ensureBaseVariantForRelease (ReleaseData release, WhoUpdated wu) throws RelizaException {
		VariantData baseVarData = null; 
		Optional<Variant> existingBaseVar = repository.findBaseVariantOfRelease(release.getUuid().toString());
		if (existingBaseVar.isPresent()) {
			baseVarData = VariantData.dataFromRecord(existingBaseVar.get());
		} else {
			baseVarData = createBaseVariant(release, wu);
		}
		return baseVarData;
	}
	
	public VariantData getBaseVariantForRelease (ReleaseData release) {
		UUID releaseId = release.getUuid();
		return getBaseVariantForRelease(releaseId);
	}
	
	public VariantData getBaseVariantForRelease (UUID releaseId) {
		var v = repository.findBaseVariantOfRelease(releaseId.toString()).get();
		return VariantData.dataFromRecord(v);
	}
	
	@Transactional
	public Boolean addOutboundDeliverables(Collection<UUID> deliverableUuids, UUID variantUuid, WhoUpdated wu){
		Boolean added = false;
		Optional<Variant> vOpt = getVariant(variantUuid);
		if (vOpt.isPresent()) {
			VariantData vd = VariantData.dataFromRecord(vOpt.get());
			Set<UUID> deliverables = vd.getOutboundDeliverables();
			var delDiff = Utils.diffUuidLists(deliverables, deliverableUuids);
			for (var du : deliverableUuids) {
				deliverables.add(du);	
			}
			vd.setOutboundDeliverables(deliverables);
			delDiff.forEach(dd -> vd.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.OUTBOUND_DELIVERY, dd.diffAction(),
					null, null, dd.object(), ZonedDateTime.now(), wu)));
			Map<String,Object> recordData = Utils.dataToRecord(vd);
			saveVariant(vOpt.get(), recordData, wu);
			added = true;
			// Resolve acollection to capture artifacts from newly attached deliverables
			acollectionService.resolveReleaseCollection(vd.getRelease(), wu);
		}
		return added;
	}
	
	@Transactional
	public void clearOutboundDeliverables(UUID variantUuid, WhoUpdated wu) {
		Optional<Variant> vOpt = getVariant(variantUuid);
		if (vOpt.isPresent()) {
			VariantData vd = VariantData.dataFromRecord(vOpt.get());
			Set<UUID> existingDeliverables = vd.getOutboundDeliverables();
			if (existingDeliverables != null && !existingDeliverables.isEmpty()) {
				existingDeliverables.forEach(du -> vd.addUpdateEvent(new ReleaseUpdateEvent(
						ReleaseUpdateScope.OUTBOUND_DELIVERY, ReleaseUpdateAction.REMOVED,
						null, null, du, ZonedDateTime.now(), wu)));
				vd.setOutboundDeliverables(new HashSet<>());
				Map<String,Object> recordData = Utils.dataToRecord(vd);
				saveVariant(vOpt.get(), recordData, wu);
			}
		}
	}
	
	@Transactional
	private VariantData createBaseVariant (ReleaseData release, WhoUpdated wu) throws RelizaException {
		VariantDto variantDto = VariantDto.builder()
				.release(release.getUuid())
				.org(release.getOrg())
				.type(VariantType.BASE)
				.version(release.getVersion())
				.marketingVersion(release.getMarketingVersion())
				.order(0)
				.status(release.getStatus())
				.build();
		Variant v = createVariant(variantDto, wu);
		return VariantData.dataFromRecord(v);
	}
	
	@Transactional
	public Variant createVariant (VariantDto variantDto, WhoUpdated wu) throws RelizaException {
		Variant v = new Variant();
		VariantData vData = VariantData.variantDataFactory(variantDto);
		Map<String,Object> recordData = Utils.dataToRecord(vData);
		v = saveVariant(v, recordData, wu);
		return v;
	}
	
	@Transactional
	private Variant saveVariant (Variant v, Map<String,Object> recordData, WhoUpdated wu) {
		// let's add some validation here
		// per schema version 0 we require that schema version 0 has version and identifier
		if (null == recordData || recordData.isEmpty() ||  StringUtils.isEmpty((String) recordData.get(CommonVariables.VERSION_FIELD))) {
			throw new IllegalStateException("Variant must have record data");
		}
		
		v.setRecordData(recordData);
		VariantData vd = VariantData.dataFromRecord(v);
		ValidationResult vr = VariantData.validateVariantData(vd);
		if (!vr.isValid()) {
			throw new IllegalStateException(vr.getSingleStringError());
		}
		
		Optional<Variant> ov = getVariant(v.getUuid());
		if (ov.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.VARIANTS, ov.get());
			v.setRevision(ov.get().getRevision() + 1);
			v.setLastUpdatedDate(ZonedDateTime.now());
		}

		v = (Variant) WhoUpdated.injectWhoUpdatedData(v, wu);
		return repository.save(v);
	}

}
