/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.util.UUID;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.tea.TeaCle;
import io.reliza.model.tea.TeaPaginatedProductReleaseResponse;
import io.reliza.model.tea.TeaProduct;
import io.reliza.model.tea.TeaProductRelease;
import io.reliza.service.GetComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.tea.TeaTransformerService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import org.springframework.web.context.request.NativeWebRequest;

import java.util.List;
import java.util.Optional;
import jakarta.annotation.Generated;
import jakarta.validation.Valid;

@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
@Controller
@RequestMapping("${openapi.transparencyExchange.base-path:/tea/v0.4.0}")
public class ProductApiController implements ProductApi {

    @Autowired
	private GetComponentService getComponentService;
	
	@Autowired
	private TeaTransformerService teaTransformerService;
	
	@Autowired
	private SharedReleaseService sharedReleaseService;

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
        public ResponseEntity<TeaPaginatedProductReleaseResponse> getReleasesByProductId(
                @Parameter(name = "uuid", description = "UUID of TEA Product in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid,
                @Parameter(name = "pageSize", description = "The maximum number of results to return.", in = ParameterIn.QUERY) @Valid @RequestParam(value = "pageSize", required = false, defaultValue = "25") Long pageSize,
                @Parameter(name = "pageToken", description = "An opaque token used to retrieve the next page of results.", in = ParameterIn.QUERY) @Valid @RequestParam(value = "pageToken", required = false) @Nullable String pageToken,
                @Parameter(name = "sortField", description = "The field by which to sort the results.", in = ParameterIn.QUERY) @Valid @RequestParam(value = "sortField", required = false, defaultValue = "createdDate") String sortField,
                @Parameter(name = "sortOrder", description = "The direction of the sort.", in = ParameterIn.QUERY) @Valid @RequestParam(value = "sortOrder", required = false, defaultValue = "asc") String sortOrder
            ) {
        var ocd = getComponentService.getComponentData(uuid);
        if (ocd.isEmpty() || ocd.get().getType() != ComponentType.PRODUCT || !UserService.USER_ORG.equals(ocd.get().getOrg())) {
            return ResponseEntity.notFound().build();
        } else {
            long size = TeaPaginationUtil.effectiveSize(pageSize);
            long offset = TeaPaginationUtil.decodeOffset(pageToken);
            var releases = sharedReleaseService.listReleaseDatasOfComponent(uuid, (int) (size + 1), (int) offset);
            var teaProductReleases = releases.stream().map(rd -> teaTransformerService.transformProductReleaseToTea(rd)).toList();
            var page = TeaPaginationUtil.fromOverfetch(teaProductReleases, offset, size);
        	TeaPaginatedProductReleaseResponse tpprr = new TeaPaginatedProductReleaseResponse();
        	tpprr.setResults(page.items());
        	tpprr.setHasNext(page.hasNext());
        	tpprr.setNextPageToken(page.nextPageToken());
            return ResponseEntity.ok(tpprr);
        }
    }

    @Override
    public ResponseEntity<TeaProduct> getTeaProductByUuid(
            @Parameter(name = "uuid", description = "UUID of the TEA product in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid
        ) {
            var opd = getComponentService.getComponentData(uuid);
            if (opd.isEmpty() || opd.get().getType() != ComponentType.PRODUCT  || !UserService.USER_ORG.equals(opd.get().getOrg())) {
            	return ResponseEntity.notFound().build();
            } else {
            	TeaProduct tp = teaTransformerService.transformProductToTea(opd.get());
            	return ResponseEntity.ok(tp);
            }

        }

    @Override
    public ResponseEntity<TeaCle> getCleByProductId(
            @Parameter(name = "uuid", description = "UUID of TEA Product in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid
        ) {
        var opd = getComponentService.getComponentData(uuid);
        if (opd.isEmpty() || opd.get().getType() != ComponentType.PRODUCT || !UserService.USER_ORG.equals(opd.get().getOrg())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(teaTransformerService.transformProductToCle(opd.get()));
    }


}
