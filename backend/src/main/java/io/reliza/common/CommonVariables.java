/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;

import com.fasterxml.jackson.annotation.JsonValue;

import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.AuthPrincipal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.Setter;

public class CommonVariables {
	private CommonVariables() {} // non-initializable class
	
	public static final Pattern UUID_REGEX =
			  Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
	
	public static final UUID DEFAULT_RESOURCE_GROUP = new UUID(0,0); // 00000000-0000-0000-0000-000000000000
	public static final String DEFAULT_RESOURCE_GROUP_NAME = "default";
	
	
	public static final String NAME_FIELD = "name";
	public static final String DESCRIPTION_FIELD = "description";
	public static final String ORGANIZATION_FIELD = "org";
	public static final String PARENT_FIELD = "parent";
	public static final String PARENT_TYPE_FIELD = "parentType";
	public static final String CHILDREN_FIELD = "children";
	public static final String COMPONENT_FIELD = "component";
	public static final String COMPONENTS_FIELD = "components";
	public static final String PRODUCT_FIELD = "product";
	public static final String PRODUCTS_FIELD = "products";
	public static final String STATUS_FIELD = "status";
	public static final String TYPE_FIELD = "type";
	public static final String BRANCH_FIELD = "branch";
	public static final String PARENT_BRANCH_FIELD = "parentBranch";
	public static final String BRANCHES_FIELD = "branches";
	public static final String TARGET_BRANCH_FIELD = "targetBranch";
	public static final String FEATURE_SET_FIELD = "featureSet";
	public static final String FEATURE_SETS_FIELD = "featureSets";
	public static final String PROJECTS_W_BRANCHES_FIELD = "childProjectsWithBranches";
	public static final String BASE_FEATURE_SET_NAME = "Base Feature Set";
	public static final String DEFAULT_BRANCH_FIELD = "defaultBranch";
	public static final String BASE_TYPE = "BASE";
	public static final String URI_FIELD = "uri";
	public static final String BRANCH_NAME_FIELD = "branchName";
	public static final String RELEASE_FIELD = "release";
	public static final String ORDER_FIELD = "order";
	public static final String COMMIT_FIELD = "commit";
	public static final String COMMITS_FIELD = "commits";
	public static final String COMMIT_RECORDS = "commitRecords";
	public static final String COMMIT_MESSAGE_FIELD = "commitMessage";
	public static final String COMMIT_MESSAGES_FIELD = "commitMessages";
	public static final String COMMIT_AUTHOR_FIELD = "commitAuthor";
	public static final String COMMIT_EMAIL_FIELD = "commitEmail";
	public static final String AUTHENTICATION_FIELD = "authentication";
	public static final String VERSION_FIELD = "version";
	public static final String MARKETING_VERSION_FIELD = "marketingVersion";
	public static final String VERSIONS_FIELD = "versions";
	public static final String NOTES_FIELD = "notes";
	public static final String IDENTIFIER_FIELD = "identifier";
	public static final String ARTIFACTS_FIELD = "artifacts";
	public static final String ARTIFACT_DETAILS_FIELD = "artifactDetails";
	public static final String ARTIFACT_FIELD = "artifact";
	public static final String VCS_TAG_FIELD = "vcsTag";
	public static final String VCS_BRANCH_FIELD = "vcsBranch";
	public static final String SOURCE_CODE_ENTRY_FIELD = "sourceCodeEntry";
	public static final String SOURCE_CODE_ENTRY_DETAILS_FIELD = "sourceCodeEntryDetails";
	public static final String BUILD_ID_FIELD = "buildId";
	public static final String BUILD_URI_FIELD = "buildUri";
	public static final String CICD_META_FIELD = "cicdMeta";
	public static final String DIGESTS_FIELD = "digests";
	public static final String PROJECT_RELEASE_FIELD = "projectRelease";
	public static final String UUIDS_FIELD = "uuids";
	public static final String UUID_FIELD = "uuid";
	public static final String IS_INTERNAL_FIELD = "isInternal";
	public static final String RELEASES_FIELD = "releases";
	public static final String TARGET_RELEASES_FIELD = "targetReleases";
	public static final String PARENT_RELEASES_FIELD = "parentReleases";
	public static final String FIRST_RELEASE_FIELD = "firstRelease";
	public static final String LAST_RELEASE_FIELD = "lastRelease";
	public static final String CONFIGURABLE_PROPS_FIELD = "configurableProps";
	public static final String INFO_PROPS_FIELD = "infoProps";
	public static final String PROPERTIES_FIELD = "properties";
	public static final String PROPERTY_FIELD = "property";
	public static final String SOFTWARE_VERSIONS_FIELD = "softwareVersions";
	public static final String INSTANCE_FIELD = "instance";
	public static final String INSTANCE_UNIT_FIELD = "instanceUnit";
	public static final String INSTANCE_UNITS_FIELD = "instanceUnits";
	public static final String DATA_TYPE_FIELD = "dataType";
	public static final String SCOPE_FIELD = "scope";
	public static final String VALUE_FIELD = "value";
	public static final String EMAIL_FIELD = "email";
	public static final String GITHUB_ID_FIELD = "githubId";
	public static final String OWNER_FIELD = "owner";
	public static final String DATE_PLANNED_FIELD = "datePlanned";
	public static final String DATE_ACTUAL_FIELD = "dateActual";
	public static final String DURATION_FIELD = "duration";
	public static final String SOURCE_RELEASE_FIELD = "sourceRelease";
	public static final String TARGET_RELEASE_FIELD = "targetRelease";
	public static final String SOURCE_PROPERTY_MAP_FIELD = "sourcePropertyMap";
	public static final String TARGET_PROPERTY_MAP_FIELD = "targetPropertyMap";
	public static final String COMMENT_FIELD = "comment";
	public static final String APP_SERVICE_FIELD = "appService";
	public static final String ORCHESTRATOR_FIELD = "orchestrator";
	public static final String USER_FIELD = "user";
	public static final String USERS_FIELD = "users";
	public static final String GLOBAL_ADMIN_FIELD = "isGlobalAdmin";
	public static final String API_KEY_ID_FIELD = "apiKeyId";
	public static final String API_KEY_FIELD = "apiKey";
	public static final String PLACEHOLDER_RELEASE_VERSION = "Placeholder Release";
	public static final String AGENT_DATA_FIELD = "agentData";
	public static final String VERSION_SCHEMA_FIELD = "versionSchema";
	public static final String MARKETING_VERSION_SCHEMA_FIELD = "marketingVersionSchema";
	public static final String VERSION_TYPE_FIELD = "versionType";
	public static final String MODIFIER_FIELD = "modifier";
	public static final String METADATA_FIELD = "metadata";
	public static final String ACTION_FIELD = "action";
	public static final String POLICIES_ACCEPTED_FIELD = "policiesAccepted";
	public static final String PRIMARY_EMAIL_VERIFIED_FIELD = "emailVerified";
	public static final String CREATED_TYPE_FIELD = "createdType";
	public static final String LAST_UPDATED_BY_FIELD = "lastUpdatedBy";
	public static final String LAST_UPDATED_IP_ADDRESS_FIELD = "lastUpdatedIp";
	public static final String CREATED_DATE_FIELD = "createdDate";
	public static final String ENVIRONMENT_FIELD = "environment";
	public static final String OPTIONAL_RELEASES_FIELD = "optionalReleases";
	public static final String REVISION_FIELD = "revision";
	public static final String UPDATE_TYPE_FIELD = "updateType"; // programmatic or manual
	public static final String TARGET_TYPE_FIELD = "targetType";
	public static final String TARGET_UUID_FIELD = "targetUuid";
	public static final String DEFAULT_VALUE_FIELD = "defaultValue";
	public static final String NAMESPACE_FIELD = "namespace";
	public static final String CLUSTER_WIDE_NAMESPACE = "CLUSTER--WIDE";
	public static final String DEFAULT_NAMESPACE = "default";
	public static final String DEFAULT_CONFIGURATION = "default";
	public static final String DEFAULT_SENDER_ID = "default";
	public static final String IMAGES_FIELD = "images";
	public static final String SENDER_ID_FIELD = "senderId";
	public static final String HASH_FIELD = "hash"; // hash and digest we use interchangeably
	public static final String QUERY_FIELD = "query";
	public static final String DATE_FROM_FIELD = "dateFrom";
	public static final String DATE_TO_FIELD = "dateTo";
	public static final String MATRIX_FIELD = "matrix";
	public static final String APPROVALS_FIELD = "approvals";
	public static final String VCS_REPOSITORY_FIELD = "vcsRepository";
	public static final String SECRET_FIELD = "secret";
	public static final String TAGS_FIELD = "tags";
	public static final String OBJECT_FIELD = "object";
	public static final String PERMISSIONS_FIELD = "permissions";
	public static final String INVITEES_FIELD = "invitees";
	public static final String ALL_EMAILS_FIELD = "allEmails";
	public static final String WHO_INVITED_FIELD = "whoInvited";
	public static final String IS_PRIMARY_FIELD = "isPrimary";
	public static final String IS_VERIFIED_FIELD = "isVerified";
	public static final String EXPIRY_FIELD = "expiry";
	public static final String FEATURE_BRANCH_VERSION_FIELD = "featureBranchVersioning";
	public static final String IS_ACCEPT_MARKETING = "isAcceptMarketing";
	public static final String LIFECYCLE_FIELD = "lifecycle";
	public static final String EVENT_FIELD = "event";
	public static final String CONTENT_FIELD = "content";
	public static final String PACKAGE_TYPE_FIELD = "packageType";
	public static final String PUBLISHER_FIELD = "publisher";
	public static final String GROUP_FIELD = "group";
	public static final String ARTIFACT_VERSION_FIELD = "artifactVersion"; // may be different from release version, i.e. in Google App Engine deployment
	public static final String ENDPOINT_FIELD = "endpoint";
	public static final String INTEGRATION_FIELD = "integration";
	public static final String INTEGRATIONS_FIELD = "integrations";
	public static final String DEPENDENCIES_FIELD = "dependencies";
	public static final Object INTEGRATION_UUID_FIELD = "integrationUuid";
	public static final String PARAMETERS_FIELD = "parameters";
	public static final String ENV_BRANCH_MAP_FIELD = "envBranchMap";
	public static final String OAUTH_ID_FIELD = "oauthId";
	public static final String OAUTH_TYPE_FIELD = "oauthType";
	public static final String IS_REPOSITORY_ENABLED = "isRepositoryEnabled";
	public static final String ONLY_VERSION_FLAG = "onlyVersion";
	public static final String REGISTRY_PROJECT_ID_FIELD = "project_id";
	public static final String REGISTRY_PROJECT_NAME_FIELD = "project_name";
	public static final String REGISTRY_PROJECT_PUBLIC_FIELD = "public";
	public static final String REGISTRY_ROBOT_ID_FIELD = "registryRobotId";
	public static final String ALL_REGISTRY_ROBOTS_FIELD = "allRegistryRobots";
	public static final String ALL_REGISTRY_USERS_FIELD = "allRegistryUsers";
	public static final String REGISTRY_USER_ID_FIELD = "registryUserId";
	public static final String REGISTRY_PROJECTS_FIELD = "registryProjects";
	public static final String All_REGISTRY_PROJECT_MEMBERS_FIELD = "allRegistryProjectMembers";
	public static final String VISIBILITY_FIELD = "visibility";
	public static final String ID_FIELD = "id";
	public static final String USERNAME_FIELD = "username";
	public static final String USER_ID_FIELD = "user_id";
	public static final String ENTITY_NAME_FIELD = "entity_name";
	public static final String KEY_ORDER_FIELD = "keyOrder";
	public static final String STATE_FIELD = "state";
	public static final String CHANGE_FIELD = "change";
	public static final String CHANGES_FIELD = "changes";
	public static final String CHANGE_TYPE_FIELD = "changeType";
	public static final String TICKET_FIELD = "ticket";
	public static final String TICKETS_FIELD = "tickets";
	public static final String TICKET_SUBJECT_FIELD = "ticketSubject";
	public static final String DONE_IN_RELEASE_FIELD = "doneInRelease";
	public static final String TICKETING_SYSTEM_URI_FIELD = "ticketingSystemUri";
	public static final String DATE_DONE_FIELD = "dateDone";
	public static final String CLOUD_ID_FIELD = "cloud_id";
	public static final String DOMAIN_FIELD = "domain";
	public static final String JIRA_INEGRATION_DATA = "jiraIntegrationData";
	public static final String WEBHOOK_ID_FIELD = "webhookId";
	public static final String SUMMARY_FIELD = "summary";
	public static final String NUMBER_FIELD = "number";
	public static final String PULL_REQUEST_DATA_FEILD = "pullRequestData";
	public static final String TITLE_FIELD = "title";
	public static final String CLOSED_DATE_FIELD = "closedDate";
	public static final String MERGED_DATE_FIELD = "mergedDate";
	public static final String RAW_TEXT_FIELD = "rawText";
	public static final String LINKIFIED_TEXT_FIELD = "linkifiedText";
	public static final String PROTECTED_ENVIRONMENTS_FEILD = "protectedEnvironments";
	public static final String KEY_FIELD = "key";
	public static final String PUBLIC_SSH_KEYS_FIELD = "publicSshKeys";
	public static final String MEDIA_TYPE_FIELD = "mediaType";
	public static final String DIGEST_FEILD = "digest";
	public static final String SIZE_FEILD = "size";
	public static final String ACCESS_DATE_FIELD = "accessDate";
	public static final String IP_ADDRESS_FIELD = "ipAddress";
	public static final String NOT_MATCHING_SINCE_FIELD = "notMatchingSince";
	public static final String ALERTS_ENABLED = "alertsEnabled";
	public static final String INSTANCES_FIELD = "instances";
	public static final String INSTANCE_TYPE = "instanceType";
	public static final String EPHEMERAL_PROPERTIES_FIELD = "ephemeralProperties";
	public static final String SPAWN_TYPE_FIELD = "spawnType";
	public static final String CLUSTER_ID_FIELD = "clusterId";
	public static final String REBOM_UUID_FIELD = "rebomUuid";
	public static final String REBOMS_FIELD = "reboms";
	public static final String BOM_INPUTS_FIELD = "bomInputs";
	public static final String BOM_FIELD = "bom";
	public static final String FS_BOM_FIELD = "fsBom";
	public static final String DOWNLOAD_LINKS_FIELD = "downloadLinks";
	public static final String INVENTORY_TYPES_FIELD = "inventoryTypes";
	public static final String DOCUMENT_TYPE_FIELD = "documentType";
	public static final String IDENTIFIERS_FIELD = "identifiers";
	public static final String INTERNAL_BOMS_FIELD = "internalBoms";
	public static final String EXTERNAL_BOMS_FIELD = "externalBoms";
	public static final String BOM_FORMAT_FIELD = "bomFormat";
	public static final String DATE_FIELD = "date";

