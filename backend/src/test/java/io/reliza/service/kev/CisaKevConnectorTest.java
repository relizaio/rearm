/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import io.reliza.model.KevAssertionData;
import io.reliza.model.KevRansomwareStatus;
import io.reliza.model.KevSource;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link CisaKevConnector}. The HTTP layer is substituted
 * with an {@link ExchangeFunction} stub (no real socket). Pins the feed →
 * {@link KevAssertionData} field mapping incl. the three-state ransomware
 * enum, and the fail-closed contract (fetch error / malformed / empty all
 * return an empty list so the orchestrator skips reconcile).
 */
class CisaKevConnectorTest {

	private static final String FEED_JSON = """
			{
			  "catalogVersion": "2026.06.11",
			  "count": 3,
			  "vulnerabilities": [
			    {
			      "cveID": "CVE-2021-44228",
			      "vendorProject": "Apache",
			      "product": "Log4j2",
			      "vulnerabilityName": "Apache Log4j2 RCE",
			      "dateAdded": "2021-12-10",
			      "shortDescription": "JNDI lookup RCE",
			      "requiredAction": "Apply updates per vendor instructions.",
			      "dueDate": "2021-12-24",
			      "knownRansomwareCampaignUse": "Known",
			      "notes": "https://example.com/notes",
			      "cwes": ["CWE-502", "CWE-400"]
			    },
			    {
			      "cveID": "CVE-2026-0001",
			      "vendorProject": "Vendor",
			      "product": "Product",
			      "vulnerabilityName": "Some vuln",
			      "dateAdded": "2026-06-11",
			      "shortDescription": "desc",
			      "requiredAction": "Patch.",
			      "dueDate": "2026-07-02",
			      "knownRansomwareCampaignUse": "Unknown",
			      "notes": "",
			      "cwes": []
			    },
			    {
			      "cveID": "CVE-2026-0002",
			      "vendorProject": "Vendor",
			      "product": "Product2",
			      "vulnerabilityName": "Silent-ransomware vuln",
			      "dateAdded": "2026-06-11",
			      "shortDescription": "desc",
			      "requiredAction": "Patch.",
			      "dueDate": "2026-07-02",
			      "notes": "",
			      "cwes": []
			    }
			  ]
			}
			""";

	private static final String PRIMARY_URL = "https://kev.test/feed.json";
	private static final String BACKUP_URL = "https://kev-backup.test/feed.json";

	private CisaKevConnector newConnector(ExchangeFunction exchange) throws Exception {
		CisaKevConnector connector = new CisaKevConnector(PRIMARY_URL, BACKUP_URL);
		WebClient stub = WebClient.builder().exchangeFunction(exchange).build();
		Field f = CisaKevConnector.class.getDeclaredField("webClient");
		f.setAccessible(true);
		f.set(connector, stub);
		return connector;
	}

	private CisaKevConnector withFeed(String body) throws Exception {
		return newConnector(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
				.headers(h -> h.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
				.body(body)
				.build()));
	}

	@Test
	void sourceIsCisa() throws Exception {
		assertEquals(KevSource.CISA, withFeed(FEED_JSON).source());
	}

	@Test
	void parsesFeedAndMapsRansomwareToThreeStateEnum() throws Exception {
		List<KevAssertionData> entries = withFeed(FEED_JSON).fetchCatalog(null);

		assertEquals(3, entries.size());
		KevAssertionData first = entries.get(0);
		assertEquals("CVE-2021-44228", first.getCveId());
		assertEquals("Apache", first.getVendorProject());
		assertEquals("Log4j2", first.getProduct());
		assertEquals("2021-12-24", first.getDueDate());
		assertEquals(List.of("CWE-502", "CWE-400"), first.getCwes());
		assertEquals(KevRansomwareStatus.KNOWN, first.getRansomwareStatus());
		// explicit "Unknown" vs absent field stay distinct
		assertEquals(KevRansomwareStatus.UNKNOWN, entries.get(1).getRansomwareStatus());
		assertEquals(KevRansomwareStatus.UNSPECIFIED, entries.get(2).getRansomwareStatus());
	}

	@Test
	void fetchFailureReturnsEmptyList() throws Exception {
		assertTrue(newConnector(req -> Mono.error(new RuntimeException("connection refused")))
				.fetchCatalog(null).isEmpty());
	}

	@Test
	void malformedJsonReturnsEmptyList() throws Exception {
		assertTrue(withFeed("<html>maintenance</html>").fetchCatalog(null).isEmpty());
	}

	@Test
	void emptyCatalogReturnsEmptyList() throws Exception {
		assertTrue(withFeed("{\"catalogVersion\":\"x\",\"vulnerabilities\":[]}").fetchCatalog(null).isEmpty());
	}

	// ---------- CISA WAF hardening: User-Agent + transient backoff ----------

