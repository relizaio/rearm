/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;

@Entity
@Table(schema=ModelProperties.DB_SCHEMA, name="audit")
public class Audit implements Serializable {
	private static final long serialVersionUID = 234734;
	
	private static final Logger log = LoggerFactory.getLogger(Audit.class);
	
	@Id
	private UUID uuid = UUID.randomUUID();
	
	@Column(nullable = false)
	private String entityName;
	
	@Column(nullable = false)
	private UUID entityUuid;
	
	@Column(nullable = false)
	private int revision=0;
	
	@Column(nullable = false)
	private int schemaVersion=0;

	@Column(nullable = false)
	private ZonedDateTime revisionCreatedDate = ZonedDateTime.now(); // essentially, entity updated date
	
	@Column(nullable = false)
	private ZonedDateTime entityCreatedDate;
	
	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private String revisionRecordData;
	
	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	public int getRevision() {
		return revision;
	}

	public void setRevision(int revision) {
		this.revision = revision;
	}

	public ZonedDateTime getRevisionCreatedDate() {
		return revisionCreatedDate;
	}

	public void setRevisionCreatedDate(ZonedDateTime createdDate) {
		this.revisionCreatedDate = createdDate;
	}

	public ZonedDateTime getEntityCreatedDate() {
		return entityCreatedDate;
	}

	public void setEntityCreatedDate(ZonedDateTime entityCreatedDate) {
		this.entityCreatedDate = entityCreatedDate;
	}
	
	public String getRawJson() {
		return this.revisionRecordData;
	}
	
	public Map<String, Object> getRevisionRecordData() {
		Map<String, Object> retObj = new LinkedHashMap<>();
		try {
			retObj =  Utils.OM.readValue(revisionRecordData, Map.class);
		} catch (JsonMappingException e) {
			log.error("Exception when mapping record data from instance to JSON", e);
		} catch (JsonProcessingException e) {
			log.error("Exception when mapping record data from instance to JSON", e);
		}
		return retObj;
	}

	public void setRevisionRecordData(Map<String, Object> recordData) {
		try {
			this.revisionRecordData = Utils.OM.writeValueAsString(recordData);
		} catch (JsonProcessingException e) {
			log.error("Exception when setting record data into instance", e);
		}
	}

	public int getSchemaVersion() {
		return schemaVersion;
	}

	public void setSchemaVersion(int schemaVersion) {
		this.schemaVersion = schemaVersion;
	}

	public TableName getEntityName() {
		return TableName.get(entityName);
	}

	public void setEntityName(TableName entityName) {
		this.entityName = entityName.toString();
	}

	public UUID getEntityUuid() {
		return entityUuid;
	}

	public void setEntityUuid(UUID entityUuid) {
		this.entityUuid = entityUuid;
	}
}
