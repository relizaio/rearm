/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.io.IOException;
import java.util.Collection;
import java.util.Properties;

import jakarta.mail.internet.MimeMessage;

import org.apache.commons.lang3.StringUtils;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.apache.commons.validator.routines.EmailValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import com.sendgrid.helpers.mail.objects.Personalization;

import io.reliza.model.SystemInfoData.EmailSendType;

@Service
public class EmailService {
	
	private static final Logger log = LoggerFactory.getLogger(EmailService.class);
		
	@Autowired
	SystemInfoService systemInfoService;
	
	public boolean sendEmail(Collection<String> toEmails, String subject, String contentType, String contentStr) {
		boolean isSuccess = true;
		EmailValidator ev = EmailValidator.getInstance();
		for (String toe : toEmails) {
			if (!ev.isValid(toe)) {
				throw new RuntimeException("Valid email required!");
			}
		}
		EmailSendType emailSendType = systemInfoService.getEmailSendType();
		if (emailSendType == EmailSendType.SMTP) {
			isSuccess = sendSmtpEmail(toEmails, subject, contentType, contentStr);
		} else if (emailSendType == EmailSendType.SENDGRID) {
			isSuccess = sendSendgridEmail(toEmails, subject, contentType, contentStr);
		}
		return isSuccess;
	}

	private boolean sendSmtpEmail (Collection<String> toEmails, String subject, String contentType, String contentStr) {
		boolean isSuccess = true;
		try {
			var smtpProps = systemInfoService.getSmtpProps();
			JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
			mailSender.setHost(smtpProps.smtpHost());
			mailSender.setPort(smtpProps.port());
			mailSender.setUsername(smtpProps.userName());
			mailSender.setPassword(smtpProps.password());
			Properties props = mailSender.getJavaMailProperties();
			props.put("mail.transport.protocol", "smtp");
			props.put("mail.smtp.auth", "true");
			if (smtpProps.isStarttls()) props.put("mail.smtp.starttls.enable", "true");
			if (smtpProps.isSsl()) props.put("mail.smtp.ssl.enable", "true");
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
			String fromName = StringUtils.isNotEmpty(smtpProps.fromName()) ? smtpProps.fromName() : "ReARM - Do Not Reply";
			helper.setFrom(systemInfoService.getFromEmail(), fromName);
			helper.setTo(toEmails.toArray(new String[0]));
			helper.setSubject(subject);
			helper.setText(contentStr, "text/html".equalsIgnoreCase(contentType));
			mailSender.send(message);
		} catch (Exception e) {
			log.error("Error on sending smtp email", e);
			isSuccess = false;
		}
		return isSuccess;
	}
	
	
	private boolean sendSendgridEmail (Collection<String> toEmails, String subject, String contentType, String contentStr) {
		boolean isSuccess = true;
		final Mail mail = new Mail();
	    mail.setFrom(new Email(systemInfoService.getFromEmail()));
	    final Personalization personalization = new Personalization();
	    toEmails.forEach(toe -> personalization.addTo(new Email(toe)));
	    mail.addPersonalization(personalization);
	    mail.setSubject(subject);
	    mail.addContent(new Content(contentType, contentStr));

	    SendGrid sg = new SendGrid(systemInfoService.getSendGridKey());
	    Request request = new Request();
	    try {
			request.setMethod(Method.POST);
			request.setEndpoint("mail/send");
			request.setBody(mail.build());
			Response response = sg.api(request);
			log.info("Status = " + response.getStatusCode());
			log.info(response.getBody());
			log.info(response.getHeaders().toString());
			if (response.getStatusCode() > 299) isSuccess = false;
	    } catch (IOException ex) {
	    	log.error("IO exception when sending sendgrid email", ex);
	    	isSuccess = false;
	    }
	    return isSuccess;
	}
	
}