	@Test
	void sendsDescriptiveUserAgentSoCisaWafDoesNotBlock() throws Exception {
		// CISA/Akamai 403s reactor-netty's default UA; the fetch must carry a
		// real, non-default identifier. Capture the outgoing request headers.
		AtomicReference<String> ua = new AtomicReference<>();
		AtomicReference<String> accept = new AtomicReference<>();
		CisaKevConnector connector = newConnector(req -> {
			ua.set(req.headers().getFirst(HttpHeaders.USER_AGENT));
			accept.set(req.headers().getFirst(HttpHeaders.ACCEPT));
			return Mono.just(ClientResponse.create(HttpStatus.OK)
					.headers(h -> h.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
					.body(FEED_JSON)
					.build());
		});

		connector.fetchCatalog(null);

		assertNotNull(ua.get(), "KEV fetch must send a User-Agent");
		assertTrue(ua.get().startsWith("ReARM-KEV-Sync/"), "expected descriptive UA, got: " + ua.get());
		assertFalse(ua.get().toLowerCase().contains("reactornetty"), "must not send reactor-netty's default UA");
		assertEquals(MediaType.APPLICATION_JSON_VALUE, accept.get());
	}

	@Test
	void retriesTransientTooManyRequestsThenSucceeds() throws Exception {
		AtomicInteger attempts = new AtomicInteger();
		CisaKevConnector connector = newConnector(req -> {
			if (attempts.getAndIncrement() < 2) {
				return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).build());
			}
			return Mono.just(ClientResponse.create(HttpStatus.OK)
					.headers(h -> h.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
					.body(FEED_JSON)
					.build());
		});
		connector.retryFirstBackoff = Duration.ofMillis(1); // don't sleep for real

		List<KevAssertionData> entries = connector.fetchCatalog(null);

		assertEquals(3, entries.size());
		assertEquals(3, attempts.get(), "expected 2 retries after the initial attempt");
	}

	@Test
	void doesNotRetryForbiddenAndFailsClosedImmediately() throws Exception {
		// CISA's WAF 403 is a sticky IP cooldown, not transient -- retrying it
		// in-pass never recovers and only loads an already-blocked IP. So a 403
		// must not be retried on either feed: one attempt on the primary, then
		// straight to the backup's single attempt.
		AtomicInteger attempts = new AtomicInteger();
		CisaKevConnector connector = newConnector(req -> {
			attempts.incrementAndGet();
			return Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).build());
		});
		connector.retryFirstBackoff = Duration.ofMillis(1);

		assertTrue(connector.fetchCatalog(null).isEmpty());
		assertEquals(2, attempts.get(), "403 must not be retried: one attempt per feed");
	}

	@Test
	void abortsAfterExhaustingRetriesOnPersistentServerError() throws Exception {
		AtomicInteger attempts = new AtomicInteger();
		CisaKevConnector connector = newConnector(req -> {
			attempts.incrementAndGet();
			return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
		});
		connector.retryFirstBackoff = Duration.ofMillis(1);

		// Fail-closed: exhausted retries return an empty list, never throw.
		assertTrue(connector.fetchCatalog(null).isEmpty());
		assertEquals(8, attempts.get()); // (initial + 3 backoff retries) per feed
	}

	// ---------- backup feed fallback ----------

	@Test
	void backupFeedIsUsedWhenPrimaryFails() throws Exception {
		// Primary 403s (WAF cooldown); the backup mirror serves the catalog.
		// The result must come from the backup with no error-grade outcome.
		AtomicInteger primaryAttempts = new AtomicInteger();
		AtomicInteger backupAttempts = new AtomicInteger();
		CisaKevConnector connector = newConnector(req -> {
			if (req.url().toString().equals(PRIMARY_URL)) {
				primaryAttempts.incrementAndGet();
				return Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).build());
			}
			backupAttempts.incrementAndGet();
			return Mono.just(ClientResponse.create(HttpStatus.OK)
					.headers(h -> h.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
					.body(FEED_JSON)
					.build());
		});
		connector.retryFirstBackoff = Duration.ofMillis(1);

		List<KevAssertionData> entries = connector.fetchCatalog(null);

		assertEquals(3, entries.size(), "catalog must be served from the backup feed");
		assertEquals(1, primaryAttempts.get());
		assertEquals(1, backupAttempts.get());
	}

	@Test
	void backupFeedIsUsedWhenPrimaryParsesEmpty() throws Exception {
		// An empty/degenerate primary catalog is a failure mode too (a bad
		// publish would otherwise wipe enrichment downstream) -- it must fall
		// through to the backup rather than being accepted.
		CisaKevConnector connector = newConnector(req -> {
			String body = req.url().toString().equals(PRIMARY_URL)
					? "{\"catalogVersion\":\"x\",\"vulnerabilities\":[]}"
					: FEED_JSON;
			return Mono.just(ClientResponse.create(HttpStatus.OK)
					.headers(h -> h.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
					.body(body)
					.build());
		});

		assertEquals(3, connector.fetchCatalog(null).size());
	}

	@Test
	void primarySuccessNeverTouchesBackup() throws Exception {
		AtomicInteger backupAttempts = new AtomicInteger();
		CisaKevConnector connector = newConnector(req -> {
			if (!req.url().toString().equals(PRIMARY_URL)) {
				backupAttempts.incrementAndGet();
			}
			return Mono.just(ClientResponse.create(HttpStatus.OK)
					.headers(h -> h.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
					.body(FEED_JSON)
					.build());
		});

		assertEquals(3, connector.fetchCatalog(null).size());
		assertEquals(0, backupAttempts.get(), "healthy primary must not fan out to the backup");
	}
}
