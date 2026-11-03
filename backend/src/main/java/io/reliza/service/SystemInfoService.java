/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.common.util.StringUtils;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import java.security.SecureRandom;
import java.util.Base64;
import io.reliza.model.SystemInfo;
import io.reliza.model.SystemInfoData;
import io.reliza.model.SystemInfoData.SmtpProps;
import io.reliza.model.SystemInfoData.AzureCreds;
import io.reliza.model.SystemInfoData.EmailSendType;
import io.reliza.model.SystemInfoData.FanOutWalkState;
import io.reliza.model.SystemInfoData.SetEmailPropertiesDto;
import io.reliza.model.UserData;
import io.reliza.repositories.SystemInfoRepository;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SystemInfoService {

	@Autowired
	EncryptionService encryptionService;
	
	@Value("${relizaprops.installationSecret}")
	private String installationSecret;
			
	private final SystemInfoRepository repository;
	
	SystemInfoService(SystemInfoRepository repository) {
	    this.repository = repository;
	}
	
    private SystemInfoData findSystemInfo(){
        SystemInfo sysInfo =  this.repository.findSystemInfo();

        if(null == sysInfo){
            sysInfo = new SystemInfo();
            SystemInfoData data = new SystemInfoData();
			sysInfo = saveSystemInfo(sysInfo, data);

        }

        return SystemInfoData.dataFromRecord(sysInfo);
    }

	protected String getSendGridKey(){
		var sysInfo = findSystemInfo();
		String encSendGridKey = sysInfo.getSendGridKey();
		String decSendGridKey = null;
		if(StringUtils.isNotEmpty(encSendGridKey))
			decSendGridKey = encryptionService.decrypt(encSendGridKey);
		return decSendGridKey;
	}
	
	protected String getFromEmail() {
		var sysInfo = findSystemInfo();
		return sysInfo.getFromEmail();
	}
	
	protected SmtpProps getSmtpProps() {
		var sysInfo = findSystemInfo();
		var givenSmtpProps = sysInfo.getSmtpProps();
		String decPass = encryptionService.decrypt(givenSmtpProps.password());
		SmtpProps decSmtpProps = new SmtpProps(givenSmtpProps.userName(), decPass, givenSmtpProps.smtpHost(), 
				givenSmtpProps.port(), givenSmtpProps.isStarttls(), givenSmtpProps.isSsl(), givenSmtpProps.fromName());
		return decSmtpProps;
	}
	
	protected EmailSendType getEmailSendType () {
		var sysInfo = findSystemInfo();
		return sysInfo.getEmailSendType();
	}
	
	public UUID getDefaultOrg () {
		var sysInfo = findSystemInfo();
		return sysInfo.getDefaultOrg();
	}
	
	protected ZonedDateTime getLastDtrackSync () {
		var sysInfo = findSystemInfo();
		return sysInfo.getLastDtrackSync();
	}

	protected ZonedDateTime getUnmappedBomSweepWatermark () {
		var sysInfo = findSystemInfo();
		return sysInfo.getUnmappedBomSweepWatermark();
	}

	/** Org keys whose stored fan-out watermark did not parse, so the WARN is logged once each. */
	private final Set<String> unparsableFanOutDrainedAt = ConcurrentHashMap.newKeySet();

	/**
	 * The org's persisted fan-out drained-at watermark (TPF-114), or null when the map or the key
	 * is absent. A value that does not parse is logged once (at ERROR with the exception, the
	 * board's caught-exception convention) and read as absent: the only
	 * writer is {@link #stampFanOutDrainedAt}, so this guards a corrupted row, and an absent
	 * watermark only costs a bounded bucket walk, never a skipped pass.
	 */
	@Transactional
	public ZonedDateTime getFanOutDrainedAt(UUID org) {
		Map<String, String> drained = findSystemInfo().getFanOutDrainedAt();
		if (null == drained || null == org) return null;
		String key = org.toString();
		String raw = drained.get(key);
		if (null == raw) return null;
		try {
			return Instant.parse(raw).atZone(ZoneOffset.UTC);
		} catch (DateTimeParseException e) {
			if (unparsableFanOutDrainedAt.add(key)) {
				log.error("Unparsable fan-out drained-at watermark for org {}; treated as absent", key, e);
			}
			return null;
		}
	}

	/**
	 * Stamp the org's fan-out drained-at watermark in place (TPF-114): one key of the
	 * fanOutDrainedAt map, as an ISO-8601 UTC instant. Creates the system_info row and stamps
	 * once more when the row does not exist yet. Bookkeeping: no revision, no audit row.
	 */
	@Transactional
	public void stampFanOutDrainedAt(UUID org, ZonedDateTime at) {
		String key = org.toString();
		String value = DateTimeFormatter.ISO_INSTANT.format(at.toInstant());
		if (repository.stampFanOutDrainedAt(key, value) == 0) {
			findSystemInfo();
			repository.stampFanOutDrainedAt(key, value);
		}
	}

	/** Org keys whose stored fan-out walk state did not parse, so the ERROR is logged once each. */
	private final Set<String> unparsableFanOutWalk = ConcurrentHashMap.newKeySet();

	/**
	 * The org's running fan-out drain (TPF-137), or null when the map or the key is absent. A
	 * value that is not an object, whose cutoff does not parse as an instant or whose cursor is not
	 * a uuid is logged once (at ERROR with the exception) and read as absent: the drain then
	 * restarts from the beginning of the uuid space, which costs a walk and loses nothing.
	 */
	@Transactional
	public FanOutWalkState getFanOutWalk(UUID org) {
		Map<String, Object> walks = findSystemInfo().getFanOutWalk();
		if (null == walks || null == org) return null;
		String key = org.toString();
		Object raw = walks.get(key);
		if (null == raw) return null;
		try {
			if (!(raw instanceof Map<?, ?> m) || !(m.get("cutoff") instanceof String cutoff)
					|| !(m.get("cursor") instanceof String cursor)) {
				throw new IllegalArgumentException("not a {cutoff, cursor} object of strings");
			}
			Instant.parse(cutoff);
			UUID.fromString(cursor);
			return new FanOutWalkState(cutoff, cursor);
		} catch (RuntimeException e) {
			if (unparsableFanOutWalk.add(key)) {
				log.error("Unparsable fan-out walk state for org {}; treated as absent", key, e);
			}
			return null;
		}
	}

	/**
	 * Stamp the org's fan-out walk state in place (TPF-137): one key of the fanOutWalk map, the
	 * drain's cutoff as an ISO-8601 UTC instant and the cursor. Creates the system_info row and
	 * stamps once more when the row does not exist yet. Bookkeeping: no revision, no audit row.
	 */
	@Transactional
	public void stampFanOutWalk(UUID org, ZonedDateTime cutoff, UUID cursor) {
		String key = org.toString();
		String at = DateTimeFormatter.ISO_INSTANT.format(cutoff.toInstant());
		if (repository.stampFanOutWalk(key, at, cursor.toString()) == 0) {
			findSystemInfo();
			repository.stampFanOutWalk(key, at, cursor.toString());
		}
	}

	/** Remove the org's fan-out walk state in place (TPF-137): the drain completed or restarts. */
	@Transactional
	public void clearFanOutWalk(UUID org) {
		repository.clearFanOutWalk("{fanOutWalk," + org + "}");
	}

	/**
	 * The TEA custom domain assignments (TPF-142), host -> organization uuid as text, read without
	 * the lock; an absent map reads as empty. Creates the system_info row when it does not exist yet.
	 */
	public Map<String, String> getTeaHostAssignments() {
		Map<String, String> m = findSystemInfo().getTeaHostAssignments();
		return null == m ? Map.of() : new LinkedHashMap<>(m);
	}

	/**
	 * Write one TEA host assignment in place (TPF-142). Creates the system_info row and writes once
	 * more when the row does not exist yet. The caller (TeaHostAssignmentService) holds the row lock
	 * and has checked the invariants on the locked map.
	 */
	@Transactional
	public void assignTeaHost(String host, UUID org) {
		if (repository.assignTeaHost(host, org.toString()) == 0) {
			findSystemInfo();
			repository.assignTeaHost(host, org.toString());
		}
	}

	/**
	 * Remove one TEA host assignment in place (TPF-142); the host is a bound parameter, no path
	 * literal is built from it. 0 rows is the absent-row retry of {@link #assignTeaHost}.
	 */
	@Transactional
	public void unassignTeaHost(String host) {
		if (repository.unassignTeaHost(host) == 0) {
			findSystemInfo();
			repository.unassignTeaHost(host);
		}
	}
	

	@Transactional
	public void setEmailProperties(SetEmailPropertiesDto emailPropsDto) throws RelizaException {
		if (emailPropsDto.getEmailSendType() == EmailSendType.UNSET) {
			throw new RelizaException("Cannot set email properties with unspecified type");
		}
		String sendGridKey = emailPropsDto.getSendGridKey();
		SystemInfo sysInfo =  this.repository.findSystemInfo();
		SystemInfoData sd = SystemInfoData.dataFromRecord(sysInfo);
		sd.setEmailSendType(emailPropsDto.getEmailSendType());
		sd.setFromEmail(emailPropsDto.getFromEmail());
		if (StringUtils.isNotEmpty(sendGridKey)) {
			String encSendGridKey = encryptionService.encrypt(sendGridKey);
			sd.setSendGridKey(encSendGridKey);
		}
		SmtpProps givenSmtpProps = emailPropsDto.getSmtpProps();
		if (null != givenSmtpProps) {
			String encPass = encryptionService.encrypt(givenSmtpProps.password());
			SmtpProps encSmtpProps = new SmtpProps(givenSmtpProps.userName(), encPass, givenSmtpProps.smtpHost(), 
					givenSmtpProps.port(), givenSmtpProps.isStarttls(), givenSmtpProps.isSsl(), givenSmtpProps.fromName());
			sd.setSmtpProps(encSmtpProps);
		}
		saveSystemInfo(sysInfo, sd);
	}
	
	@Transactional
	public void setSendGridKey(String sendGridKey){
		String encSendGridKey = encryptionService.encrypt(sendGridKey);
		SystemInfo sysInfo =  this.repository.findSystemInfo();
		SystemInfoData sd = SystemInfoData.dataFromRecord(sysInfo);
		sd.setSendGridKey(encSendGridKey);
		saveSystemInfo(sysInfo, sd);
	}
	
	// VulnCheck token getter/setter on SystemInfo removed in V54 KEV per-org
	// refactor; per-org tokens now live on VULNCHECK_KEV Integration rows
	// (encrypted via the same EncryptionService, looked up by the per-org
	// KevCatalogSyncService.syncOrgSourceAsync entry point).

	@Transactional
	public void setDefaultOrg (UUID defaultOrg) {
		SystemInfo sysInfo =  this.repository.findSystemInfo();
		SystemInfoData sd = SystemInfoData.dataFromRecord(sysInfo);
		sd.setDefaultOrg(defaultOrg);
		saveSystemInfo(sysInfo, sd);
	}
	
	/**
	 * Scheduler-driven whole-record setter: reads the row under its lock (TPF-114, coding principle
	 * "whole-record writers re-read the row under its lock"), so an in-place stamp such as
	 * stampFanOutDrainedAt waits for this save instead of being overwritten by it.
	 */
	@Transactional
	public void setLastDtrackSync (ZonedDateTime lastDtrackSync) {
		SystemInfo sysInfo = this.repository.findSystemInfoWriteLocked();
		SystemInfoData sd = SystemInfoData.dataFromRecord(sysInfo);
		sd.setLastDtrackSync(lastDtrackSync);
		saveSystemInfo(sysInfo, sd);
	}

	/** Scheduler-driven whole-record setter: locked read, as {@link #setLastDtrackSync}. */
	@Transactional
	public void setUnmappedBomSweepWatermark (ZonedDateTime watermark) {
		SystemInfo sysInfo = this.repository.findSystemInfoWriteLocked();
		SystemInfoData sd = SystemInfoData.dataFromRecord(sysInfo);
		sd.setUnmappedBomSweepWatermark(watermark);
		saveSystemInfo(sysInfo, sd);
	}
	

	/**
	 * Claim the one-time model-catalogue sweep, or report that somebody already ran it.
	 *
	 * <p>Conditional write inside one transaction, the same shape as the token pepper: two pods
	 * starting together must not both fold the same rows, because the second would be folding
	 * rows the first had already re-pointed.
	 *
	 * @return true when this caller claimed the sweep and must run it
	 */
	@Transactional
	public boolean claimModelCatalogueSweep() {
		SystemInfo sysInfo = this.repository.findSystemInfoWriteLocked();
		SystemInfoData sd = SystemInfoData.dataFromRecord(sysInfo);
		if (null != sd.getModelCatalogueSweepAt()) return false;
		sd.setModelCatalogueSweepAt(ZonedDateTime.now());
		saveSystemInfo(sysInfo, sd);
		return true;
	}

	/**
	 * Give the claim back after a failed sweep so a later start retries it.
	 *
	 * <p>The flag is taken BEFORE the sweep runs, and it has to be: it is the lock that stops two
	 * instances sweeping the same rows concurrently. But taken and never released, a sweep that
	 * died halfway would be marked done forever, leaving duplicate rows nobody would ever fold.
	 * Releasing on failure is safe because the sweep is idempotent -- the fold finds nothing left
	 * to fold and the converter skips rows already marked -- so a retry re-does only the part that
	 * did not finish.
	 */
	@Transactional
	public void releaseModelCatalogueSweep() {
		SystemInfo sysInfo = this.repository.findSystemInfoWriteLocked();
		SystemInfoData sd = SystemInfoData.dataFromRecord(sysInfo);
		sd.setModelCatalogueSweepAt(null);
		saveSystemInfo(sysInfo, sd);
	}

	/**
	 * The one whole-record save of the system_info row. TPF-142 r5: the committed TEA host map, read
	 * under the row lock (a native FOR UPDATE text read, past any managed copy), replaces whatever
	 * copy the setter started from, so a setter that read the row before a concurrent assign or
	 * unassign cannot write a stale map back. Nothing writes teaHostAssignments through here.
	 */
	@Transactional
	private SystemInfo saveSystemInfo(SystemInfo s, SystemInfoData data){
		Map<String,Object> recordData = Utils.dataToRecord(data);
		recordData = SystemInfoData.withStoredTeaHostAssignments(recordData, repository.currentTeaHostAssignmentsForUpdate());
		s.setData(recordData);
		return repository.save(s);
	}


	@Transactional
	public void unSealSystem(String secret, UserData ud) throws RuntimeException{

		SystemInfo sysInfo =  this.repository.findSystemInfo();
		SystemInfoData sd = SystemInfoData.dataFromRecord(sysInfo);
		if(!sd.isSystemSealed()){
			throw new RuntimeException("System Already Unsealed");
		}
		if(!this.installationSecret.equals(secret)){
			throw new RuntimeException("Incorrect installation secret, system can't be unslead");
		}

		sd.setSystemSealed(false);
		saveSystemInfo(sysInfo, sd);
		makeUserGlobalAdmin(ud.getUuid());
	}

	/**
	 * The installation's API-token pepper, generated on first use.
	 *
	 * <p>Write-once through a conditional statement rather than read-then-write: two pods reaching
	 * this at the same moment must not end up with different peppers, because a token signed by
	 * one would then fail to verify on the other. The loser of the race reads the winner's value
	 * back.
	 */
	@Transactional
	public String getOrCreateApiTokenPepper() {
		SystemInfoData sd = getSystemInfoData();
		if (null != sd && StringUtils.isNotEmpty(sd.getApiTokenPepper())) return sd.getApiTokenPepper();
		byte[] raw = new byte[32];
		new SecureRandom().nextBytes(raw);
		repository.setApiTokenPepperIfAbsent(Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
		String stored = getSystemInfoData().getApiTokenPepper();
		if (StringUtils.isEmpty(stored)) {
			throw new IllegalStateException("Could not establish the API token pepper on system_info");
		}
		return stored;
	}

	public SystemInfoData.EncProps getEncryption(){
		var sysInfo = findSystemInfo();
		SystemInfoData.EncProps encEncryption = sysInfo.getEncryption();
		var pass = StringUtils.isNotEmpty(encEncryption.password()) ? encryptionService.decrypt(encEncryption.password()) : null;
		var salt = StringUtils.isNotEmpty(encEncryption.salt()) ? encryptionService.decrypt(encEncryption.salt()) : null;
		var oldPass = StringUtils.isNotEmpty(encEncryption.oldPassword()) ? encryptionService.decrypt(encEncryption.oldPassword()) : null;
		var oldSalt = StringUtils.isNotEmpty(encEncryption.oldSalt()) ? encryptionService.decrypt(encEncryption.oldSalt()) : null;
		SystemInfoData.EncProps decEncryption = new SystemInfoData.EncProps(
			pass,
			salt,
			oldPass,
			oldSalt
		);
		return decEncryption;

	}

	private void makeUserGlobalAdmin(UUID userId){
		this.repository.makeUserGlobalAdmin(userId);
	}

	public Boolean isSystemSealed(){
		return findSystemInfo().isSystemSealed();
	}

	// vulncheckKevConfigured dropped from SystemInfoDto in V54 (per-org now;
	// surfaced through the org's Integration catalog cards instead).
	public static record SystemInfoDto (Boolean emailDetailsSet, UUID defaultOrg) {}

	public SystemInfoDto getSystemInfoIsSet(){
		var sysInfo = findSystemInfo();
		return new SystemInfoDto(sysInfo.getEmailSendType() != EmailSendType.UNSET, sysInfo.getDefaultOrg());
	}
	
	public SystemInfoData getSystemInfoData() {
		return findSystemInfo();
	}

	@Transactional
	public void setLicenseData(String license, ZonedDateTime licenseStartDate, ZonedDateTime licenseEndDate,
			int maxWriteUsers, int maxReadUsers) {
		SystemInfo sysInfo = this.repository.findSystemInfo();
		SystemInfoData sd = SystemInfoData.dataFromRecord(sysInfo);
		sd.setLicense(license);
		sd.setLicenseStartDate(licenseStartDate);
		sd.setLicenseEndDate(licenseEndDate);
		sd.setLicensedMaxWriteUsers(maxWriteUsers);
		sd.setLicensedMaxReadUsers(maxReadUsers);
		saveSystemInfo(sysInfo, sd);
	}

	public AzureCreds getAzureCredentials(){
		var sysInfo = findSystemInfo();
		AzureCreds encAzureCreds = sysInfo.getAzureCredentials();
		AzureCreds decAzureCreds = null;
		if(encAzureCreds != null)
			decAzureCreds = new AzureCreds(
				encryptionService.decrypt(encAzureCreds.getClientId()), 
				encryptionService.decrypt(encAzureCreds.getClientSecret()), 
				encryptionService.decrypt(encAzureCreds.getTenantId()), 
				encryptionService.decrypt(encAzureCreds.getVaultName())
			);
		
		
		return decAzureCreds;
	}

}
