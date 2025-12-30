/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import io.reliza.common.CommonVariables;

class VariableQueries {
	
	private VariableQueries() {} // non-initializable


	/** API Key Access **/
	
	protected static final String FIND_API_KEY_ACCESS_BY_ORGANIZATION = """
		 		SELECT DISTINCT ON (aka.api_key_uuid) aka.* FROM rearm.api_key_access aka
				where aka.org = :orgUuid 
				ORDER BY aka.api_key_uuid, aka.access_date DESC
		 		""";
	
	 protected static final String FIND_API_KEY_ACCESS_BY_ORGANIZATION_KEY_ID = """
		 		SELECT DISTINCT ON (aka.api_key_uuid) aka.* FROM rearm.api_key_access aka
				where aka.org = :orgUuid and aka.api_key_uuid = :keyUuid 
				ORDER BY aka.api_key_uuid, aka.access_date DESC
		 		""";
	 
	 protected static final String COUNT_KEY_ACCESS_BY_KEY_ID = """
		 		SELECT COUNT(aka.*) FROM rearm.api_key_access aka
				where aka.api_key_uuid = :keyUuid
		 		""";
	 
	 /** API Key **/
 
	protected static final String FIND_API_KEY_BY_UUID = "SELECT * from rearm.api_keys ak WHERE (ak.api_key IS NOT NULL OR "
			+ "(ak.object_type = 'REGISTRY_ORG' OR ak.object_type = 'REGISTRY_USER')) AND ak.uuid = :uuid";

	protected static final String FIND_API_KEY_BY_ID_AND_TYPE = "SELECT * from rearm.api_keys ak WHERE ak.api_key IS NOT NULL and "
			+ "ak.object_uuid = :uuid and ak.object_type = :type";
	
	protected static final String FIND_REGISTRY_API_KEY = "SELECT * from rearm.api_keys ak WHERE "
			+ "ak.object_uuid = :objUuid and ak.object_type = :type and ak.org = :orgUuid";

	protected static final String FIND_USER_API_KEY_BY_USER_ID_AND_ORG = "SELECT * from rearm.api_keys ak WHERE ak.api_key IS NOT NULL and "
			+ "ak.object_uuid = :userUuid and ak.object_type = 'USER' and ak.org = :orgUuid";
	
	protected static final String FIND_API_KEYS_BY_ORGANIZATION = "SELECT * from rearm.api_keys ak WHERE (ak.api_key IS NOT NULL OR "
			+ "((ak.object_type = 'REGISTRY_ORG' OR ak.object_type = 'REGISTRY_USER') AND "
			+ "cast (ak.record_data->>'registryRobotId' as integer) > -1)) and ak.org = :orgUuid";
	 
	 /** Organization **/ 
	
	
	 /** User **/
	 
	protected static final String FIND_USERS_BY_ORGANIZATION = """
			SELECT * FROM rearm.users
			WHERE jsonb_contains(record_data, jsonb_build_object('org', jsonb_build_array(:orgUuidAsString))) 
			AND (record_data->>'status' = 'ACTIVE')
			""";

	protected static final String FIND_USER_BY_ID_WITH_ORGANIZATION = """
			SELECT * FROM rearm.users
			WHERE uuid = :userUuid 
			AND jsonb_contains(record_data, jsonb_build_object('org', jsonb_build_array(:orgUuidAsString))) 
			AND (record_data->>'status' = 'ACTIVE')
			""";
	
	protected static final String FIND_USER_BY_EMAIL = """
		WITH search_emails AS (select uuid as emuuid, jsonb_array_elements(record_data->'allEmails')->>'email'
		AS email from rearm.users)
		SELECT * FROM rearm.users u, search_emails WHERE ((u.uuid = search_emails.emuuid
		AND search_emails.email = :email) OR (u.uuid = search_emails.emuuid AND u.record_data->>'email' = :email)) 
		AND (u.record_data->>'status' = 'ACTIVE') limit 1;
			""";
	

	// Similar to FIND_USER_BY_EMAIL but won't filter on active users, used to resolve new registrations
	protected static final String FIND_ANY_USER_BY_EMAIL = """
		WITH search_emails AS (select uuid as emuuid, jsonb_array_elements(record_data->'allEmails')->>'email'
		AS email from rearm.users)
		SELECT * FROM rearm.users u, search_emails WHERE ((u.uuid = search_emails.emuuid
		AND search_emails.email = :email) OR (u.uuid = search_emails.emuuid AND u.record_data->>'email' = :email)) 
		limit 1;
			""";
	
	protected static final String FIND_USER_BY_GITHUB_ID = "select * from rearm.users u where u.record_data->>'"
			+ CommonVariables.GITHUB_ID_FIELD + "' = :githubId AND (u.record_data->>'"
			+ CommonVariables.STATUS_FIELD +"'is null OR u.record_data->>'"
			+ CommonVariables.STATUS_FIELD +"' <> 'INACTIVE')"; //
	
	protected static final String FIND_USER_BY_OAUTH_ID_AND_TYPE = "select * from rearm.users u where u.record_data->>'"
			+ CommonVariables.OAUTH_TYPE_FIELD + "' = :oauthType AND u.record_data->>'" 
			+ CommonVariables.OAUTH_ID_FIELD + "' = :oauthId AND (u.record_data->>'"
			+ CommonVariables.STATUS_FIELD +"'is null OR u.record_data->>'"
			+ CommonVariables.STATUS_FIELD +"' <> 'INACTIVE')"; //
	
	protected static final String FIND_ALL_USERS_CREATED_AFTER_DATE = "select * from rearm.users u where"
			+ " u.created_date > cast (:cutOffDate as timestamptz)";

	protected static final String FIND_USERS_BY_PERMISSION_OBJECT = "select * from rearm.users WHERE EXISTS ("
				+ " SELECT 1 FROM jsonb_array_elements(record_data->'permissions'->'permissions') p"
				+ " WHERE p->>'object' = :objectId"
			+ ")";
	
	
	/** Audit **/
	
	protected static final String RETRIEVE_AUDIT_RECORDS_FOR_ENTITY = "SELECT * FROM rearm.audit a WHERE "
			+ "a.entity_name = :tableName AND a.entity_uuid = cast (:entUuidAsStr as UUID) ORDER BY a.revision desc LIMIT cast (:limitAsStr as bigint) "
			+ "OFFSET cast (:offsetAsStr as bigint)";

	protected static final String RETRIEVE_ENTITY_REVISION = "SELECT * FROM rearm.audit a WHERE "
			+ "a.entity_name = :tableName AND a.entity_uuid = cast (:entUuidAsStr as UUID) AND a.revision = cast (:revisionAsStr as int)";

	protected static final String RETRIEVE_ALL_AUDIT_REVISIONS = "SELECT * FROM rearm.audit a WHERE"
			+ " a.revision_record_data->>'" + CommonVariables.ORGANIZATION_FIELD + "' = :orgUuid and a.entity_name = :tableName AND a.revision_created_date >"
			+ " cast (:cutOffDate as timestamptz)";

	protected static final String RETRIEVE_ALL_AUDIT_REVISIONS_BY_ORG = "SELECT * FROM rearm.audit a WHERE a.revision_record_data->>'" + CommonVariables.ORGANIZATION_FIELD + "' = :orgUuid";

	protected static final String RETRIEVE_AUDIT_RECORDS_FOR_ENTITY_BY_DATES = "SELECT * FROM rearm.audit a WHERE"
			+ " a.revision_record_data->>'" + CommonVariables.ORGANIZATION_FIELD + "' = :orgUuid and a.entity_name = :tableName AND a.revision_created_date >"
			+ " cast (:dateFrom as timestamptz)"
			+ " AND a.revision_created_date < cast (:dateTo as timestamptz) ORDER BY a.revision desc LIMIT cast (:limitAsStr as bigint)";

	protected static final String RETRIEVE_AUDIT_RECORDS_FOR_SINGLE_ENTITY_BY_DATES = "SELECT * FROM rearm.audit a WHERE"
			+ " a.entity_uuid = cast (:entUuidAsStr as UUID) AND"
			+ " a.entity_name = :tableName AND a.revision_created_date > cast (:dateFrom as timestamptz)" 
			+ " AND a.revision_created_date < cast (:dateTo as timestamptz) ORDER BY a.revision desc LIMIT :limit"
			+ " OFFSET :offset";
	
	
	/*
	* SystemInfo
	*/

	protected static final String FIND_SYSTEM_INFO = "select * from rearm.system_info s where s.id = 1";
	
