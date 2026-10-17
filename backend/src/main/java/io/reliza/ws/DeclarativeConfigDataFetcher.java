/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.AuthorizationResponse;
import io.reliza.model.dto.AuthorizationResponse.InitType;
import io.reliza.model.dto.ProgrammaticAuthContext;
import io.reliza.service.ApiKeyDeclarativeService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.FreeformKeyVerification;
import io.reliza.service.DeclarativeConfigService;
import io.reliza.service.DeclarativeConfigService.ApplyResult;
import io.reliza.service.DeclarativeConfigService.BranchesSpecDto;
import io.reliza.service.DeclarativeConfigService.CatalogSpecDto;
import io.reliza.service.DeclarativeConfigService.SourceDto;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.AgentBoardService;
import io.reliza.model.AgentActor;

/**
 * Declarative configuration surface for API keys: apply / export of the Catalog and
 * Branches slices. FREEFORM and USER keys need the CONFIGURATION_WRITE function with write access
 * on the org to apply, and CONFIGURATION_READ (or WRITE, which implies it) with read
 * access to export; org admins pass implicitly. Legacy ORGANIZATION_RW keys have no
 * function grants and pass on their org-wide tier as before. A board's apply, archive and export
 * are the exception: see {@link #authorizeBoardOperation}.
 */
@DgsComponent
public class DeclarativeConfigDataFetcher {

	@Autowired private AuthorizationService authorizationService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private DeclarativeConfigService declarativeConfigService;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private ApiKeyDeclarativeService apiKeyDeclarativeService;

	private record Authed(UUID orgUuid, WhoUpdated whoUpdated, AuthHeaderParse ahp) {}

	private Authed authorize(DgsDataFetchingEnvironment dfe, CallType ct) throws RelizaException {
		return authorize(caller(dfe), ct);
	}

	private Authed authorize(Caller c, CallType ct) throws RelizaException {
		// export: CONFIGURATION_READ, or CONFIGURATION_WRITE which implies it; apply: CONFIGURATION_WRITE
		if (ct == CallType.WRITE) return authorize(c, ct, PermissionFunction.CONFIGURATION_WRITE);
		try {
			return authorize(c, ct, PermissionFunction.CONFIGURATION_READ);
		} catch (AccessDeniedException readDenied) {
			return authorize(c, ct, PermissionFunction.CONFIGURATION_WRITE);
		}
	}

	/** The caller as the programmatic filter left it, and its organization. */
	private record Caller(UUID orgUuid, AuthHeaderParse ahp, RelizaObject org) {}

	private Caller caller(DgsDataFetchingEnvironment dfe) {
		DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(requestData.getHeaders(), servletWebRequest);
		var ahp = authCtx.ahp();
		UUID orgUuid = authCtx.orgUuid();
		if (null == ahp || null == orgUuid) throw new AccessDeniedException("Invalid authorization type");
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = ood.orElse(null);
		if (ro == null) throw new AccessDeniedException("Not authorized");
		return new Caller(orgUuid, ahp, ro);
	}

	/**
	 * The gate of a board's apply, archive and export (operator report 2026-10-02). An RBAC key is
	 * not asked for a function at organization scope, which a key granted on a perspective or a
	 * board never holds: the board operation judges what it touches itself -- BOARD_WRITE and
	 * CONFIGURATION_WRITE on a board it updates or archives, the consent rule on each perspective a
	 * file adds or removes, both functions at the organization for a new board in no perspective,
	 * BOARD_READ and CONFIGURATION_READ on a board it exports (architecture d8e7bd7e §3.3). The key
	 * must hold the declarative function at a scope that can cover a board. Any other key keeps
	 * its organization-wide gate.
	 */
	private Authed authorizeBoardOperation(DgsDataFetchingEnvironment dfe, CallType ct) throws RelizaException {
		Caller c = caller(dfe);
		if (!c.ahp().isRbacKey()) return authorize(c, ct);
		List<PermissionFunction> functions = ct == CallType.WRITE ? List.of(PermissionFunction.CONFIGURATION_WRITE)
				: List.of(PermissionFunction.CONFIGURATION_READ, PermissionFunction.CONFIGURATION_WRITE);
		FreeformKeyVerification fkv = authorizationService.rbacKeyForBoardConfiguration(c.ahp(), c.orgUuid(), ct, functions);
		return new Authed(c.orgUuid(), fkv.whoUpdated(), c.ahp());
	}

