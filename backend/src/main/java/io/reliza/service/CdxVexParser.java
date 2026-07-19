/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.vulnerability.Vulnerability;
import org.cyclonedx.parsers.JsonParser;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;

import io.reliza.common.Utils;
import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisResponse;
import io.reliza.model.AnalysisState;
import io.reliza.model.SourceFormat;
import io.reliza.model.dto.CdxVexParseResult;
import io.reliza.model.dto.CdxVexStatement;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.model.dto.VexParseEntry;
import io.reliza.model.dto.VexParseResult;
import lombok.extern.slf4j.Slf4j;

/**
 * Parses an inbound CycloneDX VEX 1.x JSON document.
 * Resolves each vulnerability's {@code affects[].ref} against the document's own
 * {@code components[]} table to produce a PURL per (vuln, product) pair.
 * Maps the CDX library's enums to ReARM-internal enums (1:1, with
 * RESOLVED_WITH_PEDIGREE collapsed to RESOLVED).
 */
@Slf4j
@Service
public class CdxVexParser implements VexFormatParser {

    @Override public SourceFormat format() { return SourceFormat.CDX_VEX; }

    @Override
    public VexParseResult parse(String json) {
        CdxVexParseResult raw = parseRaw(json);
        if (raw.docError() != null) {
            return new VexParseResult(java.util.List.of(), raw.docError(), 0, java.util.List.of(raw.docError()));
        }
        java.util.List<VexParseEntry> entries = raw.statements().stream()
            .map(s -> new VexParseEntry(s, java.util.List.of()))
            .toList();
        // Report no-analysis entries as invalid statements so the import summary
        // reflects them ("N could not be processed") instead of a bare 0 total.
        java.util.List<String> errors = raw.skippedNoAnalysis() > 0
            ? java.util.List.of(raw.skippedNoAnalysis()
                + " vulnerability entr" + (raw.skippedNoAnalysis() == 1 ? "y" : "ies")
                + " skipped: no analysis block (a CycloneDX vulnerability without an analysis"
                + " section carries no exploitability statement to import)")
            : java.util.List.of();
        return new VexParseResult(entries, null, raw.skippedNoAnalysis(), errors);
    }

    public CdxVexParseResult parseRaw(String json) {
        Bom bom;
        try {
            bom = new JsonParser().parse(json.getBytes(StandardCharsets.UTF_8));
        } catch (ParseException e) {
            return new CdxVexParseResult(List.of(), "doc parse failed: " + e.getMessage(), 0);
        }

        Map<String, String> bomRefToPurl = new HashMap<>();
        if (bom.getComponents() != null) {
            for (Component c : bom.getComponents()) {
                if (c.getBomRef() != null && c.getPurl() != null) {
                    bomRefToPurl.put(c.getBomRef(), c.getPurl());
                }
            }
        }

        List<CdxVexStatement> statements = new LinkedList<>();
        int skippedNoAnalysis = 0;
        if (bom.getVulnerabilities() != null) {
            for (Vulnerability v : bom.getVulnerabilities()) {
                if (v.getAnalysis() == null) {
                    log.warn("CDX-VEX: vulnerability {} has no analysis block, skipping", v.getId());
                    skippedNoAnalysis++;
                    continue;
                }
                statements.add(buildStatement(v, bomRefToPurl));
            }
        }
        return new CdxVexParseResult(statements, null, skippedNoAnalysis);
    }

    private CdxVexStatement buildStatement(Vulnerability v, Map<String, String> bomRefToPurl) {
        List<String> aliases = new ArrayList<>();
        if (v.getReferences() != null) {
            for (Vulnerability.Reference ref : v.getReferences()) {
                if (ref.getId() != null) aliases.add(ref.getId());
            }
        }
        List<String> productPurls = new ArrayList<>();
        if (v.getAffects() != null) {
            for (Vulnerability.Affect a : v.getAffects()) {
                String ref = a.getRef();
                if (ref == null) continue;
                if (ref.startsWith("pkg:") || ref.startsWith("cpe:")) {
                    productPurls.add(ref);
                } else {
                    String resolved = bomRefToPurl.get(ref);
                    if (resolved != null) productPurls.add(resolved);
                    else log.warn("CDX-VEX: bom-ref {} did not resolve to a PURL", ref);
                }
            }
        }
        Vulnerability.Analysis a = v.getAnalysis();
        List<AnalysisResponse> responses = new ArrayList<>();
        if (a.getResponses() != null) {
            for (Vulnerability.Analysis.Response r : a.getResponses()) {
                responses.add(AnalysisResponse.valueOf(r.name()));
            }
        }
        String stmtJson;
        try {
            stmtJson = Utils.OM.writeValueAsString(v);
        } catch (JacksonException e) {
            stmtJson = "{}";
        }
        VulnerabilitySeverity severity = highestSeverity(v);
        return new CdxVexStatement(
            v.getId(), aliases, productPurls,
            mapState(a.getState()),
            mapJustification(a.getJustification()),
            a.getDetail(), v.getRecommendation(), v.getWorkaround(),
            responses, severity, stmtJson);
    }

    private AnalysisState mapState(Vulnerability.Analysis.State s) {
        if (s == null) return null;
        if (s == Vulnerability.Analysis.State.RESOLVED_WITH_PEDIGREE) return AnalysisState.RESOLVED;
        return AnalysisState.valueOf(s.name());
    }

    private AnalysisJustification mapJustification(Vulnerability.Analysis.Justification j) {
        if (j == null) return null;
        return AnalysisJustification.valueOf(j.name());
    }

    private VulnerabilitySeverity highestSeverity(Vulnerability v) {
        if (v.getRatings() == null || v.getRatings().isEmpty()) return null;
        VulnerabilitySeverity best = null;
        for (Vulnerability.Rating r : v.getRatings()) {
            VulnerabilitySeverity mapped = mapSeverity(r.getSeverity());
            if (mapped == null) continue;
            if (best == null || severityRank(mapped) > severityRank(best)) best = mapped;
        }
        return best;
    }

    private VulnerabilitySeverity mapSeverity(Vulnerability.Rating.Severity s) {
        if (s == null) return null;
        return switch (s) {
            case CRITICAL -> VulnerabilitySeverity.CRITICAL;
            case HIGH -> VulnerabilitySeverity.HIGH;
            case MEDIUM -> VulnerabilitySeverity.MEDIUM;
            case LOW -> VulnerabilitySeverity.LOW;
            case INFO, NONE, UNKNOWN -> VulnerabilitySeverity.UNASSIGNED;
        };
    }

    private static int severityRank(VulnerabilitySeverity s) {
        return switch (s) {
            case CRITICAL -> 4;
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
            case UNASSIGNED -> 0;
        };
    }
}
