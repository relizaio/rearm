/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import java.time.Duration;
import java.time.ZonedDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.dto.BearIntegrationDto;
import io.reliza.model.AnalysisScope;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.FindingSourceDto;
import io.reliza.model.dto.ReleaseMetricsDto.WeaknessDto;
import io.reliza.model.tea.Rebom.ParsedBom;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.model.tea.Rebom.RebomResponse;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Slf4j
@Service
public class RebomService {

    @Autowired
    @Lazy
    private VulnAnalysisService vulnAnalysisService;

	private final WebClient rebomWebClient;

    public enum BomStructureType {
		FLAT,
		HIERARCHICAL;
	}

    public enum BomMediaType {
        JSON,
        CSV,
        EXCEL;
    }
    
    public enum EnrichmentStatus {
        PENDING,
        COMPLETED,
        FAILED,
        SKIPPED
    }
    
    public RebomService(
		@Value("${relizaprops.rebom.url}") String rebomHost
	) {
		// rebom-backend is an @apollo/server v5 standalone server
		// (rearm/rebom-backend/src/index.ts:61, via startStandaloneServer);
		// its underlying Node http.Server defaults keepAliveTimeout to
		// 5 seconds and the standalone API does not expose a knob to raise
		// it. Without a matching cap on the WebClient side, Reactor Netty's
		// default ConnectionProvider keeps idle sockets indefinitely, then
		// reuses one that the server has already FIN'd — surfacing as
		// `PrematureCloseException: Connection prematurely closed BEFORE
		// response` on the next addArtifact / verifySignature call.
		// Capping maxIdleTime below the server's keepAliveTimeout forces
		// the client to close first so we never grab a half-closed socket.
		ConnectionProvider rebomPool = ConnectionProvider.builder("rebom")
			.maxIdleTime(Duration.ofSeconds(4))
			.maxLifeTime(Duration.ofMinutes(5))
			.evictInBackground(Duration.ofSeconds(15))
			.build();
		HttpClient rebomHttpClient = HttpClient.create(rebomPool);
		this.rebomWebClient = WebClient.builder()
                                .baseUrl(rebomHost + "graphql")
                                .clientConnector(new ReactorClientHttpConnector(rebomHttpClient))
                                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .exchangeStrategies(
                                    ExchangeStrategies.builder()
                                    .codecs(codecs -> codecs
                                        .defaultCodecs()
                                        .maxInMemorySize(50 * 1024 * 1024)
                                    ).build()
                                ).build();
	}

    public record BomInput(JsonNode bom, RebomOptions rebomOptions, BomFormat format, UUID org, String existingSerialNumber) {
        // Constructor without existingSerialNumber for backward compatibility
        public BomInput(JsonNode bom, RebomOptions rebomOptions, BomFormat format, UUID org) {
            this(bom, rebomOptions, format, org, null);
        }
    }

    public record GraphQLResponse<T>(T data, List<Object> errors) {}
    
    /**
     * Structured error codes from rebom-backend
     */
    public enum RebomErrorCode {
        BOM_VALIDATION_ERROR,
        BOM_STORAGE_ERROR,
        BOM_NOT_FOUND,
        BOM_CONVERSION_ERROR,
        BOM_MERGE_ERROR,
        OCI_STORAGE_ERROR,
        INTERNAL_ERROR,
        UNKNOWN
    }
    
