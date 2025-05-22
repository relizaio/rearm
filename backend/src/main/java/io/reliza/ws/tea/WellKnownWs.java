/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
public class WellKnownWs {

    @GetMapping("/.well-known/tea/{uuid}")
    public void teaDiscoveryRedirect (
    	@RequestHeader HttpHeaders headers,
        @PathVariable("uuid") UUID uuid,
        ServletWebRequest request,
        HttpServletResponse response
    ) throws IOException {
        response.sendRedirect("/tea/v0.1.0-beta.1/product/" + uuid);
    }
}
