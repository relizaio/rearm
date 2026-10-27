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
import io.reliza.model.tea.TeaPaginatedComponentReleaseResponse;
import io.reliza.model.tea.TeaComponentRelease;
import io.reliza.service.tea.TeaSurfaceService;
import io.reliza.service.tea.TeaSurfaceService.Head;
import io.reliza.ws.tea.TeaRequests.Page;
import io.reliza.ws.tea.TeaRequests.TeaRequestContext;
import lombok.extern.slf4j.Slf4j;

/** The published component releases of the organization the caller may see (task TEA-6). */
@Slf4j
@Controller
@RequestMapping(TeaPaths.ORG_BASE)
public class ComponentReleasesApiController implements ComponentReleasesApi {

	@Autowired private TeaRequests teaRequests;
	@Autowired private TeaSurfaceService teaSurfaceService;
	@Autowired private TeaRendering teaRendering;

	private final NativeWebRequest request;

	@Autowired
	public ComponentReleasesApiController(NativeWebRequest request) {
		this.request = request;
	}

	@Override
	public Optional<NativeWebRequest> getRequest() {
		return Optional.ofNullable(request);
	}

	@Override
	public ResponseEntity<TeaPaginatedComponentReleaseResponse> queryComponentReleases(TeaIdentifierType idType, String idValue,
			Long pageSize, String pageToken, String sortField, String sortOrder) {
		TeaRequestContext ctx = teaRequests.context(request);
		TeaRendering.refuseInvalidToken(ctx);
		Page page = TeaRequests.page(pageSize, pageToken, sortField, ProductApiController.RELEASE_SORT_FIELDS, sortOrder);
		List<Head> heads = TeaRendering.served(teaSurfaceService.visibleHeads(ctx.caller(), ctx.org(), ComponentType.COMPONENT));
		List<TeaComponentRelease> results = heads.stream().map(teaRendering::componentRelease).toList();
		results = TeaSurfaceService.filterByIdentifier(results, TeaComponentRelease::getIdentifiers, idType, idValue);
		TeaSurfaceService.warnIfLarge(ctx.org(), "/componentReleases", results.size());
		log.debug("TEA component releases of organization {}: caller {}, {} results", ctx.org().org(), ctx.caller().state(), results.size());
		return ResponseEntity.ok().headers(TeaResponses.headersFor(ctx)).body(new TeaPaginatedComponentReleaseResponse(false, sortReleases(results, page)));
	}

	static List<TeaComponentRelease> sortReleases(List<TeaComponentRelease> results, Page page) {
		return switch (page.sortField()) {
			case RELEASE_DATE -> TeaSurfaceService.sorted(results, TeaComponentRelease::getReleaseDate, TeaComponentRelease::getUuid, page.descending());
			case VERSION -> TeaSurfaceService.sorted(results, TeaComponentRelease::getVersion, TeaComponentRelease::getUuid, page.descending());
			case CREATED_DATE, NAME -> TeaSurfaceService.sorted(results, TeaComponentRelease::getCreatedDate, TeaComponentRelease::getUuid, page.descending());
		};
	}
}
