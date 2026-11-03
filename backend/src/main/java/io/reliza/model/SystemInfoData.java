/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.reliza.common.Utils;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data
@EqualsAndHashCode(callSuper = true)
public class SystemInfoData extends RelizaDataParent{

	public record EncProps (String password, String salt, String oldPassword, String oldSalt) {}
	public record SmtpProps (String userName, String password, String smtpHost,
			Integer port, Boolean isStarttls, Boolean isSsl, String fromName) {}
	public enum EmailSendType {
		UNSET,
		SMTP,
		SENDGRID
	}
	
	@Data
	@Builder
	@lombok.NoArgsConstructor
	@lombok.AllArgsConstructor
	public static class SetEmailPropertiesDto {
		private String sendGridKey;
		private SmtpProps smtpProps;
		private EmailSendType emailSendType;
		private String fromEmail;
		private String fromName;
	}
	
	@Data
	public static class AzureCreds {		
		private final String clientId;
		private final String clientSecret;
		private final String tenantId;
		private final String vaultName;
	}
	
    private boolean systemSealed = true;
	private EncProps encryption;
	/**
	 * Marks the one-time model-catalogue sweep as done: the fold of duplicate rows and the 1.6 to
	 * 2.0 card conversion. A flag rather than a migration because both steps are per-org
	 * application logic over jsonb, and neither is expressible as DDL.
	 */
	private ZonedDateTime modelCatalogueSweepAt;
	private String sendGridKey;
	private String fromEmail;
	private SmtpProps smtpProps;
	private EmailSendType emailSendType = EmailSendType.UNSET;
	private UUID defaultOrg;
	private ZonedDateTime lastDtrackSync;
	// Everything created before this instant has been verified by the
	// unmapped-BOM sweep (mapped, or found and handed to the heal path).
	// NB: a canonical-map REBUILD (truncate + re-enqueue) that relies on the
	// sweep rather than flow_control re-enqueue must null this first.
	private ZonedDateTime unmappedBomSweepWatermark;
	/**
	 * Scheduler bookkeeping of the synthetic fan-out (TPF-114): per org (key: the org uuid as
	 * text), the drained-at watermark, i.e. the pass cutoff of the last pass that walked every
	 * INGESTED bucket dated after the previous value and found them drained, as an ISO-8601 UTC
	 * instant with a trailing Z. Written in place by
	 * {@code SystemInfoRepository.stampFanOutDrainedAt}; carried through the whole-record setters
	 * of {@code SystemInfoService} by this field (a setter that did not know the field would drop
	 * it on save). Absent map or absent key = no watermark for that org. Never exposed through
	 * the API.
	 */
	private Map<String, String> fanOutDrainedAt;
	/**
	 * The running change-driven drain of an org (TPF-137): per org (key: the org uuid as text), a
	 * {@link FanOutWalkState} object, i.e. the drain's cutoff (the pass cutoff its changed set was
	 * taken at, ISO-8601 UTC) and the cursor (the last canonical uuid the walk passed, or
	 * FanOutWalkSlice.START before the first slice). Absent map or key = no drain running. Written
	 * in place by {@code SystemInfoRepository.stampFanOutWalk} and removed by
	 * {@code clearFanOutWalk}; carried through the whole-record setters by this field. The values
	 * are held untyped and parsed by {@code SystemInfoService.getFanOutWalk}, so a corrupted value
	 * (a string where the object belongs) reads as absent there instead of failing the conversion
	 * of the whole system_info row for every reader. Never exposed through the API.
	 */
	private Map<String, Object> fanOutWalk;
	/** One org's drain state in {@link #fanOutWalk} (TPF-137). */
	public record FanOutWalkState(String cutoff, String cursor) implements Serializable {}
	/** The record key of {@link #teaHostAssignments}. */
	public static final String TEA_HOST_ASSIGNMENTS_FIELD = "teaHostAssignments";
	/**
	 * TEA custom domain assignments (TPF-142): host (bare lowercase DNS name, no port) -> the
	 * organization uuid as text. One host maps to one organization and one organization holds at
	 * most one host. Written in place by SystemInfoRepository.assignTeaHost / unassignTeaHost;
	 * declared here so no whole-record setter drops it (a setter that did not know the field would
	 * drop it on save, as fanOutWalk documents) and re-read from the committed row under its lock by
	 * SystemInfoService.saveSystemInfo before every whole-record save, so a copy read before a
	 * concurrent assign or unassign cannot write a stale map back (TPF-142 r5). Absent map = nothing
	 * assigned.
	 */
	private Map<String, String> teaHostAssignments;
	private AzureCreds azureCredentials;
	// vulncheckKevToken (instance-global) removed in V54 KEV per-org refactor;
	// per-org VulnCheck tokens now live on VULNCHECK_KEV Integration rows.
	/**
	 * Per-installation random value mixed into the API-token signing key.
	 *
	 * <p>The signing key is derived from {@code relizaprops.encryption.password} and salt, and both
	 * ship with defaults that are printed in this repository. An installation left on them derives
	 * the same key as every other such installation, so a token from one verifies its signature on
	 * another. This value makes the derived key unique per installation even then, and it is
	 * generated once, on first use, rather than configured.
	 *
	 * <p>Stored in the clear on purpose. Encrypting it with the encryption password would protect
	 * it from nobody: an attacker holding this row already holds the API key hashes that the
	 * token's {@code fp} claim is derived from, so the database is the boundary either way. What
	 * the pepper defends against is an attacker who has no database access at all and is relying
	 * on published defaults.
	 */
	private String apiTokenPepper;
	private String license;
	private ZonedDateTime licenseStartDate;
	private ZonedDateTime licenseEndDate;
	private Integer licensedMaxWriteUsers;
	private Integer licensedMaxReadUsers;
	
	/**
	 * The record with the row's committed TEA host map in place of whatever the caller's copy
	 * carried (TPF-142, the OrganizationData.withStoredTotals shape): a whole-record save is never a
	 * writer of the map, so it can neither revert a committed assign or unassign nor introduce an
	 * entry. {@code stored} is the text of {@code data->>'teaHostAssignments'} read FOR UPDATE (an
	 * object's JSON text, or empty when the key is absent, JSON null or the row does not exist).
	 */
	public static Map<String, Object> withStoredTeaHostAssignments(Map<String, Object> recordData, Optional<String> stored) {
		Map<String, Object> out = new LinkedHashMap<>(recordData);
		if (stored.isPresent()) {
			@SuppressWarnings("unchecked")
			Map<String, Object> storedMap = Utils.OM.readValue(stored.get(), LinkedHashMap.class);
			out.put(TEA_HOST_ASSIGNMENTS_FIELD, storedMap);
		} else {
			out.remove(TEA_HOST_ASSIGNMENTS_FIELD);
		}
		return out;
	}

	public static SystemInfoData dataFromRecord (SystemInfo t) {
		Map<String,Object> recordData = t.getData();
		SystemInfoData td = Utils.OM.convertValue(recordData, SystemInfoData.class);
		return td;
	}

}
