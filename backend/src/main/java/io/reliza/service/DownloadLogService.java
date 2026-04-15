/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.Utils;
import io.reliza.model.DownloadLog;
import io.reliza.model.DownloadLogData;
import io.reliza.model.DownloadLogData.DownloadConfig;
import io.reliza.model.DownloadLogData.DownloadSubjectType;
import io.reliza.model.DownloadLogData.DownloadType;
import io.reliza.model.ArtifactData;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.DownloadLogRepository;
import io.reliza.repositories.ReleaseRepository;

@Service
public class DownloadLogService {

	private static final Logger log = LoggerFactory.getLogger(DownloadLogService.class);

	private final DownloadLogRepository downloadLogRepository;

	@Autowired
	private ArtifactRepository artifactRepository;

	@Autowired
	private ReleaseRepository releaseRepository;

	@Autowired
	private GetComponentService getComponentService;

	@Autowired
	private ArtifactService artifactService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private UserService userService;

	DownloadLogService(DownloadLogRepository downloadLogRepository) {
		this.downloadLogRepository = downloadLogRepository;
	}

	@Async
	@Transactional
	public void createDownloadLog(UUID org, DownloadType downloadType, DownloadSubjectType subjectType,
			UUID subjectUuid, WhoUpdated wu, DownloadConfig config) {
		try {
			DownloadLogData dld = new DownloadLogData();
			dld.setOrg(org);
			dld.setDownloadType(downloadType);
			dld.setSubjectType(subjectType);
			dld.setSubjectUuid(subjectUuid);
			if (wu != null) {
				dld.setCreatedType(wu.getCreatedType());
				dld.setDownloadedBy(wu.getLastUpdatedBy());
				dld.setIpAddress(wu.getLastUpdatedIp());
				dld.setLastUpdatedBy(wu.getLastUpdatedBy());
				dld.setLastUpdatedIp(wu.getLastUpdatedIp());
			}
			dld.setDownloadConfig(config);

			DownloadLog dl = new DownloadLog();
			Map<String, Object> recordData = Utils.dataToRecord(dld);
			dl.setRecordData(recordData);
			dl = (DownloadLog) WhoUpdated.injectWhoUpdatedData(dl, wu);
			downloadLogRepository.save(dl);
		} catch (Exception e) {
			log.error("Failed to create download log for org={} type={} subject={}: {}",
					org, downloadType, subjectUuid, e.getMessage(), e);
		}
	}

	public List<DownloadLogData> listDownloadLogs(UUID orgUuid, ZonedDateTime fromDate, ZonedDateTime toDate,
			UUID perspectiveUuid) {
		String orgStr = orgUuid.toString();
		List<DownloadLog> logs;

		if (perspectiveUuid != null) {
			Collection<String> subjectUuids = resolveSubjectUuidsForPerspective(perspectiveUuid);
			if (subjectUuids.isEmpty()) {
				return Collections.emptyList();
			}
			logs = downloadLogRepository.listDownloadLogsByOrgAndSubjects(orgStr, fromDate, toDate, subjectUuids);
		} else {
			logs = downloadLogRepository.listDownloadLogsByOrg(orgStr, fromDate, toDate);
		}

		return logs.stream().map(DownloadLogData::dataFromRecord).map(this::enrichWithNames).collect(Collectors.toList());
	}

	private DownloadLogData enrichWithNames(DownloadLogData dld) {
		if (dld.getSubjectUuid() != null) {
			if (dld.getSubjectType() == DownloadSubjectType.ARTIFACT) {
				artifactService.getArtifactData(dld.getSubjectUuid())
						.map(ArtifactData::getDisplayIdentifier)
						.ifPresent(dld::setSubjectName);
			} else if (dld.getSubjectType() == DownloadSubjectType.RELEASE) {
				sharedReleaseService.getReleaseData(dld.getSubjectUuid())
						.map(ReleaseData::getVersion)
						.ifPresent(dld::setSubjectName);
			}
		}
		if (dld.getDownloadedBy() != null) {
			userService.getUserData(dld.getDownloadedBy()).ifPresent(ud -> {
				String label = ud.getName() != null ? ud.getName() : ud.getEmail();
				dld.setDownloadedByName(label);
			});
		}
		return dld;
	}

	private Collection<String> resolveSubjectUuidsForPerspective(UUID perspectiveUuid) {
		List<String> componentUuids = getComponentService.listComponentsByPerspective(perspectiveUuid)
				.stream()
				.map(cd -> cd.getUuid().toString())
				.collect(Collectors.toList());

		if (componentUuids.isEmpty()) {
			return Collections.emptyList();
		}

		List<String> releaseUuids = releaseRepository.listReleaseUuidsByComponents(componentUuids);
		List<String> artifactUuids = artifactRepository.listArtifactUuidsByComponents(componentUuids);

		List<String> subjectUuids = new ArrayList<>();
		subjectUuids.addAll(releaseUuids);
		subjectUuids.addAll(artifactUuids);
		return subjectUuids;
	}
}
