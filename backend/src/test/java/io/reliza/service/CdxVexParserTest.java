/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisState;
import io.reliza.model.dto.CdxVexParseResult;
import io.reliza.model.dto.CdxVexStatement;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;

class CdxVexParserTest {

    private final CdxVexParser p = new CdxVexParser();

    @Test
    void parsesSingleStatementWithBomRefResolution() {
        String doc = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "components": [
            {"bom-ref": "comp-a", "type": "library", "name": "log4j-core",
             "purl": "pkg:maven/org.apache.logging.log4j/log4j-core@2.20.0"}
          ],
          "vulnerabilities": [
            {"id": "CVE-2024-12345", "affects": [{"ref": "comp-a"}],
             "analysis": {
               "state": "false_positive",
               "justification": "code_not_reachable",
               "detail": "JndiLookup stripped at build."
             }}
          ]
        }
        """;
        CdxVexParseResult r = p.parseRaw(doc);
        assertNull(r.docError());
        assertEquals(1, r.statements().size());
        CdxVexStatement s = r.statements().get(0);
        assertEquals("CVE-2024-12345", s.vulnerabilityId());
        assertEquals(1, s.productPurls().size());
        assertEquals("pkg:maven/org.apache.logging.log4j/log4j-core@2.20.0", s.productPurls().get(0));
        assertEquals(AnalysisState.FALSE_POSITIVE, s.state());
        assertEquals(AnalysisJustification.CODE_NOT_REACHABLE, s.justification());
        assertEquals("JndiLookup stripped at build.", s.details());
    }

    @Test
    void parsesPurlInRefDirectly() {
        String doc = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "vulnerabilities": [
            {"id": "CVE-X", "affects": [{"ref": "pkg:maven/x/y@1.0"}],
             "analysis": {"state": "exploitable"}}
          ]
        }
        """;
        CdxVexParseResult r = p.parseRaw(doc);
        assertEquals(1, r.statements().size());
        assertEquals("pkg:maven/x/y@1.0", r.statements().get(0).productPurls().get(0));
        assertEquals(AnalysisState.EXPLOITABLE, r.statements().get(0).state());
    }

    @Test
    void resolvedWithPedigreeCollapsesToResolved() {
        String doc = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "vulnerabilities": [
            {"id": "CVE-Y", "affects": [{"ref": "pkg:maven/x/y@1.0"}],
             "analysis": {"state": "resolved_with_pedigree"}}
          ]
        }
        """;
        CdxVexParseResult r = p.parseRaw(doc);
        assertEquals(AnalysisState.RESOLVED, r.statements().get(0).state());
    }

    @Test
    void unresolvedBomRefIsSkippedWithEmptyPurls() {
        String doc = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "vulnerabilities": [
            {"id": "CVE-Z", "affects": [{"ref": "unknown-ref"}],
             "analysis": {"state": "not_affected", "justification": "code_not_present"}}
          ]
        }
        """;
        CdxVexParseResult r = p.parseRaw(doc);
        assertEquals(1, r.statements().size());
        assertTrue(r.statements().get(0).productPurls().isEmpty());
    }

    @Test
    void malformedJsonReturnsDocError() {
        CdxVexParseResult r = p.parseRaw("not cdx json");
        assertEquals(0, r.statements().size());
        assertEquals("doc parse failed", r.docError().substring(0, 16));
    }

    @Test
    void vulnerabilityWithoutAnalysisIsCountedAsSkipped() {
        String doc = """
        {"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
         "vulnerabilities": [
            {"id": "CVE-2024-0001",
             "affects": [{"ref": "pkg:npm/left-pad@1.3.0"}]},
            {"id": "CVE-2024-0002",
             "affects": [{"ref": "pkg:npm/left-pad@1.3.0"}],
             "analysis": {"state": "not_affected", "justification": "code_not_reachable"}}
         ]}
        """;
        CdxVexParseResult r = p.parseRaw(doc);
        assertEquals(1, r.statements().size());
        assertEquals(1, r.skippedNoAnalysis());
        // and the format-level parse() surfaces them as invalid statements with a message
        var parsed = p.parse(doc);
        assertEquals(1, parsed.entries().size());
        assertEquals(1, parsed.invalidStatements());
        assertEquals(1, parsed.errorMessages().size());
        assertTrue(parsed.errorMessages().get(0).contains("no analysis block"));
    }

    @Test
    void capturesSeverityFromSingleRating() {
        String doc = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "vulnerabilities": [
            {"id": "CVE-2020-7598",
             "ratings": [{"severity": "medium"}],
             "affects": [{"ref": "pkg:npm/minimist@1.2.0"}],
             "analysis": {"state": "not_affected", "justification": "protected_at_perimeter"}}
          ]
        }
        """;
        CdxVexParseResult r = p.parseRaw(doc);
        assertEquals(VulnerabilitySeverity.MEDIUM, r.statements().get(0).severity());
    }

    @Test
    void picksHighestSeverityAcrossRatings() {
        // CDX ratings[] can carry multiple entries (different scoring methods). The parser
        // takes the highest to avoid silently softening a vendor's worst-case call.
        String doc = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "vulnerabilities": [
            {"id": "CVE-X",
             "ratings": [{"severity": "low"}, {"severity": "high"}, {"severity": "medium"}],
             "affects": [{"ref": "pkg:maven/x/y@1.0"}],
             "analysis": {"state": "exploitable"}}
          ]
        }
        """;
        CdxVexParseResult r = p.parseRaw(doc);
        assertEquals(VulnerabilitySeverity.HIGH, r.statements().get(0).severity());
    }

    @Test
    void infoNoneUnknownMapToUnassigned() {
        String doc = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "vulnerabilities": [
            {"id": "CVE-X",
             "ratings": [{"severity": "info"}],
             "affects": [{"ref": "pkg:maven/x/y@1.0"}],
             "analysis": {"state": "exploitable"}}
          ]
        }
        """;
        CdxVexParseResult r = p.parseRaw(doc);
        assertEquals(VulnerabilitySeverity.UNASSIGNED, r.statements().get(0).severity());
    }

    @Test
    void missingRatingsLeavesSeverityNull() {
        // The screenshot bug: parser used to silently drop severity entirely. Now it
        // returns null when truly absent (no ratings array) so the fallback chain
        // (FindingAnalysis lookup → vulnerability_records lookup → STAGE demotion) can fire.
        String doc = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "vulnerabilities": [
            {"id": "CVE-X",
             "affects": [{"ref": "pkg:maven/x/y@1.0"}],
             "analysis": {"state": "exploitable"}}
          ]
        }
        """;
        CdxVexParseResult r = p.parseRaw(doc);
        assertNull(r.statements().get(0).severity());
    }
}
