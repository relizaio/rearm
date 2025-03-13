/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.common.CommonVariables.ReleaseEventType;
import io.reliza.common.Utils;
import io.reliza.model.BranchData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.TextPayload;
import io.reliza.ws.RelizaConfigProps;

@Service
public class NotificationService {
	
	@Autowired
	private IntegrationService integrationService;
	
	@Autowired
	private GetComponentService getComponentService;

	@Autowired
	private BranchService branchService;
	
	@Autowired
	private SourceCodeEntryService sourceCodeEntryService;
	
	@Autowired
	private VcsRepositoryService vcsRepositoryService;

	@Autowired
	private DeliverableService deliverableService;
	
	private RelizaConfigProps relizaConfigProps;
	
	@Autowired
    public void setProps(RelizaConfigProps relizaConfigProps) {
        this.relizaConfigProps = relizaConfigProps;
    }
	
    private UserService userService;
    
    @Autowired
    public NotificationService(@Lazy UserService userService) {
      this.userService = userService;
    }
	
	@Async
	public void processReleaseEvent (ReleaseData rd, ReleaseEventType eventType) {
		BranchData bd = branchService.getBranchData(rd.getBranch()).get();
		processReleaseEvent(rd, bd, eventType);
	}
	
	/**
	 * This method parses event and does processing on it - for now just sends slack notifications but in the future
	 * may publish to a MQ solution 
	 */
	
	@Async
	public void processReleaseEvent (ReleaseData rd, BranchData bd, ReleaseEventType eventType) {
		
		Optional<ComponentData> opd = getComponentService.getComponentData(rd.getComponent());
		
		if (!opd.isPresent())
			return;
		
		ComponentData pd = opd.get();
		
		// ignore any external org
		if (CommonVariables.EXTERNAL_PROJ_ORG_UUID.equals(pd.getOrg())) return;
		
		String projUri = null;
		List<TextPayload> payloadWrapper = new LinkedList<>();
		String productOrComponent = null;
		String branchOrFeatureSet = null;
		String releaseUri = relizaConfigProps.getBaseuri() + "/release/show/" + rd.getUuid();

		if (pd.getType() == ComponentType.COMPONENT) {
			projUri = relizaConfigProps.getBaseuri() + "/componentsOfOrg/" + pd.getOrg() + "/" + pd.getUuid();
			productOrComponent = "component";
			branchOrFeatureSet = "branch";
		} else {
			projUri = relizaConfigProps.getBaseuri() + "/productsOfOrg/" + pd.getOrg() + "/" + pd.getUuid();
			productOrComponent = "product";
			branchOrFeatureSet = "feature set";
		}
		
		String branchUri = projUri + "/" + bd.getUuid();
		
		switch (eventType) {
		case NEW_RELEASE:
			payloadWrapper.add(
					new TextPayload("New " + productOrComponent + " release created for " + productOrComponent, null)
				);
			break;
		case RELEASE_DRAFTED:
			payloadWrapper.add(
					new TextPayload(StringUtils.capitalize(productOrComponent) + " release moved to draft for " + productOrComponent, null)
				);
			break;
		case RELEASE_ASSEMBLED:
			payloadWrapper.add(
					new TextPayload(StringUtils.capitalize(productOrComponent) + " release assembled for " + productOrComponent, null)
				);
			break;
		case RELEASE_CANCELLED:
			payloadWrapper.add(
					new TextPayload(StringUtils.capitalize(productOrComponent) + " release cancelled for " + productOrComponent, null)
				);
			break;
		case RELEASE_REJECTED:
			payloadWrapper.add(
					new TextPayload(StringUtils.capitalize(productOrComponent) + " release rejected for " + productOrComponent, null)
				);
			break;
		case RELEASE_SCHEDULED:
			payloadWrapper.add(
					new TextPayload(StringUtils.capitalize(productOrComponent) + " release scheduled for " + productOrComponent, null)
				);
			break;
		}
		
		payloadWrapper.add(TextPayload.WHITESPACE_SEPARATOR_TEXT_PAYLOAD);
		payloadWrapper.add(new TextPayload(pd.getName(), URI.create(projUri)));
		payloadWrapper.add(TextPayload.COMMA_SEPARATOR_TEXT_PAYLOAD);
		payloadWrapper.add(new TextPayload(branchOrFeatureSet, null));
		payloadWrapper.add(TextPayload.WHITESPACE_SEPARATOR_TEXT_PAYLOAD);
		payloadWrapper.add(new TextPayload(bd.getName(), URI.create(branchUri)));
		payloadWrapper.add(TextPayload.COMMA_SEPARATOR_TEXT_PAYLOAD);
		payloadWrapper.add(new TextPayload("version", null));
		payloadWrapper.add(TextPayload.WHITESPACE_SEPARATOR_TEXT_PAYLOAD);
		payloadWrapper.add(new TextPayload(rd.getVersion(), URI.create(releaseUri)));
		
		//payload += " <" + projUri + "|" + pd.getName() + 
		//		">, " + branchOrFeatureSet + " <" + branchUri + "|" + bd.getName() +
		//		">, version <" + releaseUri + "|" + rd.getVersion() + ">";

		if(rd.getSourceCodeEntry() != null && pd.getVcs() != null){
			var osced = sourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry());
			var ovcsrd = vcsRepositoryService.getVcsRepositoryData(pd.getVcs());
			if(osced.isPresent() && ovcsrd.isPresent()) {
				payloadWrapper.add(new TextPayload(", commit ", null));
				String commitStr = osced.get().getCommit().substring(0, Math.min(osced.get().getCommit().length(), 7));
				String commitUri = Utils.linkifyCommit(ovcsrd.get().getUri(), osced.get().getCommit());
				payloadWrapper.add(new TextPayload(commitStr, URI.create(commitUri)));
				payloadWrapper.add(TextPayload.COMMA_SEPARATOR_TEXT_PAYLOAD);
				payloadWrapper.add(new TextPayload("\"" + osced.get().getCommitMessage() + "\"", null));
			}
		}

