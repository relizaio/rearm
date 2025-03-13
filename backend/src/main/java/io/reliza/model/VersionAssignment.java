/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.reliza.versioning.Version.VersionStringComparator;

/**
 * This entity is used to track and synchronize any version assignments requested by the organization
 * @author pavel
 *
 */
@Entity
@Table(schema=ModelProperties.DB_SCHEMA, name="version_assignments")
public class VersionAssignment implements Serializable, Comparable<VersionAssignment>, RelizaObject {
	
	public enum AssignmentTypeEnum{
		OPEN,
		ASSIGNED,
		RESERVED;

		private AssignmentTypeEnum () {}
		
		@Override
		public String toString() {
			return this.name();
		}
	}

	public enum VersionTypeEnum{
		DEV,
		MARKETING;

		private VersionTypeEnum () {}

		@Override
		public String toString() {
			return this.name();
		}
	}

	private static final long serialVersionUID = 2347342;
	
	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();
	
	@Column(nullable = false)
	private ZonedDateTime lastUpdatedDate = ZonedDateTime.now();
	
	@Column(nullable = false)
	private UUID component;
	
	@Column(nullable = false)
	private UUID branch;
	
	@Column(nullable = false)
	private String version;
	
	@Column(nullable = true)
	private String versionSchema;
	
	@Column(nullable = true)
	private String branchSchema;
	
	@Column(nullable = true)
	private UUID release;
	
	@Column(nullable = false)
	private UUID org;
		
	@Column(nullable = false)
	private String assignmentType;

	@Column(nullable = false)
	private String versionType;
	
	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}


	public ZonedDateTime getCreatedDate() {
		return createdDate;
	}

	public void setCreatedDate(ZonedDateTime createdDate) {
		this.createdDate = createdDate;
	}

	public ZonedDateTime getLastUpdatedDate() {
		return lastUpdatedDate;
	}

	public void setLastUpdatedDate(ZonedDateTime lastUpdatedDate) {
		this.lastUpdatedDate = lastUpdatedDate;
	}

	public UUID getComponent() {
		return component;
	}

	public void setComponent(UUID component) {
		this.component = component;
	}

	public UUID getBranch() {
		return branch;
	}

	public void setBranch(UUID branch) {
		this.branch = branch;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public String getVersionSchema() {
		return versionSchema;
	}

	public void setVersionSchema(String versionSchema) {
		this.versionSchema = versionSchema;
	}
	
	public String getBranchSchema() {
		return branchSchema;
	}

	public void setBranchSchema(String branchSchema) {
		this.branchSchema = branchSchema;
	}

	public UUID getRelease() {
		return release;
	}

	public void setRelease(UUID release) {
		this.release = release;
	}

	@Override
	public int compareTo(VersionAssignment otherVa) {
		VersionStringComparator vsc = new VersionStringComparator(this.versionSchema);
		return vsc.compare(version, otherVa.version);
	}
	
	@Override
	public UUID getOrg() {
		return org;
	}
	
	public void setOrg(UUID org) {
		this.org = org;
	}

	public AssignmentTypeEnum getAssignmentType() {
		return AssignmentTypeEnum.valueOf(assignmentType);
	}

	public void setAssignmentType(AssignmentTypeEnum assignmentType) {
		this.assignmentType = assignmentType.toString();
	}

	public VersionTypeEnum getVersionType() {
		return VersionTypeEnum.valueOf(versionType);
	}

	public void setVersionType(VersionTypeEnum versionType) {
		this.versionType = versionType.toString();
	}

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}
	
}
