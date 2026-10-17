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
import io.reliza.model.SystemInfoData.SmtpProps;
import io.reliza.service.BrandingService.ResolvedBranding;

@Service
public class EmailService {
	
	private static final Logger log = LoggerFactory.getLogger(EmailService.class);
		
	@Autowired
	SystemInfoService systemInfoService;

	@Autowired
	BrandingService brandingService;

	/**
	 * The SMTP sender's display name: the one the admin set in the SMTP settings, else the
	 * branding's. The admin's explicit setting is the more specific choice, so it wins.
	 */
	static String senderName(SmtpProps smtpProps, ResolvedBranding branding) {
		return StringUtils.isNotEmpty(smtpProps.fromName()) ? smtpProps.fromName() : branding.values().emailSenderName();
	}
	
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
			helper.setFrom(systemInfoService.getFromEmail(), senderName(smtpProps, brandingService.current()));
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

	/**
	 * Send a multipart/alternative email: HTML body with a plaintext
	 * fallback. Used by the notification dispatcher so recipients on
	 * HTML-disabled clients (some Outlook profiles, terminal mail
	 * readers, accessibility tooling) still see a useful body.
	 *
	 * <p>The two bodies should carry the same information — clients only
	 * render one. Both go in the same MIME message under
	 * {@code multipart/alternative}; sending two separate emails would
	 * double-deliver and is not the multipart semantic.
	 */
	public boolean sendMultipartEmail(Collection<String> toEmails, String subject,
			String htmlBody, String plainTextBody) {
		EmailValidator ev = EmailValidator.getInstance();
		for (String toe : toEmails) {
			if (!ev.isValid(toe)) {
				throw new RuntimeException("Valid email required!");
			}
		}
		EmailSendType emailSendType = systemInfoService.getEmailSendType();
		if (emailSendType == EmailSendType.SMTP) {
			return sendSmtpMultipartEmail(toEmails, subject, htmlBody, plainTextBody);
		} else if (emailSendType == EmailSendType.SENDGRID) {
			return sendSendgridMultipartEmail(toEmails, subject, htmlBody, plainTextBody);
		}
		return false;
	}

	private boolean sendSmtpMultipartEmail(Collection<String> toEmails, String subject,
			String htmlBody, String plainTextBody) {
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
			// multipart=true is the key flag — MimeMessageHelper builds a
			// multipart/alternative envelope, and setText(plain, html)
			// places both bodies under it.
			MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
			helper.setFrom(systemInfoService.getFromEmail(), senderName(smtpProps, brandingService.current()));
			helper.setTo(toEmails.toArray(new String[0]));
			helper.setSubject(subject);
			helper.setText(plainTextBody, htmlBody);
			mailSender.send(message);
			return true;
		} catch (Exception e) {
			log.error("Error on sending multipart smtp email", e);
			return false;
		}
	}

	private boolean sendSendgridMultipartEmail(Collection<String> toEmails, String subject,
			String htmlBody, String plainTextBody) {
		try {
			Mail mail = new Mail();
			mail.setFrom(new Email(systemInfoService.getFromEmail()));
			Personalization personalization = new Personalization();
			toEmails.forEach(toe -> personalization.addTo(new Email(toe)));
			mail.addPersonalization(personalization);
			mail.setSubject(subject);
			// SendGrid spec: order matters — plain first, html second. The
			// client renders the last alternative it can display, so HTML
			// wins on capable clients.
			mail.addContent(new Content("text/plain", plainTextBody));
			mail.addContent(new Content("text/html", htmlBody));

			SendGrid sg = new SendGrid(systemInfoService.getSendGridKey());
			Request request = new Request();
			request.setMethod(Method.POST);
			request.setEndpoint("mail/send");
			request.setBody(mail.build());
			Response response = sg.api(request);
			if (response.getStatusCode() > 299) {
				log.error("SendGrid multipart returned {}: {}",
						response.getStatusCode(), response.getBody());
				return false;
			}
			return true;
		} catch (IOException ex) {
			log.error("IO exception when sending sendgrid multipart email", ex);
			return false;
		}
	}

}
