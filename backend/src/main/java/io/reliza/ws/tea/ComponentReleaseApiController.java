/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.NativeWebRequest;

import io.reliza.model.tea.TeaCle;
import io.reliza.model.tea.TeaCollection;
import io.reliza.model.tea.TeaPaginatedCollectionResponse;
import io.reliza.model.tea.TeaComponentReleaseWithCollection;
import io.reliza.service.tea.TeaSurfaceService;
import io.reliza.service.tea.TeaSurfaceService.Head;
import io.reliza.service.tea.TeaTransformerService;
import io.reliza.ws.tea.TeaRequests.Page;
import io.reliza.ws.tea.TeaRequests.TeaRequestContext;
import lombok.extern.slf4j.Slf4j;

/**
 * A published TEA component release, its CLE and its collections (task TEA-6). Collections are the
 * documents TEA-5 stored, written byte for byte; the methods that write them return null.
 */
@Slf4j
@Controller
@RequestMapping(TeaPaths.ORG_BASE)
public class ComponentReleaseApiController implements ComponentReleaseApi {


	@Autowired private TeaRequests teaRequests;
	@Autowired private TeaSurfaceService teaSurfaceService;
	@Autowired private TeaTransformerService teaTransformerService;
	@Autowired private TeaRendering teaRendering;

	private final NativeWebRequest request;

	@Autowired
	public ComponentReleaseApiController(NativeWebRequest request) {
		this.request = request;
	}

	@Override
	public Optional<NativeWebRequest> getRequest() {
		return Optional.ofNullable(request);
	}

	private Head head(TeaRequestContext ctx, UUID uuid) {
		return TeaRendering.served(teaSurfaceService.componentRelease(ctx.caller(), ctx.org(), uuid));
	}

	@Override
	public ResponseEntity<TeaComponentReleaseWithCollection> getComponentRelease(UUID uuid) {
		TeaRequestContext ctx = teaRequests.context(request);
		Head h = head(ctx, uuid);
		teaRendering.writeWithCollection(request, ctx, teaRendering.componentRelease(h), h.publication().getUuid());
		return null;
	}

	@Override
	public ResponseEntity<TeaCle> getComponentReleaseCle(UUID uuid) {
		TeaRequestContext ctx = teaRequests.context(request);
		Head h = head(ctx, uuid);
		return ResponseEntity.ok().headers(TeaResponses.headersFor(ctx)).body(teaTransformerService.transformReleaseToCle(h.release()));
	}

	@Override
	public ResponseEntity<TeaPaginatedCollectionResponse> listComponentReleaseCollections(UUID uuid, Long pageSize,
			String pageToken, String sortField, String sortOrder) {
		TeaRequestContext ctx = teaRequests.context(request);
		Page page = TeaRequests.page(pageSize, pageToken, sortField, ProductReleaseApiController.COLLECTION_SORT_FIELDS, sortOrder);
		Head h = head(ctx, uuid);
		teaRendering.writeCollections(request, ctx, h.publication().getUuid(), page.descending());
		return null;
	}

	@Override
	public ResponseEntity<TeaCollection> getLatestComponentReleaseCollection(UUID uuid) {
		TeaRequestContext ctx = teaRequests.context(request);
		Head h = head(ctx, uuid);
		teaRendering.writeCollection(request, ctx, h.publication().getUuid(), null);
		return null;
	}

	@Override
	public ResponseEntity<TeaCollection> getComponentReleaseCollectionByVersion(UUID uuid, Integer collectionVersion) {
		TeaRequestContext ctx = teaRequests.context(request);
		Head h = head(ctx, uuid);
		teaRendering.writeCollection(request, ctx, h.publication().getUuid(), collectionVersion);
		return null;
	}
}
