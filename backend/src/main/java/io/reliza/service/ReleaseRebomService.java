/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebom;
import io.reliza.model.ReleaseRebomData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.repositories.ReleaseRebomRepository;

@Service
public class ReleaseRebomService {
	
	@Autowired
    private AuditService auditService;
	
	private final ReleaseRebomRepository repository;
	
	ReleaseRebomService(ReleaseRebomRepository repository) {
	    this.repository = repository;
	}
	
	public Optional<ReleaseRebom> getReleaseRebom (UUID id) {
		Optional<ReleaseRebom> orr = Optional.empty();
		if (null != id) {
			orr = repository.findById(id);
		}
		return orr;
	}
	public Optional<ReleaseRebom> getReleaseRebom (UUID org, UUID release) {
		Optional<ReleaseRebom> orr = Optional.empty();
		if (null != org && null != release) {
			orr = repository.findReleaseRebomByOrgAndRelease(org.toString(), release.toString());
		}
		return orr;
	}
	public Optional<ReleaseRebomData> getReleaseRebomData (ReleaseData rd) {
		Optional<ReleaseRebomData> releaseRebomData = Optional.empty();
		Optional<ReleaseRebom> orr = getReleaseRebom(rd.getOrg(), rd.getUuid());
		if (orr.isPresent()) {
			releaseRebomData = Optional
							.of(
									ReleaseRebomData
									.dataFromRecord(orr
										.get()
								));
		}
		return releaseRebomData;
	}

	private ReleaseRebom setReboms (UUID org, UUID release, 
													List<ReleaseBom> reboms, WhoUpdated wu) {
		ReleaseRebom rr = new ReleaseRebom();
		Optional<ReleaseRebom> orr = getReleaseRebom(org, release);
		if(orr.isPresent()){
			rr = orr.get();
		}
		ReleaseRebomData rrd = ReleaseRebomData.releaseRebomFactory(org, release, reboms);
		Map<String,Object> recordData = Utils.dataToRecord(rrd);
		return saveReleaseRebom(rr, recordData, wu);
	}
	
	private ReleaseRebom saveReleaseRebom (ReleaseRebom rr, Map<String,Object> recordData, WhoUpdated wu) {
		Optional<ReleaseRebom> orr = getReleaseRebom(rr.getUuid());
		if (orr.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.VCS_REPOSITORIES, rr);
			rr.setRevision(rr.getRevision() + 1);
			rr.setLastUpdatedDate(ZonedDateTime.now());
		}
		rr.setRecordData(recordData);
		rr = (ReleaseRebom) WhoUpdated.injectWhoUpdatedData(rr, wu);
		return repository.save(rr);
	}
	
    public List<ReleaseBom> getReleaseBoms(ReleaseData rd){
        List<ReleaseBom> reboms = new ArrayList<>();
		Optional<ReleaseRebomData> rrd = getReleaseRebomData(rd);
		if(rrd.isPresent()){
			reboms = rrd.get().getReboms();
		}
        return reboms;
    }

    public void setReboms(ReleaseData rd, List<ReleaseBom> reboms, WhoUpdated wu){
		setReboms(rd.getOrg(),rd.getUuid(), reboms, wu);
    }
}
