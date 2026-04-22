/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.common.Utils;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DownloadLogData extends RelizaDataParent implements RelizaObject {

	public enum DownloadType {
		ARTIFACT_DOWNLOAD,
		RAW_ARTIFACT_DOWNLOAD,
		VDR_EXPORT,
		VEX_EXPORT,
		SBOM_EXPORT
	}

	public enum DownloadSubjectType {
		ARTIFACT,
		RELEASE
	}

	/** Single flat config class — only fields relevant to the DownloadType will be non-null. */
	@Builder
	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class DownloadConfig {
		// ARTIFACT_DOWNLOAD / RAW_ARTIFACT_DOWNLOAD
		private UUID artifactUuid;
		private Integer artifactVersion;

		// SBOM_EXPORT / VDR_EXPORT / OBOM_EXPORT
		private UUID releaseUuid;

		// SBOM-specific
		private Boolean tldOnly;
		private Boolean ignoreDev;
		private String structure;
		private String belongsTo;
		private String mediaType;
		private List<String> excludeCoverageTypes;

		// VDR-specific / VEX-specific (shared snapshot controls)
		private Boolean includeSuppressed;
		private ZonedDateTime upToDate;
		private String targetLifecycle;
		private String targetApproval;

		// VEX-specific: whether IN_TRIAGE statements are included in the exported VEX document.
		private Boolean includeInTriage;

	}

	private UUID uuid;
	private UUID org;
	private DownloadType downloadType;
	private DownloadSubjectType subjectType;
	private UUID subjectUuid;
	private String subjectName;
	private UUID downloadedBy;
	private String downloadedByName;
	private String ipAddress;
	private DownloadConfig downloadConfig;

	@Override
	public UUID getResourceGroup() {
		return null;
	}

	public static DownloadLogData dataFromRecord(DownloadLog dl) {
		if (dl.getSchemaVersion() != 0) {
			throw new IllegalStateException("DownloadLog schema version " + dl.getSchemaVersion() + " is not currently supported");
		}
		Map<String, Object> recordData = dl.getRecordData();
		DownloadLogData dld = Utils.OM.convertValue(recordData, DownloadLogData.class);
		dld.setUuid(dl.getUuid());
		dld.setCreatedDate(dl.getCreatedDate());
		return dld;
	}
}
