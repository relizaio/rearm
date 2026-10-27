/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.NativeWebRequest;

import io.reliza.model.ReleaseData;
import io.reliza.model.tea.TeaCle;
import io.reliza.model.tea.TeaComponent;
import io.reliza.model.tea.TeaComponentRelease;
import io.reliza.model.tea.TeaPaginatedComponentReleaseResponse;
import io.reliza.service.tea.TeaSurfaceService;
import io.reliza.service.tea.TeaSurfaceService.Head;
import io.reliza.service.tea.TeaSurfaceService.TeaObject;
import io.reliza.service.tea.TeaTransformerService;
import io.reliza.ws.tea.TeaRequests.Page;
import io.reliza.ws.tea.TeaRequests.TeaRequestContext;
import lombok.extern.slf4j.Slf4j;

/** A TEA component, its releases and its CLE (task TEA-6), from published releases only. */
@Slf4j
@Controller
@RequestMapping(TeaPaths.ORG_BASE)
public class ComponentApiController implements ComponentApi {

	@Autowired private TeaRequests teaRequests;
	@Autowired private TeaSurfaceService teaSurfaceService;
	@Autowired private TeaTransformerService teaTransformerService;
	@Autowired private TeaRendering teaRendering;

	private final NativeWebRequest request;

	@Autowired
	public ComponentApiController(NativeWebRequest request) {
		this.request = request;
	}

	@Override
	public Optional<NativeWebRequest> getRequest() {
		return Optional.ofNullable(request);
	}

	@Override
	public ResponseEntity<TeaComponent> getComponent(UUID uuid) {
		TeaRequestContext ctx = teaRequests.context(request);
		TeaObject o = TeaRendering.served(teaSurfaceService.component(ctx.caller(), ctx.org(), uuid));
		log.debug("TEA component of organization {}: caller {}", ctx.org().org(), ctx.caller().state());
		return ResponseEntity.ok().headers(TeaResponses.headersFor(ctx)).body(teaTransformerService.transformComponentToTea(o.component()));
	}

	@Override
	public ResponseEntity<TeaPaginatedComponentReleaseResponse> listComponentReleases(UUID uuid, Long pageSize,
			String pageToken, String sortField, String sortOrder) {
		TeaRequestContext ctx = teaRequests.context(request);
		TeaRendering.refuseInvalidToken(ctx);
		Page page = TeaRequests.page(pageSize, pageToken, sortField, ProductApiController.RELEASE_SORT_FIELDS, sortOrder);
		TeaObject o = TeaRendering.served(teaSurfaceService.component(ctx.caller(), ctx.org(), uuid));
		List<Head> heads = TeaRendering.served(teaSurfaceService.visibleHeadsOfComponent(ctx.caller(), ctx.org(), o.component().getUuid()));
		List<TeaComponentRelease> results = heads.stream().map(teaRendering::componentRelease).toList();
		TeaSurfaceService.warnIfLarge(ctx.org(), "/component/{uuid}/releases", results.size());
		log.debug("TEA component releases of organization {}: caller {}, {} results", ctx.org().org(), ctx.caller().state(), results.size());
		return ResponseEntity.ok().headers(TeaResponses.headersFor(ctx))
				.body(new TeaPaginatedComponentReleaseResponse(false, ComponentReleasesApiController.sortReleases(results, page)));
	}

	@Override
	public ResponseEntity<TeaCle> getComponentCle(UUID uuid) {
		TeaRequestContext ctx = teaRequests.context(request);
		TeaObject o = TeaRendering.served(teaSurfaceService.component(ctx.caller(), ctx.org(), uuid));
		List<ReleaseData> releases = o.servedHeads().stream().map(Head::release).toList();
		return ResponseEntity.ok().headers(TeaResponses.headersFor(ctx))
				.body(teaTransformerService.transformComponentOrProductToCle(o.component(), releases));
	}
}
