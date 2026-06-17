/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;

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

	private CisaKevConnector newConnector(ExchangeFunction exchange) throws Exception {
		CisaKevConnector connector = new CisaKevConnector("https://kev.test/feed.json");
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
		List<KevAssertionData> entries = withFeed(FEED_JSON).fetchCatalog();

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
				.fetchCatalog().isEmpty());
	}

	@Test
	void malformedJsonReturnsEmptyList() throws Exception {
		assertTrue(withFeed("<html>maintenance</html>").fetchCatalog().isEmpty());
	}

	@Test
	void emptyCatalogReturnsEmptyList() throws Exception {
		assertTrue(withFeed("{\"catalogVersion\":\"x\",\"vulnerabilities\":[]}").fetchCatalog().isEmpty());
	}
}
