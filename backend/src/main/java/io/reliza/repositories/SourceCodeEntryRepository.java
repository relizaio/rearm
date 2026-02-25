/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.SourceCodeEntry;

public interface SourceCodeEntryRepository extends CrudRepository<SourceCodeEntry, UUID> {
	@Query(
			value = VariableQueries.FIND_SCE_BY_COMMIT_AND_VCS,
			nativeQuery = true)
	Optional<SourceCodeEntry> findByCommitAndVcs(String commit, String vcsUuidAsString);

	@Query(
			value = VariableQueries.FIND_SCE_BY_COMMITS_AND_VCS,
			nativeQuery = true)
	List<SourceCodeEntry> findByCommitsAndVcs(List<String> commits, String vcsUuidAsString);

	@Query(
			value = VariableQueries.FIND_SCE_BY_COMMIT_OR_TAG_AND_ORG,
			nativeQuery = true)
	List<SourceCodeEntry> findByCommitOrTag(String orgUuidAsString, String commit, String tag);

	@Query(
			value = VariableQueries.FIND_SCE_BY_COMPONENT,
			nativeQuery = true)
	List<SourceCodeEntry> findByComponent(String compUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_ALL_SCES_BY_ORG,
			nativeQuery = true)
	List<SourceCodeEntry> findByOrg(String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_ALL_SCES_OF_ORGS_BY_ID,
			nativeQuery = true)
	List<SourceCodeEntry> findScesOfOrgsByIds(Collection<UUID> sces, Collection<UUID> orgs);

	@Query(
		value = VariableQueries.FIND_SCE_BY_TICKET_AND_ORG,
		nativeQuery = true)
	Optional<SourceCodeEntry> findByTicketAndOrg(String ticket, String org);

	@Query(
		value = VariableQueries.FIND_SCE_BY_TICKET_AND_ORG,
		nativeQuery = true)
	Optional<SourceCodeEntry> findSourceCodeEntriesByArtifact(String ticket, String org);
}