	private Authed authorize(Caller c, CallType ct, PermissionFunction function) throws RelizaException {
		var ahp = c.ahp();
		UUID orgUuid = c.orgUuid();
		RelizaObject ro = c.org();
		AuthorizationResponse ar;
		if (ahp.isRbacKey()) {
			FreeformKeyVerification fkv = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(
					ahp, function, PermissionScope.ORGANIZATION, orgUuid, List.of(ro), ct);
			ar = AuthorizationResponse.initialize(InitType.ALLOW);
			ar.setWhoUpdated(fkv.whoUpdated());
		} else {
			List<ApiTypeEnum> supportedApiTypes = Arrays.asList(ApiTypeEnum.ORGANIZATION_RW);
			ar = authorizationService.isApiKeyAuthorized(ahp, supportedApiTypes, orgUuid, ct, ro);
		}
		return new Authed(orgUuid, ar.getWhoUpdated(), ahp);
	}

	private static SourceDto source(DgsDataFetchingEnvironment dfe) {
		Map<String, Object> m = dfe.getArgument("source");
		return m == null ? null : Utils.OM.convertValue(m, SourceDto.class);
	}

	private static boolean dryRun(DgsDataFetchingEnvironment dfe) {
		Boolean d = dfe.getArgument("dryRun");
		return Boolean.TRUE.equals(d);
	}

