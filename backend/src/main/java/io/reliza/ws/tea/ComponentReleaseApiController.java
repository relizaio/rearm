/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.tea.TeaCle;
import io.reliza.model.tea.TeaCollection;
import io.reliza.model.tea.TeaPaginatedCollectionResponse;
import jakarta.validation.Valid;
import io.reliza.model.tea.TeaComponentReleaseWithCollection;
import io.reliza.service.AcollectionService;
import io.reliza.service.GetComponentService;
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
@RequestMapping("${openapi.transparencyExchange.base-path:/tea/v0.4.0}")
@Slf4j
public class ComponentReleaseApiController implements ComponentReleaseApi {

	@Autowired
	private AcollectionService acollectionService;
	
	@Autowired
	private SharedReleaseService sharedReleaseService;
	
	@Autowired
	private TeaTransformerService teaTransformerService;
	
	@Autowired
	private GetComponentService getComponentService;
	
    private final NativeWebRequest request;

    @Autowired
    public ComponentReleaseApiController(NativeWebRequest request) {
        this.request = request;
    }

    @Override
    public Optional<NativeWebRequest> getRequest() {
        return Optional.ofNullable(request);
    }
    
    @Override
    public ResponseEntity<TeaPaginatedCollectionResponse> getCollectionsByReleaseId(
            @Parameter(name = "uuid", description = "UUID of TEA Component Release in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid,
            @Parameter(name = "pageSize", description = "The maximum number of results to return.", in = ParameterIn.QUERY) @Valid @RequestParam(value = "pageSize", required = false, defaultValue = "25") Long pageSize,
            @Parameter(name = "pageToken", description = "An opaque token used to retrieve the next page of results.", in = ParameterIn.QUERY) @Valid @RequestParam(value = "pageToken", required = false) @Nullable String pageToken,
            @Parameter(name = "sortField", description = "The field by which to sort the results.", in = ParameterIn.QUERY) @Valid @RequestParam(value = "sortField", required = false, defaultValue = "version") String sortField,
            @Parameter(name = "sortOrder", description = "The direction of the sort.", in = ParameterIn.QUERY) @Valid @RequestParam(value = "sortOrder", required = false, defaultValue = "asc") String sortOrder
        ) {
    		var release = sharedReleaseService.getReleaseData(uuid);
    		if (release.isEmpty() || !UserService.USER_ORG.equals(release.get().getOrg())) {
            	return ResponseEntity.notFound().build();
    		} else {
	    		var collections = acollectionService.getAcollectionDatasOfRelease(uuid);
	    		var teaCollections = collections.stream().map(c -> teaTransformerService.transformAcollectionToTea(c)).toList();
	    		var page = TeaPaginationUtil.slice(teaCollections, pageToken, pageSize);
	    		TeaPaginatedCollectionResponse resp = new TeaPaginatedCollectionResponse();
	    		resp.setResults(page.items());
	    		resp.setHasNext(page.hasNext());
	    		resp.setNextPageToken(page.nextPageToken());
	    		return ResponseEntity.ok(resp);
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
            @Parameter(name = "collectionVersion", description = "Version of TEA Collection", required = true, in = ParameterIn.PATH) @PathVariable("collectionVersion") Integer collectionVersion
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
	    			Long verLong = Long.valueOf(collectionVersion);
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
    public ResponseEntity<TeaCle> getCleByComponentReleaseId(
            @Parameter(name = "uuid", description = "UUID of TEA Component Release in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid
        ) {
		var release = sharedReleaseService.getReleaseData(uuid);
		if (release.isEmpty() || !UserService.USER_ORG.equals(release.get().getOrg())) {
			return ResponseEntity.notFound().build();
		}
		TeaCle cle = teaTransformerService.transformReleaseToCle(release.get());
		return ResponseEntity.ok(cle);
    }

    @Override
    public ResponseEntity<TeaComponentReleaseWithCollection> getComponentReleaseById(
            @Parameter(name = "uuid", description = "UUID of TEA Component Release in the TEA server", required = true, in = ParameterIn.PATH) @PathVariable("uuid") UUID uuid
        ) {
		var release = sharedReleaseService.getReleaseData(uuid);
		if (release.isEmpty() || !UserService.USER_ORG.equals(release.get().getOrg())) {
        	return ResponseEntity.notFound().build();
		} else {
			ComponentData cd = getComponentService.getComponentData(release.get().getComponent()).get();
			if (cd.getType() != ComponentType.COMPONENT) {
				return ResponseEntity.notFound().build();
			} else {
				var tcr = teaTransformerService.transformComponentReleaseWithCollectionToTea(release.get());
				return ResponseEntity.ok(tcr);
			}
		}
    }
}
