/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomStructureType;
import reactor.core.publisher.Mono;

/**
 * TEA-1: a rebom failure under a merge reaches the merged-SBOM path as a RelizaException.
 *
 * <p>mergeAndStoreBoms used to let the reactive wrapper's RuntimeException escape, which the
 * product branch's catch never saw and DGS reported as "Internal server error" with the message
 * dropped -- the other way the merged export hid its cause.
 */
class RebomServiceMergeRefusalTest {

	private static RebomService rebomAnswering(int status, String body) {
		ExchangeFunction exchange = req -> Mono.just(ClientResponse.create(HttpStatus.valueOf(status))
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.body(body)
				.build());
		RebomService rebom = new RebomService("http://rebom.invalid/");
		WebClient stub = WebClient.builder()
				.baseUrl("http://rebom.invalid/graphql")
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.exchangeFunction(exchange)
				.build();
		ReflectionTestUtils.setField(rebom, "rebomWebClient", stub);
		return rebom;
	}

	private static UUID merge(RebomService rebom) throws RelizaException {
		return rebom.mergeAndStoreBoms(List.of(UUID.randomUUID(), UUID.randomUUID()),
				new RebomOptions(null, false, false, BomStructureType.FLAT), UUID.randomUUID());
	}

	@Test
	void aRebomRefusalArrivesAsItself() {
		RebomService rebom = rebomAnswering(200, """
				{"data": null, "errors": [{"message": "bom 1234 does not exist",
				  "extensions": {"code": "BOM_NOT_FOUND"}}]}""");

		RelizaException e = assertThrows(RelizaException.class, () -> merge(rebom));
		assertTrue(e.getMessage().startsWith("BOM not found: "), e.getMessage());
		assertTrue(e.getMessage().contains("bom 1234 does not exist"), e.getMessage());
	}

	@Test
	void anHttpFailureArrivesAsARebomMergeCallFailure() {
		RebomService rebom = rebomAnswering(503, "{}");

		RelizaException e = assertThrows(RelizaException.class, () -> merge(rebom));
		assertTrue(e.getMessage().startsWith("rebom merge call failed: "), e.getMessage());
		assertTrue(e.getMessage().contains("503"), e.getMessage());
	}

	@Test
	void aWellFormedMergeAnswersTheMergedBomsId() throws Exception {
		UUID merged = UUID.randomUUID();
		RebomService rebom = rebomAnswering(200, """
				{"data": {"mergeAndStoreBoms": {"uuid": "%s", "meta": {"serialNumber": "urn:uuid:%s"}}}}"""
				.formatted(UUID.randomUUID(), merged));

		assertEquals(merged, merge(rebom));
	}
}
