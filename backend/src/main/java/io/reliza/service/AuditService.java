/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.model.Audit;
import io.reliza.model.RelizaEntity;
import io.reliza.repositories.AuditRepository;

@Service
public class AuditService {
	
	private final AuditRepository repository;
	
	AuditService(AuditRepository repository) {
	    this.repository = repository;
	}
	
	public Optional<Audit> getAudit (UUID uuid) {
		return repository.findById(uuid);
	}
	
	public List<Audit> getAuditForEntity(UUID entityUuid, TableName tn, Integer limit, Integer offset) {
		String limitAsStr = null;
		if (null == limit || limit < 1) {
			limitAsStr = "ALL";
		} else {
			limitAsStr = limit.toString();
		}
		String offsetAsStr = null;
		if (null == offset || offset < 0) {
			offsetAsStr = "0";
		} else {
			offsetAsStr = offset.toString();
		}
		return repository.retrieveAuditRecordsForEntity(tn.toString(), entityUuid.toString(), limitAsStr, offsetAsStr);
	}
	
	public List<Audit> getAuditForSingleEntityWithDates(UUID entityUuid, TableName tn, ZonedDateTime dateFrom, 
			ZonedDateTime dateTo, Integer limit, Integer offset) {
		if (null == offset || offset < 0) {
			offset = 0;
		}
		// to string format for ZonedDateTime is 2020-06-20T15:00+02:00[Europe/Paris], so we need to remove [Europe/Paris part]
		return repository.retrieveAuditRecordsForSingleEntityWithDates(tn.toString(), entityUuid.toString(), BigInteger.valueOf(limit), BigInteger.valueOf(offset), 
				dateFrom.toString().split("\\[")[0], dateTo.toString().split("\\[")[0]);
	}
	
	public List<Audit> getAuditForEntityByDates(UUID orgUuid, TableName tn, ZonedDateTime dateFrom, ZonedDateTime dateTo, Integer limit) {
		List<Audit> retList = new LinkedList<>();
		String limitAsStr = null;
		if (null == limit || limit < 1) {
			limitAsStr = "ALL";
		} else {
			limitAsStr = limit.toString();
		}
		if (null == dateTo) {
			dateTo = ZonedDateTime.now();
		}
		// require dateFrom, at least for now
		if (null != dateFrom) {
			retList = repository.retrieveAuditRecordsForEntityByDates(orgUuid.toString(), tn.toString(), 
					dateFrom.toString().split("\\[")[0], dateTo.toString().split("\\[")[0], limitAsStr);
		}
		return retList;
	}
	
	public Optional<Audit> getAuditRevision(UUID entityUuid, TableName tn, Integer revision) {
		return repository.retrieveEntityRevision(tn.toString(), entityUuid.toString(), revision.toString());
	}
	
	public List<Audit> getAllAuditRevisions(UUID orgUuid, TableName tn, LocalDateTime cutOffDate) {
		return repository.getAllAuditRevisions(orgUuid.toString(), tn.toString(), cutOffDate.toString());
	}
	
	public List<Audit> getAllAuditRevisions(UUID orgUuid) {
		return repository.getAllAuditRevisions(orgUuid.toString());
	}
	
	
	public Audit createAndSaveAuditRecord (TableName tn, RelizaEntity re) {
		Audit retA = null;
		// ensure that we are not duplicating revision, in which case reject
		Optional<Audit> oa = getAuditRevision(re.getUuid(), tn, re.getRevision());
		if (oa.isPresent()) {
			retA = oa.get();
		} else {
			Audit a = new Audit();
			a.setEntityName(tn);
			a.setEntityUuid(re.getUuid());
			a.setEntityCreatedDate(re.getCreatedDate());
			a.setRevision(re.getRevision());
			a.setSchemaVersion(re.getSchemaVersion());
			a.setRevisionCreatedDate(re.getLastUpdatedDate());
			a.setRevisionRecordData(re.getRecordData());
			retA = repository.save(a);
		}
		return retA;
	}
	
	
	
}