	public static final UUID EXTERNAL_PROJ_ORG_UUID = new UUID(0,0); // 00000000-0000-0000-0000-000000000000
	public static final String EXTERNAL_PROJ_ORG_STRING = EXTERNAL_PROJ_ORG_UUID.toString();
	
	public static final String JOIN_SECRET_SEPARATOR = "-.-";
	
	public static final String SPAWNED_INSTANCE_AUTH_CLASS = "spawnedInstance";
	
	public static final UUID PERMISSION_TO_SPAWN = new UUID(0,2); // 00000000-0000-0000-0000-000000000002
	
	public static final String DETAILS_UNAVAILABLE_MESSAGE = "Change details unavailable";
	
	public static final String ADDED_ON_COMPLETE = "addedOnComplete";
	
	public static final String BASE_INTEGRATION_IDENTIFIER = "base";
	
	public record ApprovalRole (String id, String displayView) {}
	public static final Set<ApprovalRole> DEFAULT_APPROVAL_ROLES = Set.of(
		new ApprovalRole("DEV", "Development"),
		new ApprovalRole("QA", "QA"),
		new ApprovalRole("QA_AUTO", "Automated QA"),
		new ApprovalRole("PM", "Project Management"),
		new ApprovalRole("MARKETING", "Marketing"),
		new ApprovalRole("CLIENT", "Client"),
		new ApprovalRole("EXEC", "Exec")
	);

