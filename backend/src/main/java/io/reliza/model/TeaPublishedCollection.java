/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.annotations.Type;
import org.springframework.data.domain.Persistable;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

/**
 * One immutable TEA collection version of a publication (task TEA-5). {@link #getUuid()} is the
 * publication uuid, which is what every reader means by the collection's uuid. Never updated:
 * {@link #isNew()} answers true until the row is persisted or loaded, so a save always inserts
 * and a second save of the same (uuid, version) fails on the primary key instead of merging.
 * The class is named for what it is, not {@code TeaCollection}: that is the generated 0.4.0 wire
 * class in {@code model/tea}.
 */
@Entity
@IdClass(TeaPublishedCollectionId.class)
@Table(schema = ModelProperties.DB_SCHEMA, name = "tea_collections")
public class TeaPublishedCollection implements Serializable, Persistable<TeaPublishedCollectionId> {
	private static final long serialVersionUID = 20261003L;

	@Id
	private UUID uuid;

	@Id
	private int version;

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private UUID release;

	@Column(nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	@Column
	private UUID createdBy;

	@Column(nullable = false)
	private int schemaVersion = 0;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private Map<String, Object> recordData;

	@Column(nullable = false)
	private String document;

	@Column(nullable = false)
	private String documentSha256;

	/** Reserved for the collection signature; signing is out of scope, so always NULL today. */
	@Column
	private String signature;

	@Transient
	private transient boolean fresh = true;

	@PostLoad
	@PostPersist
	void markStored() {
		this.fresh = false;
	}

	@Override
	public TeaPublishedCollectionId getId() {
		return new TeaPublishedCollectionId(uuid, version);
	}

	@Override
	public boolean isNew() {
		return fresh;
	}

	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	public int getVersion() {
		return version;
	}

	public void setVersion(int version) {
		this.version = version;
	}

	public UUID getOrg() {
		return org;
	}

	public void setOrg(UUID org) {
		this.org = org;
	}

	public UUID getRelease() {
		return release;
	}

	public void setRelease(UUID release) {
		this.release = release;
	}

	public ZonedDateTime getCreatedDate() {
		return createdDate;
	}

	public void setCreatedDate(ZonedDateTime createdDate) {
		this.createdDate = createdDate;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(UUID createdBy) {
		this.createdBy = createdBy;
	}

	public int getSchemaVersion() {
		return schemaVersion;
	}

	public void setSchemaVersion(int schemaVersion) {
		this.schemaVersion = schemaVersion;
	}

	public Map<String, Object> getRecordData() {
		return recordData;
	}

	public void setRecordData(Map<String, Object> recordData) {
		this.recordData = recordData;
	}

	public String getDocument() {
		return document;
	}

	public void setDocument(String document) {
		this.document = document;
	}

	public String getDocumentSha256() {
		return documentSha256;
	}

	public void setDocumentSha256(String documentSha256) {
		this.documentSha256 = documentSha256;
	}

	public String getSignature() {
		return signature;
	}

	public void setSignature(String signature) {
		this.signature = signature;
	}
}
