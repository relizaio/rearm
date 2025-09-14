package io.reliza.ws.tea;

import io.reliza.model.tea.TeaCollection;
import io.reliza.model.tea.TeaProductRelease;
import io.reliza.service.AcollectionService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.tea.TeaTransformerService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import java.util.UUID;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.context.request.NativeWebRequest;

import jakarta.validation.constraints.*;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.annotation.Generated;

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-09-13T12:58:45.490102-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
@Controller
@RequestMapping("${openapi.transparencyExchange.base-path:/tea/v0.1.0-beta.1}")
@Slf4j
public class ProductReleaseApiController implements ProductReleaseApi {

	@Autowired
	private AcollectionService acollectionService;
	
	@Autowired
	private SharedReleaseService sharedReleaseService;
	
	@Autowired
	private TeaTransformerService teaTransformerService;
	
    private final NativeWebRequest request;

    @Autowired
    public ProductReleaseApiController(NativeWebRequest request) {
        this.request = request;
    }

    @Override
    public Optional<NativeWebRequest> getRequest() {
        return Optional.ofNullable(request);
    }
    
    @Override
    public ResponseEntity<TeaCollection> getCollectionForProductRelease(
            @Parameter(name = "uuid", description = "UUID of TEA Product Release in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid,
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
    
    @Override
    public ResponseEntity<List<TeaCollection>> getCollectionsByProductReleaseId(
            @Parameter(name = "uuid", description = "UUID of TEA Product Release in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid
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
    public ResponseEntity<TeaCollection> getLatestCollectionForProductRelease(
    		@Parameter(name = "uuid", description = "UUID of TEA Product Release in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid
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
    public ResponseEntity<TeaProductRelease> getTeaProductReleaseByUuid(
            @Parameter(name = "uuid", description = "UUID of TEA Product Release in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid
        ) {
			var release = sharedReleaseService.getReleaseData(uuid);
			if (release.isEmpty() || !UserService.USER_ORG.equals(release.get().getOrg())) {
	        	return ResponseEntity.notFound().build();
			} else {
	    		var teaProductRelease = teaTransformerService.transformProductReleaseToTea(release.get());
		    	return ResponseEntity.ok(teaProductRelease);
			}
        }
}
