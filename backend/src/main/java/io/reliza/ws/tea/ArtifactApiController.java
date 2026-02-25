/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.util.UUID;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import io.reliza.model.tea.TeaArtifact;
import io.reliza.service.ArtifactService;
import io.reliza.service.UserService;
import io.reliza.service.tea.TeaTransformerService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import org.springframework.web.context.request.NativeWebRequest;

import java.util.Optional;
import jakarta.annotation.Generated;

@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
@Controller
@RequestMapping("${openapi.transparencyExchange.base-path:/tea/v0.2.0-beta.2}")
public class ArtifactApiController implements ArtifactApi {

	@Autowired
	ArtifactService artifactService;
	
	@Autowired
	TeaTransformerService teaTransformerService;
	
    private final NativeWebRequest request;

    @Autowired
    public ArtifactApiController(NativeWebRequest request) {
        this.request = request;
    }

    @Override
    public Optional<NativeWebRequest> getRequest() {
        return Optional.ofNullable(request);
    }
    
    @Override
    public ResponseEntity<TeaArtifact> getArtifact(
            @Parameter(name = "uuid", description = "UUID of TEA Artifact in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid
        ) {
    		
        var oad = artifactService.getArtifactData(uuid);
        if (oad.isEmpty() || !UserService.USER_ORG.equals(oad.get().getOrg())) {
        	return ResponseEntity.notFound().build();
        } else {
        	TeaArtifact ta = teaTransformerService.transformArtifactToTea(oad.get());
        	return ResponseEntity.ok(ta);
        }
    }

}
