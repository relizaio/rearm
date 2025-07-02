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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AcollectionData.ArtifactChangelog;
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

    public record BomInput(JsonNode bom, RebomOptions rebomOptions, UUID org) {}

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
    private RebomResponse uploadRebomRequest(JsonNode bomJson, RebomOptions rebomOptions, UUID org){
        String mutation = """
            mutation addBom ($bomInput: BomInput!) {
                addBom(bomInput: $bomInput) {
                    uuid
                    meta
                    bom
                    duplicate
                }
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        BomInput bomInput = new BomInput(bomJson, rebomOptions, org);
        variables.put("bomInput", bomInput);
        Map<String, Object> response = executeGraphQLQuery(mutation, variables).block();
        //TODO: deserialize bom response as an object of ArtifactUploadRseponseDTO
        ObjectMapper clonedMapper = Utils.OM.copy();
        // Configure the cloned ObjectMapper to ignore unknown properties
        clonedMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        RebomResponse bomResponse = clonedMapper.convertValue(response.get("addBom"), RebomResponse.class);
        return bomResponse;
    }
    public JsonNode findBomById(UUID bomSerialNumber, UUID org) throws JsonProcessingException{
        String query = """
            query bomById ($id: ID, $org: ID) {
                bomById(id: $id, org: $org)
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", bomSerialNumber.toString());
        variables.put("org", org.toString());
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        var br = response.get("bomById");
        JsonNode bomJson = Utils.OM.valueToTree(br);
        return bomJson;

    }
    public JsonNode findRawBomById(UUID bomSerialNumber, UUID org) throws JsonProcessingException{
        String query = """
            query rawBomId ($id: ID, $org: ID) {
                rawBomId(id: $id, org: $org)
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", bomSerialNumber.toString());
        variables.put("org", org.toString());
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        var br = response.get("rawBomId");
        JsonNode bomJson = Utils.OM.valueToTree(br);
        return bomJson;
    }
    
    public record BomMeta (String name, String group, String bomVersion, String hash, String belongsTo, Boolean tldOnly, String structure,
    		String notes, String stripBom, String serialNumber) {}
    
    public List<BomMeta> resolveBomMetas(UUID bomSerialNumber, UUID org) {
        String query = """
                query bomMetaBySerialNumber ($serialNumber: ID!, $org: ID!) {
                    bomMetaBySerialNumber(serialNumber: $serialNumber, org: $org) {
                   	    name
					    group
					    bomVersion
					    hash
					    belongsTo
					    tldOnly
					    serialNumber
                    }
                }""";
            
        Map<String, Object> variables = new HashMap<>();
        variables.put("serialNumber", bomSerialNumber.toString());
        variables.put("org", org.toString());
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        var br = response.get("bomMetaBySerialNumber");
        List<BomMeta> bomMetas = Utils.OM.convertValue(br, new TypeReference<List<BomMeta>>() {});
        return bomMetas;
    }

    public record DiffComponent (String purl, String version) {}
    
    public record DiffResult (List<DiffComponent> added, List<DiffComponent> removed) {}

    public ArtifactChangelog getArtifactChangelog(List<UUID> fromIds, List<UUID> toIds, UUID org){
        String query = """
        query bomDiff ($fromIds: [ID], $toIds: [ID], $org: ID!) {
            bomDiff(fromIds: $fromIds, toIds: $toIds, org: $org) {
               added{
                purl
                version
               }
               removed{
                purl
                version
               }
            }
        }""";
            
        Map<String, Object> variables = new HashMap<>();
        variables.put("fromIds", fromIds);
        variables.put("toIds", toIds);
        variables.put("org", org.toString());
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        var br = response.get("bomDiff");

        ArtifactChangelog diffResult = Utils.OM.convertValue(br, new TypeReference<ArtifactChangelog>() {});
        return diffResult;
    }
    public RebomResponse uploadSbom(JsonNode bomJson, RebomOptions rebomOverride, UUID org)  throws RelizaException{
        return uploadRebomRequest(bomJson, rebomOverride, org);
    }

    // public JsonNode mergeBoms(List<UUID> bomIds, RebomOptions rebomOptions) throws RelizaException{ 
    //     if(bomIds.size() < 1){
    //         throw new RelizaException("Merge Error: Need atleast two bomIds in the list to merge!");
    //     }
    //     String mergedBomString = mergeBomsRequest(bomIds, rebomOptions);
    //     JsonNode mergedBomJsonNode;
    //     try {
    //         mergedBomJsonNode = Utils.OM.readTree(mergedBomString);
    //     } catch (JsonProcessingException e) {
    //         throw new RelizaException(e.getMessage());
    //     }
    //     return mergedBomJsonNode;
    // }

    // private String mergeBomsRequest(List<UUID> bomIds, RebomOptions rebomOptions) {
    //     String query = """
    //         query mergeBoms ($ids: [ID]!, $rebomOptions: RebomOptions!) {
    //             mergeBoms(ids: $ids, rebomOptions: $rebomOptions)
    //         }""";
        
    //     Map<String, Object> variables = new HashMap<>();
    //     variables.put("ids", bomIds);
    //     variables.put("rebomOptions", rebomOptions);
       
    //     Map<String, Object> response = executeGraphQLQuery(query, variables).block();
    //     String mergedBom = Utils.OM.convertValue(response.get("mergeBoms"), String.class);
        
    //     return mergedBom;

    // }

    public UUID mergeAndStoreBoms(List<UUID> bomIds, RebomOptions rebomOptions, UUID org) {
        String query = """
            mutation mergeAndStoreBoms ($ids: [ID]!, $rebomOptions: RebomOptions!, $org: ID!) {
                mergeAndStoreBoms(ids: $ids, rebomOptions: $rebomOptions, org: $org){
                    uuid
                    meta
                }
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("ids", bomIds);
        variables.put("rebomOptions", rebomOptions);
        variables.put("org", org);
       
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        ObjectMapper clonedMapper = Utils.OM.copy();
        // Configure the cloned ObjectMapper to ignore unknown properties
        clonedMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
        @SuppressWarnings("unchecked")
        RebomResponse bomResponse = clonedMapper.convertValue(response.get("mergeAndStoreBoms"), RebomResponse.class);
        String bomSerialNumber = bomResponse.meta().serialNumber();
        if(bomSerialNumber.startsWith("urn")){
            bomSerialNumber = bomSerialNumber.replace("urn:uuid:","");
        }
        return UUID.fromString(bomSerialNumber);
    }
}
