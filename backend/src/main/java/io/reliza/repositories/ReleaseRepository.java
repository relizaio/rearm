/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.Release;
import jakarta.persistence.LockModeType;

public interface ReleaseRepository extends CrudRepository<Release, UUID> {

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT i FROM Release i where uuid = :uuid")
	public Optional<Release> findByIdWriteLocked(UUID uuid);
	
	@Query(
			value = VariableQueries.FIND_RELEASE_BY_ID_AND_ORG,
			nativeQuery = true)
	public Optional<Release> findReleaseByIdAndOrg(UUID releaseUuid, String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_BRANCH,
			nativeQuery = true)
	List<Release> findReleasesOfBranch(String branchUuidAsString, String limitAsStr, String offsetAsStr);

	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_BRANCH_UP_TO_DATE,
			nativeQuery = true)
	List<Release> findReleasesOfBranchUpToDate(String branchUuidAsString, ZonedDateTime upToDate, String limitAsStr, String offsetAsStr);

	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_BRANCH_WHERE_IN_SCE,
			nativeQuery = true)
	List<Release> findReleasesOfBranchWhereInSce(String branchUuidAsString, List<String> sces, String limitAsStr, String offsetAsStr);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_ORG,
			nativeQuery = true)
	List<Release> findReleasesOfOrg(String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_ALL_PRODUCT_RELEASES_OF_ORG,
			nativeQuery = true)
	List<Release> findProductReleasesOfOrg(String orgUuidAsString, String limitAsStr, String offsetAsStr);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_COMPONENT,
			nativeQuery = true)
	List<Release> findReleasesOfComponent(String componentUuidAsString, String limitAsStr, String offsetAsStr);

	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_ORG_AFTER_CREATE_DATE,
			nativeQuery = true)
	List<Release> findReleasesOfOrgAfterDate(String orgUuidAsString, ZonedDateTime cutOffDate);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_ORG_BETWEEN_DATES,
			nativeQuery = true)
	List<Release> findReleasesOfOrgBetweenDates(String orgUuidAsString, ZonedDateTime startDate, ZonedDateTime endDate);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_COMPONENT_BETWEEN_DATES,
			nativeQuery = true)
	List<Release> findReleasesOfComponentBetweenDates(String componentUuidAsString, ZonedDateTime startDate, ZonedDateTime endDate);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_BRANCH_BETWEEN_DATES,
			nativeQuery = true)
	List<Release> findReleasesOfBranchBetweenDates(String branchUuidAsString, ZonedDateTime startDate, ZonedDateTime endDate);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_COMPONENT_AFTER_CREATE_DATE,
			nativeQuery = true)
	List<Release> findReleasesOfComponentAfterDate(String componentUuidAsString, ZonedDateTime cutOffDate);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_BRANCH_AFTER_CREATE_DATE,
			nativeQuery = true)
	List<Release> findReleasesOfBranchAfterDate(String branchUuidAsString, ZonedDateTime cutOffDate);
	
	@Query(
			value = VariableQueries.FIND_RELEASES_BY_DELIVERABLE_AND_ORG,
			nativeQuery = true)
	List<Release> findReleasesByDeliverable(String deliverableUuidAsString, String orgUuidAsString);
	
	/**
	 * This only locates releases by artifacts directly attached to release and not to its deliverables
	 * @param artifactUuidAsString
	 * @param orgUuidAsString
	 * @return
	 */
	@Query(
			value = VariableQueries.FIND_RELEASES_BY_ARTIFACT_AND_ORG,
			nativeQuery = true)
	List<Release> findReleasesByReleaseArtifact(String artifactUuidAsString, String orgUuidAsString);

	@Query(
			value = VariableQueries.FIND_PENDING_RELEASES_AFTER_CUTOFF,
			nativeQuery = true)
	List<Release> findPendingReleasesAfterCutoff(String lifecycle, String cutOffDate);
	
	@Query(
			value = VariableQueries.FIND_ALL_RELEASES_OF_ORG_BY_VERSION,
			nativeQuery = true)
	List<Release> findReleasesOfOrgByVersion(String orgUuidAsString, String version);

	@Query(
			value = VariableQueries.FIND_RELEASES_BY_SCE_AND_ORG,
			nativeQuery = true)
	List<Release> findReleaseBySce(String sceUuidAsString, String orgUuidAsString);

	@Query(
			value = VariableQueries.FIND_LATEST_RELEASE_BY_SCE_AND_ORG,
			nativeQuery = true)
	Optional<Release> findLatestReleaseBySce(String sceUuidAsString, String orgUuidAsString);

	@Query(
			value = VariableQueries.FIND_RELEASE_BY_COMPONENT_AND_VERSION,
			nativeQuery = true)
	Optional<Release> findByComponentAndVersion(String compUuidAsString, String version);
	
	@Query(
			value = VariableQueries.FIND_PRODUCTS_THAT_HAVE_THIS_RELEASE,
			nativeQuery = true)
	List<Release> findProductsByRelease(String orgUuidAsString, String releaseUuidAsString);	

	@Query(
			value = VariableQueries.FIND_PRODUCTS_THAT_HAVE_THESE_RELEASES,
			nativeQuery = true)
	List<Release> findProductsByReleases(String orgUuidAsString, String releaseArrString);	
	
	@Query(
		value = VariableQueries.LIST_RELEASES_BY_COMPONENT,
		nativeQuery = true
	)
	List<Release> listReleasesByComponent(String compUuidAsString);
	
	@Query(
		value = VariableQueries.LIST_RELEASES_BY_COMPONENTS,
		nativeQuery = true
	)
	List<Release> listReleasesByComponents(Collection<UUID> componentUuids);

	@Query(
			value = VariableQueries.FIND_RELEASES_OF_BRANCH_BETWEEN_DATES,
			nativeQuery = true)
	List<Release> findReleasesOfBranchBetweenDates(String branchUuidAsString, String fromDate, String toDate);
	
	@Query(
			value = VariableQueries.FIND_PREVIOUS_RELEASES_OF_BRANCH_FOR_RELEASE,
			nativeQuery = true)
	UUID findPreviousReleasesOfBranchForRelease(String branch, UUID release);
	
	@Query(
			value = VariableQueries.FIND_NEXT_RELEASES_OF_BRANCH_FOR_RELEASE,
			nativeQuery = true)
	UUID findNextReleasesOfBranchForRelease(String branch, UUID release);
	
	@Query(
			value = VariableQueries.FIND_DISTINCT_RELEASE_TAG_KEYS_OF_ORG,
			nativeQuery = true)
	List<String> findDistrinctReleaseKeysOfOrg(String orgUuidAsString);

	@Query(
			value = VariableQueries.FIND_RELEASES_BY_TAG_KEY,
			nativeQuery = true)
	List<Release> findReleasesByTagKey(String orgUuidAsString, String tagKey);

	@Query(
			value = VariableQueries.FIND_BRANCH_RELEASES_BY_TAG_KEY,
			nativeQuery = true)
	List<Release> findBranchReleasesByTagKey(String orgUuidAsString, String branchUuidAsString, String tagKey);
	
	@Query(
			value = VariableQueries.FIND_RELEASES_BY_TAG_KEY_VALUE,
			nativeQuery = true)
	List<Release> findReleasesByTagKeyAndValue(String orgUuidAsString, String tagKey, String tagValue);
	
	@Query(
			value = VariableQueries.FIND_BRANCH_RELEASES_BY_TAG_KEY_VALUE,
			nativeQuery = true)
	List<Release> findBranchReleasesByTagKeyAndValue(String orgUuidAsString, String branchUuidAsString, String tagKey, String tagValue);

	@Query(
			value = VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_BY_ARTIFACT_DIRECT,
			nativeQuery = true)
	List<Release> findReleasesForMetricsComputeByArtifactDirect();
	
	@Query(
			value = VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_BY_SCE,
			nativeQuery = true)
	List<Release> findReleasesForMetricsComputeBySce();
	
	@Query(
			value = VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_BY_OUTBOUND_DELIVERABLES,
			nativeQuery = true)
	List<Release> findReleasesForMetricsComputeByOutboundDeliverables();

	@Query(
			value = VariableQueries.FIND_PRODUCT_RELEASES_FOR_METRICS_COMPUTE,
			nativeQuery = true)
	List<Release> findProductReleasesForMetricsCompute();
	
	@Query(
			value = VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_BY_UPDATE,
			nativeQuery = true)
	List<Release> findReleasesForMetricsComputeByUpdate();
	
	@Query(
		value = VariableQueries.FIND_RELEASES_SHARING_SCE_ARTIFACT,
		nativeQuery = true)
		List<Release> findReleasesSharingSceArtifact(String artUuidAsString);

	@Query(
		value = VariableQueries.FIND_RELEASES_SHARING_DELIVRABLE_ARTIFACT,
		nativeQuery = true)
		List<Release> findReleasesSharingDeliverableArtifact(String artUuidAsString);

	@Query(
		value = VariableQueries.FIND_RELEASES_BY_ARTIFACTS_AND_ORG,
		nativeQuery = true)
	List<Release> findReleasesByReleaseArtifacts(Collection<String> artifactUuidsAsStrings, String orgUuidAsString);

	@Query(
		value = VariableQueries.FIND_RELEASES_SHARING_SCE_ARTIFACTS,
		nativeQuery = true)
	List<Release> findReleasesSharingSceArtifacts(Collection<String> artifactUuidsAsStrings);

	@Query(
		value = VariableQueries.FIND_RELEASES_SHARING_DELIVERABLE_ARTIFACTS,
		nativeQuery = true)
	List<Release> findReleasesSharingDeliverableArtifacts(Collection<String> artifactUuidsAsStrings);

	@Query(
		value = VariableQueries.FIND_RELEASES_BY_ORG_AND_IDENTIFIER,
		nativeQuery = true)
		List<Release> findReleasesByOrgAndIdentifier(String orgUuidAsString, String idType, String idValue);
	
	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_VULNERABILITY,
			nativeQuery = true)
	List<Release> findReleasesWithVulnerability(String orgUuidAsString, String location, String findingId);
	
	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_VIOLATION,
			nativeQuery = true)
	List<Release> findReleasesWithViolation(String orgUuidAsString, String location, String findingId);
	
	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_WEAKNESS,
			nativeQuery = true)
	List<Release> findReleasesWithWeakness(String orgUuidAsString, String location, String findingId);
	
	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_VULNERABILITY_IN_BRANCH,
			nativeQuery = true)
	List<Release> findReleasesWithVulnerabilityInBranch(String orgUuidAsString, String branchUuidAsString, String location, String findingId);
	
	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_VIOLATION_IN_BRANCH,
			nativeQuery = true)
	List<Release> findReleasesWithViolationInBranch(String orgUuidAsString, String branchUuidAsString, String location, String findingId);
	
	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_WEAKNESS_IN_BRANCH,
			nativeQuery = true)
	List<Release> findReleasesWithWeaknessInBranch(String orgUuidAsString, String branchUuidAsString, String location, String findingId);
	
	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_VULNERABILITY_IN_COMPONENT,
			nativeQuery = true)
	List<Release> findReleasesWithVulnerabilityInComponent(String orgUuidAsString, String componentUuidAsString, String location, String findingId);
	
	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_VIOLATION_IN_COMPONENT,
			nativeQuery = true)
	List<Release> findReleasesWithViolationInComponent(String orgUuidAsString, String componentUuidAsString, String location, String findingId);
	
	@Query(
			value = VariableQueries.FIND_RELEASES_WITH_WEAKNESS_IN_COMPONENT,
			nativeQuery = true)
	List<Release> findReleasesWithWeaknessInComponent(String orgUuidAsString, String componentUuidAsString, String location, String findingId);
	
	@Query(
			value = VariableQueries.FIND_RELEASES_BY_CVE_ID,
			nativeQuery = true)
	List<Release> findReleasesByCveId(String orgUuidAsString, String cveId);
	
}
