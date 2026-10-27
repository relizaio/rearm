/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.Integration;

public interface IntegrationRepository extends CrudRepository<Integration, UUID> {

	@Query(
			value = VariableQueries.FIND_INTEGRATION_BY_ORG_TYPE_IDENTIFIER,
			nativeQuery = true)
	Optional<Integration> findIntegrationByOrgTypeIdentifier(String orgUuidAsString, String typeAsString, String identifier);

	@Query(
			value = VariableQueries.FIND_INTEGRATION_BY_ORG_IDENTIFIER,
			nativeQuery = true)
	Optional<Integration> findIntegrationByOrgIdentifier(String orgUuidAsString, String identifier);


	@Query(
			value = VariableQueries.LIST_INTEGRATIONS_BY_ORG,
			nativeQuery = true)
	List<Integration> listIntegrationsByOrg(String orgUuidAsString);

	@Query(
			value = VariableQueries.LIST_ORGS_WITH_DTRACK_INTEGRATION,
			nativeQuery = true)
	List<String> listOrgsWithDtrackIntegration();

	@Query(
			value = VariableQueries.LIST_BASE_INTEGRATIONS_BY_TYPE,
			nativeQuery = true)
	List<Integration> listBaseIntegrationsByType(String typeAsString);

	@Query(
			value = VariableQueries.LIST_ENABLED_INTEGRATIONS_BY_TYPE,
			nativeQuery = true)
	List<Integration> listEnabledIntegrationsByType(String typeAsString);

	/**
	 * Store the Dependency-Track version a probe detected (IntegrationService.redetectDtrackVersions) in place:
	 * no revision, no last_updated_date, no who-updated keys and no audit row -- a probe result the next probe
	 * supersedes (TPF-71 / TPF-107). The guard the sweep applied in Java after re-reading the row is the WHERE:
	 * the stored version must still be the one the probe started from (both null, or equal) and the URI must
	 * still be the one probed, so a change made while the probe waited is kept and a row deleted meanwhile is
	 * not brought back. The database re-tests the condition at write time, which the Java re-read could not.
	 *
	 * @param known the version stored when the probe started, as its name, or null for not detected
	 * @return the rows updated: 1, or 0 when the row is gone, its version moved or its URI changed meanwhile
	 */
	@Modifying(flushAutomatically = true)
	@Transactional
	@Query(value = "UPDATE rearm.integrations SET record_data = jsonb_set(record_data, '{dtrackVersion}', "
			+ "to_jsonb(cast(:detected as text))) "
			+ "WHERE uuid = :uuid AND record_data->>'dtrackVersion' IS NOT DISTINCT FROM cast(:known as text) "
			+ "AND record_data->>'uri' = cast(:uri as text)", nativeQuery = true)
	int stampDtrackVersion(@Param("uuid") UUID uuid, @Param("detected") String detected, @Param("known") String known,
			@Param("uri") String uri);

}