    /**
     * Parse and handle structured errors from rebom-backend GraphQL response
     */
    private void handleRebomError(GraphQLResponse<?> response) throws RelizaException {
        if (response.errors() != null && !response.errors().isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.errors().get(0);
            String message = (String) error.get("message");
            
            // Extract structured error information
            @SuppressWarnings("unchecked")
            Map<String, Object> extensions = (Map<String, Object>) error.get("extensions");
            
            if (extensions != null) {
                String codeStr = (String) extensions.get("code");
                Object details = extensions.get("details");
                
                RebomErrorCode code = parseErrorCode(codeStr);
                
                // Log structured error with details
                log.warn("Rebom error [{}]: {} - Details: {}", code, message, details);
                
                // Throw specific exception based on error code
                switch (code) {
                    case BOM_VALIDATION_ERROR:
                        throw new RelizaException("BOM validation failed: " + message);
                    case BOM_STORAGE_ERROR:
                        throw new RelizaException("BOM storage failed: " + message);
                    case BOM_NOT_FOUND:
                        throw new RelizaException("BOM not found: " + message);
                    case BOM_CONVERSION_ERROR:
                        throw new RelizaException("BOM conversion failed: " + message);
                    case BOM_MERGE_ERROR:
                        throw new RelizaException("BOM merge failed: " + message);
                    case OCI_STORAGE_ERROR:
                        throw new RelizaException("OCI storage error: " + message);
                    case INTERNAL_ERROR:
                    case UNKNOWN:
                    default:
                        throw new RelizaException("Rebom error: " + message);
                }
            } else {
                // Fallback for errors without extensions
                log.warn("Rebom error (no extensions): {}", message);
                throw new RelizaException("Rebom error: " + message);
            }
        }
    }
    
