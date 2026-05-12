/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import io.reliza.common.Utils;
import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisState;
import io.reliza.model.SourceFormat;
import io.reliza.model.dto.CdxVexStatement;
import io.reliza.model.dto.OpenVexParseResult;
import io.reliza.model.dto.OpenVexParseResult.InvalidStatement;
import io.reliza.model.dto.OpenVexStatement;
import io.reliza.model.dto.OpenVexValidationResult;
import io.reliza.model.dto.VexParseEntry;
import io.reliza.model.dto.VexParseResult;

/**
 * Parses an OpenVEX 0.2.0 JSON document and translates each valid statement into the
 * canonical CDX-VEX shape. Per-statement validation uses {@link OpenVexStatementValidator}.
 * Vocabulary expansion uses {@link OpenVexInverseMapper}; lossy translations produce
 * a translation note that flows onto the resulting proposal so reviewers can see the
 * default and override.
 */
@Service
public class OpenVexParser implements VexFormatParser {

    @Autowired OpenVexInverseMapper inverseMapper;

    private final OpenVexStatementValidator validator;

    public OpenVexParser(OpenVexStatementValidator validator) {
        this.validator = validator;
    }

    @Override public SourceFormat format() { return SourceFormat.OPENVEX; }

    @Override
    public VexParseResult parse(String json) {
        OpenVexParseResult raw = parseRaw(json);
        if (raw.docError() != null) {
            return new VexParseResult(List.of(), raw.docError(), 0, List.of(raw.docError()));
        }
        List<String> errors = new ArrayList<>();
        for (InvalidStatement inv : raw.invalid()) errors.addAll(inv.errors());
        List<VexParseEntry> entries = new ArrayList<>(raw.valid().size());
        for (OpenVexStatement stmt : raw.valid()) entries.add(toCanonicalEntry(stmt));
        return new VexParseResult(entries, null, raw.invalid().size(), errors);
    }

    public OpenVexParseResult parseRaw(String json) {
        JsonNode root;
        try {
            root = Utils.OM.readTree(json);
        } catch (IOException e) {
            return new OpenVexParseResult(List.of(), List.of(), "doc parse failed: " + e.getMessage());
        }

        JsonNode statements = root.path("statements");
        if (!statements.isArray()) {
            return new OpenVexParseResult(List.of(), List.of(), "missing or non-array `statements`");
        }

        List<OpenVexStatement> valid = new ArrayList<>();
        List<InvalidStatement> invalid = new ArrayList<>();
        for (JsonNode s : statements) {
            OpenVexStatement stmt = readStatement(s);
            OpenVexValidationResult vr = validator.validate(stmt);
            if (vr.valid()) {
                valid.add(stmt);
            } else {
                invalid.add(new InvalidStatement(stmt, vr.errors()));
            }
        }
        return new OpenVexParseResult(valid, invalid, null);
    }

    private OpenVexStatement readStatement(JsonNode s) {
        String vuln = s.path("vulnerability").path("name").asText(null);
        List<String> products = new ArrayList<>();
        for (JsonNode p : s.path("products")) {
            String id = p.path("@id").asText(null);
            if (id != null) products.add(id);
        }
        return new OpenVexStatement(
            vuln,
            products,
            s.path("status").asText(null),
            s.path("justification").asText(null),
            s.path("impact_statement").asText(null),
            s.path("action_statement").asText(null)
        );
    }

    private VexParseEntry toCanonicalEntry(OpenVexStatement stmt) {
        AnalysisState state = inverseMapper.toState(stmt.status());
        AnalysisJustification just = inverseMapper.toJustification(stmt.justification());
        List<String> notes = new ArrayList<>();
        if (stmt.justification() != null && just != null) {
            notes.add("OpenVEX justification '" + stmt.justification() + "' expanded to CDX "
                + just.name() + " (default; reviewer can override)");
        }
        CdxVexStatement cdx = new CdxVexStatement(
            stmt.vulnerability(),
            List.of(),
            stmt.products(),
            state,
            just,
            stmt.impactStatement(),
            stmt.actionStatement(),
            null,
            List.of(),
            jsonOfStatement(stmt));
        return new VexParseEntry(cdx, notes);
    }

    private String jsonOfStatement(OpenVexStatement s) {
        try {
            return Utils.OM.writeValueAsString(s);
        } catch (Exception e) {
            return "{}";
        }
    }
}
