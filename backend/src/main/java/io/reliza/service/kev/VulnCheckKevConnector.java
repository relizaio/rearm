/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.common.Utils;
import io.reliza.model.KevAssertionData;
import io.reliza.model.KevRansomwareStatus;
import io.reliza.model.KevSource;
import io.reliza.service.SystemInfoService;
import lombok.extern.slf4j.Slf4j;

/**
 * VulnCheck Community KEV connector: a CISA-plus source (~3x the CISA
 * catalog) reached via the v3 index API with a Bearer token. Token-gated —
 * with no token configured on {@link SystemInfoService} the connector is a
 * no-op, so the source is simply absent until a global admin configures a
 * (free, registration-only) token via the Integrations admin card.
 *
 * <p>The API is paginated and each entry's {@code cve} is an ARRAY (one
 * entry may cover several CVEs), so we page through and emit one
 * {@link KevAssertionData} per CVE. Dates arrive as ISO timestamps and are
 * sliced to the feed's {@code yyyy-MM-dd} display form; ransomware use maps
 * to the same {@link KevRansomwareStatus} three-state as CISA.
 *
 * <p><b>Fail-closed.</b> Any page fetch/parse failure aborts the whole
 * source for this tick (returns empty), so a partial page never reconciles
 * — which would wrongly revoke the un-fetched CVEs.
 */
@Slf4j
@Component
public class VulnCheckKevConnector implements KevSourceConnector {

	private static final int PAGE_LIMIT = 1000;
	// Runaway guard; with PAGE_LIMIT=1000 the ~5k-entry catalog is ~5 pages.
	private static final int MAX_PAGES = 50;

	private final SystemInfoService systemInfoService;
	private final String baseUrl;
	// Non-final so tests can substitute an ExchangeFunction-stubbed client.
	private WebClient webClient;

	public VulnCheckKevConnector(
			SystemInfoService systemInfoService,
			@Value("${relizaprops.vulncheckKevUrl:https://api.vulncheck.com/v3/index/vulncheck-kev}")
			String baseUrl) {
		this.systemInfoService = systemInfoService;
		this.baseUrl = baseUrl;
		ExchangeStrategies strategies = ExchangeStrategies.builder()
				.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
				.build();
		this.webClient = WebClient.builder()
				.exchangeStrategies(strategies)
				.build();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record VcResponse(VcMeta _meta, List<VcEntry> data) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record VcMeta(Integer total_documents, Integer page, Integer total_pages) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record VcEntry(List<String> cve, String vendorProject, String product,
			String vulnerabilityName, String shortDescription, String date_added,
			String dueDate, String knownRansomwareCampaignUse, String required_action,
			List<String> cwes) {}

	@Override
	public KevSource source() {
		return KevSource.VULNCHECK;
	}

	@Override
	public List<KevAssertionData> fetchCatalog() {
		String token = systemInfoService.getVulncheckKevToken();
		if (StringUtils.isBlank(token)) {
			log.debug("VulnCheck KEV token not configured — VulnCheck source inactive");
			return List.of();
		}
		List<KevAssertionData> all = new ArrayList<>();
		int page = 1;
		int totalPages = 1;
		try {
			do {
				String body = webClient.get()
						.uri(URI.create(baseUrl + "?limit=" + PAGE_LIMIT + "&page=" + page))
						.header("Authorization", "Bearer " + token)
						.retrieve()
						.bodyToMono(String.class)
						.block(Duration.ofSeconds(120));
				VcResponse resp = Utils.OM.readValue(body, VcResponse.class);
				if (resp == null || resp.data() == null) break;
				for (VcEntry e : resp.data()) {
					if (e.cve() == null) continue;
					for (String cve : e.cve()) {
						if (StringUtils.isNotBlank(cve)) all.add(toAssertionData(e, cve));
					}
				}
				totalPages = resp._meta() != null && resp._meta().total_pages() != null
						? resp._meta().total_pages() : page;
				page++;
			} while (page <= totalPages && page <= MAX_PAGES);
		} catch (Exception ex) {
			log.error("VulnCheck KEV fetch aborted at page {} of {}", page, totalPages, ex);
			return List.of();
		}
		if (all.isEmpty()) {
			log.error("VulnCheck KEV fetch returned no entries");
			return List.of();
		}
		log.info("VulnCheck KEV fetched {} assertions across {} page(s)", all.size(), page - 1);
		return all;
	}

	private static KevAssertionData toAssertionData(VcEntry e, String cve) {
		KevAssertionData kad = new KevAssertionData();
		kad.setCveId(cve);
		kad.setVendorProject(e.vendorProject());
		kad.setProduct(e.product());
		kad.setVulnerabilityName(e.vulnerabilityName());
		kad.setDateAdded(dateOnly(e.date_added()));
		kad.setDueDate(dateOnly(e.dueDate()));
		kad.setShortDescription(e.shortDescription());
		kad.setRequiredAction(e.required_action());
		kad.setRansomwareStatus(parseRansomware(e.knownRansomwareCampaignUse()));
		kad.setCwes(e.cwes() != null ? new ArrayList<>(e.cwes()) : new ArrayList<>());
		return kad;
	}

	/** ISO timestamp (2021-12-10T00:00:00Z) -> feed-format yyyy-MM-dd. */
	private static String dateOnly(String iso) {
		if (iso == null) return null;
		return iso.length() >= 10 ? iso.substring(0, 10) : iso;
	}

	private static KevRansomwareStatus parseRansomware(String raw) {
		if ("Known".equalsIgnoreCase(raw)) return KevRansomwareStatus.KNOWN;
		if ("Unknown".equalsIgnoreCase(raw)) return KevRansomwareStatus.UNKNOWN;
		return KevRansomwareStatus.UNSPECIFIED;
	}
}
