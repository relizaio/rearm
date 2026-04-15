/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.DownloadLog;

public interface DownloadLogRepository extends CrudRepository<DownloadLog, UUID> {

	@Query(
		value = VariableQueries.LIST_DOWNLOAD_LOGS_BY_ORG,
		nativeQuery = true)
	List<DownloadLog> listDownloadLogsByOrg(String orgUuidAsString, ZonedDateTime fromDate, ZonedDateTime toDate);

	@Query(
		value = VariableQueries.LIST_DOWNLOAD_LOGS_BY_ORG_AND_SUBJECTS,
		nativeQuery = true)
	List<DownloadLog> listDownloadLogsByOrgAndSubjects(String orgUuidAsString, ZonedDateTime fromDate, ZonedDateTime toDate, Collection<String> subjectUuids);
}
