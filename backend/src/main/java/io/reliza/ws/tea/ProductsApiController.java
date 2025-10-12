/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import io.reliza.common.CommonVariables;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.model.tea.TeaPaginatedProductResponse;
import io.reliza.service.ComponentService;
import io.reliza.service.GetComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.tea.TeaTransformerService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import org.springframework.web.context.request.NativeWebRequest;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.annotation.Generated;

@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
@Controller
@RequestMapping("${openapi.transparencyExchange.base-path:/tea/v0.2.0-beta.2}")
public class ProductsApiController implements ProductsApi {
	
	@Autowired
	ComponentService componentService;
	
	@Autowired
	TeaTransformerService teaTransformerService;

    private final NativeWebRequest request;

    @Autowired
    public ProductsApiController(NativeWebRequest request) {
        this.request = request;
    }

    @Override
    public Optional<NativeWebRequest> getRequest() {
        return Optional.ofNullable(request);
    }
    
    @Override
    public ResponseEntity<TeaPaginatedProductResponse> queryTeaProducts(
            @Parameter(name = "pageOffset", description = "Pagination offset", in = ParameterIn.QUERY) @Valid @RequestParam(value = "pageOffset", required = false, defaultValue = "0") Long pageOffset,
            @Parameter(name = "pageSize", description = "Pagination offset", in = ParameterIn.QUERY) @Valid @RequestParam(value = "pageSize", required = false, defaultValue = "100") Long pageSize,
            @Parameter(name = "idType", description = "Type of identifier specified in the `idValue` parameter", in = ParameterIn.QUERY) @Valid @RequestParam(value = "idType", required = false) @Nullable TeaIdentifierType idType,
            @Parameter(name = "idValue", description = "If present, only the objects with the given identifier value will be returned.", in = ParameterIn.QUERY) @Valid @RequestParam(value = "idValue", required = false) @Nullable String idValue
        ) {
    	var products = componentService.listComponentDataByOrganization(UserService.USER_ORG, ComponentType.PRODUCT);
    	var teaProducts = products.stream().map(p -> teaTransformerService.transformProductToTea(p)).toList();
    	TeaPaginatedProductResponse tppr = new TeaPaginatedProductResponse();
    	tppr.setPageSize(pageSize);
    	tppr.setPageStartIndex(pageOffset);
    	tppr.setTotalResults(Long.valueOf(teaProducts.size()));
    	tppr.setResults(teaProducts);
    	tppr.setTimestamp(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    	return ResponseEntity.ok(tppr);
    }

}
