/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.KevSource;
import io.reliza.repositories.IntegrationRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Which KEV catalog an organisation's findings are checked against, for the VDR (FDAW-2, B2): the
 * enabled CISA_KEV integration if any, else the enabled VULNCHECK_KEV one, with the catalog release
 * date and the last successful sync the sync stamped on that row. A separate bean from
 * {@link KevCatalogSyncService}, which reaches the vulnerability-analysis services (no circular
 * references are allowed); it depends on the integration repository only.
 */
@Slf4j
@Service
public class KevCatalogStateService {

	/** The order a source is preferred in when an organisation has both enabled. */
	private static final List<IntegrationType> SOURCE_PREFERENCE =
			List.of(IntegrationType.CISA_KEV, IntegrationType.VULNCHECK_KEV);

	/**
	 * One organisation's KEV catalog: the source; the catalog's release date when the source
	 * publishes one (CISA) and the instant of its last successful sync for the organisation, each
	 * null until the first sync stamped it.
	 */
	public record KevCatalogState(KevSource source, Instant dateReleased, Instant syncedAt) {}

	@Autowired
	private IntegrationRepository integrationRepository;

	public Optional<KevCatalogState> forOrg(UUID org) {
		if (null == org) return Optional.empty();
		for (IntegrationType type : SOURCE_PREFERENCE) {
			Optional<Integration> row = integrationRepository.findIntegrationByOrgTypeIdentifier(
					org.toString(), type.name(), CommonVariables.BASE_INTEGRATION_IDENTIFIER);
			if (row.isEmpty()) continue;
			IntegrationData id = IntegrationData.dataFromRecord(row.get());
			if (!Boolean.TRUE.equals(id.getIsEnabled())) continue;
			KevSource source = IntegrationType.CISA_KEV == type ? KevSource.CISA : KevSource.VULNCHECK;
			return Optional.of(new KevCatalogState(source, parseStamp(id.getKevCatalogDateReleased(), row.get()),
					parseStamp(id.getKevSyncedAt(), row.get())));
		}
		return Optional.empty();
	}

	/** A stamped RFC-3339 instant; null when absent or not parseable (one WARN naming the row). */
	private static Instant parseStamp(String raw, Integration row) {
		if (null == raw || raw.isBlank()) return null;
		try {
			return Instant.parse(raw);
		} catch (DateTimeParseException e) {
			log.warn("KEV integration {} carries a stamp that is not an RFC-3339 instant; left out of the VDR", row.getUuid());
			return null;
		}
	}
}
