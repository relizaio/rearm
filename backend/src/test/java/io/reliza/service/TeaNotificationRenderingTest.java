/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.reliza.common.Utils;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.notifications.ReleaseRef;
import io.reliza.model.dto.notifications.ReleaseTeaPublicationPayload;
import io.reliza.service.NotificationInboxFormatter.InboxRendering;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.saas.EmailContentFormatter;
import io.reliza.service.saas.EmailContentFormatter.EmailContent;
import io.reliza.service.saas.SentinelLogFormatter;

/**
 * Task TEA-5, design 4.7 case 37: every switch over NotificationEventType renders
 * RELEASE_TEA_PUBLISHED and RELEASE_TEA_HIDDEN without throwing, with the plain headline ("Release
 * c v published on TEA (collection version N, PRIVATE)" / "Release c v hidden from TEA") wherever
 * the channel carries a title. Formatters that swallow a failure into a fallback would drop the
 * headline, so its presence is also what proves the TEA branch ran.
 */
class TeaNotificationRenderingTest {

	private static final String BASE = "https://rearm.example.com";
	private static final UUID PUBLICATION = UUID.randomUUID();
	private static final String URL = BASE + "/tea/" + UUID.randomUUID() + "/v1.0.0/componentRelease/" + PUBLICATION;

	private final SlackBlockKitFormatter slack = new SlackBlockKitFormatter(BASE, new NotificationLabelProvider());
	private final TeamsAdaptiveCardFormatter teams = new TeamsAdaptiveCardFormatter(BASE, new NotificationLabelProvider());
	private final NotificationInboxFormatter inbox = new NotificationInboxFormatter(new NotificationLabelProvider());
	private final EmailContentFormatter email = new EmailContentFormatter(BASE, new NotificationLabelProvider(),
			BrandingTestFixtures.serviceOver(BrandingTestFixtures.DEFAULT));
	private final SentinelLogFormatter sentinel = new SentinelLogFormatter(BASE);
	private final EventActivationMapBuilder activation = new EventActivationMapBuilder();

	private static ReleaseRef release() {
		return new ReleaseRef(UUID.randomUUID(), "1.4.2", UUID.randomUUID(), "widget", ComponentType.COMPONENT,
				UUID.randomUUID(), "main", ReleaseLifecycle.GENERAL_AVAILABILITY, "0123abcd",
				"https://git.example.com/commit/0123abcd", "a commit", "A User", "user@example.com");
	}

	private static ReleaseTeaPublicationPayload payload(NotificationEventType type) {
		return NotificationEventType.RELEASE_TEA_HIDDEN == type
				? new ReleaseTeaPublicationPayload(release(), PUBLICATION, TeaPublicationState.HIDDEN, null, null, URL, 0)
				: new ReleaseTeaPublicationPayload(release(), PUBLICATION, TeaPublicationState.PUBLISHED, 3,
						TeaExposureState.PRIVATE, URL, 0);
	}

	private static String headline(NotificationEventType type) {
		return NotificationEventType.RELEASE_TEA_HIDDEN == type ? "Release widget 1.4.2 hidden from TEA"
				: "Release widget 1.4.2 published on TEA (collection version 3, PRIVATE)";
	}

	@SuppressWarnings("unchecked")
	private static NotificationOutboxEvent event(NotificationEventType type, Object payload) {
		NotificationOutboxEvent e = new NotificationOutboxEvent();
		e.setUuid(UUID.randomUUID());
		e.setOrg(UUID.randomUUID());
		e.setEventType(type);
		e.setOccurredAt(ZonedDateTime.now());
		e.setDedupKey("tea-test:" + type);
		e.setRecordData(Utils.OM.convertValue(payload, Map.class));
		return e;
	}

	private static String json(Object rendered) {
		return Utils.OM.writeValueAsString(rendered);
	}

	@ParameterizedTest
	@EnumSource(value = NotificationEventType.class, names = { "RELEASE_TEA_PUBLISHED", "RELEASE_TEA_HIDDEN" })
	void theHeadlineIsThePlainRendering(NotificationEventType type) {
		assertEquals(headline(type), payload(type).headline());
		ReleaseTeaPublicationPayload withChildren = new ReleaseTeaPublicationPayload(release(), PUBLICATION,
				TeaPublicationState.PUBLISHED, 2, TeaExposureState.PUBLIC, null, 3);
		assertEquals("Release widget 1.4.2 published on TEA (collection version 2, PUBLIC) with 3 component releases",
				withChildren.headline());
	}

	@ParameterizedTest
	@EnumSource(value = NotificationEventType.class, names = { "RELEASE_TEA_PUBLISHED", "RELEASE_TEA_HIDDEN" })
	void slackRendersTheHeadline(NotificationEventType type) {
		Map<String, Object> out = slack.format(event(type, payload(type)));
		assertTrue(String.valueOf(out.get("text")).contains(headline(type)), String.valueOf(out.get("text")));
		assertTrue(json(out.get("blocks")).contains(URL), "the TEA URL is a fact");
	}