	protected static final String MAKE_USER_GLOBAL_ADMIN = "update rearm.users set record_data = jsonb_set(record_data, '{isGlobalAdmin}', 'true') where uuid = :userId";
	
	/*
	 * Resource Groups
	 */
	protected static final String FIND_RESOURCE_GROUP_BY_ORG = """
				select * from rearm.resource_groups a where a.record_data->>'org' = 
				:orgUuidAsString
			""";

	protected static final String FIND_RESOURCE_GROUP_BY_ID_ORG = """
			select * from rearm.resource_groups a where a.record_data->>'uuid' = 
			:uuid and a.record_data->>'org' = :orgUuidAsString
		""";
	
	/*
	 * Deliverables
	 */
	protected static final String FIND_DELIVERABLE_BY_DIGEST_RECORD = """
	    select * from rearm.deliverables a where a.record_data->>'org' = :orgUuidAsString 
	    and jsonb_contains(a.record_data->'softwareMetadata', jsonb_build_object('digestRecords', jsonb_build_array(jsonb_build_object('algo',:algo,'scope',:scope,'digest',:digest))));
	""";
	
	protected static final String FIND_DELIVERABLE_BY_DIGEST_ANY_ALGO = """
		    select * from rearm.deliverables a where a.record_data->>'org' = :orgUuidAsString 
		    and jsonb_contains(a.record_data->'softwareMetadata', jsonb_build_object('digestRecords', jsonb_build_array(jsonb_build_object('scope',:scope,'digest',:digest))));
		""";
	
	protected static final String FIND_DELIVERABLE_BY_DIGEST_AND_COMPONENT = """
			SELECT * FROM rearm.deliverables a
				WHERE jsonb_contains(a.record_data->'softwareMetadata', jsonb_build_object('digestRecords', jsonb_build_array(jsonb_build_object('digest',:digest))))
				AND cast (a.record_data->>'branch' as UUID) in (select uuid from rearm.branches where
					record_data->>'component' = :compUuidAsStr)
				AND (a.record_data->>'status' != 'ARCHIVED' or a.record_data->>'status' is null)
			""";
	
	protected static final String LIST_DELIVERABLES_BY_COMPONENT = "select * from rearm.deliverables a"
			+ " where cast (a.record_data->>'" + CommonVariables.BRANCH_FIELD + "' as UUID) in"
			+ " (select uuid from rearm.branches where"
			+ " record_data->>'" + CommonVariables.COMPONENT_FIELD + "' = :compUuidAsStr)"
			+ " and (a.record_data->>'status' != 'ARCHIVED' or a.record_data->>'status' is null)";

	protected static final String LIST_DELIVERABLES_BY_ORG = "select * from rearm.deliverables a where a.record_data->>'org' = :orgUuidAsString";

	protected static final String FIND_DELIVERABLE_BY_BUILD_ID = "select * from rearm.deliverables a"
			+ " where a.record_data->'softwareMetadata'->>'buildId' = :query AND a.record_data->>'" + 
			CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString"
			+ " and (a.record_data->>'status' != 'ARCHIVED' or a.record_data->>'status' is null)";
	
	/*
	 * Artifacts
	 */
	protected static final String LIST_ARTIFACTS_BY_ORG = "select * from rearm.artifacts a where a.record_data->>'org' = :orgUuidAsString";
	
	protected static final String LIST_INITIAL_ARTIFACTS_PENDING_DEPENDENCY_TRACK = """
			select * from rearm.artifacts a where a.record_data->'metrics'->>'lastScanned' is null 
			and record_data->'metrics'->>'uploadToken' is not null
		""";

	protected static final String FIND_ARTIFACTS_BY_STORED_DIGEST = "select * from rearm.artifacts a where a.record_data->>'org' = :orgUuidAsString" 
	+ " and jsonb_contains(record_data, jsonb_build_object('digestRecords', jsonb_build_array(jsonb_build_object('digest',:digest))))";
	
	protected static final String FIND_ARTIFACTS_BY_DTRACK_PROJECTS = """
			select * from rearm.artifacts a where a.record_data->'metrics'->>'dependencyTrackProject' in (:dtrackProjectIds)
		""";
	
	protected static final String FIND_ORPHANED_DTRACK_PROJECTS = """
			SELECT DISTINCT a.record_data->'metrics'->>'dependencyTrackProject' as project_id
			FROM rearm.artifacts a
			WHERE a.record_data->>'org' = :orgUuidAsString
			AND a.record_data->'metrics'->>'dependencyTrackProject' IS NOT NULL
			AND a.record_data->'metrics'->>'dependencyTrackProject' != ''
			AND NOT EXISTS (
				-- Path 1: Not used by active branch via direct release artifacts
				SELECT 1 FROM rearm.releases r
				JOIN rearm.branches b ON r.record_data->>'branch' = b.uuid::text
				WHERE jsonb_contains(r.record_data, 
					jsonb_build_object('artifacts', jsonb_build_array(a.uuid::text)))
				AND b.record_data->>'status' != 'ARCHIVED'
			)
			AND NOT EXISTS (
				-- Path 2: Not used by active branch via SCE artifacts
				SELECT 1 FROM rearm.source_code_entries sce
				JOIN rearm.releases r ON r.record_data->>'sourceCodeEntry' = sce.uuid::text
				JOIN rearm.branches b ON r.record_data->>'branch' = b.uuid::text
				WHERE jsonb_contains(sce.record_data,
					jsonb_build_object('artifacts', jsonb_build_array(a.uuid::text)))
				AND b.record_data->>'status' != 'ARCHIVED'
			)
			AND NOT EXISTS (
				-- Path 3: Not used by active branch via deliverable artifacts
				SELECT 1 FROM rearm.deliverables d
				JOIN rearm.variants v ON jsonb_contains(v.record_data,
					jsonb_build_object('outboundDeliverables', jsonb_build_array(d.uuid::text)))
				JOIN rearm.releases r ON v.record_data->>'release' = r.uuid::text
				JOIN rearm.branches b ON r.record_data->>'branch' = b.uuid::text
				WHERE jsonb_contains(d.record_data,
					jsonb_build_object('artifacts', jsonb_build_array(a.uuid::text)))
				AND b.record_data->>'status' != 'ARCHIVED'
			)
		""";
	
	protected static final String FIND_ARTIFACTS_WITH_VULNERABILITY = """
			SELECT * FROM rearm.artifacts
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->'metrics' IS NOT NULL
				AND EXISTS (
					SELECT 1 FROM jsonb_array_elements(record_data->'metrics'->'vulnerabilityDetails') AS vuln
					WHERE vuln->>'purl' = :location
					AND vuln->>'vulnId' = :findingId
				)
			""";
	
	protected static final String FIND_ARTIFACTS_WITH_VIOLATION = """
			SELECT * FROM rearm.artifacts
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->'metrics' IS NOT NULL
				AND EXISTS (
					SELECT 1 FROM jsonb_array_elements(record_data->'metrics'->'violationDetails') AS violation
					WHERE violation->>'purl' = :location
					AND violation->>'type' = :findingId
				)
			""";
	
	protected static final String FIND_ARTIFACTS_WITH_WEAKNESS = """
			SELECT * FROM rearm.artifacts
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->'metrics' IS NOT NULL
				AND EXISTS (
					SELECT 1 FROM jsonb_array_elements(record_data->'metrics'->'weaknessDetails') AS weakness
					WHERE weakness->>'location' = :location
					AND (weakness->>'cweId' = :findingId OR weakness->>'ruleId' = :findingId)
				)
			""";
	
	/*
	 * Branches and Feature Sets
	 */
	protected static final String FIND_ALL_BRANCHES_OF_COMPONENT = "select * from rearm.branches b where b.record_data->>'"
			+ CommonVariables.COMPONENT_FIELD + "' = :compUuidAsString AND b.record_data->>'status' != 'ARCHIVED'";
	
	protected static final String FIND_BRANCHES_OF_COMPONENT_BY_STATUS = "select * from rearm.branches b where b.record_data->>'"
			+ CommonVariables.COMPONENT_FIELD + "' = :compUuidAsString" + " and b.record_data->>'" + CommonVariables.STATUS_FIELD + "' = :status";

	protected static final String FIND_BASE_BRANCH_OF_COMPONENT = "select * from rearm.branches b where b.record_data->>'"
			+ CommonVariables.COMPONENT_FIELD + "' = :compUuidAsString" + " and b.record_data->>'" + CommonVariables.TYPE_FIELD + "' = '"
			+ CommonVariables.BASE_TYPE + "' and b.record_data->>'" + CommonVariables.STATUS_FIELD + "' != 'ARCHIVED'";

