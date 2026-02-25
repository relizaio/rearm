/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/


package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Interface to cover Reliza Model classes interacting with database and using jsonb interface
 * @author pavel
 *
 */
public interface RelizaEntity {
	
	public UUID getUuid();
	
	public ZonedDateTime getCreatedDate();
	
	public ZonedDateTime getLastUpdatedDate();
	
	public Map<String, Object> getRecordData();
	
	public void setRecordData(Map<String, Object> recordData);
	
	public int getSchemaVersion();
	
	public int getRevision();
}