    /**
     * Parse error code string to enum
     */
    private RebomErrorCode parseErrorCode(String codeStr) {
        if (codeStr == null) {
            return RebomErrorCode.UNKNOWN;
        }
        try {
            return RebomErrorCode.valueOf(codeStr);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown error code from rebom-backend: {}", codeStr);
            return RebomErrorCode.UNKNOWN;
        }
    }


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
                // Use structured error handler
                try {
                    handleRebomError(response);
                } catch (RelizaException e) {
                    throw new RuntimeException(e);
                }
                @SuppressWarnings("unchecked")
				Map<String, Object> gqlResp = (Map<String, Object>) response.data();
                return gqlResp;
            });
    }   
    private RebomResponse uploadRebomRequest(JsonNode bomJson, RebomOptions rebomOptions, BomFormat bomFormat, UUID org, String existingSerialNumber){
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
        BomInput bomInput = new BomInput(bomJson, rebomOptions, bomFormat, org, existingSerialNumber);
        variables.put("bomInput", bomInput);
        Map<String, Object> response = executeGraphQLQuery(mutation, variables).block();
        // Jackson 3: ObjectMapper is immutable and ObjectReader no longer
        // takes a tree node directly. Round-trip through a JSON string so
        // the per-call FAIL_ON_UNKNOWN_PROPERTIES toggle still applies.
        String addBomJson = Utils.OM.writeValueAsString(response.get("addBom"));
        RebomResponse bomResponse = Utils.OM
                .readerFor(RebomResponse.class)
                .without(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(addBomJson);
        return bomResponse;
    }
    public record EnrichedBomProbeResult(EnrichmentStatus status, String enrichedBom) {}

    /**
     * Calls rebom getBomDigestProbe to get the REARM digest for a BOM without storing it.
     */
    public String getBomDigestProbe(String bomContent, BomFormat format, UUID org) {
        String query = """
            query getBomDigestProbe ($bomContent: BomContentInput!) {
                getBomDigestProbe(bomContent: $bomContent)
            }""";
        Map<String, Object> bomContentInput = new HashMap<>();
        try {
            bomContentInput.put("bom", Utils.OM.readTree(bomContent));
        } catch (Exception e) {
            throw new RuntimeException("Invalid BOM JSON for digest probe: " + e.getMessage(), e);
        }
        bomContentInput.put("format", format != null ? format.name() : null);
        bomContentInput.put("org", org != null ? org.toString() : null);
        Map<String, Object> variables = new HashMap<>();
        variables.put("bomContent", bomContentInput);
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        return (String) response.get("getBomDigestProbe");
    }

    /**
     * Calls rebom getEnrichedBomProbe to enrich a BOM via BEAR.
     * Blocks until enrichment is complete (COMPLETED, SKIPPED, or FAILED).
     */
    public EnrichedBomProbeResult getEnrichedBomProbe(String bomContent, BomFormat format, UUID org) {
        String query = """
            query getEnrichedBomProbe ($bomContent: BomContentInput!) {
                getEnrichedBomProbe(bomContent: $bomContent) {
                    status
                    enrichedBom
                }
            }""";
        Map<String, Object> bomContentInput = new HashMap<>();
        try {
            bomContentInput.put("bom", Utils.OM.readTree(bomContent));
        } catch (Exception e) {
            throw new RuntimeException("Invalid BOM JSON for enrichment probe: " + e.getMessage(), e);
        }
        bomContentInput.put("format", format != null ? format.name() : null);
        bomContentInput.put("org", org != null ? org.toString() : null);
        Map<String, Object> variables = new HashMap<>();
        variables.put("bomContent", bomContentInput);
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("getEnrichedBomProbe");
        EnrichmentStatus status = EnrichmentStatus.valueOf((String) result.get("status"));
        String enrichedBom = (String) result.get("enrichedBom");
        return new EnrichedBomProbeResult(status, enrichedBom);
    }

    public JsonNode findBomByIdJson(UUID bomSerialNumber, UUID org) throws JacksonException{
        String query = """
            query bomById ($id: ID, $org: ID) {
                bomById(id: $id, org: $org)
            }
        """;
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", bomSerialNumber.toString());
        variables.put("org", org.toString());
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        var br = response.get("bomById");
        JsonNode bomJson = Utils.OM.valueToTree(br);
        return bomJson;
    }

    /**
     * Fetch parsed SBOM components and dependencies for a BOM identified by
     * UUID or serialNumber. A single round trip returns both arrays so the
     * backend can ingest components and edges from the same payload.
     */
    public ParsedBom parseBom(UUID bomSerialNumber, UUID org) {
        String query = """
            query parseBomById ($id: ID!, $org: ID!) {
                parseBomById(id: $id, org: $org) {
                    components {
                        canonicalPurl fullPurl type group name version isRoot
                        cpe licenses
                    }
                    dependencies {
                        sourceCanonicalPurl sourceFullPurl
                        targetCanonicalPurl targetFullPurl
                        relationshipType
                    }
                }
            }""";
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", bomSerialNumber.toString());
        variables.put("org", org.toString());
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        Object raw = response.get("parseBomById");
        if (raw == null) return new ParsedBom(List.of(), List.of());
        return Utils.OM.convertValue(raw, ParsedBom.class);
    }

    /**
     * Find augmented BOM by serialNumber and specific version.
     * Used for downloading historical versions of augmented BOMs.
     */
    public JsonNode findBomByVersion(UUID bomSerialNumber, UUID org, Integer version) throws JacksonException{
        String query = """
            query bomBySerialNumberAndVersion ($serialNumber: ID!, $version: Int!, $org: ID!, $raw: Boolean) {
                bomBySerialNumberAndVersion(serialNumber: $serialNumber, version: $version, org: $org, raw: $raw)
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("serialNumber", bomSerialNumber.toString());
        variables.put("version", version);
        variables.put("org", org.toString());
        variables.put("raw", false);  // false for augmented
        
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        var br = response.get("bomBySerialNumberAndVersion");
        JsonNode bomJson = Utils.OM.valueToTree(br);
        return bomJson;
    }

    public String findBomByIdCsv(UUID bomSerialNumber, UUID org) {
        String query = """
            query bomByIdCsv ($id: ID, $org: ID) {
                bomByIdCsv(id: $id, org: $org)
            }
        """;
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", bomSerialNumber.toString());
        variables.put("org", org.toString());
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        var br = response.get("bomByIdCsv");
        return br.toString();
    }

    public String findBomByIdExcel(UUID bomSerialNumber, UUID org) {
        String query = """
            query bomByIdExcel ($id: ID, $org: ID) {
                bomByIdExcel(id: $id, org: $org)
            }
        """;
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", bomSerialNumber.toString());
        variables.put("org", org.toString());
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        var br = response.get("bomByIdExcel");
        return br.toString();
    }

    public JsonNode findRawBomById(UUID bomSerialNumber, UUID org) throws JacksonException{
        return findRawBomById(bomSerialNumber, org, null);
    }

    /**
     * Find BOM by serialNumber and specific version.
     * Used for downloading historical versions of SPDX BOMs.
     */
    public JsonNode findRawBomByVersion(UUID bomSerialNumber, UUID org, Integer version) throws JacksonException{
        String query = """
            query bomBySerialNumberAndVersion ($serialNumber: ID!, $version: Int!, $org: ID!, $raw: Boolean) {
                bomBySerialNumberAndVersion(serialNumber: $serialNumber, version: $version, org: $org, raw: $raw)
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("serialNumber", bomSerialNumber.toString());
        variables.put("version", version);
        variables.put("org", org.toString());
        variables.put("raw", true);
        
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        var br = response.get("bomBySerialNumberAndVersion");
        JsonNode bomJson = Utils.OM.valueToTree(br);
        return bomJson;
    }

    /**
     * Finds a raw BOM by its ID and organization.
     * 
     * Note: rebom-backend manages its own OCI storage internally, including monthly repository rotation.
     * The ociRepositoryName is stored in the BOM's JSONB meta field and retrieved automatically by rebom-backend.
     * This method does not need to know about repository names - it only needs the BOM UUID.
     * 
     * @param bomSerialNumber The UUID of the BOM
     * @param org The organization UUID
     * @param format Optional BomFormat (CYCLONEDX or SPDX) - used for SPDX to retrieve original format vs converted
     * @return JsonNode containing the raw BOM data
     * @throws JacksonException if JSON processing fails
     */
    public JsonNode findRawBomById(UUID bomSerialNumber, UUID org, BomFormat format) throws JacksonException{
        String query;
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", bomSerialNumber.toString());
        variables.put("org", org.toString());
        
        if (format != null) {
            query = """
                query rawBomId ($id: ID, $org: ID, $format: BomFormat) {
                    rawBomId(id: $id, org: $org, format: $format)
                }""";
            variables.put("format", format);
        } else {
            query = """
                query rawBomId ($id: ID, $org: ID) {
                    rawBomId(id: $id, org: $org)
                }""";
        }
        
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        var br = response.get("rawBomId");
        JsonNode bomJson = Utils.OM.valueToTree(br);
        return bomJson;
    }
    
    public record BomMeta (String name, String group, String version, String bomVersion, String bomDigest, String belongsTo, Boolean tldOnly, Boolean ignoreDev, String structure,
    		String notes, String stripBom, String serialNumber, EnrichmentStatus enrichmentStatus, String enrichmentTimestamp, String enrichmentError, String lastUpdatedDate) {}
    
    public BomMeta getBomMetadataById(UUID bomId, UUID org) {
        String query = """
                query bomMetadataById ($id: ID!, $org: ID!) {
                    bomMetadataById(id: $id, org: $org) {
                        name
                        group
                        version
                        bomVersion
                        bomDigest
                        belongsTo
                        tldOnly
                        ignoreDev
                        structure
                        notes
                        stripBom
                        serialNumber
                        enrichmentStatus
                        enrichmentTimestamp
                        enrichmentError
                        lastUpdatedDate
                    }
                }""";
            
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", bomId.toString());
        variables.put("org", org.toString());
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        var br = response.get("bomMetadataById");
        if (br == null) {
            return null;
        }
        return Utils.OM.convertValue(br, BomMeta.class);
    }

    /**
     * Whether BEAR enrichment is configured for the org (rebom-side integration).
     * Drives the synthetic Dependency-Track gate and the enrichment puller: a
     * BEAR-configured org ships only enriched components and is drained by the
     * puller; a non-BEAR org ships every matchable component immediately and is
     * skipped by the puller.
     */
    public boolean isEnrichmentConfigured(UUID org) {
        String query = """
                query isEnrichmentConfigured ($org: ID!) {
                    isEnrichmentConfigured(org: $org)
                }""";
        Map<String, Object> variables = new HashMap<>();
        variables.put("org", org.toString());
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        Object val = response != null ? response.get("isEnrichmentConfigured") : null;
        return Boolean.TRUE.equals(val);
    }

    public List<BomMeta> resolveBomMetas(UUID bomSerialNumber, UUID org) {
        String query = """
                query bomMetaBySerialNumber ($serialNumber: ID!, $org: ID!) {
                    bomMetaBySerialNumber(serialNumber: $serialNumber, org: $org) {
                   	    name
					    group
					    bomVersion
					    bomDigest
					    belongsTo
					    tldOnly
					    ignoreDev
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

    public RebomResponse uploadSbom(JsonNode bomJson, RebomOptions rebomOverride, BomFormat bomFormat, UUID org)  throws RelizaException{
        return uploadRebomRequest(bomJson, rebomOverride, bomFormat, org, null);
    }
    
    /**
     * Upload SBOM with optional existingSerialNumber for SPDX updates.
     * When updating an existing SPDX artifact, pass the existing internalBom.id as existingSerialNumber
     * to maintain serialNumber continuity for DTrack and artifact identity.
     */
    public RebomResponse uploadSbom(JsonNode bomJson, RebomOptions rebomOverride, BomFormat bomFormat, UUID org, UUID existingSerialNumber)  throws RelizaException{
        String serialStr = existingSerialNumber != null ? existingSerialNumber.toString() : null;
        return uploadRebomRequest(bomJson, rebomOverride, bomFormat, org, serialStr);
    }

    // public JsonNode mergeBoms(List<UUID> bomIds, RebomOptions rebomOptions) throws RelizaException{ 
    //     if(bomIds.size() < 1){
    //         throw new RelizaException("Merge Error: Need atleast two bomIds in the list to merge!");
    //     }
    //     String mergedBomString = mergeBomsRequest(bomIds, rebomOptions);
    //     JsonNode mergedBomJsonNode;
    //     try {
    //         mergedBomJsonNode = Utils.OM.readTree(mergedBomString);
    //     } catch (JacksonException e) {
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
        String mergedJson = Utils.OM.writeValueAsString(response.get("mergeAndStoreBoms"));
        RebomResponse bomResponse = Utils.OM
                .readerFor(RebomResponse.class)
                .without(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(mergedJson);
        String bomSerialNumber = bomResponse.meta().serialNumber();
        if(bomSerialNumber.startsWith("urn")){
            bomSerialNumber = bomSerialNumber.replace("urn:uuid:","");
        }
        return UUID.fromString(bomSerialNumber);
    }

    public ReleaseMetricsDto parseSarifOnRebom(String sarifContent, ArtifactDto artifactDto) {
        UUID artifactUuid = artifactDto.getUuid();
        String query = """
            query parseSarifContent($sarifContent: String!) {
                parseSarifContent(sarifContent: $sarifContent) {
                    cweId
                    ruleId
                    location
                    fingerprint
                    severity
                }
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("sarifContent", sarifContent);
        
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        var weaknessesResponse = response.get("parseSarifContent");
        
        // Directly convert to WeaknessDto using a custom TypeReference
        List<Map<String, Object>> rawWeaknesses = Utils.OM.convertValue(weaknessesResponse, new TypeReference<List<Map<String, Object>>>() {});
        
        final FindingSourceDto source = new FindingSourceDto(artifactUuid, null, null);
        
        ReleaseMetricsDto rmd = new ReleaseMetricsDto();
        
        // Map raw response to WeaknessDto objects
        List<WeaknessDto> weaknessDtos = rawWeaknesses.stream()
            .map(w -> new WeaknessDto(
                (String) w.get("cweId"), 
                (String) w.get("ruleId"), 
                (String) w.get("location"), 
                (String) w.get("fingerprint"), 
                mapSeverity((String) w.get("severity")),
                Set.of(source),
                null,
                null,
                null
            ))
            .toList();
        
        rmd.setWeaknessDetails(weaknessDtos);
        // Mark scan as complete. Without these the UI artifact-row gate
        // (`row.metrics.firstScanned`) is false for direct-parse SARIF
        // artifacts that don't go through DTrack, so the row always shows
        // N/A in the Vulnerabilities & Weaknesses / Policy Violations
        // columns even after the SARIF has been parsed (including the
        // happy-path zero-findings case).
        ZonedDateTime now = ZonedDateTime.now();
        rmd.setFirstScanned(now);
        rmd.setLastScanned(now);
        vulnAnalysisService.processReleaseMetricsDto(artifactDto.getOrg(), artifactDto.getOrg(), AnalysisScope.ORG, rmd);
        return rmd;
    }

    public ReleaseMetricsDto parseCycloneDxContent(String vdrContent, ArtifactDto artifactDto) {
        UUID artifactUuid = artifactDto.getUuid();
        String query = """
            query parseCycloneDxContent($vdrContent: String!) {
                parseCycloneDxContent(vdrContent: $vdrContent) {
                    purl
                    vulnId
                    severity
                }
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("vdrContent", vdrContent);
        
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        var vulnsResponse = response.get("parseCycloneDxContent");
        
        List<Map<String, Object>> rawVulns = Utils.OM.convertValue(vulnsResponse, new TypeReference<List<Map<String, Object>>>() {});
        
        ReleaseMetricsDto rmd = new ReleaseMetricsDto();
        final FindingSourceDto source = new FindingSourceDto(artifactUuid, null, null);
        List<ReleaseMetricsDto.VulnerabilityDto> vulnerabilityDtos = rawVulns.stream()
            .map(v -> {
                String vulnId = (String) v.get("vulnId");
                ReleaseMetricsDto.VulnerabilitySeverity severity = mapSeverity((String) v.get("severity"));
                ReleaseMetricsDto.SeveritySourceDto severitySource = Utils.createSeveritySourceDto(vulnId, severity);
                
                return new ReleaseMetricsDto.VulnerabilityDto(
                    (String) v.get("purl"),
                    vulnId,
                    severity,
                    new LinkedHashSet<>(),
                    Set.of(source),
                    Set.of(severitySource),
                    null,
                    null,
                    null,
                    null, null, null, null, null
                );
            })
            .toList();
        rmd.setVulnerabilityDetails(vulnerabilityDtos);
        // Same temporal-marker fix as parseSarifOnRebom — see comment
        // there. Without these, direct-parse VDR/BOV artifacts (those
        // not routed through DTrack) leave firstScanned null and the
        // UI artifact-row columns render N/A even after parsing.
        ZonedDateTime now = ZonedDateTime.now();
        rmd.setFirstScanned(now);
        rmd.setLastScanned(now);
        vulnAnalysisService.processReleaseMetricsDto(artifactDto.getOrg(), artifactDto.getOrg(), AnalysisScope.ORG, rmd);
        return rmd;
    }

    private ReleaseMetricsDto.VulnerabilitySeverity mapSeverity(String severity) {
        if (severity == null) return ReleaseMetricsDto.VulnerabilitySeverity.UNASSIGNED;
        
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> ReleaseMetricsDto.VulnerabilitySeverity.CRITICAL;
            case "HIGH" -> ReleaseMetricsDto.VulnerabilitySeverity.HIGH;
            case "MEDIUM" -> ReleaseMetricsDto.VulnerabilitySeverity.MEDIUM;
            case "LOW" -> ReleaseMetricsDto.VulnerabilitySeverity.LOW;
            case "UNKNOWN" -> ReleaseMetricsDto.VulnerabilitySeverity.UNASSIGNED;
            default -> ReleaseMetricsDto.VulnerabilitySeverity.UNASSIGNED;
        };
    }
    
    public BearIntegrationDto getBearIntegration(UUID org) {
        String query = """
            query getBearIntegration($org: ID!) {
                getBearIntegration(org: $org) {
                    uri
                    configured
                    skipPatterns
                }
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("org", org.toString());
        
        Map<String, Object> response = executeGraphQLQuery(query, variables).block();
        var result = response.get("getBearIntegration");
        if (result == null) {
            return BearIntegrationDto.builder().configured(false).build();
        }
        return Utils.OM.convertValue(result, BearIntegrationDto.class);
    }
    
    public BearIntegrationDto setBearIntegration(UUID org, String uri, String apiKey, List<String> skipPatterns) {
        String mutation = """
            mutation setBearIntegration($org: ID!, $uri: String!, $apiKey: String!, $skipPatterns: [String]) {
                setBearIntegration(org: $org, uri: $uri, apiKey: $apiKey, skipPatterns: $skipPatterns) {
                    uri
                    configured
                    skipPatterns
                }
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("org", org.toString());
        variables.put("uri", uri);
        variables.put("apiKey", apiKey);
        variables.put("skipPatterns", skipPatterns);
        
        Map<String, Object> response = executeGraphQLQuery(mutation, variables).block();
        var result = response.get("setBearIntegration");
        if (result == null) {
            return BearIntegrationDto.builder().configured(false).build();
        }
        return Utils.OM.convertValue(result, BearIntegrationDto.class);
    }
    
    public BearIntegrationDto updateBearSkipPatterns(UUID org, List<String> skipPatterns) {
        String mutation = """
            mutation updateBearSkipPatterns($org: ID!, $skipPatterns: [String]) {
                updateBearSkipPatterns(org: $org, skipPatterns: $skipPatterns) {
                    uri
                    configured
                    skipPatterns
                }
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("org", org.toString());
        variables.put("skipPatterns", skipPatterns);
        
        Map<String, Object> response = executeGraphQLQuery(mutation, variables).block();
        var result = response.get("updateBearSkipPatterns");
        if (result == null) {
            return BearIntegrationDto.builder().configured(false).build();
        }
        return Utils.OM.convertValue(result, BearIntegrationDto.class);
    }
    
    public Boolean deleteBearIntegration(UUID org) {
        String mutation = """
            mutation deleteBearIntegration($org: ID!) {
                deleteBearIntegration(org: $org)
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("org", org.toString());
        
        Map<String, Object> response = executeGraphQLQuery(mutation, variables).block();
        var result = response.get("deleteBearIntegration");
        return result != null ? (Boolean) result : false;
    }
    
    public record EnrichmentTriggerResult(Boolean triggered, String message, UUID bomUuid) {}
    
    public EnrichmentTriggerResult triggerEnrichment(UUID bomId, UUID org) {
        String mutation = """
            mutation triggerEnrichment($id: ID!, $org: ID!, $force: Boolean) {
                triggerEnrichment(id: $id, org: $org, force: $force) {
                    triggered
                    message
                    bomUuid
                }
            }""";
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", bomId.toString());
        variables.put("org", org.toString());
        variables.put("force", true);
        
        Map<String, Object> response = executeGraphQLQuery(mutation, variables).block();
        var result = response.get("triggerEnrichment");
        if (result == null) {
            return new EnrichmentTriggerResult(false, "No response from rebom", null);
        }
        return Utils.OM.convertValue(result, EnrichmentTriggerResult.class);
    }

    public record SignatureVerifyResult(String verdict, String matchedFingerprint, String details) {}

    /**
     * Calls rebom-backend's verifySignature mutation. Signature +
     * payload bytes are passed inline base64. The trust store is the
     * format-appropriate aggregate of enrolled pubkeys (ssh
     * allowed_signers lines or ascii-armoured GPG blocks); the caller
     * builds it from {@code SigningKeyService.listByOrg}.
     *
     * Returns a verdict string matching the
     * {@link io.reliza.model.SignatureVerificationData.SignatureVerificationState}
     * enum names — {@code VERIFIED}, {@code INVALID_SIGNATURE},
     * {@code UNKNOWN_KEY}, {@code ERRORED}. Mapping to
     * KEY_REVOKED / WRONG_SIGNER is the caller's job (verifier
     * subprocess doesn't know our org's revocation state or the
     * expected-owner constraint).
     */
    public SignatureVerifyResult verifySignature(String format, String signatureB64, String payloadB64,
            String trustStoreB64, String expectedIdentity) {
        String mutation = """
            mutation verifySignature ($input: VerifySignatureInput!) {
                verifySignature(input: $input) {
                    verdict
                    matchedFingerprint
                    details
                }
            }""";
        Map<String, Object> input = new HashMap<>();
        input.put("format", format);
        input.put("signatureB64", signatureB64);
        input.put("payloadB64", payloadB64);
        input.put("trustStoreB64", trustStoreB64);
        if (expectedIdentity != null) input.put("expectedIdentity", expectedIdentity);
        Map<String, Object> variables = new HashMap<>();
        variables.put("input", input);
        Map<String, Object> response = executeGraphQLQuery(mutation, variables).block();
        var result = response.get("verifySignature");
        if (result == null) {
            return new SignatureVerifyResult("ERRORED", null, "No response from rebom");
        }
        return Utils.OM.convertValue(result, SignatureVerifyResult.class);
    }
}
