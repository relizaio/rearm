/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;

import io.reliza.model.SystemInfoData.EncProps;
import io.reliza.ws.RelizaConfigProps;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EncryptionService {

	private TextEncryptor teNew;
	private TextEncryptor teOld;
	
	@Autowired
    public void setProps(RelizaConfigProps relizaConfigProps) {
        // this.relizaConfigProps = relizaConfigProps;
		this.teNew = Encryptors.delux(relizaConfigProps.getEncryption().password(), 
				relizaConfigProps.getEncryption().salt());
		this.teOld = Encryptors.delux(relizaConfigProps.getEncryption().oldPassword(),
				relizaConfigProps.getEncryption().oldSalt());
    }
	
	protected String encrypt (String plainText) {
		return teNew.encrypt(plainText);
	}
	
	protected String decrypt (String cypherText) {
		String plainText = null;
		try {
			plainText = teNew.decrypt(cypherText);
		} catch (Exception e) {
			try {
				plainText = teOld.decrypt(cypherText);
			} catch (Exception eint) {
				log.warn("Security - decryption failed with both new and old algorithms", eint);
			}
		}
		return plainText;
	}

	protected String encryptCookie (String plainTextCookie, EncProps currEnc) {
		TextEncryptor ceNew = Encryptors.delux(currEnc.password(), currEnc.salt());
		return ceNew.encrypt(plainTextCookie);
	}
	
	protected String decryptCookie (String cypherText, EncProps currEnc) {
		String plainText = null;
		try {
			TextEncryptor ceNew = Encryptors.delux(currEnc.password(), currEnc.salt());
			plainText = ceNew.decrypt(cypherText);
		} catch (Exception e) {
			try {
				TextEncryptor ceOld = Encryptors.delux(currEnc.oldPassword(), currEnc.oldSalt());
				plainText = ceOld.decrypt(cypherText);
			} catch (Exception eint) {
				log.warn("Security - decryption failed with both new and old algorithms", eint);
			}
		}
		return plainText;
	}
}