/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;


import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class DownloadableArtifactService {

    private final String registryHost;
    private final String url;
    private final WebClient webClient;
    private final String ociRepository;

    public DownloadableArtifactService(
		@Value("${relizaprops.ociArtifacts.registry}") String registryHost,
		@Value("${relizaprops.ociArtifacts.namespace}") String registryNamespace,
        @Value("${relizaprops.ociArtifacts.serviceUrl}") String url
	) {
		this.registryHost = registryHost;
        this.url= url;
        this.ociRepository = registryNamespace + "/downloadable-artifacts";
		// Configure WebClient with increased buffer size for large OCI artifacts
		ExchangeStrategies strategies = ExchangeStrategies.builder()
			.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(50 * 1024 * 1024)) // 50MB buffer
			.build();
		
		this.webClient = WebClient.builder()
			.baseUrl(this.url)
			.exchangeStrategies(strategies)
			.build();
	}

    public Mono<ResponseEntity<byte[]>> downloadArtifact(ArtifactData ad) throws Exception{
        var tags = ad.getTags();
        Boolean isDownloadable = tags.stream().anyMatch(t -> t.key().equals(CommonVariables.DOWNLOADABLE_ARTIFACT) && t.value().equalsIgnoreCase("true"));

        if(!isDownloadable)
            throw new RelizaException("No Downloadable object associated with artifact: " + ad.getUuid().toString());
        
        String tagValue = tags.stream().filter((TagRecord t) -> t.key().equals(CommonVariables.TAG_FIELD)).findFirst().get().value();
        String mediaType = tags.stream().filter((TagRecord t) -> t.key().equals(CommonVariables.MEDIA_TYPE_FIELD)).findFirst().get().value();
        String fileName = tags.stream().filter((TagRecord t) -> t.key().equals(CommonVariables.FILE_NAME_FIELD)).findFirst().get().value();
        
        String resolvedFileName = StringUtils.isNotEmpty(fileName) ? fileName : tagValue;
        return this.webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/pull")
                    .queryParam("registry", this.registryHost)
                    .queryParam("repo",this.ociRepository)
                    .queryParam("tag", ad.getDigests().toArray()[0])
                    .build()
                )
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .bodyToMono(byte[].class)
                .map(data -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(mediaType))
                        .header("Content-Disposition", "attachment; filename=\"" + resolvedFileName + "\"")
                        .body(data));
    }
}