	protected static final String FIND_BRANCHES_OF_ORG = "select * from rearm.branches b where b.record_data->>'"
			+ CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString AND b.record_data->>'status' != 'ARCHIVED'";
	
	protected static final String FIND_BRANCHES_BY_CHILD_COMPONENT_AND_BRANCH = "select * from rearm.branches b where b.record_data->>'"
			+ CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString AND b.record_data->>'status' != 'ARCHIVED' AND "
			+ "jsonb_contains(record_data, jsonb_build_object('dependencies', jsonb_build_array(json_build_object('uuid', :compUuidAsString, 'branch', :branchUuidAsString)))) ";

	protected static final String FIND_FEATURE_SETS_BY_CHILD_COMPONENT = "select * from rearm.branches b where b.record_data->>'"
			+ CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString AND b.record_data->>'status' != 'ARCHIVED' AND "
			+ "jsonb_contains(record_data, jsonb_build_object('dependencies', jsonb_build_array(json_build_object('uuid', :compUuidAsString)))) ";
	
	protected static final String FIND_FEATURE_SETS_WITH_DEPENDENCY_PATTERNS = "select * from rearm.branches b where b.record_data->>'"
			+ CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString AND b.record_data->>'status' != 'ARCHIVED' AND "
			+ "b.record_data->>'autoIntegrate' = 'ENABLED' AND "
			+ "jsonb_array_length(COALESCE(b.record_data->'dependencyPatterns', '[]'::jsonb)) > 0";
	
	/*
	 * Components
	 */ 
	protected static final String FIND_COMPONENTS_BY_ORG = "select * from rearm.components p where p.record_data->>'"
			+ CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString"
			+ " and (p.record_data->>'status' != 'ARCHIVED' or p.record_data->>'status' is null)";
	
	protected static final String FIND_COMPONENTS_BY_ORG_BY_TYPE = "select * from rearm.components p where p.record_data->>'"
			+ CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString and p.record_data->>'" + CommonVariables.TYPE_FIELD + "' = :componentTypeAsString"
			+ " and (p.record_data->>'status' != 'ARCHIVED' or p.record_data->>'status' is null)";
	
	protected static final String FIND_COMPONENT_BY_PARENT = """
			SELECT * FROM rearm.components p
				WHERE p.record_data->>'parent' =:parentUuidAsString 
					AND p.record_data->>'org' = :orgUuidAsString
			""";
	
	protected static final String FIND_COMPONENT_BY_ORG_NAME_TYPE = """
				SELECT * from rearm.components p WHERE 
				p.record_data->>'org' = :orgUuidAsString 
				AND p.record_data->>'name' = :componentName
				AND p.record_data->>'type' = :componentType
				AND (p.record_data->>'status' != 'ARCHIVED' 
					OR p.record_data->>'status' IS NULL)
			""";
	
	protected static final String FIND_COMPONENTS_BY_BRANCH = "select * from rearm.components p where p.uuid = (select cast (b.record_data->>'"
			+ CommonVariables.COMPONENT_FIELD + "' as uuid) from rearm.branches b where b.uuid = cast (:branchUuidAsString as uuid))"
			+ " and (p.record_data->>'status' != 'ARCHIVED' or p.record_data->>'status' is null)";

	protected static final String FIND_COMPONENTS_BY_SOURCE_CODE_ENTRY = "select * from rearm.components p where p.uuid = (select cast (b.record_data->>'"
			+ CommonVariables.COMPONENT_FIELD + "' as uuid) from rearm.branches b where b.uuid = (select cast (sce.record_data->>'"
			+ CommonVariables.BRANCH_FIELD + "' as uuid) from rearm.source_code_entries sce where sce.uuid = cast (:sourceCodeEntryUuidAsString as uuid)))"
			+ " and (p.record_data->>'status' != 'ARCHIVED' or p.record_data->>'status' is null)";
	
	protected static final String FIND_COMPONENTS_BY_APPROVAL_POLICY = """
				SELECT * from rearm.components c 
				WHERE c.record_data->>'approvalPolicy' = :approvalPolicyUuid
				AND c.record_data->>'status' != 'ARCHIVED';
			""";
	
	protected static final String FIND_COMPONENT_BY_VCS_AND_PATH = """
			SELECT * from rearm.components c 
			WHERE c.record_data->>'vcs' = :vcsUuidAsString
			AND c.record_data->>'org' = :orgUuidAsString
			AND (
				c.record_data->>'repoPath' = :repoPath 
				OR (
					(NULLIF(c.record_data->>'repoPath', '') IS NULL OR c.record_data->>'repoPath' = '.') 
					AND (NULLIF(:repoPath, '') IS NULL OR :repoPath = '.')
				)
			)
			AND (c.record_data->>'status' IS NULL OR c.record_data->>'status' != 'ARCHIVED');
		""";
			
	/*
	 * ReleaseReboms
	 */
	 protected static final String FIND_RELEASE_REBOM_BY_RELEASE_AND_ORG = """
		SELECT * from rearm.release_reboms r where r.record_data->>'release' = :releaseUuidAsString
		AND r.record_data->>'org' in (:orgUuidAsString, '00000000-0000-0000-0000-000000000000')
		""";



	/*
	 * Releases
	 */

	protected static final String FIND_RELEASE_BY_ID_AND_ORG = """
			SELECT * from rearm.releases r where r.uuid = :releaseUuid
			AND r.record_data->>'org' in (:orgUuidAsString, '00000000-0000-0000-0000-000000000000')
			""";
	
	protected static final String FIND_ALL_RELEASES_OF_BRANCH = "select * from rearm.releases r where r.record_data->>'"
			+ CommonVariables.BRANCH_FIELD + "' = :branchUuidAsString ORDER BY r.created_date desc LIMIT cast (:limitAsStr as bigint) "
			+ "OFFSET cast (:offsetAsStr as bigint)";

	protected static final String FIND_ALL_RELEASES_OF_BRANCH_UP_TO_DATE = "select * from rearm.releases r where r.record_data->>'"
			+ CommonVariables.BRANCH_FIELD + "' = :branchUuidAsString AND r.created_date < :upToDate "
			+ "ORDER BY r.created_date desc LIMIT cast (:limitAsStr as bigint) "
			+ "OFFSET cast (:offsetAsStr as bigint)";

	protected static final String FIND_ALL_RELEASES_OF_BRANCH_WHERE_IN_SCE = "select * from rearm.releases r where r.record_data->>'"
			+ CommonVariables.BRANCH_FIELD + "' = :branchUuidAsString "
			+ "and r.record_data->>'" + CommonVariables.SOURCE_CODE_ENTRY_FIELD + "' in (:sces) "
			+ "ORDER BY r.created_date desc LIMIT cast (:limitAsStr as bigint) "
			+ "OFFSET cast (:offsetAsStr as bigint)";

	protected static final String FIND_RELEASES_OF_BRANCH_BETWEEN_DATES = "select * from rearm.releases r where r.record_data->>'"
			+ CommonVariables.BRANCH_FIELD + "' = :branchUuidAsString" 
			+ " AND r.created_date >= cast (:fromDate as timestamptz)"
			+ " AND r.created_date <= cast (:toDate as timestamptz)"
			+ " ORDER BY r.created_date desc ";

	protected static final String FIND_PREVIOUS_RELEASES_OF_BRANCH_FOR_RELEASE = 
			"""
			WITH PreviousRelease AS (
				SELECT *,
					LAG(uuid) OVER (
						ORDER BY created_date
					) as previous_release_uuid
				FROM rearm.releases r where r.record_data->>'branch' = :branch
				AND r.record_data->>'lifecycle' NOT IN ('CANCELLED', 'REJECTED')
			)	
			""" 
			+ "select previous_release_uuid from PreviousRelease where record_data->>'branch' = :branch AND uuid = :release ORDER BY created_date desc;";
	
		protected static final String FIND_NEXT_RELEASES_OF_BRANCH_FOR_RELEASE = 
			"""
			WITH NextRelease AS (
				SELECT *,
					LEAD(uuid) OVER (
						ORDER BY created_date
					) as next_release_uuid
				FROM rearm.releases r where r.record_data->>'branch' = :branch
				AND r.record_data->>'lifecycle' NOT IN ('CANCELLED', 'REJECTED')
			)	
			""" 
			+ "select next_release_uuid from NextRelease where record_data->>'branch' = :branch AND uuid = :release ORDER BY created_date desc;";

	protected static final String FIND_ALL_RELEASES_OF_ORG = "select * from rearm.releases r where r.record_data->>'"
			+ CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString";
	
