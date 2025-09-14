/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import io.reliza.model.tea.TeaCollection;
import io.reliza.service.AcollectionService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.tea.TeaTransformerService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import org.springframework.web.context.request.NativeWebRequest;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Generated;

@Generated(value = "io.reliza.codegen.languages.SpringCodegen", date = "2025-05-08T09:03:56.085827200-04:00[America/Toronto]", comments = "Generator version: 7.13.0")
@Controller
@RequestMapping("${openapi.transparencyExchange.base-path:/tea/v0.1.0-beta.1}")
@Slf4j
public class ReleaseApiController implements ReleaseApi {

	@Autowired
	AcollectionService acollectionService;
	
	@Autowired
	SharedReleaseService sharedReleaseService;
	
	@Autowired
	TeaTransformerService teaTransformerService;
	
    private final NativeWebRequest request;

    @Autowired
    public ReleaseApiController(NativeWebRequest request) {
        this.request = request;
    }

    @Override
    public Optional<NativeWebRequest> getRequest() {
        return Optional.ofNullable(request);
    }
    
    @Override
    public ResponseEntity<List<TeaCollection>> getCollectionsByReleaseId(
            @Parameter(name = "uuid", description = "UUID of TEA Release in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid
        ) {
    		var release = sharedReleaseService.getReleaseData(uuid);
    		if (release.isEmpty() || !UserService.USER_ORG.equals(release.get().getOrg())) {
            	return ResponseEntity.notFound().build();
    		} else {
	    		var collections = acollectionService.getAcollectionDatasOfRelease(uuid);
	    		var teaCollections = collections.stream().map(c -> teaTransformerService.transformAcollectionToTea(c)).toList();
	    		return ResponseEntity.ok(teaCollections);
    		}
     }
    
    
    @Override
    public ResponseEntity<TeaCollection> getLatestCollection(
    		@Parameter(name = "uuid", description = "UUID of TEA Release in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid
        ) {
    		var release = sharedReleaseService.getReleaseData(uuid);
    		if (release.isEmpty() || !UserService.USER_ORG.equals(release.get().getOrg())) {
            	return ResponseEntity.notFound().build();
    		} else {
	    		var collections = acollectionService.getAcollectionDatasOfRelease(uuid);
	    		if (collections.isEmpty()) {
	    			log.warn("Empty collections list return for release = " + uuid);
	    			return ResponseEntity.notFound().build();
	    		} else {
	    			var teaCollection = teaTransformerService.transformAcollectionToTea(collections.get(0));
		    		return ResponseEntity.ok(teaCollection);	
	    		}
    		}
     }
    
    @Override
    public ResponseEntity<TeaCollection> getCollection(
            @Parameter(name = "uuid", description = "UUID of TEA Collection in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid,
            @Parameter(name = "version", description = "Version of TEA Collection", required = true, in = ParameterIn.PATH) @PathVariable("version") Integer version
        ) {
    		var release = sharedReleaseService.getReleaseData(uuid);
    		if (release.isEmpty() || !UserService.USER_ORG.equals(release.get().getOrg())) {
            	return ResponseEntity.notFound().build();
    		} else {
	    		var collections = acollectionService.getAcollectionDatasOfRelease(uuid);
	    		if (collections.isEmpty()) {
	    			log.warn("Empty collections list return for release = " + uuid);
	    			return ResponseEntity.notFound().build();
	    		} else {
	    			Long verLong = Long.valueOf(version);
	    			var colVer = collections.stream().filter(c -> c.getVersion() == verLong).findFirst();
	    			if (colVer.isEmpty()) {
	    				return ResponseEntity.notFound().build();
	    			} else {
	    				var teaCollection = teaTransformerService.transformAcollectionToTea(colVer.get());
			    		return ResponseEntity.ok(teaCollection);	
	    			}
	    		}
    		}
     }


}
