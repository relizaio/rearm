/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import io.reliza.service.TeaAuthorizationService;
import io.reliza.service.TeaAuthorizationService.TeaAuthState;
import io.reliza.service.TeaAuthorizationService.TeaCaller;
import io.reliza.service.TeaAuthorizationService.TeaPrincipal;
import io.reliza.service.tea.TeaSurfaceService;
import io.reliza.service.tea.TeaSurfaceService.TeaOrg;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The request side of every TEA 1.0.0 controller (task TEA-6): the organization of the base, read
 * from the matched path template, and the caller the TEA authentication filter classified, as this
 * organization sees it (task TEA-18: a valid token of another organization is the anonymous caller).
 */
@Component
public class TeaRequests {

	/** The organization of the base, the caller, and the servlet request. */
	public record TeaRequestContext(TeaOrg org, TeaCaller caller, HttpServletRequest raw) {

		/**
		 * The caller's principal when it is a valid key of the base's organization (task TEA-10, the
		 * download log); empty for an anonymous caller, an invalid token and a valid key of another
		 * organization, which this organization records alike.
		 */
		public Optional<TeaPrincipal> ownPrincipal() {
			if (null == org || null == caller || TeaAuthState.AUTHENTICATED != caller.state() || null == caller.principal()) {
				return Optional.empty();
			}
			return org.org().equals(caller.principal().org()) ? Optional.of(caller.principal()) : Optional.empty();
		}
	}

	@Autowired private TeaSurfaceService teaSurfaceService;
	@Autowired private TeaAuthorizationService teaAuthorizationService;

	/** Throws a 404 OBJECT_UNKNOWN refusal for an unknown, malformed or archived base; nothing else is read first. */
	public TeaRequestContext context(NativeWebRequest request) {
		String segment = orgSegment(request);
		TeaOrg org = teaSurfaceService.orgOfBase(segment).orElseThrow(TeaRefusal::objectUnknown);
		HttpServletRequest raw = null == request ? null : request.getNativeRequest(HttpServletRequest.class);
		return new TeaRequestContext(org, TeaAuthorizationService.callerOn(teaAuthorizationService.callerOf(raw), org.org()), raw);
	}

	@SuppressWarnings("unchecked")
	static String orgSegment(NativeWebRequest request) {
		if (null == request) return null;
		Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
		if (!(vars instanceof Map<?, ?> m)) return null;
		Object v = ((Map<String, Object>) m).get(TeaPaths.ORG_VARIABLE);
		return v instanceof String s ? s : null;
	}

	/**
	 * The client address as the rate limiter reads it (task TEA-10, the download log): the first
	 * X-Forwarded-For entry, trimmed, else the remote address.
	 */
	public static String clientAddress(HttpServletRequest request) {
		if (null == request) return null;
		String forwarded = request.getHeader("X-Forwarded-For");
		if (null != forwarded && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
		return request.getRemoteAddr();
	}

	/**
	 * The {@code sortField} values of the TEA 1.0.0 {@code sort-field-*} parameters, by their spec
	 * strings; each listing operation allows its own subset. The generated API interfaces take the
	 * parameter as a String, which {@link #page} reads into this type once it is validated.
	 */
	public enum TeaSortField {
		NAME("name"), CREATED_DATE("createdDate"), RELEASE_DATE("releaseDate"), VERSION("version");

		private final String wire;

		TeaSortField(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		/** The field of a spec string; null for anything else. */
		static TeaSortField ofWire(String s) {
			for (TeaSortField f : values()) {
				if (f.wire.equals(s)) return f;
			}
			return null;
		}
	}

	/** The {@code sortOrder} values of the TEA 1.0.0 {@code sort-order} parameter, by their spec strings. */
	public enum TeaSortOrder {
		ASC("asc"), DESC("desc");

		private final String wire;

		TeaSortOrder(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		/** The order of a spec string; null for anything else. */
		static TeaSortOrder ofWire(String s) {
			for (TeaSortOrder o : values()) {
				if (o.wire.equals(s)) return o;
			}
			return null;
		}
	}

	/** The listing parameters of 1.0.0 that this server validates: one page, no token ever issued. */
	public record Page(TeaSortField sortField, TeaSortOrder sortOrder) {

		public boolean descending() {
			return TeaSortOrder.DESC == sortOrder;
		}
	}

	/**
	 * Validates the listing parameters: a page token, present in any form, is refused (none was ever
	 * issued); the page size must be 1 to 100 and is not applied (the page holds every result); the sort
	 * field and order must be the operation's.
	 */
	public static Page page(Long pageSize, String pageToken, String sortField, Set<TeaSortField> sortFields,
			String sortOrder) {
		if (null != pageToken) throw TeaRefusal.invalidPageToken();
		if (null != pageSize && (pageSize < 1 || pageSize > 100)) {
			throw TeaRefusal.invalidRequest("pageSize must be between 1 and 100");
		}
		TeaSortField field = TeaSortField.ofWire(sortField);
		if (null == field || !sortFields.contains(field)) {
			throw TeaRefusal.invalidRequest("sortField must be one of "
					+ String.join(", ", sortFields.stream().map(TeaSortField::wire).sorted().toList()));
		}
		TeaSortOrder order = TeaSortOrder.ofWire(sortOrder);
		if (null == order) {
			throw TeaRefusal.invalidRequest("sortOrder must be asc or desc");
		}
		return new Page(field, order);
	}
}