	protected static final String FIND_ALL_PRODUCT_RELEASES_OF_ORG = """
			SELECT * FROM rearm.releases r WHERE r.record_data->>'org' = :orgUuidAsString
			 AND r.record_data->>'parentReleases' != '[]'
			 ORDER BY r.created_date desc 
			 LIMIT cast (:limitAsStr as bigint)
			 OFFSET cast (:offsetAsStr as bigint)
			""";
	
	protected static final String FIND_ALL_RELEASES_OF_COMPONENT = """
			SELECT * FROM rearm.releases r WHERE r.record_data->>'component' = :componentUuidAsString 
				ORDER BY r.created_date desc LIMIT cast (:limitAsStr as bigint)
				OFFSET cast (:offsetAsStr as bigint)
			""";
	
	protected static final String FIND_RELEASES_BY_DELIVERABLE_AND_ORG = """
			select * from rearm.releases where uuid = (select cast (record_data->>'release' as UUID) from rearm.variants
			WHERE jsonb_contains(record_data, jsonb_build_object('outboundDeliverables', jsonb_build_array(:deliverableUuidAsString)))
			AND record_data->>'org' in (:orgUuidAsString, '00000000-0000-0000-0000-000000000000'))
			""";
	
	protected static final String FIND_RELEASES_BY_ARTIFACT_AND_ORG = """
			select * from rearm.releases
			WHERE jsonb_contains(record_data, jsonb_build_object('artifacts', jsonb_build_array(:artifactUuidAsString)))
			AND record_data->>'org' in (:orgUuidAsString, '00000000-0000-0000-0000-000000000000')
			""";
	
	protected static final String FIND_RELEASES_BY_SCE_AND_ORG = "select * from rearm.releases"
			+ " where record_data->>'"+ CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString"
			+ " AND "
			+ "(record_data->>'" + CommonVariables.SOURCE_CODE_ENTRY_FIELD + "' = :sceUuidAsString OR "
			+ "jsonb_contains(record_data->'commits', jsonb_build_array(:sceUuidAsString)))";

	protected static final String FIND_LATEST_RELEASE_BY_SCE_AND_ORG = "select * from rearm.releases"
			+ " where record_data->>'"+ CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString"
			+ " AND "
			+ "(record_data->>'" + CommonVariables.SOURCE_CODE_ENTRY_FIELD + "' = :sceUuidAsString OR "
			+ "jsonb_contains(record_data->'commits', jsonb_build_array(:sceUuidAsString)))"
			+ "ORDER BY last_updated_date desc LIMIT 1";

	protected static final String FIND_ALL_RELEASES_OF_ORG_AFTER_CREATE_DATE = "select * from rearm.releases r where r.record_data->>'"
			+ CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString AND r.created_date > :cutOffDate";
	
	protected static final String FIND_ALL_RELEASES_OF_COMPONENT_AFTER_CREATE_DATE = "select * from rearm.releases r where r.record_data->>'"
			+ CommonVariables.COMPONENT_FIELD + "' = :componentUuidAsString AND r.created_date > :cutOffDate";
	
	protected static final String FIND_ALL_RELEASES_OF_BRANCH_AFTER_CREATE_DATE = "select * from rearm.releases r where r.record_data->>'"
			+ CommonVariables.BRANCH_FIELD + "' = :branchUuidAsString AND r.created_date > :cutOffDate";
	
	protected static final String FIND_ALL_RELEASES_OF_ORG_BY_VERSION = "select * from rearm.releases r where r.record_data->>'"
			+ CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString AND (r.record_data->>'" + CommonVariables.VERSION_FIELD + "' = :version OR r.record_data->>'" + CommonVariables.MARKETING_VERSION_FIELD + "' = :version)";
	
	protected static final String FIND_RELEASE_BY_COMPONENT_AND_VERSION = "select * from rearm.releases r where r.record_data->>'"
			+ CommonVariables.COMPONENT_FIELD + "' = :compUuidAsString AND r.record_data->>'" + CommonVariables.VERSION_FIELD + "' = :version";

	protected static final String LIST_RELEASES_BY_COMPONENT = "select * from rearm.releases r" + " where r.record_data->>'"
			+ CommonVariables.COMPONENT_FIELD + "' = :compUuidAsString ORDER BY r.created_date desc";

	protected static final String LIST_RELEASES_BY_COMPONENTS = "select * from rearm.releases r where cast(r.record_data->>'"
			+ CommonVariables.COMPONENT_FIELD + "' as uuid) in (:componentUuids)";
	
