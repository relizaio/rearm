/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.common.Utils;
import io.reliza.model.KevAssertionData;
import io.reliza.model.KevRansomwareStatus;
import io.reliza.model.KevSource;
import lombok.extern.slf4j.Slf4j;
import reactor.util.retry.Retry;

/**
 * CISA Known Exploited Vulnerabilities catalog connector: fetches the
 * public JSON feed (~1300 entries, no auth) and normalizes each entry into
 * a {@link KevAssertionData}. CISA's {@code knownRansomwareCampaignUse}
 * string ({@code "Known"}/{@code "Unknown"}) maps to a
 * {@link KevRansomwareStatus}; CISA supplies no campaign names, so
 * {@code ransomwareCampaigns} stays empty.
 */
@Slf4j
@Component
public class CisaKevConnector implements KevSourceConnector {

	// CISA is fronted by Akamai, which 403s requests carrying reactor-netty's
	// default User-Agent (and requests with no browser-ish UA). A descriptive
	// UA with a contact URL is what reliably gets the public feed served; keep
	// it a real, non-spoofed identifier so CISA can reach us if needed.
	private static final String KEV_USER_AGENT = "ReARM-KEV-Sync/1.0 (+https://reliza.io)";

	private final String feedUrl;
	// Non-final so tests can substitute an ExchangeFunction-stubbed client.
	private WebClient webClient;
	// First backoff step; exponential from here. Package-private and non-final
	// so retry tests can shrink it and not sleep for real seconds.
	Duration retryFirstBackoff = Duration.ofSeconds(2);

	public CisaKevConnector(
			@Value("${relizaprops.kevFeedUrl:https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json}")
			String feedUrl) {
		this.feedUrl = feedUrl;
		// Feed is ~4 MB and growing; default 256 KB codec buffer would reject it.
		ExchangeStrategies strategies = ExchangeStrategies.builder()
				.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
				.build();
		this.webClient = WebClient.builder()
				.exchangeStrategies(strategies)
				.build();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record CisaKevCatalog(String catalogVersion, String dateReleased, Integer count,
			List<CisaKevEntry> vulnerabilities) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record CisaKevEntry(String cveID, String vendorProject, String product,
			String vulnerabilityName, String dateAdded, String shortDescription,
			String requiredAction, String dueDate, String knownRansomwareCampaignUse,
			String notes, List<String> cwes) {}

	@Override
	public KevSource source() {
		return KevSource.CISA;
	}

	@Override
	public List<KevAssertionData> fetchCatalog(String credential) {
		// Public feed — no auth, credential is ignored.
		try {
			String body = webClient.get()
					// URI.create: pass the configured URL byte-for-byte, no template re-encoding
					.uri(URI.create(feedUrl))
					.header(HttpHeaders.USER_AGENT, KEV_USER_AGENT)
					.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
					.retrieve()
					.bodyToMono(String.class)
					// Back off on transient Akamai throttling (429/5xx). NOT 403:
					// once CISA's WAF 403s the egress IP the penalty persists for
					// a cooldown far longer than this bounded backoff, so retrying
					// only adds load to an already-blocked IP -- fail closed and let
					// the next scheduled pass retry. (The dedup upstream is what
					// keeps us from tripping the WAF in the first place.)
					.retryWhen(Retry.backoff(3, retryFirstBackoff)
							.maxBackoff(Duration.ofSeconds(30))
							.filter(CisaKevConnector::isTransient))
					.block(Duration.ofSeconds(120));
			CisaKevCatalog catalog = Utils.OM.readValue(body, CisaKevCatalog.class);
			if (catalog == null || catalog.vulnerabilities() == null || catalog.vulnerabilities().isEmpty()) {
				log.error("CISA KEV fetch aborted: feed at {} parsed to an empty catalog", feedUrl);
				return List.of();
			}
			List<KevAssertionData> entries = new ArrayList<>(catalog.vulnerabilities().size());
			for (CisaKevEntry e : catalog.vulnerabilities()) {
				entries.add(toAssertionData(e));
			}
			return entries;
		} catch (Exception e) {
			log.error("CISA KEV fetch aborted: failed to fetch or parse feed at {}", feedUrl, e);
			return List.of();
		}
	}

	private static KevAssertionData toAssertionData(CisaKevEntry e) {
		KevAssertionData kad = new KevAssertionData();
		kad.setCveId(e.cveID());
		kad.setVendorProject(e.vendorProject());
		kad.setProduct(e.product());
		kad.setVulnerabilityName(e.vulnerabilityName());
		kad.setDateAdded(e.dateAdded());
		kad.setShortDescription(e.shortDescription());
		kad.setRequiredAction(e.requiredAction());
		kad.setDueDate(e.dueDate());
		kad.setRansomwareStatus(parseRansomware(e.knownRansomwareCampaignUse()));
		kad.setNotes(e.notes());
		kad.setCwes(e.cwes() != null ? new ArrayList<>(e.cwes()) : new ArrayList<>());
		return kad;
	}

	/**
	 * Retry only genuinely transient upstream rejections: rate limiting (429)
	 * and server errors (5xx). A 403 from CISA's WAF is a sticky IP-cooldown,
	 * not a transient blip -- retrying it in-pass never recovers and only adds
	 * load, so it fails closed for the next scheduled pass instead.
	 */
	private static boolean isTransient(Throwable t) {
		if (t instanceof WebClientResponseException wcre) {
			HttpStatusCode status = wcre.getStatusCode();
			return status.is5xxServerError()
					|| status.value() == HttpStatus.TOO_MANY_REQUESTS.value();
		}
		return false;
	}

	/** Feed publishes "Known" / "Unknown"; anything absent is UNSPECIFIED. */
	private static KevRansomwareStatus parseRansomware(String raw) {
		if ("Known".equalsIgnoreCase(raw)) return KevRansomwareStatus.KNOWN;
		if ("Unknown".equalsIgnoreCase(raw)) return KevRansomwareStatus.UNKNOWN;
		return KevRansomwareStatus.UNSPECIFIED;
	}
}
