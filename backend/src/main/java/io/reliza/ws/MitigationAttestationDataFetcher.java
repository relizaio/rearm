/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AttestationStatus;
import io.reliza.model.MitigationAttestationData;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.MitigationAttestationWebDto;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.MitigationAttestationService;
import io.reliza.service.UserService;
import io.reliza.service.VexAttestationBridge;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class MitigationAttestationDataFetcher {

    @Autowired private AuthorizationService authorizationService;
    @Autowired private MitigationAttestationService attestationService;
    @Autowired private VexAttestationBridge attestationBridge;
    @Autowired private UserService userService;
    @Autowired private GetOrganizationService getOrganizationService;

    @PreAuthorize("isAuthenticated()")
    @DgsData(parentType = "Query", field = "getMitigationAttestations")
    public List<MitigationAttestationWebDto> list(
            @InputArgument("org") UUID org,
            @InputArgument("status") AttestationStatus status) throws RelizaException {
        gateOrg(org, PermissionFunction.FINDING_ANALYSIS_READ);
        return attestationService.listForOrgAndStatus(org, status == null ? AttestationStatus.PENDING : status)
            .stream().map(MitigationAttestationWebDto::fromData).collect(Collectors.toList());
    }

    @PreAuthorize("isAuthenticated()")
    @DgsData(parentType = "Query", field = "getMitigationAttestation")
    public MitigationAttestationWebDto get(@InputArgument("uuid") UUID uuid) throws RelizaException {
        Optional<MitigationAttestationData> opt = attestationService.get(uuid);
        if (opt.isEmpty()) throw new AccessDeniedException("Attestation not found: " + uuid);
        gateOrg(opt.get().getOrg(), PermissionFunction.FINDING_ANALYSIS_READ);
        return MitigationAttestationWebDto.fromData(opt.get());
    }

    @PreAuthorize("isAuthenticated()")
    @DgsData(parentType = "Query", field = "getMitigationAttestationsByAssignee")
    public List<MitigationAttestationWebDto> byAssignee(
            @InputArgument("assignee") UUID assignee,
            @InputArgument("status") AttestationStatus status) throws RelizaException {
        // Self-query only — without this check any authenticated user could enumerate
        // another user's attestations across org boundaries (claim text, evidence, proposal links).
        var auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        var oud = userService.getUserDataByAuth(auth);
        if (oud.isEmpty() || !assignee.equals(oud.get().getUuid())) {
            throw new AccessDeniedException("Can only query attestations assigned to the current user");
        }
        return attestationService.listForAssignee(assignee, status == null ? AttestationStatus.PENDING : status)
            .stream().map(MitigationAttestationWebDto::fromData).collect(Collectors.toList());
    }

    @PreAuthorize("isAuthenticated()")
    @DgsData(parentType = "Mutation", field = "attestMitigation")
    public MitigationAttestationWebDto attest(
            @InputArgument("uuid") UUID uuid,
            @InputArgument("evidence") String evidence) throws RelizaException {
        Optional<MitigationAttestationData> opt = attestationService.get(uuid);
        if (opt.isEmpty()) throw new AccessDeniedException("Attestation not found: " + uuid);
        WhoUpdated wu = gateOrgAndGetWho(opt.get().getOrg(), PermissionFunction.FINDING_ANALYSIS_WRITE);
        return MitigationAttestationWebDto.fromData(attestationBridge.attestAndPropagate(uuid, evidence, wu));
    }

    @PreAuthorize("isAuthenticated()")
    @DgsData(parentType = "Mutation", field = "waiveMitigation")
    public MitigationAttestationWebDto waive(
            @InputArgument("uuid") UUID uuid,
            @InputArgument("reason") String reason) throws RelizaException {
        Optional<MitigationAttestationData> opt = attestationService.get(uuid);
        if (opt.isEmpty()) throw new AccessDeniedException("Attestation not found: " + uuid);
        WhoUpdated wu = gateOrgAndGetWho(opt.get().getOrg(), PermissionFunction.FINDING_ANALYSIS_WRITE);
        return MitigationAttestationWebDto.fromData(attestationService.waive(uuid, reason, wu));
    }

    private void gateOrg(UUID org, PermissionFunction perm) throws RelizaException {
        var auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        var oud = userService.getUserDataByAuth(auth);
        Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(org);
        RelizaObject ro = ood.isPresent() ? ood.get() : null;
        authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), perm,
            PermissionScope.ORGANIZATION, org, List.of(ro), CallType.READ);
    }

    private WhoUpdated gateOrgAndGetWho(UUID org, PermissionFunction perm) throws RelizaException {
        var auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        var oud = userService.getUserDataByAuth(auth);
        Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(org);
        RelizaObject ro = ood.isPresent() ? ood.get() : null;
        authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), perm,
            PermissionScope.ORGANIZATION, org, List.of(ro), CallType.READ);
        return WhoUpdated.getWhoUpdated(oud.get());
    }
}