		if (rd.getArtifacts().size() != 0) {
			List<TextPayload> buildUris = new ArrayList<>();
			for (UUID uuid : rd.getArtifacts()) {
				var odd = deliverableService.getDeliverableData(uuid);
				if (odd.isPresent() && !ObjectUtils.isEmpty(odd.get().getSoftwareMetadata()) &&
						!ObjectUtils.isEmpty(odd.get().getSoftwareMetadata().getBuildUri())) {
					buildUris.add(
							new TextPayload(":package:", URI.create(odd.get().getSoftwareMetadata().getBuildUri()))
						);
				}
			}
			if (buildUris.size() > 0) {
				payloadWrapper.add(TextPayload.COMMA_SEPARATOR_TEXT_PAYLOAD);
				payloadWrapper.add(new TextPayload("artifacts:", null));
				for (int i=0; i<buildUris.size(); i++) {
					payloadWrapper.add(buildUris.get(i));
					if (i < buildUris.size() - 1) payloadWrapper.add(TextPayload.COMMA_SEPARATOR_TEXT_PAYLOAD);
				}
			}
		}

		if(rd.getLastUpdatedBy() != null && (rd.getCreatedType() == ProgrammaticType.MANUAL || rd.getCreatedType() == ProgrammaticType.MANUAL_AND_AUTO)){
			var oud = userService.getUserData(rd.getLastUpdatedBy());
			if(oud.isPresent()) {
				payloadWrapper.add(new TextPayload(" by ", null));
				payloadWrapper.add(new TextPayload(oud.get().getName(), URI.create("mailto:" + oud.get().getEmail())));
			}
		}

		integrationService.sendNotification(rd.getOrg(), IntegrationType.SLACK, CommonVariables.BASE_INTEGRATION_IDENTIFIER, payloadWrapper);
		integrationService.sendNotification(rd.getOrg(), IntegrationType.MSTEAMS, CommonVariables.BASE_INTEGRATION_IDENTIFIER, payloadWrapper);
	}
}