	/*** Artifact Tags ***/
	public static final String TAG_FIELD = "tag";
	public static final String FILE_NAME_FIELD = "fileName";
	public static final String DOWNLOADABLE_ARTIFACT = "downloadableArtifact";
	/*** Release Tags ***/
	public static final String HELM_APP_VERSION = "HELM_APP_VERSION";
	
	public enum ProgrammaticType {
		MANUAL,
		AUTO,
		MANUAL_AND_AUTO,
		API,
		TEST;
		
		private ProgrammaticType () {}
	}
	
	public enum AuthPrincipalType {
		MANUAL,
		PROGRAMMATIC;
		
		private AuthPrincipalType() {}
	}
	
	public static final List<StatusEnum> RELEASE_STATUSES = Collections.unmodifiableList(List.of(StatusEnum.DRAFT,
			StatusEnum.COMPLETE, StatusEnum.REJECTED));
	
	public enum ApprovalState {
		APPROVED,
		DISAPPROVED,
		UNSET
	}
	
	public enum StatusEnum {
		ANY,
		ACTIVE,
		APPROVED,
		ARCHIVED,
		COMPLETE,
		DRAFT,
		IGNORED,
		PENDING,
		REJECTED,
		REQUIRED,
		TRANSIENT
		;
		
		public static StatusEnum get(String statusString) {
			StatusEnum retSe = null;
			for (StatusEnum se : StatusEnum.values()) {
				if (se.toString().equalsIgnoreCase(statusString)) {
					retSe = se;
					break;
				}
			}
			return retSe;
		}
		
