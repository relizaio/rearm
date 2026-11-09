/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ImportSummary;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * The {@link ArtifactImportHandler} of each importable artifact type, shared by both upload
 * paths: preconditions before the upload stores anything, dispatch after it. Handlers are the
 * Spring beans (proxies), so each handler's own REQUIRES_NEW boundary holds.
 */
@Slf4j
@Service
public class ArtifactImportRegistry {

	@Autowired List<ArtifactImportHandler> handlerBeans;
	@Autowired ArtifactService artifactService;

	private final Map<ArtifactType, ArtifactImportHandler> handlers = new EnumMap<>(ArtifactType.class);

	@PostConstruct
	void indexHandlers() {
		for (ArtifactImportHandler h : handlerBeans) {
			ArtifactImportHandler prior = handlers.put(h.type(), h);
			if (prior != null) {
				throw new IllegalStateException("Two import handlers for artifact type " + h.type());
			}
		}
	}

	/** True when uploads of {@code type} with a file run an import. */
	public boolean isImportable(ArtifactType type) {
		return type != null && handlers.containsKey(type);
	}

	/** Runs the precondition of each distinct importable type in {@code types}, once per type. */
	public void verifyPreconditions(Collection<ArtifactType> types, ReleaseData rd) throws RelizaException {
		for (ArtifactType t : types.stream().distinct().toList()) {
			ArtifactImportHandler h = handlers.get(t);
			if (h != null) h.verifyPreconditions(rd);
		}
	}

	/**
	 * Runs the import of a stored artifact and saves its summary on the artifact, in the
	 * caller's transaction. Best-effort: a failure is logged and never fails the upload.
	 * A type with no handler is a no-op.
	 */
	public void dispatchAndRecord(UUID artifactUuid, ArtifactDto artDto, ReleaseData rd,
			ArtifactBelongsTo belongsTo, byte[] content, WhoUpdated wu) {
		ArtifactImportHandler h = handlers.get(artDto.getType());
		if (h == null) return;
		try {
			ImportSummary summary = h.dispatch(artifactUuid, artDto, rd, belongsTo, content, wu);
			// Stamp the outcome on the artifact so the UI can report matched / unmatched
			// counts instead of a silently empty tab.
			artifactService.saveArtifactImportSummary(artifactUuid, summary, wu);
		} catch (Exception e) {
			log.warn("{} import dispatch failed for artifact {} on release {}: {}",
				artDto.getType(), artifactUuid, rd.getUuid(), e.getMessage());
		}
	}
}