	@ParameterizedTest
	@EnumSource(value = NotificationEventType.class, names = { "RELEASE_TEA_PUBLISHED", "RELEASE_TEA_HIDDEN" })
	void teamsRendersTheHeadline(NotificationEventType type) {
		String card = json(teams.format(event(type, payload(type))));
		assertTrue(card.contains(headline(type)), card);
		assertTrue(card.contains(URL), card);
	}

	@ParameterizedTest
	@EnumSource(value = NotificationEventType.class, names = { "RELEASE_TEA_PUBLISHED", "RELEASE_TEA_HIDDEN" })
	void theInboxTitleIsTheHeadline(NotificationEventType type) {
		InboxRendering r = inbox.format(event(type, payload(type)));
		assertEquals(headline(type), r.title(), "a swallowed failure would answer the event type name instead");
		assertNotNull(r.description());
		assertTrue(r.description().contains(URL), r.description());
	}

	@ParameterizedTest
	@EnumSource(value = NotificationEventType.class, names = { "RELEASE_TEA_PUBLISHED", "RELEASE_TEA_HIDDEN" })
	void theEmailSubjectAndBodiesCarryTheHeadline(NotificationEventType type) {
		EmailContent c = email.format(event(type, payload(type)));
		assertTrue(c.subject().contains(headline(type)), c.subject());
		assertTrue(c.plainBody().contains(headline(type)), c.plainBody());
		assertTrue(c.plainBody().contains(URL), c.plainBody());
		assertNotNull(c.htmlBody());
		assertFalse(c.htmlBody().isBlank());
	}

	@ParameterizedTest
	@EnumSource(value = NotificationEventType.class, names = { "RELEASE_TEA_PUBLISHED", "RELEASE_TEA_HIDDEN" })
	void theSentinelRowCarriesTheHeadlineAndTheTeaFields(NotificationEventType type) {
		List<Map<String, Object>> rows = sentinel.format(event(type, payload(type)));
		assertEquals(1, rows.size());
		Map<String, Object> row = rows.get(0);
		assertEquals(headline(type), row.get("Summary"));
		assertEquals(PUBLICATION.toString(), row.get("TeaPublication"));
		assertEquals(payload(type).state().name(), row.get("TeaPublicationState"));
		assertEquals(URL, row.get("TeaUrl"));
		if (NotificationEventType.RELEASE_TEA_PUBLISHED == type) {
			assertEquals(3, row.get("TeaCollectionVersion"));
			assertEquals("PRIVATE", row.get("TeaExposure"));
		} else {
			assertFalse(row.containsKey("TeaCollectionVersion"));
			assertFalse(row.containsKey("TeaExposure"));
		}
	}

	@ParameterizedTest
	@EnumSource(value = NotificationEventType.class, names = { "RELEASE_TEA_PUBLISHED", "RELEASE_TEA_HIDDEN" })
	@SuppressWarnings("unchecked")
	void theActivationMapExposesTheTeaFields(NotificationEventType type) {
		Map<String, Object> ev = (Map<String, Object>) activation.buildForEvent(event(type, payload(type))).get("event");
		assertEquals(type.name(), ev.get("type"));
		assertEquals(PUBLICATION.toString(), ev.get("teaPublication"));
		assertEquals(payload(type).state().name(), ev.get("teaState"));
		assertEquals(NotificationEventType.RELEASE_TEA_PUBLISHED == type ? 3 : 0, ev.get("teaCollectionVersion"));
		assertEquals(NotificationEventType.RELEASE_TEA_PUBLISHED == type ? "PRIVATE" : "", ev.get("teaExposure"));
		assertEquals(URL, ev.get("teaUrl"));
		assertEquals(0, ev.get("teaChildrenPublished"));
	}

	/** SyntheticEventTemplates' sample of each type renders through every formatter (the preview path). */
	@ParameterizedTest
	@EnumSource(value = NotificationEventType.class, names = { "RELEASE_TEA_PUBLISHED", "RELEASE_TEA_HIDDEN" })
	void theSyntheticSampleRendersEverywhere(NotificationEventType type) {
		NotificationOutboxEvent sample = SyntheticEventTemplates.sampleEventForType(UUID.randomUUID(), type);
		ReleaseTeaPublicationPayload p = Utils.OM.convertValue(sample.getRecordData(), ReleaseTeaPublicationPayload.class);
		String expected = NotificationEventType.RELEASE_TEA_HIDDEN == type ? "Release sample-service v2.0 hidden from TEA"
				: "Release sample-service v2.0 published on TEA (collection version 1, PRIVATE)";
		assertEquals(expected, p.headline());

		assertTrue(String.valueOf(slack.format(sample).get("text")).contains(expected));
		assertTrue(json(teams.format(sample)).contains(expected));
		assertEquals(expected, inbox.format(sample).title());
		assertTrue(email.format(sample).subject().contains(expected));
		assertEquals(expected, sentinel.format(sample).get(0).get("Summary"));
		assertEquals(type.name(), ((Map<?, ?>) activation.buildForEvent(sample).get("event")).get("type"));
	}
}
