/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.micrometer.common.util.StringUtils;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.InstallationType;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.repositories.SourceCodeEntryRepository;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class GetSourceCodeEntryService {
	
	@Autowired
	UserService userService;

	private final SourceCodeEntryRepository repository;
	
	GetSourceCodeEntryService(SourceCodeEntryRepository repository) {
	    this.repository = repository;
	}
	
	protected Optional<SourceCodeEntry> getSourceCodeEntry (UUID uuid) {
		return repository.findById(uuid);
	}
	
	public Optional<SourceCodeEntryData> getSourceCodeEntryData (UUID uuid) {
		Optional<SourceCodeEntryData> sceData = Optional.empty();
		Optional<SourceCodeEntry> sce = getSourceCodeEntry(uuid);
		if (sce.isPresent()) {
			SourceCodeEntryData sceDataOrig = SourceCodeEntryData.dataFromRecord(sce.get());
			if (StringUtils.isNotEmpty(sceDataOrig.getCommitEmail()) 
				&& userService.getInstallationType() == InstallationType.DEMO
				&& sceDataOrig.getOrg().equals(UserService.USER_ORG)) {
					sceDataOrig = SourceCodeEntryData.dataFromRecord(sce.get(), true);
			}
			sceData = Optional.of(sceDataOrig);
		}
		return sceData;
	}
	
	private List<SourceCodeEntry> getSceList (Collection<UUID> uuidList, Collection<UUID> orgs) {
		// return (List<Release>) repository.findAllById(uuidList);
		return repository.findScesOfOrgsByIds(uuidList, orgs);
	}
	
	public List<SourceCodeEntryData> getSceDataList (Collection<UUID> uuidList, Collection<UUID> orgs) {
		// check if list includes null uuid - in which case include placeholder sce
		Set<UUID> uuidListToResolve;
		boolean includesNull = false;
		if (uuidList.contains(new UUID(0,0)) || uuidList.contains(null)) {
			uuidListToResolve = uuidList.stream().filter(x -> null != x && !x.equals(new UUID(0,0)))
												.collect(Collectors.toSet());
			includesNull = true;
		} else {
			uuidListToResolve = new LinkedHashSet<>(uuidList);
		}
		List<SourceCodeEntry> sces = getSceList(uuidListToResolve, orgs);
		List<SourceCodeEntryData> sceds = sces
				.stream()
				.map(SourceCodeEntryData::dataFromRecord)
				.collect(Collectors.toList());
		if (includesNull) {
			// construct null sced
			var nullSced = SourceCodeEntryData.obtainNullSceData();
			sceds.add(nullSced);
		}
		
		return sceds;
	}

	public List<SourceCodeEntry> getSourceCodeEntrys (Iterable<UUID> uuids) {
		return (List<SourceCodeEntry>) repository.findAllById(uuids);
	}
	
	public List<SourceCodeEntryData> getSourceCodeEntryDataList (Iterable<UUID> uuids) {
		List<SourceCodeEntry> branches = getSourceCodeEntrys(uuids);
		return branches.stream().map(SourceCodeEntryData::dataFromRecord).collect(Collectors.toList());
	}

	public List<SourceCodeEntry> getSourceCodeEntriesByVcsAndCommits (UUID vcsUuid, List<String> commits) {
		return repository.findByCommitsAndVcs(commits, vcsUuid.toString());
	}
	
	public List<SourceCodeEntry> getSourceCodeEntriesByCommitTag (UUID orgUuid, String commit) {
		return repository.findByCommitOrTag(orgUuid.toString(), commit + "%", commit);
	}
	
	public List<SourceCodeEntry> listSceByComponent (UUID projUuid) {
		return repository.findByComponent(projUuid.toString());
	}
	
	public List<SourceCodeEntry> listSceByOrg (UUID orgUuid) {
		return repository.findByOrg(orgUuid.toString());
	}
	
	public List<SourceCodeEntryData> listSceDataByComponent(UUID projUuid){
		List<SourceCodeEntry> sceList = listSceByComponent(projUuid);
		return sceList
		.stream()
		.map(SourceCodeEntryData::dataFromRecord)
		.collect(Collectors.toList());
	}

	
	/**
	 * Mutates commits
	 * @param sceMap
	 * @param commits
	 */
	public void normalizeSceMapAndCommits(Map<String, Object> sceMap, List<Map<String, Object>> commits) {
		
	}

	public Set<UUID> getTicketsList (Collection<UUID> uuidList, Collection<UUID> orgs) {
		List<SourceCodeEntryData> sces = getSceDataList(uuidList, orgs);
		return sces
				.stream()
				.map(SourceCodeEntryData::getTicket)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
	}

	public Optional<SourceCodeEntry> findLatestSceWithTicketAndOrg(UUID ticket, UUID org) {
		return repository.findByTicketAndOrg(ticket.toString(), org.toString());
	}


	public void saveAll(List<SourceCodeEntry> sourceCodeEntries){
		repository.saveAll(sourceCodeEntries);
	}
}
