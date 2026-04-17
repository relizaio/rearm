/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.oss;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.RelizaObject;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.UpdateComponentDto;
import io.reliza.service.AuthorizationService;
import io.reliza.service.ComponentService;
import io.reliza.service.GetComponentService;
import io.reliza.service.IntegrationService;
import io.reliza.service.UserService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.saas.ApprovalPolicyService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class OssComponentDataFetcher {

	@Autowired
	private UserService userService;

	@Autowired
	private ComponentService componentService;

	@Autowired
	private GetComponentService getComponentService;

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private VcsRepositoryService vcsRepositoryService;

	@Autowired
	private IntegrationService integrationService;

	@Autowired
	private ApprovalPolicyService approvalPolicyService;

	@PreAuthorize("isAuthenticated()")
	@Transactional
	@DgsData(parentType = "Mutation", field = "updateComponent")
	public ComponentData updateComponent(DgsDataFetchingEnvironment dfe) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Map<String, Object> componentUpdateData = dfe.getArgument("component");

		UpdateComponentDto ucdto = io.reliza.common.Utils.OM.convertValue(componentUpdateData, UpdateComponentDto.class);
		UUID componentUuid = ucdto.getUuid();
		List<RelizaObject> ros = new LinkedList<>();
		Optional<ComponentData> ocd = getComponentService.getComponentData(componentUuid);
		RelizaObject ro = ocd.isPresent() ? ocd.get() : null;
		ros.add(ro);
		if (null != ucdto.getVcs()) ros.add(vcsRepositoryService.getVcsRepositoryData(ucdto.getVcs()).orElseThrow());
		if (null != ucdto.getApprovalPolicy()) ros.add(approvalPolicyService.getApprovalPolicyData(ucdto.getApprovalPolicy()).orElseThrow());

		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.COMPONENT, componentUuid, ros, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());

		List<RelizaObject> orgCheckList = new LinkedList<>();
		orgCheckList.add(ro);
		if (null != ocd.get().getVcs()) orgCheckList.add(vcsRepositoryService
				.getVcsRepositoryData(ocd.get().getVcs()).get());
		if (null != ucdto.getOutputTriggers() && !ucdto.getOutputTriggers().isEmpty()) {
			for (var trigger : ucdto.getOutputTriggers()) {
				if (null != trigger.getIntegration()) {
					orgCheckList.add(integrationService.getIntegrationData(trigger.getIntegration()).get());
				}
				if (null != trigger.getVcs()) {
					orgCheckList.add(vcsRepositoryService
							.getVcsRepositoryData(trigger.getVcs()).get());
				}
				if (null != trigger.getUsers() && trigger.getUsers().isEmpty()) {
					authorizationService.doUsersBelongToOrg(trigger.getUsers(), ro.getOrg());
				}
				if (StringUtils.isNotEmpty(trigger.getNotificationMessage())) {
					trigger.setNotificationMessage(Jsoup.clean(trigger.getNotificationMessage(), Safelist.basic()));
				}
			}
		}
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.COMPONENT, componentUuid, orgCheckList, CallType.WRITE);

		try {
			List<ReleaseOutputEvent> processedOutputTriggers = new LinkedList<>();
			if (null != ucdto.getOutputTriggers() && !ucdto.getOutputTriggers().isEmpty()) {
				for (var trigger : ucdto.getOutputTriggers()) {
					IntegrationType it = null;
					if (trigger.getType() == EventType.INTEGRATION_TRIGGER) {
						Set<IntegrationType> supportedTypes = Set.of(IntegrationType.ADO, IntegrationType.GITHUB,
								IntegrationType.GITLAB, IntegrationType.JENKINS);
						IntegrationData id = integrationService.getIntegrationData(trigger.getIntegration()).get();
						if (!supportedTypes.contains(id.getType())) {
							throw new RuntimeException("Unsupported trigger type");
						}
						it = id.getType();
					}
					try {
						var processedTrigger = UpdateComponentDto
								.convertReleaseOutputEventFromInput(trigger, it);
						processedOutputTriggers.add(processedTrigger);
					} catch (Exception e) {
						log.error("Error on processing output trigger on component update", e);
						throw new RuntimeException("Error on processing output trigger");
					}
				}
			}
			ComponentDto cdto = UpdateComponentDto.convertToComponentDto(ucdto, processedOutputTriggers);
			return ComponentData.dataFromRecord(componentService.updateComponent(cdto, wu));
		} catch (RelizaException re) {
			throw new AccessDeniedException(re.getMessage());
		}
	}
}