	// select * from rearm.releases where jsonb_path_match(record_data, cast(concat('$.parentReleases[*].release == ', '"', '4c1a81f9-736e-4863-8fee-6b56a91607fc', '"') as jsonpath));
	protected static final String FIND_PRODUCTS_THAT_HAVE_THIS_RELEASE = "select * from rearm.releases r where r.record_data->>'"
			+ CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString AND jsonb_path_match(r.record_data, "
			+ "cast(concat('$.parentReleases[*].release == ', '\"', :releaseUuidAsString, '\"') as jsonpath))";
	
	protected static final String FIND_PRODUCTS_THAT_HAVE_THESE_RELEASES = """
			select * from rearm.releases r where r.record_data->>'org'
			 = :orgUuidAsString AND 
			 jsonb_contains(jsonb_path_query_array(record_data, 
			 '$.parentReleases[*].release'), cast (:releaseArrString as jsonb))
	""";
	
	protected static final String FIND_RELEASES_BY_ORG_AND_IDENTIFIER = """
		SELECT * FROM rearm.releases where record_data->>'org' = :orgUuidAsString 
		AND jsonb_contains(record_data, jsonb_build_object('identifiers', 
		jsonb_build_array(jsonb_build_object('idType', :idType, 'idValue', :idValue))))
	""";
			
	protected static final String FIND_PENDING_RELEASES_AFTER_CUTOFF = "SELECT * FROM rearm.releases r WHERE r.record_data->>'"
	+ CommonVariables.LIFECYCLE_FIELD +"' = :lifecycle AND r.created_date < cast (:cutOffDate as timestamptz)";

	protected static final String FIND_DISTINCT_RELEASE_TAG_KEYS_OF_ORG = """
	  SELECT distinct(tags.key)
	    FROM rearm.releases r, jsonb_to_recordset(r.record_data->'tags')
	    AS tags(key text, value text, removable text) 
	    WHERE r.record_data->'tags' IS NOT NULL and r.record_data->>'tags' != '[]' and r.record_data->>'org' = :orgUuidAsString
	""";

	protected static final String FIND_RELEASES_BY_TAG_KEY = """
			SELECT * FROM rearm.releases rlzs
			WHERE rlzs.record_data->>'org' = :orgUuidAsString AND record_data->'tags' IS NOT NULL and record_data->>'tags' != '[]'
			AND :tagKey IN (SELECT tags.key FROM jsonb_to_recordset(record_data->'tags') AS tags(key text, value text, removable text))
	""";

	protected static final String FIND_BRANCH_RELEASES_BY_TAG_KEY = """
			SELECT * FROM rearm.releases rlzs
			WHERE rlzs.record_data->>'org' = :orgUuidAsString
			AND rlzs.record_data->>'branch' = :branchUuidAsString
			AND :tagKey IN (SELECT tags.key FROM jsonb_to_recordset(record_data->'tags') AS tags(key text, value text, removable text))
	""";
	
	protected static final String FIND_RELEASES_BY_TAG_KEY_VALUE = """
			SELECT * FROM rearm.releases rlzs, jsonb_to_recordset(record_data->'tags') AS tags(key text, value text, removable text)
			  WHERE rlzs.record_data->>'org' = :orgUuidAsString
			  AND tags.key = :tagKey AND tags.value = :tagValue
	""";
	
	protected static final String FIND_BRANCH_RELEASES_BY_TAG_KEY_VALUE = """
			SELECT * FROM rearm.releases rlzs, jsonb_to_recordset(record_data->'tags') AS tags(key text, value text, removable text)
			  WHERE rlzs.record_data->>'org' = :orgUuidAsString 
			  AND rlzs.record_data->>'branch' = :branchUuidAsString
			  AND tags.key = :tagKey AND tags.value = :tagValue
	""";

	protected static final String FIND_RELEASES_FOR_METRICS_COMPUTE_BY_ARTIFACT_DIRECT = """
		WITH
		lastComputedRlz (maxVal) AS (
			SELECT coalesce(max(cast (record_data->'metrics'->>'lastScanned' as float)), 0) 
			FROM rearm.releases
		),
		unprocessedArts AS (
			SELECT ra.uuid 
			FROM lastComputedRlz, rearm.artifacts ra 
			WHERE coalesce(cast (ra.record_data->'metrics'->>'lastScanned' as float), 0) > lastComputedRlz.maxVal
		),
		releases_with_artifacts AS (
			SELECT rlzs.*, jsonb_array_elements_text(record_data->'artifacts')::uuid as artifact_uuid
			FROM rearm.releases rlzs where rlzs.record_data->>'artifacts' != '[]'
		)
		SELECT DISTINCT rwa.uuid, rwa.revision, rwa.schema_version, rwa.created_date, rwa.last_updated_date, rwa.record_data
		FROM releases_with_artifacts rwa
		INNER JOIN unprocessedArts ua ON ua.uuid = rwa.artifact_uuid;
	""";
	
	protected static final String FIND_RELEASES_FOR_METRICS_COMPUTE_BY_SCE = """
	WITH
	  lastComputedRlz (maxVal) AS (
	    select coalesce(max(cast (record_data->'metrics'->>'lastScanned' as float)), 0) AS lastUpd from rearm.releases
	  ),
	  unprocessedArts (uuid) AS (
	    SELECT ra.uuid from lastComputedRlz, rearm.artifacts ra where coalesce(cast (record_data->'metrics'->>'lastScanned' as float), 0) > lastComputedRlz.maxVal ),
	  unprocessedSces (uuid) AS (
	    SELECT sce.uuid from unprocessedArts, rearm.source_code_entries sce
	      WHERE jsonb_contains(record_data, jsonb_build_object('artifacts', jsonb_build_array(jsonb_build_object('artifactUuid', unprocessedArts.uuid)))))
	  SELECT rlzs.* FROM unprocessedSces, rearm.releases rlzs
	    WHERE record_data->>'sourceCodeEntry' = cast (unprocessedSces.uuid as text);
	""";
	
	protected static final String FIND_RELEASES_FOR_METRICS_COMPUTE_BY_OUTBOUND_DELIVERABLES = """
	WITH
	  lastComputedRlz (maxVal) AS (
	    select coalesce(max(cast (record_data->'metrics'->>'lastScanned' as float)), 0) AS lastUpd from rearm.releases
	  ),
	  unprocessedArts (uuid) AS (
	    SELECT ra.uuid from lastComputedRlz, rearm.artifacts ra where coalesce(cast (record_data->'metrics'->>'lastScanned' as float), 0) > lastComputedRlz.maxVal ),
	  unprocessedDeliverables (uuid) AS (
	    SELECT del.uuid from unprocessedArts, rearm.deliverables del
	      WHERE jsonb_contains(record_data, jsonb_build_object('artifacts', jsonb_build_array(unprocessedArts.uuid)))),
	  unprocessedRlzIds (uuid) AS (
	    SELECT distinct var.record_data->>'release' from unprocessedDeliverables, rearm.variants var
	      WHERE jsonb_contains(record_data, jsonb_build_object('outboundDeliverables', jsonb_build_array(unprocessedDeliverables.uuid))))
	  SELECT rlzs.* FROM unprocessedRlzIds, rearm.releases rlzs
	    WHERE rlzs.uuid = cast (unprocessedRlzIds.uuid as uuid);
	""";
	
	protected static final String FIND_PRODUCT_RELEASES_FOR_METRICS_COMPUTE = """
		WITH
		  lastComputedRlz (maxVal) AS (
		    select coalesce(max(cast (record_data->'metrics'->>'lastScanned' as float)), 0) AS lastUpd from rearm.releases
		  )
	    SELECT rlz.* from lastComputedRlz, rearm.releases rlz where coalesce(cast (rlz.record_data->'metrics'->>'lastScanned' as float), 0) < (lastComputedRlz.maxVal - 0.01)
	    AND rlz.record_data->>'parentReleases' != '[]';
			""";
	
	protected static final String FIND_RELEASES_FOR_METRICS_COMPUTE_BY_UPDATE = """
		SELECT * FROM rearm.releases 
			 WHERE last_updated_date - interval '15 seconds' > to_timestamp(coalesce(cast (record_data->'metrics'->>'lastScanned' as float), 0));
	""";

	protected static final String FIND_RELEASES_SHARING_SCE_ARTIFACT = """
		WITH
		unprocessedSces (uuid, componentUuid) AS (
			SELECT sce.uuid,
           		(artifact_element->>'componentUuid')::uuid as componentUuid
    			FROM rearm.source_code_entries sce,
         		jsonb_array_elements(sce.record_data->'artifacts') as artifact_element
    		WHERE artifact_element->>'artifactUuid' = :artUuidAsString
		)
		SELECT rlzs.* FROM unprocessedSces, rearm.releases rlzs
		WHERE record_data->>'sourceCodeEntry' = cast (unprocessedSces.uuid as text)
		  AND (rlzs.record_data->>'component')::uuid = unprocessedSces.componentUuid;
		""";

	protected static final String FIND_RELEASES_SHARING_DELIVRABLE_ARTIFACT = """
		WITH
			unprocessedDeliverables (uuid) AS (
				SELECT del.uuid from rearm.deliverables del
				WHERE jsonb_contains(record_data, jsonb_build_object('artifacts', jsonb_build_array(:artUuidAsString)))),
			unprocessedRlzIds (uuid) AS (
				SELECT distinct var.record_data->>'release' from unprocessedDeliverables, rearm.variants var
				WHERE jsonb_contains(record_data, jsonb_build_object('outboundDeliverables', jsonb_build_array(unprocessedDeliverables.uuid))))
			SELECT rlzs.* FROM unprocessedRlzIds, rearm.releases rlzs
				WHERE rlzs.uuid = cast (unprocessedRlzIds.uuid as uuid);
		""";

	protected static final String FIND_RELEASES_WITH_VULNERABILITY = """
			SELECT * FROM rearm.releases
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->'metrics' IS NOT NULL
				AND EXISTS (
					SELECT 1 FROM jsonb_array_elements(record_data->'metrics'->'vulnerabilityDetails') AS vuln
					WHERE vuln->>'purl' = :location
					AND vuln->>'vulnId' = :findingId
				)
			""";
	
	protected static final String FIND_RELEASES_WITH_VIOLATION = """
			SELECT * FROM rearm.releases
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->'metrics' IS NOT NULL
				AND EXISTS (
					SELECT 1 FROM jsonb_array_elements(record_data->'metrics'->'violationDetails') AS violation
					WHERE violation->>'purl' = :location
					AND violation->>'type' = :findingId
				)
			""";
	
	protected static final String FIND_RELEASES_WITH_WEAKNESS = """
			SELECT * FROM rearm.releases
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->'metrics' IS NOT NULL
				AND EXISTS (
					SELECT 1 FROM jsonb_array_elements(record_data->'metrics'->'weaknessDetails') AS weakness
					WHERE weakness->>'location' = :location
					AND (weakness->>'cweId' = :findingId OR weakness->>'ruleId' = :findingId)
				)
			""";
	
	protected static final String FIND_RELEASES_WITH_VULNERABILITY_IN_BRANCH = """
			SELECT * FROM rearm.releases
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->>'branch' = :branchUuidAsString
				AND record_data->'metrics' IS NOT NULL
				AND EXISTS (
					SELECT 1 FROM jsonb_array_elements(record_data->'metrics'->'vulnerabilityDetails') AS vuln
					WHERE vuln->>'purl' = :location
					AND vuln->>'vulnId' = :findingId
				)
			""";
	
	protected static final String FIND_RELEASES_WITH_VIOLATION_IN_BRANCH = """
			SELECT * FROM rearm.releases
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->>'branch' = :branchUuidAsString
				AND record_data->'metrics' IS NOT NULL
				AND EXISTS (
					SELECT 1 FROM jsonb_array_elements(record_data->'metrics'->'violationDetails') AS violation
					WHERE violation->>'purl' = :location
					AND violation->>'type' = :findingId
				)
			""";
	
	protected static final String FIND_RELEASES_WITH_WEAKNESS_IN_BRANCH = """
			SELECT * FROM rearm.releases
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->>'branch' = :branchUuidAsString
				AND record_data->'metrics' IS NOT NULL
				AND EXISTS (
					SELECT 1 FROM jsonb_array_elements(record_data->'metrics'->'weaknessDetails') AS weakness
					WHERE weakness->>'location' = :location
					AND (weakness->>'cweId' = :findingId OR weakness->>'ruleId' = :findingId)
				)
			""";
	
	protected static final String FIND_RELEASES_WITH_VULNERABILITY_IN_COMPONENT = """
			SELECT * FROM rearm.releases
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->>'component' = :componentUuidAsString
				AND record_data->'metrics' IS NOT NULL
				AND EXISTS (
					SELECT 1 FROM jsonb_array_elements(record_data->'metrics'->'vulnerabilityDetails') AS vuln
					WHERE vuln->>'purl' = :location
					AND vuln->>'vulnId' = :findingId
				)
			""";
	
	protected static final String FIND_RELEASES_WITH_VIOLATION_IN_COMPONENT = """
			SELECT * FROM rearm.releases
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->>'component' = :componentUuidAsString
				AND record_data->'metrics' IS NOT NULL
				AND EXISTS (
					SELECT 1 FROM jsonb_array_elements(record_data->'metrics'->'violationDetails') AS violation
					WHERE violation->>'purl' = :location
					AND violation->>'type' = :findingId
				)
			""";
	
	protected static final String FIND_RELEASES_WITH_WEAKNESS_IN_COMPONENT = """
			SELECT * FROM rearm.releases
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->>'component' = :componentUuidAsString
				AND record_data->'metrics' IS NOT NULL
				AND EXISTS (
					SELECT 1 FROM jsonb_array_elements(record_data->'metrics'->'weaknessDetails') AS weakness
					WHERE weakness->>'location' = :location
					AND (weakness->>'cweId' = :findingId OR weakness->>'ruleId' = :findingId)
				)
			""";
	
	
	/*
	 * Variants 
	 */
	protected static final String FIND_ALL_VARIANTS_OF_RELEASE = """
			SELECT * FROM rearm.variants WHERE
			record_data->>'release' = :releaseUuidAsString
			""";
	
	protected static final String FIND_BASE_VARIANT_OF_RELEASE = """
			SELECT * FROM rearm.variants WHERE
			record_data->>'release' = :releaseUuidAsString
			AND record_data->>'type' = 'BASE'
			""";
	
	/*
	 * Source Code Entries
	 */
	protected static final String FIND_SCE_BY_COMMIT_AND_VCS = "select * from rearm.source_code_entries sce"
			+ " where sce.record_data->>'" + CommonVariables.COMMIT_FIELD + "' = :commit and"
			+ " sce.record_data->>'vcs' = :vcsUuidAsString";

	protected static final String FIND_SCE_BY_COMMITS_AND_VCS = "select * from rearm.source_code_entries sce"
			+ " where sce.record_data->>'" + CommonVariables.COMMIT_FIELD + "' in (:commits) and"
			+ " sce.record_data->>'vcs' = :vcsUuidAsString";

	protected static final String FIND_SCE_BY_TICKET_AND_ORG = "select * from rearm.source_code_entries sce"
			+ " where sce.record_data->>'" + CommonVariables.TICKET_FIELD + "' = :ticket and"
			+ " sce.record_data->>'" + CommonVariables.ORGANIZATION_FIELD + "' = :org "
			+ "ORDER BY sce.record_data->>'" + CommonVariables.DATE_ACTUAL_FIELD + "' desc LIMIT 1";
	
	protected static final String FIND_SCE_BY_COMMIT_OR_TAG_AND_ORG = "select * from rearm.source_code_entries sce"
			+ " where sce.record_data->>'" + CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString and"
			+ " (sce.record_data->>'" + CommonVariables.COMMIT_FIELD + "' like :commit OR "
					+ "sce.record_data->>'" + CommonVariables.VCS_TAG_FIELD + "' = :tag)";
	
	protected static final String FIND_SCE_BY_COMPONENT = "select * from rearm.source_code_entries where"
			+ " uuid in (select cast (record_data->>'sourceCodeEntry' as uuid) from rearm.releases where"
			+ " record_data->>'component' = :compUuidAsString and record_data ->>'sourceCodeEntry'"
			+ " is not null)";

	protected static final String FIND_ALL_SCES_OF_ORGS_BY_ID = "select * from rearm.source_code_entries where uuid in (:sces)"
			+ " AND cast(record_data->>'org' as uuid) in (:orgs)";

	protected static final String FIND_ALL_SCES_BY_ORG = "select * from rearm.source_code_entries a where a.record_data->>'org' = :orgUuidAsString";
	
	/*
	 * VCS Repositories
	 */
	protected static final String FIND_VCS_REPOS_BY_ORG = "select * from rearm.vcs_repositories v"
			+ " where v.record_data->>'" + CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString"
			+ " and (v.record_data->>'status' != 'ARCHIVED' or v.record_data->>'status' is null)";

	protected static final String FIND_VCS_REPO_BY_ORG_AND_URI = """
			select * from rearm.vcs_repositories v
			where v.record_data->>'org' = :orgUuidAsString and
			(v.record_data->>'uri' = :uri or v.record_data->>'uri' = 'https://' || :uri or v.record_data->>'uri' = 'http://' || :uri)
			and (v.record_data->>'status' != 'ARCHIVED' or v.record_data->>'status' is null)
			""";
	
	protected static final String FIND_VCS_REPO_BY_ORG_AND_URI_INCLUDING_ARCHIVED = """
			select * from rearm.vcs_repositories v
			where v.record_data->>'org' = :orgUuidAsString and
			(v.record_data->>'uri' = :uri or v.record_data->>'uri' = 'https://' || :uri or v.record_data->>'uri' = 'http://' || :uri)
			""";
	
	protected static final String FIND_COMPONENTS_BY_VCS = """
			select * from rearm.components c
			where c.record_data->>'vcs' = :vcsUuidAsString
			and (c.record_data->>'status' != 'ARCHIVED' or c.record_data->>'status' is null)
			""";
	
	protected static final String FIND_COMPONENTS_BY_PERSPECTIVE = """
			select * from rearm.components c
			where jsonb_exists(c.record_data->'perspectives', :perspectiveUuidAsString)
			and (c.record_data->>'status' != 'ARCHIVED' or c.record_data->>'status' is null)
			""";

	protected static final String FIND_BRANCHES_BY_VCS = """
			select * from rearm.branches b
			where b.record_data->>'vcs' = :vcsUuidAsString
			and (b.record_data->>'status' != 'ARCHIVED' or b.record_data->>'status' is null)
			""";
	/*
	 * Version Assignments
	 */
	protected static final String LATEST_VERSION_ASSIGNMENTS_WITH_LIMIT = "SELECT * from rearm.version_assignments va WHERE "
			+ "va.branch = :branchUuid AND va.version_type = :versionType ORDER BY va.created_date desc LIMIT :limit";
	
	protected static final String LATEST_VERSION_ASSIGNMENTS_WITH_BRANCH_SCHEMA_AND_LIMIT_BY_BRANCH = "SELECT * from rearm.version_assignments va "
			+ "WHERE va.branch = :branchUuid AND va.branch_schema = :schema AND va.version_type = :versionType ORDER BY va.created_date desc LIMIT :limit";
	
	protected static final String LATEST_VERSION_ASSIGNMENTS_WITH_COMPONENT_SCHEMA_AND_LIMIT_BY_BRANCH = "SELECT * from rearm.version_assignments va "
			+ "WHERE va.branch = :branchUuid AND va.version_schema = :schema AND va.version_type = :versionType ORDER BY va.created_date desc LIMIT :limit";
	
	protected static final String LATEST_VERSION_ASSIGNMENTS_WITH_SCHEMA_AND_LIMIT_BY_COMPONENT = "SELECT * from rearm.version_assignments va "
			+ "WHERE va.component = :componentUuid AND va.version_schema = :schema AND va.version_type = :versionType ORDER BY va.created_date desc LIMIT :limit";
	
	protected static final String FIND_VERSION_ASSIGNMENT_BY_COMPONENT_AND_VERSION = "SELECT * from rearm.version_assignments va "
			+ "WHERE va.component = :componentUuid AND va.version = :version AND va.version_type = :versionType";
	
	protected static final String FIND_OPEN_VERSION_ASSIGNMENT_BY_BRANCH = "SELECT * from rearm.version_assignments va WHERE "
			+ "va.branch = :branchUuid AND va.assignment_type = 'OPEN' AND va.version_type = :versionType";		

	protected static final String FIND_ALL_VERSION_ASSIGNMENTS_BY_ORG = "select * from rearm.version_assignments a where a.org = :orgUuid";
	
	/*
	 * Integrations 
	 */
	protected static final String FIND_INTEGRATION_BY_ORG_TYPE_IDENTIFIER = "select * from rearm.integrations"
			+ " where record_data->>'" + CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString AND"
			+ " record_data->>'" + CommonVariables.TYPE_FIELD + "' = :typeAsString AND"
			+ " record_data->>'" + CommonVariables.IDENTIFIER_FIELD + "' = :identifier";
	
	protected static final String FIND_INTEGRATION_BY_ORG_IDENTIFIER = "select * from rearm.integrations"
			+ " where record_data->>'" + CommonVariables.ORGANIZATION_FIELD + "' = :orgUuidAsString AND"
			+ " record_data->>'" + CommonVariables.IDENTIFIER_FIELD + "' = :identifier";

	protected static final String LIST_INTEGRATIONS_BY_ORG = "select * from rearm.integrations a where a.record_data->>'org' = :orgUuidAsString";

	/*
	 * Organizations
	 */
	protected static final String GET_NUMERIC_ANALYTICS_FOR_ORG = """
			select component_count.components, product_count.products, release_count.releases,
			vcs_count.vcs, artifact_count.artifacts, sce_count.commits, branch_count.branches, deliverable_count.deliverables, feature_set_count.feature_sets
			from
			(select count(*) as components from rearm.components where record_data->>'org' = :orgUuidAsString 
			   and record_data->>'type' = 'COMPONENT' and (record_data->>'status' is null or record_data->>'status' = 'ACTIVE')) as component_count,
			(select count(*) as products from rearm.components where record_data->>'org' = :orgUuidAsString 
			   and record_data->>'type' = 'PRODUCT' and (record_data->>'status' is null or record_data->>'status' = 'ACTIVE')) as product_count,
			(select count(*) as releases from rearm.releases where record_data->>'org' = :orgUuidAsString 
			    and (record_data->>'status' is null or record_data->>'status' = 'ACTIVE')) as release_count,
			(select count(*) as vcs from rearm.vcs_repositories as vcs where record_data->>'org' = :orgUuidAsString 
			    and (record_data->>'status' is null or record_data->>'status' = 'ACTIVE')) as vcs_count,
			(select count(*) as artifacts from rearm.artifacts where record_data->>'org' = :orgUuidAsString
				) as artifact_count,
			(select count(*) as commits from rearm.source_code_entries where record_data->>'org' = :orgUuidAsString) as sce_count,
			(select count(*) as branches from rearm.branches b where b.record_data->>'org' = :orgUuidAsString and
				(b.record_data->>'status' is null or b.record_data->>'status' = 'ACTIVE') and 
				'COMPONENT' = (select rc.record_data->>'type' from rearm.components rc where rc.record_data->>'uuid' = b.record_data->>'component')) as branch_count,
			(select count(*) as feature_sets from rearm.branches b where b.record_data->>'org' = :orgUuidAsString and
				(b.record_data->>'status' is null or b.record_data->>'status' = 'ACTIVE') and 
				'PRODUCT' = (select rc.record_data->>'type' from rearm.components rc where rc.record_data->>'uuid' = b.record_data->>'component')) as feature_set_count,
			(select count(*) as deliverables from rearm.deliverables where record_data->>'org' = :orgUuidAsString and
				(record_data->>'status' is null or record_data->>'status' = 'ACTIVE')) as deliverable_count
		""";

	/*
	 * Analytical Queries
	 */
	protected static final String ANALYTICS_COMPONENTS_WITH_MOST_RELEASES = """
			WITH release_stats (uuid, rlzcount) AS (
				select record_data->>'component', count(rlz) AS rlzcount FROM rearm.releases rlz
					WHERE rlz.record_data->>'org' = :organization 
					AND rlz.created_date > :cutOffDate group by record_data->>'component')
			SELECT comp.uuid AS componentuuid, comp.record_data->>'name' AS componentname,
				comp.record_data->>'type' AS componenttype, rs.rlzcount AS rlzcount 
				FROM rearm.components comp, release_stats rs WHERE comp.uuid::text = rs.uuid
				AND (comp.record_data->>'status' IS NULL OR comp.record_data->>'status' = 'ACTIVE')
				AND comp.record_data->>'type' = :compType ORDER by rlzcount desc limit :maxComponents
			""";
	
	protected static final String ANALYTICS_BRANCHES_WITH_MOST_RELEASES = """
			WITH release_stats (uuid, rlzcount) AS (
				SELECT record_data->>'branch', count(rlz) AS rlzcount FROM rearm.releases rlz
					WHERE rlz.record_data->>'org' = :organization 
					AND rlz.created_date > :cutOffDate group by record_data->>'branch')
			SELECT comp.uuid AS componentuuid, comp.record_data->>'name' AS componentname,
			    branch.uuid AS branchuuid, branch.record_data->>'name' AS branchname,
				comp.record_data->>'type' AS componenttype, rs.rlzcount AS rlzcount 
				FROM rearm.components comp, rearm.branches branch, release_stats rs WHERE branch.uuid::text = rs.uuid
			    AND comp.uuid::text = branch.record_data->>'component'
				AND (comp.record_data->>'status' IS NULL OR comp.record_data->>'status' = 'ACTIVE')
				AND comp.record_data->>'type' = :compType ORDER by rlzcount desc limit :maxBranches
			""";
	
	protected static final String ANALYTICS_COMPONENTS_WITH_MOST_RELEASES_BY_PERSPECTIVE = """
			WITH release_stats (uuid, rlzcount) AS (
				select record_data->>'component', count(rlz) AS rlzcount FROM rearm.releases rlz
					WHERE rlz.record_data->>'org' = :organization 
					AND rlz.created_date > :cutOffDate group by record_data->>'component')
			SELECT comp.uuid AS componentuuid, comp.record_data->>'name' AS componentname,
				comp.record_data->>'type' AS componenttype, rs.rlzcount AS rlzcount 
				FROM rearm.components comp, release_stats rs WHERE comp.uuid::text = rs.uuid
				AND (comp.record_data->>'status' IS NULL OR comp.record_data->>'status' = 'ACTIVE')
				AND comp.record_data->>'type' = :compType
				AND jsonb_exists(comp.record_data->'perspectives', :perspectiveUuidAsString)
				ORDER by rlzcount desc limit :maxComponents
			""";
	
	protected static final String ANALYTICS_BRANCHES_WITH_MOST_RELEASES_BY_PERSPECTIVE = """
			WITH release_stats (uuid, rlzcount) AS (
				SELECT record_data->>'branch', count(rlz) AS rlzcount FROM rearm.releases rlz
					WHERE rlz.record_data->>'org' = :organization 
					AND rlz.created_date > :cutOffDate group by record_data->>'branch')
			SELECT comp.uuid AS componentuuid, comp.record_data->>'name' AS componentname,
			    branch.uuid AS branchuuid, branch.record_data->>'name' AS branchname,
				comp.record_data->>'type' AS componenttype, rs.rlzcount AS rlzcount 
				FROM rearm.components comp, rearm.branches branch, release_stats rs WHERE branch.uuid::text = rs.uuid
			    AND comp.uuid::text = branch.record_data->>'component'
				AND (comp.record_data->>'status' IS NULL OR comp.record_data->>'status' = 'ACTIVE')
				AND comp.record_data->>'type' = :compType
				AND jsonb_exists(comp.record_data->'perspectives', :perspectiveUuidAsString)
				ORDER by rlzcount desc limit :maxBranches
			""";
	
	protected static final String ANALYTICS_COMPONENTS_WITH_MOST_RELEASES_BY_PRODUCT = """
			WITH release_stats (uuid, rlzcount) AS (
				select record_data->>'component', count(rlz) AS rlzcount FROM rearm.releases rlz
					WHERE rlz.record_data->>'org' = :organization 
					AND rlz.created_date > :cutOffDate group by record_data->>'component')
			SELECT comp.uuid AS componentuuid, comp.record_data->>'name' AS componentname,
				comp.record_data->>'type' AS componenttype, rs.rlzcount AS rlzcount 
				FROM rearm.components comp, release_stats rs WHERE comp.uuid::text = rs.uuid
				AND (comp.record_data->>'status' IS NULL OR comp.record_data->>'status' = 'ACTIVE')
				AND comp.record_data->>'type' = :compType
				AND comp.uuid IN (:componentUuids)
				ORDER by rlzcount desc limit :maxComponents
			""";
	
	protected static final String ANALYTICS_BRANCHES_WITH_MOST_RELEASES_BY_PRODUCT = """
			WITH release_stats (uuid, rlzcount) AS (
				SELECT record_data->>'branch', count(rlz) AS rlzcount FROM rearm.releases rlz
					WHERE rlz.record_data->>'org' = :organization 
					AND rlz.created_date > :cutOffDate group by record_data->>'branch')
			SELECT comp.uuid AS componentuuid, comp.record_data->>'name' AS componentname,
			    branch.uuid AS branchuuid, branch.record_data->>'name' AS branchname,
				comp.record_data->>'type' AS componenttype, rs.rlzcount AS rlzcount 
				FROM rearm.components comp, rearm.branches branch, release_stats rs WHERE branch.uuid::text = rs.uuid
			    AND comp.uuid::text = branch.record_data->>'component'
				AND (comp.record_data->>'status' IS NULL OR comp.record_data->>'status' = 'ACTIVE')
				AND comp.record_data->>'type' = :compType
				AND comp.uuid IN (:componentUuids)
				ORDER by rlzcount desc limit :maxBranches
			""";
	
	/*
	 * Analytics Metrics
	 */
	protected static final String FIND_ANALYTICS_METRICS_BY_ORG_DATES = """
			SELECT * from rearm.analytics_metrics am 
				WHERE am.record_data->>'org' = :orgUuidAsString
				AND am.record_data->>'dateKey' >= :dateKeyFrom AND am.record_data->>'dateKey' <= :dateKeyTo
				AND (am.record_data->>'perspective' is null OR am.record_data->>'perspective' = '' OR am.record_data->>'perspective' = '00000000-0000-0000-0000-000000000000')
			""";
	
	protected static final String FIND_ANALYTICS_METRICS_BY_ORG_DATE_KEY = """
			SELECT * from rearm.analytics_metrics am 
				WHERE am.record_data->>'org' = :orgUuidAsString
				AND am.record_data->>'dateKey' = :dateKey
				AND (am.record_data->>'perspective' is null OR am.record_data->>'perspective' = '' OR am.record_data->>'perspective' = '00000000-0000-0000-0000-000000000000')  
			""";
	
	protected static final String FIND_ANALYTICS_METRICS_BY_ORG_PERSPECTIVE_DATE_KEY = """
			SELECT * from rearm.analytics_metrics am 
				WHERE am.record_data->>'org' = :orgUuidAsString
				AND am.record_data->>'perspective' = :perspectiveUuidAsString
				AND am.record_data->>'dateKey' = :dateKey
			""";
	
	protected static final String FIND_ANALYTICS_METRICS_BY_ORG_PERSPECTIVE_DATES = """
			SELECT * from rearm.analytics_metrics am 
				WHERE am.record_data->>'org' = :orgUuidAsString
				AND am.record_data->>'perspective' = :perspectiveUuidAsString
				AND am.record_data->>'dateKey' >= :dateKeyFrom AND am.record_data->>'dateKey' <= :dateKeyTo
			""";

	/*
	 * Artifact Collections
	 */
	protected static final String FIND_ACOLLECTIONS_BY_RELEASE = """
			SELECT * from rearm.acollections a
			    WHERE a.record_data->>'release' = :releaseUuidAsString
			    ORDER BY a.record_data->'version' DESC
			""";
	
	/*
	 * User Groups
	 */
	protected static final String FIND_USER_GROUPS_BY_ORGANIZATION = """
			SELECT * FROM rearm.user_groups
				WHERE record_data->>'org' = :orgUuidAsString 
				AND record_data->>'status' = 'ACTIVE'
			""";
	
	protected static final String FIND_USER_GROUP_BY_NAME_AND_ORGANIZATION = """
			SELECT * FROM rearm.user_groups
				WHERE record_data->>'org' = :orgUuidAsString 
				AND record_data->>'name' = :name
			""";
	
	protected static final String FIND_USER_GROUPS_BY_USER_AND_ORGANIZATION = """
			SELECT * FROM rearm.user_groups
				WHERE record_data->>'org' = :orgUuidAsString
				AND jsonb_contains(record_data, jsonb_build_object('users', jsonb_build_array(:userUuidAsString)))
				AND record_data->>'status' = 'ACTIVE'
			""";
	
	/*
	 * Vulnerability Analysis
	 */
	protected static final String FIND_VULN_ANALYSIS_BY_ORG_LOCATION_FINDING_SCOPE = """
			SELECT * FROM rearm.vuln_analysis
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->>'location' = :location
				AND (record_data->>'findingId' = :findingId OR EXISTS (
					SELECT 1 FROM jsonb_array_elements_text(record_data->'findingAliases') AS alias
					WHERE alias = :findingId
				))
				AND record_data->>'scope' = :scope
			""";
	
	protected static final String FIND_VULN_ANALYSIS_BY_ORG_LOCATION_FINDING_SCOPE_TYPE = """
			SELECT * FROM rearm.vuln_analysis
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->>'location' = :location
				AND (record_data->>'findingId' = :findingId OR EXISTS (
					SELECT 1 FROM jsonb_array_elements_text(record_data->'findingAliases') AS alias
					WHERE alias = :findingId
				))
				AND record_data->>'scope' = :scope
				AND record_data->>'scopeUuid' = :scopeId
				AND record_data->>'findingType' = :findingType
			""";
	
	protected static final String FIND_VULN_ANALYSIS_BY_ORG_SCOPE_SCOPEUUID = """
			SELECT * FROM rearm.vuln_analysis
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->>'scope' = :scope
				AND record_data->>'scopeUuid' = :scopeUuidAsString
			""";
	
	protected static final String FIND_VULN_ANALYSIS_BY_ORG_LOCATION = """
			SELECT * FROM rearm.vuln_analysis
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->>'location' = :location
			""";
	
	protected static final String FIND_VULN_ANALYSIS_BY_ORG_FINDING_ID = """
			SELECT * FROM rearm.vuln_analysis
				WHERE record_data->>'org' = :orgUuidAsString
				AND (record_data->>'findingId' = :findingId OR EXISTS (
					SELECT 1 FROM jsonb_array_elements_text(record_data->'findingAliases') AS alias
					WHERE alias = :findingId
				))
			""";
	
	protected static final String FIND_VULN_ANALYSIS_BY_ORG_LOCATION_FINDING_TYPE = """
			SELECT * FROM rearm.vuln_analysis
				WHERE record_data->>'org' = :orgUuidAsString
				AND record_data->>'location' = :location
				AND (record_data->>'findingId' = :findingId OR EXISTS (
					SELECT 1 FROM jsonb_array_elements_text(record_data->'findingAliases') AS alias
					WHERE alias = :findingId
				))
				AND record_data->>'findingType' = :findingType
			""";
	
	protected static final String FIND_VULN_ANALYSIS_BY_ORG = """
			SELECT * FROM rearm.vuln_analysis
				WHERE record_data->>'org' = :orgUuidAsString
			""";
	
	protected static final String FIND_VULN_ANALYSIS_AFFECTING_COMPONENT = """
			SELECT * FROM rearm.vuln_analysis
				WHERE record_data->>'org' = :orgUuidAsString
				AND (record_data->>'component' = :componentUuidAsString
					OR record_data->>'scope' = 'ORG')
			""";
	
	protected static final String FIND_VULN_ANALYSIS_AFFECTING_BRANCH = """
			SELECT * FROM rearm.vuln_analysis
				WHERE record_data->>'org' = :orgUuidAsString
				AND (record_data->>'branch' = :branchUuidAsString
					OR record_data->>'component' = :componentUuidAsString
					OR record_data->>'scope' = 'ORG')
			""";
	
	protected static final String FIND_VULN_ANALYSIS_AFFECTING_RELEASE = """
			SELECT * FROM rearm.vuln_analysis
				WHERE record_data->>'org' = :orgUuidAsString
				AND (record_data->>'release' = :releaseUuidAsString
					OR record_data->>'branch' = :branchUuidAsString
					OR record_data->>'component' = :componentUuidAsString
					OR record_data->>'scope' = 'ORG')
			""";
	
	/*
	 * Release Metrics CVE Search
	 */
	protected static final String FIND_RELEASES_BY_CVE_ID = """
			SELECT * FROM rearm.releases r
			WHERE r.record_data->>'org' = :orgUuidAsString
			AND r.record_data->'metrics' IS NOT NULL
			AND (
				EXISTS (
					SELECT 1 FROM jsonb_array_elements(r.record_data->'metrics'->'vulnerabilityDetails') AS vuln
					WHERE vuln->>'vulnId' = :cveId
				)
				OR EXISTS (
					SELECT 1 FROM jsonb_array_elements(r.record_data->'metrics'->'vulnerabilityDetails') AS vuln,
					jsonb_array_elements(vuln->'aliases') AS alias
					WHERE alias->>'aliasId' = :cveId
				)
			)
			ORDER BY r.created_date DESC
			""";
}
