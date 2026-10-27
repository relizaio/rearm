/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;

import io.reliza.model.ReleaseData;
import io.reliza.model.tea.TeaDiscoveryInfo;
import io.reliza.model.tea.TeaErrorResponse;
import io.reliza.model.tea.TeaProduct;
import io.reliza.model.tea.TeaProductRelease;
import io.reliza.model.tea.TeaUnknownErrorType;
import tools.jackson.databind.JsonNode;

/** Task TEA-6, design 4.5 case 18: the TEA mapper and the converter that writes TEA bodies with it. */
class TeaJsonTest {

	private static final MediaType JSON = MediaType.APPLICATION_JSON;

	@Test
	void theMapperLeavesNullsOutAndWritesUtcInstants() {
		assertEquals("{\"error\":\"OBJECT_UNKNOWN\"}", TeaJson.MAPPER.writeValueAsString(new TeaErrorResponse(TeaUnknownErrorType.OBJECT_UNKNOWN)));
		TeaProductRelease r = new TeaProductRelease(UUID.randomUUID(), UUID.randomUUID(), "1.0.0",
				OffsetDateTime.parse("2026-10-08T10:15:00+02:00"), List.of());
		String out = TeaJson.MAPPER.writeValueAsString(r);
		JsonNode n = TeaJson.MAPPER.readTree(out);
		assertFalse(n.has("preRelease"), out);
		assertFalse(n.has("releaseDate"), out);
		assertEquals("2026-10-08T08:15:00Z", n.get("createdDate").asString());
		assertTrue(out.contains("\"components\":[]"), "a required empty list stays: " + out);
	}

	@Test
	void theConverterWritesTeaModelsOnlyAndReadsNothing() {
		TeaJsonConverter c = new TeaJsonConverter();
		assertTrue(c.canWrite(ResolvableType.forClass(TeaProduct.class), TeaProduct.class, JSON));
		assertTrue(c.canWrite(ResolvableType.forClassWithGenerics(List.class, TeaDiscoveryInfo.class), List.class, JSON));
		assertTrue(c.canWrite(ResolvableType.forClass(TeaProduct.class), TeaErrorResponse.class, JSON),
				"a refusal body under a product-typed handler");
		assertTrue(c.canWrite(TeaErrorResponse.class, JSON));
		assertFalse(c.canWrite(ResolvableType.forClass(Map.class), Map.class, JSON));
		assertFalse(c.canWrite(ResolvableType.forClass(String.class), String.class, JSON));
		assertFalse(c.canWrite(ResolvableType.forClass(ReleaseData.class), ReleaseData.class, JSON));
		assertFalse(c.canWrite(ResolvableType.forClass(JsonNode.class), JsonNode.class, JSON));
		assertFalse(c.canWrite(ResolvableType.forClassWithGenerics(List.class, String.class), List.class, JSON));
		assertFalse(c.canWrite(io.reliza.model.tea.publication.TeaCollectionDocument.class, JSON), "not the publication subpackage");
		assertFalse(c.canRead(ResolvableType.forClass(TeaProduct.class), JSON));
		assertFalse(c.canRead(TeaProduct.class, JSON));
	}

	@Test
	void theConfigRegistersTheConverterAheadOfTheDefaults() {
		HttpMessageConverters.ServerBuilder b = mock(HttpMessageConverters.ServerBuilder.class);
		new TeaWebMvcConfig().configureMessageConverters(b);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<HttpMessageConverter<?>> captor = ArgumentCaptor.forClass(HttpMessageConverter.class);
		verify(b).addCustomConverter(captor.capture());
		assertTrue(captor.getValue() instanceof TeaJsonConverter);
		verify(b, org.mockito.Mockito.never()).configureMessageConvertersList(any());
	}
}