		private StatusEnum() {}
	}
	
	public enum RegistryProjectStatus {
		PUBLIC,
		PRIVATE,
		RELIZAARTIFACTS;

		private RegistryProjectStatus () {}
	}
	
	public enum OauthType {
		GITHUB,
		GOOGLE,
		MICROSOFT,
		RELIZA_KEYCLOAK_OWN,
		RELIZA_KEYCLOAK_GITHUB,
		RELIZA_KEYCLOAK_MICROSOFT,
		RELIZA_KEYCLOAK_GOOGLE;
		
		private OauthType () {}
	}
	
	public enum UserStatus {
		ACTIVE,
		INACTIVE;
		
		private UserStatus () {}
	}
	
	public enum UserGroupStatus {
		ACTIVE,
		INACTIVE;
		
		private UserGroupStatus () {}
	}
	
	public enum TableName {
		API_KEYS("api_keys"),
		API_KEY_ACCESS("api_key_access"),
		APP_SERVICES("app_service"),
		ARTIFACTS(CommonVariables.ARTIFACTS_FIELD),
		DELIVERABLES("deliverables"),
		RESOURCE_GROUPS("resource_groups"),
		APPROVAL_MATRIX("approval_matrix"),
		APPROVAL_ENTRY("approval_entries"),
		APPROVAL_POLICY("approval_policies"),
		AUDIT("audit"),
		BRANCHES("branches"),
		CHANGES("changes"),
		COMPONENTS(CommonVariables.COMPONENTS_FIELD),
		INSTANCES("instances"),
		INSTANCE_UNITS("instances"),
		INTEGRATIONS("integrations"),
		MARKETING_RELEASES("marketing_releases"),
		ORCHESTRATORS("orchestrators"),
		ORGANIZATIONS("organizations"),
		PROPERTIES(CommonVariables.PROPERTIES_FIELD),
		RELEASES(CommonVariables.RELEASES_FIELD),
		RELEASE_REBOMS("release_reboms"),
		TICKETS(CommonVariables.TICKETS_FIELD),
		SECRETS("secrets"),
		SOURCE_CODE_ENTRIES("source_code_entries"),
		USERS("users"),
		USER_GROUPS("user_groups"),
		VARIANTS("variants"),
		VCS_REPOSITORIES("vcs_repositories"),
		VERSION_ASSIGNMENTS("version_assignments"),
		VULN_ANALYSIS("vuln_analysis")
		;
		
