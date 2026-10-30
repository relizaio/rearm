/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.HandlerMapping;

import io.reliza.model.tea.TeaUnknownErrorType;
import io.reliza.service.TeaAuthorizationService;
import io.reliza.service.TeaAuthorizationService.TeaAuthState;
import io.reliza.service.TeaAuthorizationService.TeaCaller;
import io.reliza.service.TeaAuthorizationService.TeaPrincipal;
import io.reliza.service.tea.TeaSurfaceService;
import io.reliza.service.tea.TeaSurfaceService.TeaOrg;
import io.reliza.ws.tea.TeaRequests.TeaRequestContext;
import io.reliza.ws.tea.TeaRequests.TeaSortField;
import io.reliza.ws.tea.TeaRequests.TeaSortOrder;

/** Task TEA-6, design 4.5 case 20, and the listing parameters of 3.5. */
class TeaRequestsTest {

	private static final UUID TEA = UUID.randomUUID();
	private static final TeaOrg ORG = new TeaOrg(UUID.randomUUID(), TEA, "https://x.example.com/tea/" + TEA);

	private TeaRequests requests;
	private TeaSurfaceService surface;

	@BeforeEach
	void wire() {
		requests = new TeaRequests();
		surface = mock(TeaSurfaceService.class);
		TeaAuthorizationService auth = new TeaAuthorizationService();
		ReflectionTestUtils.setField(requests, "teaSurfaceService", surface);
		ReflectionTestUtils.setField(requests, "teaAuthorizationService", auth);
		when(surface.orgOfBase(any())).thenReturn(Optional.empty());
		when(surface.orgOfBase(TEA.toString())).thenReturn(Optional.of(ORG));
	}

