/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import org.springframework.transaction.annotation.Propagation;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ImportSummary;
import io.reliza.model.IssuerClass;
import io.reliza.model.ImportMode;
import io.reliza.model.TrustAction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.Utils;
import io.reliza.model.AnalysisScope;
import io.reliza.model.ProposalStatus;
import io.reliza.model.SafetyContextProposal;
import io.reliza.model.SafetyContextRecordData;
import io.reliza.model.dto.SafetyContextEntry;
import io.reliza.model.dto.SafetyContextImportResult;
import io.reliza.model.dto.SafetyContextParseResult;
import io.reliza.repositories.SafetyContextProposalRepository;
import io.reliza.model.dto.MatchCandidate;

/**
 * Resolves CycloneDX 2.0 component PURLs against a release's reconciled SBOM
 * inventory and persists one proposal per safety-classified blueprint asset.
 *
 * <p>Matching is exact after ReARM PURL canonicalization. A name-only match is
 * never attempted. Unmatched, ambiguous, and reconciliation-pending items remain
 * proposals with an explicit match state for review.
 */
@Service
public class SafetyContextImportService implements ArtifactImportHandler {
    private final SafetyContextParser parser;
    private final SbomComponentService sbomComponentService;
    private final SafetyContextProposalRepository proposalRepository;
    private final ScopeTargetResolver scopeTargetResolver;
    private final ImportVerdictResolver verdictResolver;
    private final SafetyContextAcceptanceService acceptanceService;

public SafetyContextImportService(
        SafetyContextParser parser,
        SbomComponentService sbomComponentService,
        SafetyContextProposalRepository proposalRepository,
        ScopeTargetResolver scopeTargetResolver,
        ImportVerdictResolver verdictResolver,
        SafetyContextAcceptanceService acceptanceService) {
    this.parser = parser;
    this.sbomComponentService = sbomComponentService;
    this.proposalRepository = proposalRepository;
    this.scopeTargetResolver = scopeTargetResolver;
    this.verdictResolver = verdictResolver;
    this.acceptanceService = acceptanceService;
}
    
    @Override
    public ArtifactType type() {
        return ArtifactType.SAFETY_CONTEXT;
    }

