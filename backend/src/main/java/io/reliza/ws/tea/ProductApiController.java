/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.NativeWebRequest;

import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.tea.TeaCle;
import io.reliza.model.tea.TeaPaginatedProductReleaseResponse;
import io.reliza.model.tea.TeaProduct;
import io.reliza.model.tea.TeaProductRelease;
import io.reliza.service.tea.TeaSurfaceService;
import io.reliza.service.tea.TeaSurfaceService.Head;
import io.reliza.service.tea.TeaSurfaceService.TeaObject;
import io.reliza.service.tea.TeaTransformerService;
import io.reliza.ws.tea.TeaRequests.Page;
import io.reliza.ws.tea.TeaRequests.TeaRequestContext;
import io.reliza.ws.tea.TeaRequests.TeaSortField;
import lombok.extern.slf4j.Slf4j;

/** A TEA product, its releases and its CLE (task TEA-6), from published releases only. */
@Slf4j
@Controller
@RequestMapping(TeaPaths.ORG_BASE)
public class ProductApiController implements ProductApi {

	static final Set<TeaSortField> RELEASE_SORT_FIELDS = EnumSet.of(TeaSortField.CREATED_DATE, TeaSortField.RELEASE_DATE,
			TeaSortField.VERSION);

	@Autowired private TeaRequests teaRequests;
	@Autowired private TeaSurfaceService teaSurfaceService;
	@Autowired private TeaTransformerService teaTransformerService;
	@Autowired private TeaRendering teaRendering;

	private final NativeWebRequest request;

	@Autowired
	public ProductApiController(NativeWebRequest request) {
		this.request = request;
	}

	@Override
	public Optional<NativeWebRequest> getRequest() {
		return Optional.ofNullable(request);
	}

	@Override
	public ResponseEntity<TeaProduct> getProduct(UUID uuid) {
		TeaRequestContext ctx = teaRequests.context(request);
		TeaObject o = TeaRendering.served(teaSurfaceService.product(ctx.caller(), ctx.org(), uuid));
		log.debug("TEA product of organization {}: caller {}", ctx.org().org(), ctx.caller().state());
		return ResponseEntity.ok().headers(TeaResponses.headersFor(ctx)).body(teaTransformerService.transformProductToTea(o.component()));
	}

	@Override
	public ResponseEntity<TeaPaginatedProductReleaseResponse> listProductReleases(UUID uuid, Long pageSize, String pageToken,
			String sortField, String sortOrder) {
		TeaRequestContext ctx = teaRequests.context(request);
		TeaRendering.refuseInvalidToken(ctx);
		Page page = TeaRequests.page(pageSize, pageToken, sortField, RELEASE_SORT_FIELDS, sortOrder);
		TeaObject o = TeaRendering.served(teaSurfaceService.product(ctx.caller(), ctx.org(), uuid));
		List<Head> heads = TeaRendering.served(teaSurfaceService.visibleHeadsOfComponent(ctx.caller(), ctx.org(), o.component().getUuid()));
		List<TeaProductRelease> results = heads.stream().map(h -> teaRendering.productRelease(ctx, h)).toList();
		TeaSurfaceService.warnIfLarge(ctx.org(), "/product/{uuid}/releases", results.size());
		log.debug("TEA product releases of organization {}: caller {}, {} results", ctx.org().org(), ctx.caller().state(), results.size());
		return ResponseEntity.ok().headers(TeaResponses.headersFor(ctx))
				.body(new TeaPaginatedProductReleaseResponse(false, ProductReleasesApiController.sortReleases(results, page)));
	}

	@Override
	public ResponseEntity<TeaCle> getProductCle(UUID uuid) {
		TeaRequestContext ctx = teaRequests.context(request);
		TeaObject o = TeaRendering.served(teaSurfaceService.product(ctx.caller(), ctx.org(), uuid));
		ComponentData cd = o.component();
		List<ReleaseData> releases = o.servedHeads().stream().map(Head::release).toList();
		return ResponseEntity.ok().headers(TeaResponses.headersFor(ctx)).body(teaTransformerService.transformComponentOrProductToCle(cd, releases));
	}
}
