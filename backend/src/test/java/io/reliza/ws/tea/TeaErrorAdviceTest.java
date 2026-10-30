/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.MalformedInputException;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;

import io.reliza.common.LogSafe;
import io.reliza.model.tea.TeaErrorResponse;
import io.reliza.model.tea.TeaUnknownErrorType;
import io.reliza.service.TeaAuthorizationService.TeaAccess;
import io.reliza.service.tea.TeaSurfaceService;
import io.reliza.service.tea.TeaTransformerService;

/**
 * Task TEA-6, design 4.5 case 19: the advice renders a thrown refusal as its response, and a
 * request Spring could not bind as 400 INVALID_REQUEST, through MockMvc on the real controller.
 */
class TeaErrorAdviceTest {

	private TeaRequests requests;
	private MockMvc mvc;

	@BeforeEach
	void wire() {
		ProductApiController controller = new ProductApiController(mock(NativeWebRequest.class));
		requests = mock(TeaRequests.class);
		ReflectionTestUtils.setField(controller, "teaRequests", requests);
		ReflectionTestUtils.setField(controller, "teaSurfaceService", mock(TeaSurfaceService.class));
		ReflectionTestUtils.setField(controller, "teaTransformerService", mock(TeaTransformerService.class));
		ReflectionTestUtils.setField(controller, "teaRendering", mock(TeaRendering.class));
		mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new TeaErrorAdvice())
				.setMessageConverters(new TeaJsonConverter()).build();
	}

	private MockHttpServletResponse call(String path) throws Exception {
		return mvc.perform(get("/tea/" + UUID.randomUUID() + "/v1.0.0" + path)).andReturn().getResponse();
	}

	@Test
	void aThrownRefusalIsRenderedAsItsResponse() throws Exception {
		doThrow(TeaRefusal.of(TeaAccess.CONCEAL)).when(requests).context(any());
		MockHttpServletResponse r = call("/product/" + UUID.randomUUID());
		assertEquals(404, r.getStatus());
		assertEquals("{\"error\":\"OBJECT_UNKNOWN\"}", r.getContentAsString());
		assertEquals("no-store", r.getHeader("Cache-Control"));

		doThrow(TeaRefusal.invalidRequest("pageSize must be between 1 and 100")).when(requests).context(any());
		r = call("/product/" + UUID.randomUUID());
		assertEquals(400, r.getStatus());
		assertEquals("{\"error\":\"INVALID_REQUEST\",\"message\":\"pageSize must be between 1 and 100\"}", r.getContentAsString());

		doThrow(TeaRefusal.invalidPageToken()).when(requests).context(any());
		r = call("/product/" + UUID.randomUUID());
		assertEquals(400, r.getStatus());
		assertEquals("{\"error\":\"INVALID_PAGE_TOKEN\"}", r.getContentAsString());

		doThrow(TeaRefusal.of(TeaAccess.UNAUTHENTICATED)).when(requests).context(any());
		r = call("/product/" + UUID.randomUUID());
		assertEquals(401, r.getStatus());
		assertEquals("Bearer realm=\"tea\"", r.getHeader("WWW-Authenticate"));
		assertEquals("", r.getContentAsString());
	}

	@Test
	void aParameterSpringCannotBindIsInvalidRequestNamingIt() throws Exception {
		MockHttpServletResponse r = call("/product/not-a-uuid");
		assertEquals(400, r.getStatus());
		assertTrue(r.getContentAsString().startsWith("{\"error\":\"INVALID_REQUEST\",\"message\":\"uuid "), r.getContentAsString());
		assertEquals("no-store", r.getHeader("Cache-Control"));
	}

	// TEA-21, design section 5 rows 6, 6a and 7: parameters Tomcat could not decode. MockMvc never runs Tomcat's
	// parser, so the handler is called directly; the real container is TeaSurfaceRequestsIntegrationTest's.
	private static final String ORG = "8a0c8f4e-3d0c-4b52-9f0e-2a7b1c5d6e01";
	private static final String PRODUCTS = "/tea/" + ORG + "/v1.0.0/products";

	private static MockHttpServletRequest request(String uri, String query) {
		MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
		r.setQueryString(query);
		return r;
	}

	/** Calls the handler with the advice's logger captured at DEBUG; asserts the answer and returns the events. */
	private static List<LogEvent> refuse(InvalidParameterException e, MockHttpServletRequest request) {
		try (LogEventCapture logs = LogEventCapture.of(TeaErrorAdvice.class, Level.DEBUG)) {
			ResponseEntity<TeaErrorResponse> r = new TeaErrorAdvice().invalidParameter(e, request);
			assertEquals(400, r.getStatusCode().value());
			assertEquals(TeaUnknownErrorType.INVALID_REQUEST, r.getBody().getError());
			assertEquals("the query string could not be decoded", r.getBody().getMessage());
			assertEquals("no-store", r.getHeaders().getFirst("Cache-Control"));
			return logs.events();
		}
	}

	private static void assertOneDebugLine(List<LogEvent> events, String message) {
		assertEquals(1, events.size(), String.valueOf(events));
		assertEquals(Level.DEBUG, events.get(0).getLevel());
		assertNull(events.get(0).getThrown());
		assertEquals(message, events.get(0).getMessage().getFormattedMessage());
	}

	// 6
	@Test
	void bytesThatAreNotUtf8AreInvalidRequestWithOneDebugLine() {
		List<LogEvent> events = refuse(new InvalidParameterException("msg", new MalformedInputException(1)),
				request(PRODUCTS, "idValue=%C3%28"));
		assertOneDebugLine(events, "TEA request refused, parameters could not be decoded (MalformedInputException): path "
				+ PRODUCTS + ", parameter idValue");
	}

	// 6a
	@Test
	void aBadPercentSequenceNamesItsCauseClass() {
		List<LogEvent> events = refuse(new InvalidParameterException("msg", new java.io.CharConversionException()),
				request(PRODUCTS, "idValue=%ZZ"));
		assertOneDebugLine(events, "TEA request refused, parameters could not be decoded (CharConversionException): path "
				+ PRODUCTS + ", parameter idValue");
	}

	// 7
	@Test
	void aRefusalWithoutACauseNamesItselfAndNoParameter() {
		List<LogEvent> events = refuse(new InvalidParameterException("msg"), request(PRODUCTS, null));
		assertOneDebugLine(events, "TEA request refused, parameters could not be decoded (InvalidParameterException): path "
				+ PRODUCTS + ", parameter -");
	}

	// row 5's rendering: a name over 256 units is cut by LogSafe, and a name that is the offender prints as ?
	@Test
	void theNameIsRenderedThroughLogSafe() {
		String longName = "n".repeat(300);
		List<LogEvent> events = refuse(new InvalidParameterException("msg", new MalformedInputException(1)),
				request(PRODUCTS, "a=1&" + longName + "=%FF"));
		assertOneDebugLine(events, "TEA request refused, parameters could not be decoded (MalformedInputException): path "
				+ PRODUCTS + ", parameter " + "n".repeat(256) + "...");
		events = refuse(new InvalidParameterException("msg", new MalformedInputException(1)), request(PRODUCTS, "%C3%28=1"));
		assertOneDebugLine(events, "TEA request refused, parameters could not be decoded (MalformedInputException): path "
				+ PRODUCTS + ", parameter ?(");
	}

	// design 3.4, threat model threat 2 (run-1 T-1): the path is rendered through LogSafe too. The generated TEA
	// interfaces bind no org variable, so a request with a 300-unit org segment reaches the advice; the line carries
	// the first 256 units and the cut mark, and a control or non-ASCII unit in the path prints as ?.
	@Test
	void thePathIsRenderedThroughLogSafe() {
		String longPath = "/tea/" + "o".repeat(300) + "/v1.0.0/products";
		List<LogEvent> events = refuse(new InvalidParameterException("msg", new MalformedInputException(1)),
				request(longPath, "idValue=%C3%28"));
		assertOneDebugLine(events, "TEA request refused, parameters could not be decoded (MalformedInputException): path /tea/"
				+ "o".repeat(LogSafe.MAX_LENGTH - "/tea/".length()) + "..., parameter idValue");

		String oddPath = "/tea/" + ORG + "/v1.0.0/pro\tducts" + (char) 0xE9;
		events = refuse(new InvalidParameterException("msg", new MalformedInputException(1)),
				request(oddPath, "idValue=%C3%28"));
		assertOneDebugLine(events, "TEA request refused, parameters could not be decoded (MalformedInputException): path /tea/"
				+ ORG + "/v1.0.0/pro?ducts?, parameter idValue");
	}

	@Test
	void anythingElseIsLeftToSpring() {
		doThrow(new IllegalStateException("boom")).when(requests).context(any());
		Exception e = assertThrows(Exception.class, () -> call("/product/" + UUID.randomUUID()));
		assertTrue(e.getCause() instanceof IllegalStateException || e instanceof IllegalStateException, String.valueOf(e));
	}

	// TEA-10, design 4.2 case 8: the artifact refusals keep their TEA body under any Accept of a download
	@Test
	void theArtifactRefusalsRenderTheirBodyUnderADownloadsAccept() throws Exception {
		ArtifactApiController artifacts = new ArtifactApiController(mock(NativeWebRequest.class));
		TeaRequests artifactRequests = mock(TeaRequests.class);
		ReflectionTestUtils.setField(artifacts, "teaRequests", artifactRequests);
		ReflectionTestUtils.setField(artifacts, "teaArtifactSurfaceService", mock(io.reliza.service.tea.TeaArtifactSurfaceService.class));
		ReflectionTestUtils.setField(artifacts, "downloadLogService", mock(io.reliza.service.DownloadLogService.class));
		MockMvc amvc = MockMvcBuilders.standaloneSetup(artifacts).setControllerAdvice(new TeaErrorAdvice())
				.setMessageConverters(new TeaJsonConverter()).build();
		String path = "/tea/" + UUID.randomUUID() + "/v1.0.0/artifact/" + UUID.randomUUID() + "/1/download?mediaType=x%2Fy";
		for (String accept : new String[] { "application/vnd.cyclonedx+json", "*/*" }) {
			doThrow(TeaRefusal.noAcceptableFormat("m")).when(artifactRequests).context(any());
			MockHttpServletResponse r = amvc.perform(get(path).header("Accept", accept)).andReturn().getResponse();
			assertEquals(406, r.getStatus(), accept);
			assertEquals("{\"error\":\"NO_ACCEPTABLE_FORMAT\",\"message\":\"m\"}", r.getContentAsString(), accept);
			assertEquals("no-store", r.getHeader("Cache-Control"), accept);
			assertTrue(r.getContentType().startsWith("application/json"), accept);
		}
		doThrow(TeaRefusal.signatureNotFound()).when(artifactRequests).context(any());
		MockHttpServletResponse sig = amvc.perform(get(path.replace("/download", "/signature/download"))
				.header("Accept", "application/vnd.cyclonedx+json")).andReturn().getResponse();
		assertEquals(404, sig.getStatus());
		assertEquals("{\"error\":\"SIGNATURE_NOT_FOUND\"}", sig.getContentAsString());
		doThrow(TeaRefusal.of(TeaAccess.CONCEAL)).when(artifactRequests).context(any());
		MockHttpServletResponse hidden = amvc.perform(get(path).header("Accept", "application/vnd.cyclonedx+json")).andReturn().getResponse();
		assertEquals(404, hidden.getStatus());
		assertEquals("{\"error\":\"OBJECT_UNKNOWN\"}", hidden.getContentAsString());
		assertTrue(hidden.getContentType().startsWith("application/json"));
	}
}
