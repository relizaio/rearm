/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalysisScope;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.FindingType;
import io.reliza.model.IssuerClass;
import io.reliza.model.LocationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceFormat;
import io.reliza.model.TrustAction;
import io.reliza.model.VexImportMode;
import io.reliza.model.VexStatementProposalData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.CdxVexStatement;
import io.reliza.model.dto.MatchCandidate;
import io.reliza.model.dto.VexImportInput;
import io.reliza.model.dto.VexImportResult;
import io.reliza.model.dto.VexParseEntry;
import io.reliza.model.dto.VexParseResult;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates the VEX import pipeline:
 *   parse (format-specific) → match (PURL→releases→scope-targets) → resolve verdict → commit.
 *
 * Two public entry points:
 *   <ul>
 *     <li>{@link #dispatchFromArtifact} — called from ReleaseDatafetcher.addArtifact after a
 *         VEX artifact has been persisted; performs the B1 precondition guard, derives
 *         IssuerClass from binding context, then runs the pipeline.</li>
 *     <li>{@link #importPipeline} — pipeline entry, callable from tests or future direct
 *         programmatic paths with a fully-populated VexImportInput.</li>
 *   </ul>
 *
 * Pipeline shape (see ai-plans/vex_imports/12_v12_rewrite_plan.md §2):
 *   <pre>
 *   parser   = VexFormatParser registry by SourceFormat
 *   matches  = VexMatcher → PurlReleaseResolver + ScopeTargetResolver
 *   verdict  = VexVerdictResolver  (gate + user-mode; conflict-guard branch in Commit 4)
 *   commit   = VexStatementProposalService (createProposal + accept on AUTO_ACCEPT)
 *   </pre>
 */
@Slf4j
@Service
public class VexImportService {

    @Autowired OpenVexParser openVexParser;
    @Autowired CdxVexParser cdxVexParser;
    @Autowired VexMatcher matcher;
    @Autowired VexVerdictResolver verdictResolver;
    @Autowired VexStatementProposalService proposalService;
    @Autowired SbomComponentService sbomComponentService;
    @Autowired VulnAnalysisService vulnAnalysisService;

    /**
     * Pre-upload precondition: the release must already have SBOM inventory or the VEX has
     * nothing to match against. Called from ReleaseDatafetcher before artifact persistence so
     * a precondition failure fails loud without an orphan file.
     */
    public void verifyDispatchPreconditions(ReleaseData rd) throws RelizaException {
        if (rd != null && !sbomComponentService.releaseHasSbomComponents(rd.getUuid(), rd.getOrg())) {
            throw new RelizaException(
                "Cannot import VEX: release has no SBOM components yet. "
                + "Upload an SBOM/BOM artifact for this release first, then re-upload the VEX.");
        }
    }

    /**
     * Entry point from the artifact-upload path. The VEX artifact has just been persisted;
     * this method derives IssuerClass from the binding context and dispatches the pipeline.
     * Re-runs the precondition guard defensively (ReleaseDatafetcher already calls
     * {@link #verifyDispatchPreconditions} pre-upload).
     */
    public VexImportResult dispatchFromArtifact(
            UUID artifactUuid, ArtifactDto artDto, ReleaseData rd,
            ArtifactBelongsTo belongsTo, byte[] content, WhoUpdated wu) throws RelizaException {

        verifyDispatchPreconditions(rd);

        SourceFormat format = (artDto.getBomFormat() == BomFormat.CYCLONEDX)
            ? SourceFormat.CDX_VEX
            : SourceFormat.OPENVEX;
        IssuerClass issuerClass = deriveIssuerClass(belongsTo);

        VexImportInput input = new VexImportInput();
        input.setOrg(rd.getOrg());
        input.setSourceArtifact(artifactUuid);
        input.setContent(new String(content, StandardCharsets.UTF_8));
        input.setFormat(format);
        input.setIssuerClass(issuerClass);
        input.setRestrictToRelease(rd.getUuid());
        input.setVexScope(artDto.getVexScope() != null ? artDto.getVexScope() : AnalysisScope.COMPONENT);
        input.setVexImportMode(artDto.getVexImportMode() != null ? artDto.getVexImportMode() : VexImportMode.AUTO_ACCEPT);
        input.setUserIssuerClassOverride(artDto.getUserIssuerClassOverride());
        input.setNote(artDto.getDisplayIdentifier());

        VexImportResult result = importPipeline(input, wu);
        log.info("VEX import outcome — artifact={} release={} format={} issuerClass={} scope={} total={} created={} autoAccepted={} autoRejected={} unmatched={} errored={}{}",
            artifactUuid, rd.getUuid(), format, issuerClass, input.getVexScope(),
            result.getStatementsTotal(), result.getProposalsCreated(),
            result.getProposalsAutoAccepted(), result.getProposalsAutoRejected(),
            result.getStatementsUnmatched(), result.getStatementsErrored(),
            result.getErrorMessages().isEmpty() ? "" : " errors=" + result.getErrorMessages());
        return result;
    }

    /**
     * Initial IssuerClass from binding context — RELEASE / SCE / DELIVERABLE default to SELF
     * (own org), everything else to THIRD_PARTY. The user-facing VEX upload picker exposes
     * userIssuerClassOverride which the pipeline applies on top of this default.
     */
    private IssuerClass deriveIssuerClass(ArtifactBelongsTo belongsTo) {
        if (belongsTo == null) return IssuerClass.THIRD_PARTY;
        if (ArtifactBelongsTo.RELEASE.equals(belongsTo)) return IssuerClass.SELF;
        if (ArtifactBelongsTo.SCE.equals(belongsTo)) return IssuerClass.SELF;
        if (ArtifactBelongsTo.DELIVERABLE.equals(belongsTo)) return IssuerClass.SELF;
        return IssuerClass.THIRD_PARTY;
    }

    @Transactional
    public VexImportResult importPipeline(VexImportInput input, WhoUpdated wu) throws RelizaException {
        if (input.getOrg() == null) throw new RelizaException("VEX import: org is required");
        if (input.getSourceArtifact() == null) throw new RelizaException("VEX import: sourceArtifact is required");
        if (input.getContent() == null || input.getContent().isBlank())
            throw new RelizaException("VEX import: content is required");
        if (input.getFormat() == null)
            throw new RelizaException("VEX import: format is required");

        VexImportResult result = new VexImportResult();
        result.setSourceArtifact(input.getSourceArtifact());
        result.setCreatedProposalUuids(new LinkedList<>());
        result.setErrorMessages(new LinkedList<>());

        VexFormatParser parser = parserFor(input.getFormat());
        VexParseResult parsed = parser.parse(input.getContent());
        if (parsed.docError() != null) {
            result.getErrorMessages().add(parsed.docError());
            return result;
        }
        result.setStatementsTotal(parsed.entries().size() + parsed.invalidStatements());
        result.setStatementsErrored(parsed.invalidStatements());
        result.getErrorMessages().addAll(parsed.errorMessages());

        IssuerClass effectiveIssuerClass = input.getUserIssuerClassOverride() != null
            ? input.getUserIssuerClassOverride()
            : input.getIssuerClass();

        for (VexParseEntry entry : parsed.entries()) {
            try {
                processStatement(entry, input, effectiveIssuerClass, wu, result);
            } catch (RelizaException e) {
                String findingId = entry.statement() != null ? entry.statement().vulnerabilityId() : "?";
                log.warn("VEX import: statement {} on {} failed and was skipped: {}",
                    findingId, input.getSourceArtifact(), e.getMessage());
                result.setStatementsErrored(result.getStatementsErrored() + 1);
                result.getErrorMessages().add(findingId + ": " + e.getMessage());
            }
        }
        return result;
    }

    private void processStatement(VexParseEntry entry, VexImportInput input,
                                  IssuerClass effectiveIssuerClass, WhoUpdated wu,
                                  VexImportResult result) throws RelizaException {
        CdxVexStatement stmt = entry.statement();
        boolean anyPurlMatched = false;
        for (String productPurl : stmt.productPurls()) {
            List<MatchCandidate> matches = matcher.match(
                input.getOrg(), productPurl,
                input.getVexScope(), input.getRestrictToRelease());
            if (matches.isEmpty()) {
                continue;
            }
            anyPurlMatched = true;
            String location = Utils.minimizePurl(productPurl);
            String hash = sha256(stmt.rawJson());
            // Depends only on (org, location, findingId, type) — invariant across match candidates.
            List<io.reliza.model.VulnAnalysisData> existingRows = vulnAnalysisService
                .findByOrgAndLocationAndFindingIdAndType(
                    input.getOrg(), location, stmt.vulnerabilityId(), FindingType.VULNERABILITY);
            for (MatchCandidate mc : matches) {
                VexStatementProposalData candidate = VexStatementProposalData.createVexStatementProposalData(
                    input.getOrg(), input.getSourceArtifact(), input.getFormat(), hash, stmt.rawJson(),
                    mc.scope(), mc.scopeUuid(),
                    location, productPurl, LocationType.PURL,
                    stmt.vulnerabilityId(), stmt.aliasIds(), FindingType.VULNERABILITY,
                    stmt.state(), stmt.justification(), stmt.details(), null,
                    stmt.responses(), stmt.recommendation(), stmt.workaround(),
                    entry.translationNotes(),
                    effectiveIssuerClass, input.getVexImportMode(), input.getUserIssuerClassOverride());

                VexVerdictResolver.Verdict verdict = verdictResolver.resolveWithConflictCheck(
                    effectiveIssuerClass, stmt.state(), input.getVexImportMode(),
                    mc.scope(), existingRows);
                candidate.setDemotionReason(verdict.demotionReason());

                applyVerdict(candidate, verdict.action(), wu, result);
            }
        }
        if (!anyPurlMatched) {
            result.setStatementsUnmatched(result.getStatementsUnmatched() + 1);
        }
    }

    private void applyVerdict(VexStatementProposalData candidate, TrustAction action,
                              WhoUpdated wu, VexImportResult result) throws RelizaException {
        switch (action) {
            case REJECT -> {
                result.setProposalsAutoRejected(result.getProposalsAutoRejected() + 1);
                log.info("VEX import REJECTed statement for {} on {}",
                    candidate.getFindingId(), candidate.getScopeUuid());
            }
            case STAGE -> {
                VexStatementProposalData created = proposalService.createProposal(candidate, wu);
                result.setProposalsCreated(result.getProposalsCreated() + 1);
                result.getCreatedProposalUuids().add(created.getUuid());
                if (candidate.getDemotionReason() != null) {
                    result.setProposalsDemoted(result.getProposalsDemoted() + 1);
                    result.getDemotionReasons().merge(candidate.getDemotionReason(), 1, Integer::sum);
                }
            }
            case AUTO_ACCEPT -> {
                VexStatementProposalData created = proposalService.createProposal(candidate, wu);
                result.getCreatedProposalUuids().add(created.getUuid());
                proposalService.accept(created.getUuid(), wu);
                result.setProposalsAutoAccepted(result.getProposalsAutoAccepted() + 1);
            }
        }
    }

    private VexFormatParser parserFor(SourceFormat format) throws RelizaException {
        return switch (format) {
            case OPENVEX -> openVexParser;
            case CDX_VEX -> cdxVexParser;
            default -> throw new RelizaException("Unsupported VEX format: " + format);
        };
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK guarantee; a miss here is a broken JVM, not a recoverable
            // condition. Returning a sentinel would key dedupe on a shared hash and cascade
            // mark every proposal SUPERSEDED — fail loud instead.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
