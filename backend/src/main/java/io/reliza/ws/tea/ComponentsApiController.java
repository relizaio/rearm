/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.NativeWebRequest;

import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.model.tea.TeaPaginatedComponentResponse;
import io.reliza.model.tea.TeaComponent;
import io.reliza.service.tea.TeaSurfaceService;
import io.reliza.service.tea.TeaSurfaceService.Head;
import io.reliza.service.tea.TeaTransformerService;
import io.reliza.ws.tea.TeaRequests.Page;
import io.reliza.ws.tea.TeaRequests.TeaRequestContext;
import io.reliza.ws.tea.TeaRequests.TeaSortField;
import lombok.extern.slf4j.Slf4j;

/** The components of the organization that have a published release the caller may see (task TEA-6). */
@Slf4j
@Controller
@RequestMapping(TeaPaths.ORG_BASE)
public class ComponentsApiController implements ComponentsApi {

	static final Set<TeaSortField> SORT_FIELDS = EnumSet.of(TeaSortField.NAME);

	@Autowired private TeaRequests teaRequests;
	@Autowired private TeaSurfaceService teaSurfaceService;
	@Autowired private TeaTransformerService teaTransformerService;

	private final NativeWebRequest request;

	@Autowired
	public ComponentsApiController(NativeWebRequest request) {
		this.request = request;
	}

	@Override
	public Optional<NativeWebRequest> getRequest() {
		return Optional.ofNullable(request);
	}

	@Override
	public ResponseEntity<TeaPaginatedComponentResponse> queryComponents(TeaIdentifierType idType, String idValue, Long pageSize,
			String pageToken, String sortField, String sortOrder) {
		TeaRequestContext ctx = teaRequests.context(request);
		TeaRendering.refuseInvalidToken(ctx);
		Page page = TeaRequests.page(pageSize, pageToken, sortField, SORT_FIELDS, sortOrder);
		List<Head> heads = TeaRendering.served(teaSurfaceService.visibleHeads(ctx.caller(), ctx.org(), ComponentType.COMPONENT));
		List<TeaComponent> components = TeaSurfaceService.componentsOf(heads).stream().map(teaTransformerService::transformComponentToTea).toList();
		components = TeaSurfaceService.filterByIdentifier(components, TeaComponent::getIdentifiers, idType, idValue);
		components = TeaSurfaceService.sorted(components, TeaComponent::getName, TeaComponent::getUuid, page.descending());
		TeaSurfaceService.warnIfLarge(ctx.org(), "/components", components.size());
		log.debug("TEA components of organization {}: caller {}, {} results", ctx.org().org(), ctx.caller().state(), components.size());
		return ResponseEntity.ok().headers(TeaResponses.headersFor(ctx)).body(new TeaPaginatedComponentResponse(false, components));
	}
}