	@DgsData(parentType = "Mutation", field = "applyCatalogProgrammatic")
	public ApplyResult applyCatalogProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorize(dfe, CallType.WRITE);
		Map<String, Object> specMap = dfe.getArgument("spec");
		CatalogSpecDto spec = Utils.OM.convertValue(specMap, CatalogSpecDto.class);
		return declarativeConfigService.applyCatalog(a.orgUuid(), spec, dryRun(dfe), source(dfe), a.whoUpdated());
	}

	@DgsData(parentType = "Mutation", field = "applyBranchesProgrammatic")
	public ApplyResult applyBranchesProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorize(dfe, CallType.WRITE);
		Map<String, Object> specMap = dfe.getArgument("spec");
		BranchesSpecDto spec = Utils.OM.convertValue(specMap, BranchesSpecDto.class);
		return declarativeConfigService.applyBranches(a.orgUuid(), spec, dryRun(dfe), source(dfe), a.whoUpdated());
	}

	@DgsData(parentType = "Mutation", field = "applyBoardProgrammatic")
	public ApplyResult applyBoardProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorizeBoardOperation(dfe, CallType.WRITE);
		// Read from the raw argument map, not converted: a field sent as null clears it and one
		// left out does not, and only the map still knows which was which (declarative-boards D15).
		AgentBoardService.BoardSpecDto spec = AgentBoardService.boardSpecFromInput(dfe.getArgument("spec"));
		// Perspectives the file adds or removes need the key's consent (board-permissions.md D9).
		return agentBoardService.applyBoard(a.orgUuid(), spec, dryRun(dfe), source(dfe),
				AgentActor.system("declarative apply"), authorizationService.perspectiveConsent(a.ahp(), a.orgUuid()),
				authorizationService.boardAccess(a.ahp(), a.orgUuid()), a.whoUpdated());
	}

	@DgsData(parentType = "Mutation", field = "applyRolePresetsProgrammatic")
	public ApplyResult applyRolePresetsProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorize(dfe, CallType.WRITE);
		AgentBoardService.RolePresetsSpecDto spec = AgentBoardService.rolePresetsSpecFromInput(dfe.getArgument("spec"));
		return agentBoardService.applyRolePresets(a.orgUuid(), spec, dryRun(dfe), source(dfe), a.whoUpdated());
	}

	@DgsData(parentType = "Mutation", field = "archiveBoardProgrammatic")
	public io.reliza.model.AgentBoardData archiveBoardProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorizeBoardOperation(dfe, CallType.WRITE);
		return agentBoardService.archiveBoardByName(a.orgUuid(), dfe.getArgument("name"),
				AgentActor.system("declarative apply"), authorizationService.boardAccess(a.ahp(), a.orgUuid()),
				a.whoUpdated());
	}

	/**
	 * A board as a board file, behind the board operations' gate: BOARD_READ and CONFIGURATION_READ
	 * on the board, judged by the export; a board the key holds nothing on is not found. The agent
	 * query of the same board needs the AGENT function; a key that applies boards must be able to
	 * read back what it applied -- the Terraform provider reads after every apply.
	 */
	@DgsData(parentType = "Query", field = "exportBoardProgrammatic")
	public AgentBoardService.BoardSpecDto exportBoardProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorizeBoardOperation(dfe, CallType.READ);
		return agentBoardService.exportBoardOfOrg(a.orgUuid(), dfe.getArgument("board"),
				authorizationService.boardAccess(a.ahp(), a.orgUuid()));
	}

	/**
	 * A board's perspectives by uuid, name and product, behind the same gate as its export: the
	 * Terraform provider maps a configured perspective to what the server holds with it, since the
	 * export writes a name several perspectives share as the uuid (task b9115d09, round 3).
	 */
	@DgsData(parentType = "Query", field = "exportBoardPerspectivesProgrammatic")
	public List<io.reliza.service.BoardPerspectiveService.BoardPerspective> exportBoardPerspectivesProgrammatic(
			DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorizeBoardOperation(dfe, CallType.READ);
		return agentBoardService.exportBoardPerspectivesOfOrg(a.orgUuid(), dfe.getArgument("board"),
				authorizationService.boardAccess(a.ahp(), a.orgUuid()));
	}

	@DgsData(parentType = "Query", field = "agentRolePresetsSpecProgrammatic")
	public AgentBoardService.RolePresetsSpecDto agentRolePresetsSpecProgrammatic(DgsDataFetchingEnvironment dfe)
			throws RelizaException {
		Authed a = authorize(dfe, CallType.READ);
		return agentBoardService.exportRolePresets(a.orgUuid());
	}

	// ---------- API keys (task RD3-11) ----------
	// Every key read and write needs CONFIGURATION_WRITE, the export and the list included: they are what a
	// caller mints secrets from. A caller declares, archives and mints for keys no stronger than itself.

	@DgsData(parentType = "Mutation", field = "applyApiKeysProgrammatic")
	public ApplyResult applyApiKeysProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorize(dfe, CallType.WRITE);
		// From the raw map: a setting sent as null clears it and one left out does not.
		ApiKeyDeclarativeService.ApiKeysSpecDto spec = ApiKeyDeclarativeService.apiKeysSpecFromInput(dfe.getArgument("spec"));
		return apiKeyDeclarativeService.applyApiKeys(a.orgUuid(), spec, dryRun(dfe), source(dfe),
				authorizationService.declaringCaller(a.ahp(), a.orgUuid()), a.whoUpdated());
	}

	@DgsData(parentType = "Query", field = "exportApiKeysProgrammatic")
	public ApiKeyDeclarativeService.ApiKeysSpecDto exportApiKeysProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorize(dfe, CallType.WRITE);
		List<String> names = dfe.getArgument("keys");
		return apiKeyDeclarativeService.exportApiKeys(a.orgUuid(), names);
	}

	@DgsData(parentType = "Query", field = "apiKeysProgrammatic")
	public List<ApiKeyDeclarativeService.KeyView> apiKeysProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorize(dfe, CallType.WRITE);
		List<String> names = dfe.getArgument("keys");
		return apiKeyDeclarativeService.keys(a.orgUuid(), names);
	}

	@DgsData(parentType = "Mutation", field = "archiveApiKeyProgrammatic")
	public ApiKeyDeclarativeService.KeyView archiveApiKeyProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorize(dfe, CallType.WRITE);
		return apiKeyDeclarativeService.archiveDeclaredKey(a.orgUuid(), dfe.getArgument("name"),
				authorizationService.declaringCaller(a.ahp(), a.orgUuid()), a.whoUpdated());
	}

	@DgsData(parentType = "Mutation", field = "mintApiKeySecretProgrammatic")
	public ApiKeyDeclarativeService.MintedSecret mintApiKeySecretProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorize(dfe, CallType.WRITE);
		Integer slot = dfe.getArgument("slot");
		return apiKeyDeclarativeService.mintSecret(a.orgUuid(), dfe.getArgument("key"), null == slot ? 0 : slot,
				Boolean.TRUE.equals(dfe.getArgument("rotate")), authorizationService.declaringCaller(a.ahp(), a.orgUuid()),
				a.whoUpdated());
	}

	@DgsData(parentType = "Query", field = "exportCatalogProgrammatic")
	public CatalogSpecDto exportCatalogProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorize(dfe, CallType.READ);
		List<String> names = dfe.getArgument("components");
		return declarativeConfigService.exportCatalog(a.orgUuid(), names);
	}

	@DgsData(parentType = "Query", field = "exportBranchesProgrammatic")
	public BranchesSpecDto exportBranchesProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorize(dfe, CallType.READ);
		String component = dfe.getArgument("component");
		return declarativeConfigService.exportBranches(a.orgUuid(), component);
	}
}
