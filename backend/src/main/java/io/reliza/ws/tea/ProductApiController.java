/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.util.UUID;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.tea.TeaProduct;
import io.reliza.service.GetComponentService;
import io.reliza.service.UserService;
import io.reliza.service.tea.TeaTransformerService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import org.springframework.web.context.request.NativeWebRequest;

import java.util.Optional;
import jakarta.annotation.Generated;

@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
@Controller
@RequestMapping("${openapi.transparencyExchange.base-path:/tea/v1}")
public class ProductApiController implements ProductApi {

    private final NativeWebRequest request;

	@Autowired
	GetComponentService getComponentService;
	
	@Autowired
	TeaTransformerService teaTransformerService;
	
    @Autowired
    public ProductApiController(NativeWebRequest request) {
        this.request = request;
    }

    @Override
    public Optional<NativeWebRequest> getRequest() {
        return Optional.ofNullable(request);
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


}
