/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.micrometer.common.util.StringUtils;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.InstallationType;
import io.reliza.model.BranchData;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.repositories.BranchRepository;
import io.reliza.repositories.SourceCodeEntryRepository;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class GetSourceCodeEntryService {
	
	@Autowired
	UserService userService;

	// The repository, not BranchService: BranchService reaches back to this class (through
	// GetComponentService and SharedReleaseService), and ownerOrg needs only the branch row.
	@Autowired
	private BranchRepository branchRepository;

	private final SourceCodeEntryRepository repository;
	
	GetSourceCodeEntryService(SourceCodeEntryRepository repository) {
	    this.repository = repository;
	}
	
	protected Optional<SourceCodeEntry> getSourceCodeEntry (UUID uuid) {
		return repository.findById(uuid);
	}
	
	/**
	 * The org that owns a stored entry: its own org, or for a legacy entry stored without one, its
	 * branch's org. Null when neither is known.
	 */
	public UUID ownerOrg (SourceCodeEntryData sced) {
		UUID owner = null;
		if (null != sced) {
			owner = sced.getOrg();
			if (null == owner && null != sced.getBranch()) {
				owner = branchRepository.findById(sced.getBranch())
						.map(b -> BranchData.branchDataFromDbRecord(b).getOrg()).orElse(null);
			}
		}
		return owner;
	}

	/**
	 * Whether {@code org} may write to the entry (merge a build into it, attach artifacts to it):
	 * its {@link #ownerOrg} is {@code org}. This is the write rule. It is stricter than the
	 * read/reference rule, {@link SharedReleaseService#isReferenceableFromOrg} (used by
	 * {@link #getReferenceableSceData}): the shared external-components org is never writable from
	 * another org and an entry with no known owner belongs to no org, while an owner-less legacy
	 * entry is writable by its branch's org.
	 */
	public boolean belongsTo (SourceCodeEntryData sced, UUID org) {
		return null != org && org.equals(ownerOrg(sced));
	}

	public Optional<SourceCodeEntryData> getSourceCodeEntryData (UUID uuid) {
		return getSourceCodeEntry(uuid).map(this::dataForDisplay);
	}

	private SourceCodeEntryData dataForDisplay (SourceCodeEntry sce) {
		SourceCodeEntryData sceDataOrig = SourceCodeEntryData.dataFromRecord(sce);
		if (StringUtils.isNotEmpty(sceDataOrig.getCommitEmail()) 
			&& userService.getInstallationType() == InstallationType.DEMO
			&& UserService.USER_ORG.equals(sceDataOrig.getOrg())) {
				sceDataOrig = SourceCodeEntryData.dataFromRecord(sce, true);
		}
		return sceDataOrig;
	}

	/**
	 * The entries among {@code uuids} that a release or pull request of {@code ownerOrg} may show:
	 * owned by {@code ownerOrg} or by the shared external-components org, the rule of
	 * {@link SharedReleaseService#isReferenceableFromOrg}. One org-scoped query; the result follows
	 * the input order (duplicates included), and a uuid that is missing, owned by another org, null
	 * or the {@link SourceCodeEntryData#NULL_SCE_UUID} placeholder is simply absent from it.
	 */
	public List<SourceCodeEntryData> getReferenceableSceDataList (Collection<UUID> uuids, UUID ownerOrg) {
		if (null == uuids || uuids.isEmpty() || null == ownerOrg) return new LinkedList<>();
		Set<UUID> toResolve = uuids.stream()
				.filter(u -> null != u && !SourceCodeEntryData.NULL_SCE_UUID.equals(u))
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (toResolve.isEmpty()) return new LinkedList<>();
		Map<UUID, SourceCodeEntryData> byUuid = getSceList(toResolve,
					List.of(ownerOrg, CommonVariables.EXTERNAL_PROJ_ORG_UUID)).stream()
				.map(this::dataForDisplay)
				.collect(Collectors.toMap(SourceCodeEntryData::getUuid, Function.identity(), (a, b) -> a));
		return uuids.stream().filter(byUuid::containsKey).map(byUuid::get)
				.collect(Collectors.toCollection(LinkedList::new));
	}

	/** Single-uuid {@link #getReferenceableSceDataList}. */
	public Optional<SourceCodeEntryData> getReferenceableSceData (UUID uuid, UUID ownerOrg) {
		if (null == uuid) return Optional.empty();
		return getReferenceableSceDataList(List.of(uuid), ownerOrg).stream().findFirst();
	}
	
	/**
	 * {@link #getSourceCodeEntryData(UUID)} only when the entry {@link #belongsTo} {@code org}: a
	 * missing entry, one with no known owner, the {@link SourceCodeEntryData#NULL_SCE_UUID}
	 * placeholder and another org's entry all come back empty.
	 */
	public Optional<SourceCodeEntryData> getOwnedSceData (UUID uuid, UUID org) {
		Optional<SourceCodeEntryData> sceData = Optional.empty();
		if (null != uuid && !SourceCodeEntryData.NULL_SCE_UUID.equals(uuid)) {
			sceData = getSourceCodeEntryData(uuid).filter(sced -> belongsTo(sced, org));
		}
		return sceData;
	}

	/**
	 * The newest source code entry ReARM recorded on a repository's branch, as the entity (its createdDate is
	 * what {@link #countOnBranchAfter} counts from); empty when the branch has none (task RD4-2).
	 */
	public Optional<SourceCodeEntry> newestOnBranch(UUID vcs, String vcsBranch) {
		if (null == vcs || StringUtils.isBlank(vcsBranch)) return Optional.empty();
		return repository.findNewestOnVcsBranch(vcs.toString(), vcsBranch);
	}

	/** How many source code entries ReARM recorded on a repository's branch after {@code after}; all of them when null. */
	public long countOnBranchAfter(UUID vcs, String vcsBranch, java.time.ZonedDateTime after) {
		if (null == vcs || StringUtils.isBlank(vcsBranch)) return 0;
		return repository.countOnVcsBranchAfter(vcs.toString(), vcsBranch,
				null == after ? java.time.ZonedDateTime.parse("1970-01-01T00:00:00Z") : after);
	}

	private List<SourceCodeEntry> getSceList (Collection<UUID> uuidList, Collection<UUID> orgs) {
		// return (List<Release>) repository.findAllById(uuidList);
		return repository.findScesOfOrgsByIds(uuidList, orgs);
	}
	
	public List<SourceCodeEntryData> getSceDataList (Collection<UUID> uuidList, Collection<UUID> orgs) {
		// check if list includes null uuid - in which case include placeholder sce
		Set<UUID> uuidListToResolve;
		boolean includesNull = false;
		if (uuidList.contains(SourceCodeEntryData.NULL_SCE_UUID) || uuidList.contains(null)) {
			uuidListToResolve = uuidList.stream().filter(x -> null != x && !x.equals(SourceCodeEntryData.NULL_SCE_UUID))
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