	private static ServletWebRequest request(String org, TeaCaller caller) {
		MockHttpServletRequest r = new MockHttpServletRequest("GET", "/tea/" + org + "/v1.0.0/products");
		if (null != org) r.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("org", org));
		if (null != caller) r.setAttribute(TeaAuthorizationService.CALLER_ATTRIBUTE, caller);
		return new ServletWebRequest(r, new MockHttpServletResponse());
	}

	@Test
	void theContextIsTheBaseOrganizationAndTheFiltersCaller() {
		TeaRequestContext anon = requests.context(request(TEA.toString(), null));
		assertEquals(ORG, anon.org());
		assertEquals(TeaAuthState.ANONYMOUS, anon.caller().state());
		TeaRequestContext bad = requests.context(request(TEA.toString(), TeaCaller.invalidToken("x")));
		assertEquals(TeaAuthState.INVALID_TOKEN, bad.caller().state());
	}

	@Test
	void aValidTokenOfAnotherOrganizationIsTheAnonymousCaller() {
		TeaCaller foreign = TeaCaller.authenticated(new TeaPrincipal(UUID.randomUUID(), UUID.randomUUID(), "EXTERNAL__t__ord__o", 1, "192.0.2.9"));
		TeaRequestContext demoted = requests.context(request(TEA.toString(), foreign));
		assertEquals(TeaCaller.anonymous(), demoted.caller(), "task TEA-18: a token of another organization is no credential here");
		assertEquals("no-cache", TeaResponses.headersFor(demoted).getCacheControl());
		TeaCaller own = TeaCaller.authenticated(new TeaPrincipal(UUID.randomUUID(), ORG.org(), "EXTERNAL__t__ord__o", 1, "192.0.2.9"));
		TeaRequestContext keyed = requests.context(request(TEA.toString(), own));
		assertSame(own, keyed.caller());
		assertEquals(TeaAuthState.AUTHENTICATED, keyed.caller().state());
		assertEquals("private, no-cache", TeaResponses.headersFor(keyed).getCacheControl());
	}

	@Test
	void anUnknownOrMissingBaseIsObjectUnknown() {
		for (String org : new String[] { UUID.randomUUID().toString(), "not-a-uuid", null }) {
			TeaRefusal r = assertThrows(TeaRefusal.class, () -> requests.context(request(org, null)));
			assertEquals(404, r.response().getStatusCode().value());
			assertEquals(TeaUnknownErrorType.OBJECT_UNKNOWN, r.response().getBody().getError());
		}
	}

	@Test
	void listingParametersAreValidated() {
		Set<TeaSortField> fields = Set.of(TeaSortField.NAME);
		assertEquals(new TeaRequests.Page(TeaSortField.NAME, TeaSortOrder.DESC), TeaRequests.page(25L, null, "name", fields, "desc"));
		assertEquals(TeaUnknownErrorType.INVALID_PAGE_TOKEN,
				assertThrows(TeaRefusal.class, () -> TeaRequests.page(25L, "", "name", fields, "asc")).response().getBody().getError());
		for (Long size : new Long[] { 0L, 101L }) {
			assertEquals(TeaUnknownErrorType.INVALID_REQUEST,
					assertThrows(TeaRefusal.class, () -> TeaRequests.page(size, null, "name", fields, "asc")).response().getBody().getError());
		}
		assertThrows(TeaRefusal.class, () -> TeaRequests.page(25L, null, "version", fields, "asc"));
		assertThrows(TeaRefusal.class, () -> TeaRequests.page(25L, null, "name", fields, "sideways"));
		assertTrue(TeaRequests.page(1L, null, "name", fields, "asc").sortField() == TeaSortField.NAME);
	}

	/** The typed sort field and order answer the same refusal text as the strings did. */
	@Test
	void anUnknownSortFieldOrOrderKeepsItsRefusalText() {
		assertEquals("sortField must be one of createdDate, releaseDate, version", assertThrows(TeaRefusal.class,
				() -> TeaRequests.page(25L, null, "bogus", ProductApiController.RELEASE_SORT_FIELDS, "asc")).response()
				.getBody().getMessage());
		assertEquals("sortField must be one of name", assertThrows(TeaRefusal.class,
				() -> TeaRequests.page(25L, null, "version", ComponentsApiController.SORT_FIELDS, "asc")).response()
				.getBody().getMessage());
		assertEquals("sortField must be one of name", assertThrows(TeaRefusal.class,
				() -> TeaRequests.page(25L, null, null, ProductsApiController.SORT_FIELDS, "asc")).response()
				.getBody().getMessage());
		assertEquals("sortOrder must be asc or desc", assertThrows(TeaRefusal.class,
				() -> TeaRequests.page(25L, null, "version", ProductReleaseApiController.COLLECTION_SORT_FIELDS, "DESC"))
				.response().getBody().getMessage());
		TeaRequests.Page p = TeaRequests.page(null, null, "releaseDate", ProductApiController.RELEASE_SORT_FIELDS, "desc");
		assertEquals(new TeaRequests.Page(TeaSortField.RELEASE_DATE, TeaSortOrder.DESC), p);
		assertTrue(p.descending());
	}

	// TEA-10, design 4.2 case 9: the download log reads the address as the rate limiter does
	@Test
	void theClientAddressIsTheFirstForwardedEntryElseTheRemoteAddress() {
		MockHttpServletRequest r = new MockHttpServletRequest("GET", "/tea/x/v1.0.0/artifact/y/1/download");
		r.setRemoteAddr("192.0.2.7");
		r.addHeader("X-Forwarded-For", " 203.0.113.9 , 10.0.0.1");
		assertEquals("203.0.113.9", TeaRequests.clientAddress(r));
		MockHttpServletRequest direct = new MockHttpServletRequest("GET", "/tea/x/v1.0.0/artifact/y/1/download");
		direct.setRemoteAddr("192.0.2.7");
		assertEquals("192.0.2.7", TeaRequests.clientAddress(direct));
	}

	// TEA-10 round 2, case 23: the download log keys a row only by a key of the base's organization
	@Test
	void theOwnPrincipalIsAValidKeyOfTheBasesOrganization() {
		TeaPrincipal own = new TeaPrincipal(UUID.randomUUID(), ORG.org(), "EXTERNAL__own__ord__1", 1, "192.0.2.1");
		TeaPrincipal foreign = new TeaPrincipal(UUID.randomUUID(), UUID.randomUUID(), "EXTERNAL__far__ord__1", 1, "192.0.2.1");
		assertEquals(Optional.of(own), new TeaRequestContext(ORG, TeaCaller.authenticated(own), null).ownPrincipal());
		assertEquals(Optional.empty(), new TeaRequestContext(ORG, TeaCaller.authenticated(foreign), null).ownPrincipal(),
				"a valid key of another organization");
		assertEquals(Optional.empty(), new TeaRequestContext(ORG, TeaCaller.anonymous(), null).ownPrincipal());
		assertEquals(Optional.empty(), new TeaRequestContext(ORG, TeaCaller.invalidToken("x"), null).ownPrincipal());
		assertEquals(Optional.empty(), new TeaRequestContext(ORG, new TeaCaller(TeaAuthState.AUTHENTICATED, null, null), null)
				.ownPrincipal(), "authenticated without a principal");
		assertEquals(Optional.empty(), new TeaRequestContext(ORG, new TeaCaller(TeaAuthState.ANONYMOUS, own, null), null)
				.ownPrincipal(), "a principal on a caller that is not authenticated");
	}
}
