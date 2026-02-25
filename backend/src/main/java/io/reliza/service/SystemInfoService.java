/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.common.util.StringUtils;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.SystemInfo;
import io.reliza.model.SystemInfoData;
import io.reliza.model.SystemInfoData.SmtpProps;
import io.reliza.model.SystemInfoData.EmailSendType;
import io.reliza.model.SystemInfoData.SetEmailPropertiesDto;
import io.reliza.model.UserData;
import io.reliza.repositories.SystemInfoRepository;

@Service
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
	
	@Transactional
	public void setDefaultOrg (UUID defaultOrg) {
		SystemInfo sysInfo =  this.repository.findSystemInfo();
		SystemInfoData sd = SystemInfoData.dataFromRecord(sysInfo);
		sd.setDefaultOrg(defaultOrg);
		saveSystemInfo(sysInfo, sd);
	}
	
	@Transactional
	public void setLastDtrackSync (ZonedDateTime lastDtrackSync) {
		SystemInfo sysInfo = this.repository.findSystemInfo();
		SystemInfoData sd = SystemInfoData.dataFromRecord(sysInfo);
		sd.setLastDtrackSync(lastDtrackSync);
		saveSystemInfo(sysInfo, sd);
	}
	

	@Transactional
	private SystemInfo saveSystemInfo(SystemInfo s, SystemInfoData data){
		Map<String,Object> recordData = Utils.dataToRecord(data);
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

	public static record SystemInfoDto (Boolean emailDetailsSet, UUID defaultOrg) {}

	public SystemInfoDto getSystemInfoIsSet(){
		var sysInfo = findSystemInfo();
		return new SystemInfoDto(sysInfo.getEmailSendType() != EmailSendType.UNSET, sysInfo.getDefaultOrg());
	}

}