		private String name;
		
		private TableName(String name) {
			this.name = name;
		}
		
		@Override
		@JsonValue
		public String toString() {
			return this.name;
		}
		
		public static TableName get(String name) {
			TableName retTn = null;
			for (TableName tn : TableName.values()) {
				if (tn.toString().equalsIgnoreCase(name)) {
					retTn = tn;
					break;
				}
			}
			return retTn;
		}
	}
	
	public enum AuthorizationStatus {
		AUTHORIZED,
		FORBIDDEN;
		
		private AuthorizationStatus() {}
	}
	
	@Data
	@Builder()
	public static class AuthHeaderParse implements AuthPrincipal {
		@Setter(AccessLevel.PRIVATE) private ApiTypeEnum type;
		@Setter(AccessLevel.PRIVATE) private UUID objUuid;
		@Setter(AccessLevel.PRIVATE) private String apiKey;
		@Setter(AccessLevel.PRIVATE) private String apiKeyId;
		@Setter(AccessLevel.PRIVATE) private UUID orgUuid;
		@Setter(AccessLevel.PRIVATE) private String keyOrder;
		@Setter(AccessLevel.PRIVATE) private String remoteIp;
		private AuthorizationStatus authStatus = AuthorizationStatus.FORBIDDEN;
		
		
		public static AuthHeaderParse parseAuthHeader(HttpHeaders headers, String ipAddr) {
			AuthHeaderParseBuilder ahpBuilder = AuthHeaderParse.builder();
			final String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
			// based on parser from Gitblit project - Apache 2.0
			if (authorization != null && authorization.toLowerCase().startsWith("basic")) {
			    // Authorization: Basic base64credentials
			    String base64Credentials = authorization.substring("Basic".length()).trim();
			    byte[] credDecoded = Base64.getDecoder().decode(base64Credentials);
			    String credentials = new String(credDecoded, StandardCharsets.UTF_8);
			    // credentials = username:password
			    final String[] values = credentials.split(":", 2);
			    ahpBuilder.apiKey(values[1]);
			    
			    // resolve key order if present
			    String[] orderParse = values[0].split("__ord__", 2);
			    if (orderParse.length > 1) {
			    	ahpBuilder.keyOrder = orderParse[1];
			    }
			    ahpBuilder.apiKeyId = values[0];
			    
			    // resolve uuids and type
				String[] typeProj = orderParse[0].split("__", 0);
			    ahpBuilder.type(ApiTypeEnum.valueOf(typeProj[0]));
				ahpBuilder.objUuid(UUID.fromString(typeProj[1]));
				if(typeProj.length > 2 && typeProj[2].startsWith("ORGANIZATION")){
					ahpBuilder.orgUuid(UUID.fromString(typeProj[3]));
				} else if (typeProj[0].startsWith("ORGANIZATION")) {
					ahpBuilder.orgUuid(ahpBuilder.objUuid);
				}
				ahpBuilder.remoteIp(ipAddr);
			}
			return ahpBuilder.build();
		}



