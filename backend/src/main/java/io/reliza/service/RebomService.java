/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.model.tea.Rebom.RebomResponse;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class RebomService {
	private final WebClient rebomWebClient;

    public enum BomStructureType {
		FLAT,
		HIERARCHICAL;
	}
    
    public RebomService(
		@Value("${relizaprops.rebom.url}") String rebomHost
	) {
			
		this.rebomWebClient = WebClient.builder()
                                .baseUrl(rebomHost + "graphql")
                                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .exchangeStrategies(
                                    ExchangeStrategies.builder()
                                    .codecs(codecs -> codecs
                                        .defaultCodecs()
                                        .maxInMemorySize(5000 * 1024)
                                    ).build()
                                ).build();
	}

    public record BomInput(JsonNode bom, RebomOptions rebomOptions) {}

    public record GraphQLResponse<T>(T data, List<Object> errors) {}


    private Mono<Map<String, Object>> executeGraphQLQuery(String query, Map<String, Object> variables) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", query);
        requestBody.put("variables", variables);
        // log.info("executing graphql query: {}", variables);
        return rebomWebClient.post()
            .bodyValue(requestBody)
            .retrieve()
            .onStatus(httpStatus -> httpStatus.is4xxClientError() || httpStatus.is5xxServerError(), clientResponse -> 
                Mono.error(new RuntimeException("Error: " + clientResponse.statusCode()))
            )
            .bodyToMono(GraphQLResponse.class)
            .map(response -> {
                if (response.errors() != null && !response.errors().isEmpty()) {
                    throw new RuntimeException("GraphQL failed: " + response.errors());
                }
                @SuppressWarnings("unchecked")
				Map<String, Object> gqlResp = (Map<String, Object>) response.data();
                return gqlResp;
            });
    }   
    private RebomResponse uploadRebomRequest(JsonNode bomJson, RebomOptions rebomOptions){
        String mutation = """
            mutation addBom ($bomInput: BomInput!) {
                addBom(bomInput: $bomInput) {
                    uuid
                    meta
                    bom
                }
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        BomInput bomInput = new BomInput(bomJson, rebomOptions);
        variables.put("bomInput", bomInput);
        Map<String, Object> response = executeGraphQLQuery(mutation, variables).block();
        //TODO: deserialize bom response as an object of ArtifactUploadRseponseDTO
        ObjectMapper clonedMapper = Utils.OM.copy();
        // Configure the cloned ObjectMapper to ignore unknown properties
        clonedMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        RebomResponse bomResponse = clonedMapper.convertValue(response.get("addBom"), RebomResponse.class);
        return bomResponse;
    }
    public JsonNode findBomById(UUID bomId) throws JsonProcessingException{
        String query = """
            query bomById ($id: ID) {
                bomById(id: $id)
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", bomId.toString());
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        var br = response.get("bomById");
        JsonNode bomJson = Utils.OM.valueToTree(br);
        return bomJson;

    }


    public RebomResponse uploadSbom(JsonNode bomJson, RebomOptions rebomOverride)  throws RelizaException{
        return uploadRebomRequest(bomJson, rebomOverride);
    }

    public JsonNode mergeBoms(List<UUID> bomIds, RebomOptions rebomOptions) throws RelizaException{ 
        if(bomIds.size() < 1){
            throw new RelizaException("Merge Error: Need atleast two bomIds in the list to merge!");
        }
        String mergedBomString = mergeBomsRequest(bomIds, rebomOptions);
        JsonNode mergedBomJsonNode;
        try {
            mergedBomJsonNode = Utils.OM.readTree(mergedBomString);
        } catch (JsonProcessingException e) {
            throw new RelizaException(e.getMessage());
        }
        return mergedBomJsonNode;
    }

    private String mergeBomsRequest(List<UUID> bomIds, RebomOptions rebomOptions) {
        String query = """
            query mergeBoms ($ids: [ID]!, $rebomOptions: RebomOptions!) {
                mergeBoms(ids: $ids, rebomOptions: $rebomOptions)
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("ids", bomIds);
        variables.put("rebomOptions", rebomOptions);
       
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        String mergedBom = Utils.OM.convertValue(response.get("mergeBoms"), String.class);
        
        return mergedBom;

    }

    public UUID mergeAndStoreBoms(List<UUID> bomIds, RebomOptions rebomOptions) {
        String query = """
            mutation mergeAndStoreBoms ($ids: [ID]!, $rebomOptions: RebomOptions!) {
                mergeAndStoreBoms(ids: $ids, rebomOptions: $rebomOptions){
                    uuid
                    meta
                }
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("ids", bomIds);
        variables.put("rebomOptions", rebomOptions);
       
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        @SuppressWarnings("unchecked")
		Map<String, Object> bomResponse = Utils.OM.convertValue(response.get("mergeAndStoreBoms"), Map.class);
        return UUID.fromString(bomResponse.get("uuid").toString());
    }
}
