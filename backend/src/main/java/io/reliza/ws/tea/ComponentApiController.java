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
import io.reliza.model.tea.TeaComponent;
import io.reliza.model.tea.TeaRelease;
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

@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
@Controller
@RequestMapping("${openapi.transparencyExchange.base-path:/tea/v1}")
public class ComponentApiController implements ComponentApi {

	@Autowired
	GetComponentService getComponentService;
	
	@Autowired
	TeaTransformerService teaTransformerService;
	
	@Autowired
	SharedReleaseService sharedReleaseService;
	
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
    public ResponseEntity<List<TeaRelease>> getReleasesByComponentId(
            @Parameter(name = "uuid", description = "UUID of TEA Component in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid
        ) {
    		var ocd = getComponentService.getComponentData(uuid);
    		if (ocd.isEmpty() || ocd.get().getType() != ComponentType.COMPONENT || !UserService.USER_ORG.equals(ocd.get().getOrg())) {
	        	return ResponseEntity.notFound().build();
	        } else {
	    		var releases = sharedReleaseService.listReleaseDatasOfComponent(uuid, 300, 0); // TODO - TEA - pagination
	    		var teaReleases = releases.stream().map(rd -> teaTransformerService.transformReleaseToTea(rd)).toList();
	    		return ResponseEntity.ok(teaReleases);
	        }

        }
    
    @Override
    public ResponseEntity<TeaComponent> getTeaComponentById(
            @Parameter(name = "uuid", description = "UUID of TEA Component in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid
        ) {
	        var ocd = getComponentService.getComponentData(uuid);
	        if (ocd.isEmpty() || ocd.get().getType() != ComponentType.COMPONENT || !UserService.USER_ORG.equals(ocd.get().getOrg())) {
	        	return ResponseEntity.notFound().build();
	        } else {
	        	TeaComponent tc = teaTransformerService.transformComponentToTea(ocd.get());
	        	return ResponseEntity.ok(tc);
	        }
        }
}
