/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.RelizaObject;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetComponentService;
import io.reliza.service.SharedArtifactService;
import io.reliza.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
public class ArtifactWs {

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private SharedArtifactService sharedArtifactService;
    
	@Autowired
	GetComponentService getComponentService;

    
    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private UserService userService;

    @GetMapping("api/manual/v1/artifact/{uuid}/download")
    public Mono<ResponseEntity<byte[]>> downloadArtifact(
    	@RequestHeader HttpHeaders headers,
        @PathVariable("uuid") UUID uuid,
        ServletWebRequest request,
        @AuthenticationPrincipal OAuth2User oAuth2User,
        HttpServletResponse response
    ) throws Exception {
        JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        var oud = userService.getUserDataByAuth(auth);
		Optional<ArtifactData> oad = artifactService.getArtifactData(uuid);
        log.debug("oad is present? {}", oad.isPresent());
        log.debug("oad is  {}", oad.get());
		RelizaObject ro = oad.isPresent() ? oad.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		
		if (response.isCommitted()) return null;
		if (oad.isEmpty()) {
            throw new RelizaException("Artifact not found; uuid: " + uuid.toString());
        }
        
        return sharedArtifactService.downloadArtifact(oad.get());
        
    }
    @GetMapping("api/manual/v1/artifact/{uuid}/rawdownload")
    public Mono<ResponseEntity<byte[]>> downloadRawArtifact(
    	@RequestHeader HttpHeaders headers,
        @PathVariable("uuid") UUID uuid,
        ServletWebRequest request,
        @AuthenticationPrincipal OAuth2User oAuth2User,
        HttpServletResponse response
    ) throws Exception {
        JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        var oud = userService.getUserDataByAuth(auth);
		Optional<ArtifactData> oad = artifactService.getArtifactData(uuid);
        log.debug("oad is present? {}", oad.isPresent());
        log.debug("oad is  {}", oad.get());
		RelizaObject ro = oad.isPresent() ? oad.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		
		if (response.isCommitted()) return null;
		if (oad.isEmpty()) {
            throw new RelizaException("Artifact not found; uuid: " + uuid.toString());
        }
        
        return sharedArtifactService.downloadRawArtifact(oad.get());
        
    }
    
}