    @Override
    public void verifyPreconditions(ReleaseData rd) throws RelizaException {
        if (rd != null && !sbomComponentService.releaseHasSbomComponents(
                rd.getUuid(), rd.getOrg())) {
            throw new RelizaException(
                "Cannot import SAFETY_CONTEXT: release has no SBOM components yet. "
                + "Upload an SBOM/BOM artifact for this release first.");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportSummary dispatch(
            UUID artifactUuid,
            ArtifactDto artDto,
            ReleaseData rd,
            ArtifactBelongsTo belongsTo,
            byte[] content,
            WhoUpdated wu) throws RelizaException {

        verifyPreconditions(rd);

IssuerClass issuerClass = deriveIssuerClass(belongsTo);

if (artDto.getUserIssuerClassOverride() != null) {
    issuerClass = artDto.getUserIssuerClassOverride();
}

SafetyContextImportResult result = createProposals(
    rd.getOrg(),
    rd.getUuid(),
    artifactUuid,
    artDto.getImportScope() != null
        ? artDto.getImportScope()
        : AnalysisScope.COMPONENT,
    artDto.getImportMode() != null
        ? artDto.getImportMode()
        : ImportMode.AUTO_ACCEPT,
    issuerClass,
    wu,
    new String(content, StandardCharsets.UTF_8));

        ImportSummary summary = new ImportSummary();
        summary.setStatementsTotal(result.total());
        summary.setProposalsCreated(result.proposalUuids().size());
        summary.setStatementsUnmatched(result.unmatched());
        summary.setStatementsErrored(result.invalid());
        summary.setErrorMessages(result.errors());

        return summary;
    }
    private IssuerClass deriveIssuerClass(ArtifactBelongsTo belongsTo) {
    if (belongsTo == null) return IssuerClass.THIRD_PARTY;
    if (ArtifactBelongsTo.RELEASE.equals(belongsTo)) return IssuerClass.SELF;
    if (ArtifactBelongsTo.SCE.equals(belongsTo)) return IssuerClass.SELF;
    if (ArtifactBelongsTo.DELIVERABLE.equals(belongsTo)) return IssuerClass.SELF;
    return IssuerClass.THIRD_PARTY;
    }
    @Transactional
    public SafetyContextImportResult createProposals(
    UUID org,
    UUID releaseUuid,
    UUID sourceArtifact,
    AnalysisScope importScope,
    ImportMode importMode,
    IssuerClass issuerClass,
    WhoUpdated wu,
   String documentJson) {

    SafetyContextParseResult parsed = parser.parse(documentJson);

    if (!parsed.valid()) {
            return new SafetyContextImportResult(0, 0, 0, 0, 0, 1,
                List.of(), parsed.errors());
        }
        if (org == null || releaseUuid == null || sourceArtifact == null) {
            return new SafetyContextImportResult(parsed.entries().size(), 0, 0, 0, 0,
                parsed.entries().size(), List.of(),
                List.of("Organization, release, and source artifact are required"));
        }

        SbomComponentService.ReleaseComponentPurls releaseComponents =
            sbomComponentService.resolveReleaseComponentPurls(releaseUuid);
        if (releaseComponents.org() == null || !org.equals(releaseComponents.org())) {
            return new SafetyContextImportResult(parsed.entries().size(), 0, 0, 0,
                parsed.entries().size(), 0, List.of(),
                List.of("Release does not resolve to the requested organization"));
        }

        Map<String, Set<UUID>> componentIdsByPurl = new LinkedHashMap<>();
        releaseComponents.canonicalByComponent().forEach((componentUuid, componentPurl) -> {
            String canonical = canonicalPurl(componentPurl);
            if (canonical != null) {
                componentIdsByPurl.computeIfAbsent(canonical, ignored -> new LinkedHashSet<>())
                    .add(componentUuid);
            }
        });

        String documentHash = sha256(documentJson);
        List<UUID> proposalUuids = new ArrayList<>();
        int matched = 0;
        int unmatched = 0;
        int ambiguous = 0;
        int deferred = 0;
        for (SafetyContextEntry entry : parsed.entries()) {
            String canonical = canonicalPurl(entry.componentPurl());
            Set<UUID> candidates = canonical == null
                ? Set.of()
                : componentIdsByPurl.getOrDefault(canonical, Set.of());

            String matchStatus;
            UUID componentUuid = null;
            if (releaseComponents.reconcilePending()) {
                matchStatus = "SBOM_PENDING";
                deferred++;
            } else if (candidates.isEmpty()) {
                matchStatus = "UNMATCHED";
                unmatched++;
            } else if (candidates.size() > 1) {
                matchStatus = "AMBIGUOUS";
                ambiguous++;
            } else {
                matchStatus = "MATCHED";
                componentUuid = candidates.iterator().next();
                matched++;
            }

            SafetyContextRecordData data = new SafetyContextRecordData();
            data.setUuid(UUID.randomUUID());
            data.setOrg(org);
            data.setReleaseUuid(releaseUuid);
            data.setSourceArtifact(sourceArtifact);
            data.setSourceFormat("CYCLONEDX_2_0");
            data.setSourceDocumentHash(documentHash);
            data.setSourceAssertionHash(sha256(entry.rawAssetJson()));
            data.setSourceDocumentJson(documentJson);
            data.setSourceAssertionJson(entry.rawAssetJson());
            List<MatchCandidate> targets =
            scopeTargetResolver.targets(
                org,
                importScope,
                Set.of(releaseUuid),
                releaseUuid);

        if (!targets.isEmpty()) {
            MatchCandidate target = targets.get(0);
            data.setScope(target.scope());
            data.setScopeUuid(target.scopeUuid());
        } else {
            data.setScope(importScope);
        }
            data.setSbomComponentUuid(componentUuid);
            data.setComponentPurl(entry.componentPurl());
            data.setComponentRefId(entry.componentRefId());
            data.setComponentBomRef(entry.componentBomRef());
            data.setAssertionRefId(entry.assetRefId());
            data.setClassificationJson(entry.safetyIntegrityLevelsJson());
            data.setMatchStatus(matchStatus);
            data.setStatus(ProposalStatus.PENDING);
if (data.getScopeUuid() != null
        && !proposalRepository.findDedupeCandidates(
                org.toString(),
                sourceArtifact.toString(),
                data.getSourceAssertionHash(),
                data.getScope().name(),
                data.getScopeUuid().toString()).isEmpty()) {
    continue;
}

        TrustAction gate =
    SafetyContextTrustGate.effectiveAction(
        TrustAction.AUTO_ACCEPT,
        issuerClass);

ImportVerdictResolver.Verdict verdict =
    verdictResolver.resolve(gate, importMode);

switch (verdict.action()) {

    case REJECT -> {
        continue;
    }

    case STAGE -> {
        SafetyContextProposal proposal = new SafetyContextProposal();
        proposal.setUuid(data.getUuid());
        proposal.setRecordData(data.toRecordData());

        proposal = proposalRepository.save(proposal);
        proposalUuids.add(proposal.getUuid());
    }

    case AUTO_ACCEPT -> {
        SafetyContextProposal proposal = new SafetyContextProposal();
        proposal.setUuid(data.getUuid());
        proposal.setRecordData(data.toRecordData());

        proposal = proposalRepository.save(proposal);
        proposalUuids.add(proposal.getUuid());

        acceptanceService.applyTrustGate(
            proposal.getUuid(),
            TrustAction.AUTO_ACCEPT,
            issuerClass,
            wu.getActor() != null
                ? wu.getActor()
                : wu.getLastUpdatedBy());
    }
}    
      
        List<String> errors = new ArrayList<>(parsed.warnings());
        return new SafetyContextImportResult(parsed.entries().size(), matched, unmatched,
            ambiguous, deferred, 0, List.copyOf(proposalUuids), List.copyOf(errors));
    }

    private String canonicalPurl(String purl) {
        if (purl == null || purl.isBlank()) return null;
        try {
            return Utils.canonicalizePurl(purl);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }
}
