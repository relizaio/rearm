/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
 * Unit tests for {@link VulnCheckKevConnector}: HTTP substituted with an
 * {@link ExchangeFunction} stub. Pins token-gating (no/blank credential
 * = no-op), pagination (page through {@code _meta.total_pages}), the
 * {@code cve}-array fan-out (one assertion per CVE), the ransomware enum
 * mapping + ISO-date slicing, and the fail-closed contract.
 *
 * <p>V54: connector no longer reads the token from {@code SystemInfoService};
 * the per-org credential is passed to {@code fetchCatalog(credential)}.
 */
class VulnCheckKevConnectorTest {

	private static final String PAGE1 = """
			{"_meta":{"total_documents":3,"page":1,"total_pages":2},"data":[
			  {"cve":["CVE-2021-44228","CVE-2021-45046"],"vendorProject":"Apache","product":"Log4j2",
			   "vulnerabilityName":"Log4Shell","shortDescription":"JNDI RCE",
			   "date_added":"2021-12-10T00:00:00Z","dueDate":"2021-12-24T00:00:00Z",
			   "knownRansomwareCampaignUse":"Known","required_action":"Patch","cwes":["CWE-502"]}
			]}
			""";

	private static final String PAGE2 = """
			{"_meta":{"total_documents":3,"page":2,"total_pages":2},"data":[
			  {"cve":["CVE-2026-0001"],"vendorProject":"Vendor","product":"Product",
			   "vulnerabilityName":"Some vuln","shortDescription":"desc",
			   "date_added":"2026-06-01T00:00:00Z","dueDate":null,
			   "knownRansomwareCampaignUse":"Unknown","required_action":"Patch","cwes":[]}
			]}
			""";

	private VulnCheckKevConnector newConnector(ExchangeFunction exchange) throws Exception {
		VulnCheckKevConnector c = new VulnCheckKevConnector("https://vc.test/v3/index/vulncheck-kev");
		WebClient stub = WebClient.builder().exchangeFunction(exchange).build();
		Field f = VulnCheckKevConnector.class.getDeclaredField("webClient");
		f.setAccessible(true);
		f.set(c, stub);
		return c;
	}

	private static Mono<ClientResponse> json(String body) {
		return Mono.just(ClientResponse.create(HttpStatus.OK)
				.headers(h -> h.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
				.body(body)
				.build());
	}

	@Test
	void sourceIsVulnCheck() throws Exception {
		assertEquals(KevSource.VULNCHECK, newConnector(req -> json(PAGE1)).source());
	}

	@Test
	void blankTokenIsNoOp() throws Exception {
		VulnCheckKevConnector c = newConnector(req -> {
			throw new AssertionError("must not fetch without a token");
		});
		assertTrue(c.fetchCatalog("").isEmpty());
		assertTrue(c.fetchCatalog(null).isEmpty());
	}

	@Test
	void paginatesAndFansOutCveArray() throws Exception {
		ExchangeFunction ex = req -> req.url().toString().contains("page=1") ? json(PAGE1) : json(PAGE2);
		List<KevAssertionData> entries = newConnector(ex).fetchCatalog("tok");

		// 2 CVEs from the page-1 entry's array + 1 from page-2 = 3 assertions.
		assertEquals(3, entries.size());
		Set<String> cves = entries.stream().map(KevAssertionData::getCveId).collect(Collectors.toSet());
		assertEquals(Set.of("CVE-2021-44228", "CVE-2021-45046", "CVE-2026-0001"), cves);

		KevAssertionData log4shell = entries.stream()
				.filter(e -> e.getCveId().equals("CVE-2021-44228")).findFirst().orElseThrow();
		assertEquals(KevRansomwareStatus.KNOWN, log4shell.getRansomwareStatus());
		assertEquals("2021-12-10", log4shell.getDateAdded());   // ISO sliced to date
		assertEquals("2021-12-24", log4shell.getDueDate());
		assertEquals("Apache", log4shell.getVendorProject());

		KevAssertionData other = entries.stream()
				.filter(e -> e.getCveId().equals("CVE-2026-0001")).findFirst().orElseThrow();
		assertEquals(KevRansomwareStatus.UNKNOWN, other.getRansomwareStatus());
	}

	@Test
	void fetchErrorReturnsEmpty() throws Exception {
		assertTrue(newConnector(req -> Mono.error(new RuntimeException("connection refused")))
				.fetchCatalog("tok").isEmpty());
	}

	@Test
	void emptyDataReturnsEmpty() throws Exception {
		assertTrue(newConnector(req -> json("{\"_meta\":{\"total_pages\":1},\"data\":[]}"))
				.fetchCatalog("tok").isEmpty());
	}
}
