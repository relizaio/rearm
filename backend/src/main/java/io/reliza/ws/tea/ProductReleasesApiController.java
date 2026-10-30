/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.NativeWebRequest;

import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.model.tea.TeaPaginatedProductReleaseResponse;
import io.reliza.model.tea.TeaProductRelease;
import io.reliza.service.tea.TeaSurfaceService;
import io.reliza.service.tea.TeaSurfaceService.Head;
import io.reliza.ws.tea.TeaRequests.Page;
import io.reliza.ws.tea.TeaRequests.TeaRequestContext;
import lombok.extern.slf4j.Slf4j;

/** The published product releases of the organization the caller may see (task TEA-6). */
@Slf4j
@Controller
@RequestMapping(TeaPaths.ORG_BASE)
public class ProductReleasesApiController implements ProductReleasesApi {

	@Autowired private TeaRequests teaRequests;
	@Autowired private TeaSurfaceService teaSurfaceService;
	@Autowired private TeaRendering teaRendering;

	private final NativeWebRequest request;

	@Autowired
	public ProductReleasesApiController(NativeWebRequest request) {
		this.request = request;
	}

	@Override
	public Optional<NativeWebRequest> getRequest() {
		return Optional.ofNullable(request);
	}

	@Override
	public ResponseEntity<TeaPaginatedProductReleaseResponse> queryProductReleases(TeaIdentifierType idType, String idValue,
			Long pageSize, String pageToken, String sortField, String sortOrder) {
		TeaRequestContext ctx = teaRequests.context(request);
		TeaRendering.refuseInvalidToken(ctx);
		Page page = TeaRequests.page(pageSize, pageToken, sortField, ProductApiController.RELEASE_SORT_FIELDS, sortOrder);
		List<Head> heads = TeaRendering.served(teaSurfaceService.visibleHeads(ctx.caller(), ctx.org(), ComponentType.PRODUCT));
		List<TeaProductRelease> results = heads.stream().map(h -> teaRendering.productRelease(ctx, h)).toList();
		results = TeaSurfaceService.filterByIdentifier(results, TeaProductRelease::getIdentifiers, idType, idValue);
		TeaSurfaceService.warnIfLarge(ctx.org(), "/productReleases", results.size());
		log.debug("TEA product releases of organization {}: caller {}, {} results", ctx.org().org(), ctx.caller().state(), results.size());
		return ResponseEntity.ok().headers(TeaResponses.headersFor(ctx)).body(new TeaPaginatedProductReleaseResponse(false, sortReleases(results, page)));
	}

	static List<TeaProductRelease> sortReleases(List<TeaProductRelease> results, Page page) {
		return switch (page.sortField()) {
			case RELEASE_DATE -> TeaSurfaceService.sorted(results, TeaProductRelease::getReleaseDate, TeaProductRelease::getUuid, page.descending());
			case VERSION -> TeaSurfaceService.sorted(results, TeaProductRelease::getVersion, TeaProductRelease::getUuid, page.descending());
			case CREATED_DATE, NAME -> TeaSurfaceService.sorted(results, TeaProductRelease::getCreatedDate, TeaProductRelease::getUuid, page.descending());
		};
	}
}
