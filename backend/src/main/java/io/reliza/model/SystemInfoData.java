/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.Map;
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
	public static class SetEmailPropertiesDto {
		private String sendGridKey;
		private SmtpProps smtpProps;
		private EmailSendType emailSendType;
		private String fromEmail;
		private String fromName;
	}
	
    private boolean systemSealed = true;
	private EncProps encryption;
	private String sendGridKey;
	private String fromEmail;
	private SmtpProps smtpProps;
	private EmailSendType emailSendType = EmailSendType.UNSET;
	private UUID defaultOrg;
	private ZonedDateTime lastDtrackSync;
	
	public static SystemInfoData dataFromRecord (SystemInfo t) {
		Map<String,Object> recordData = t.getData();
		SystemInfoData td = Utils.OM.convertValue(recordData, SystemInfoData.class);
		return td;
	}

}