		@Override
		public AuthPrincipalType getAuthPrincipalType() {
			return AuthPrincipalType.PROGRAMMATIC;
		}
		
	}
	
	public enum CallType {
		ADMIN,
		GLOBAL_ADMIN,
		INIT, // initial actions - i.e. create 1st org
		READ,
		WRITE;
		
		private CallType() {}
	}
	
	public enum RequestType {
		REST,
		GRAPHQL;
		
		private RequestType() {}
	}
	
	public enum Removable {
		YES,
		NO;
	}
	
	public record TagRecord (String key, String value, Removable removable) {
		public TagRecord(String key, String value){
			this(key, value, Removable.YES);
		}
	}
	
	public record VersionResponse (String version, String dockerTagSafeVersion, String changelog) {}
	
	public enum PullRequestState {
		OPEN,
		CLOSED,
		MERGED;

		public static PullRequestState get(String stateString) {
			PullRequestState retPrs = null;
			for (PullRequestState prs : PullRequestState.values()) {
				if (prs.toString().equalsIgnoreCase(stateString)) {
					retPrs = prs;
					break;
				}
			}
			return retPrs;
		}
	}
	
	public enum VisibilitySetting {
		PUBLIC,
		ORG_INTERNAL,
		ADMIN_INVITATION
	}
	
	public enum ReleaseEventType {
		NEW_RELEASE,
		RELEASE_CANCELLED,
		RELEASE_REJECTED,
		RELEASE_DRAFTED,
		RELEASE_ASSEMBLED,
		RELEASE_SCHEDULED;
	}
	
	public enum RegistryBotPermissionType {
		PULL_REPO,
		PUSH_REPO,
		PUSH_CHART,
		PULL_CHART;

	}
	
	public enum IntegrateType {
		FOLLOW,
		TARGET,
		NONE;
	}
	
	public enum InstallationType {
		DEMO,
		OSS,
		SAAS,
		MANAGED_SERVICE;
	}
	
}
