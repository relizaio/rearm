/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.NativeWebRequest;

import io.reliza.model.ComponentData;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.tea.TeaComponentRelease;
import io.reliza.model.tea.TeaProductRelease;
import io.reliza.service.TeaAuthorizationService.TeaAccess;
import io.reliza.service.TeaAuthorizationService.TeaAuthState;
import io.reliza.service.TeaPublicationService;
import io.reliza.service.tea.TeaSurfaceService;
import io.reliza.service.tea.TeaSurfaceService.Decision;
import io.reliza.service.tea.TeaSurfaceService.Head;
import io.reliza.service.tea.TeaTransformerService;
import io.reliza.ws.tea.TeaRequests.TeaRequestContext;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the TEA 1.0.0 controllers share (task TEA-6): rendering a release head for the caller,
 * writing a with-collection object and the stored collection documents byte for byte, with the
 * strong ETag and conditional GET of a stored document.
 */
@Slf4j
@Component
public class TeaRendering {

	@Autowired private TeaSurfaceService teaSurfaceService;
	@Autowired private TeaTransformerService teaTransformerService;
	@Autowired private TeaPublicationService teaPublicationService;

	/** The value of a served decision; the refusal otherwise. */
	static <T> T served(Decision<T> d) {
		if (!d.served()) throw TeaRefusal.of(d.refusal());
		return d.value();
	}

	/** A listing refuses an invalid token before anything is read. */
	static void refuseInvalidToken(TeaRequestContext ctx) {
		if (null != ctx.caller() && TeaAuthState.INVALID_TOKEN == ctx.caller().state()) throw TeaRefusal.of(TeaAccess.INVALID_TOKEN);
	}

	public TeaProductRelease productRelease(TeaRequestContext ctx, Head h) {
		ComponentData product = h.exposure().governingComponent();
		return teaTransformerService.transformProductReleaseToTea(h.publication(), h.release(), product,
				teaTransformerService.componentRefs(h.release(), product,
						child -> TeaAccess.SERVE == teaSurfaceService.accessTo(ctx.caller(), ctx.org(), child)));
	}

	public TeaComponentRelease componentRelease(Head h) {
		return teaTransformerService.transformComponentReleaseToTea(h.publication(), h.release(), h.exposure().governingComponent());
	}

	/**
	 * Writes the release object with {@code latestCollection} set to the stored latest document,
	 * parsed (field order kept) rather than re-rendered. A PUBLISHED head without a latest
	 * collection is logged and concealed, never a 500.
	 */
	public void writeWithCollection(NativeWebRequest request, TeaRequestContext ctx, Object release, UUID publication) {
		Optional<TeaPublishedCollectionData> latest = teaPublicationService.getLatestCollection(publication);
		if (latest.isEmpty() || null == latest.get().getDocument()) {
			log.error("TEA publication {} is PUBLISHED but has no latest collection; answered as unknown", publication);
			throw TeaRefusal.objectUnknown();
		}
		ObjectNode node = TeaJson.MAPPER.valueToTree(release);
		node.set("latestCollection", TeaJson.MAPPER.readTree(latest.get().getDocument()));
		TeaRawJson.write(request, HttpStatus.OK.value(), TeaResponses.headersFor(ctx), TeaJson.MAPPER.writeValueAsString(node));
	}

	/** Every stored collection version of the publication, verbatim, in version order. */
	public void writeCollections(NativeWebRequest request, TeaRequestContext ctx, UUID publication, boolean descending) {
		Comparator<TeaPublishedCollectionData> byVersion = Comparator.comparingInt(TeaPublishedCollectionData::getVersion);
		List<String> docs = teaPublicationService.listCollections(publication).stream()
				.sorted(descending ? byVersion.reversed() : byVersion).map(TeaPublishedCollectionData::getDocument).toList();
		TeaRawJson.write(request, HttpStatus.OK.value(), TeaResponses.headersFor(ctx), TeaRawJson.page(docs));
	}

	/** One stored collection version (null: the latest), verbatim, with its sha256 as the strong ETag. */
	public void writeCollection(NativeWebRequest request, TeaRequestContext ctx, UUID publication, Integer version) {
		Optional<TeaPublishedCollectionData> c;
		if (null == version) {
			c = teaPublicationService.getLatestCollection(publication);
			if (c.isEmpty()) log.error("TEA publication {} is PUBLISHED but has no latest collection; answered as unknown", publication);
		} else {
			c = version < 1 ? Optional.empty() : teaPublicationService.getCollection(publication, version);
		}
		if (c.isEmpty() || null == c.get().getDocument()) throw TeaRefusal.objectUnknown();
		HttpHeaders headers = TeaResponses.headersFor(ctx);
		String etag = TeaResponses.etagOf(c.get().getDocumentSha256());
		headers.setETag(etag);
		String ifNoneMatch = null == ctx.raw() ? null : ctx.raw().getHeader(HttpHeaders.IF_NONE_MATCH);
		if (TeaResponses.ifNoneMatchMatches(ifNoneMatch, etag)) {
			TeaRawJson.notModified(request, headers);
			return;
		}
		TeaRawJson.write(request, HttpStatus.OK.value(), headers, c.get().getDocument());
	}
}
