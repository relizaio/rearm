import gql from 'graphql-tag'

const MULTI_RELEASE_GQL_DATA = `
    createdDate
    org
    artifacts
    artifactDetails {
        uuid
        displayIdentifier
        digestRecords{
            algo
            digest
            scope
        }
        type
        tags {
            key
            value
            removable
        }
        enrichmentStatus
        metrics {
            dependencyTrackFullUri
            dtrackSubmissionFailed
        }
    }
    status
    lifecycle
    uuid
    version
    marketingVersion
    sourceCodeEntry
    sourceCodeEntryDetails {
        uuid
        commit
        commitMessage
        commitAuthor
        commitEmail
        dateActual
        vcsRepository {
            uri
        }
        vcsBranch
        vcsTag
        agent
        agentSession
        signature {
            state
            format
            signedByOwnerType
            signedByOwnerUuid
            keyFingerprint
            verifiedAt
        }
    }
    branch
    branchDetails {
        uuid
        name
    }
    componentDetails {
        uuid
        name
        type
        resourceGroup
    }
    ticketDetails {
        uuid
        identifier
        status
        org
        summary
        content
        doneInRelease
        dateDone
        uri
    }
    tags {
        key
        value
        removable
    }
    parentReleases {
        release
    }
    metrics {
        lastScanned
        firstScanned
        critical
        high
        medium
        low
        unassigned
        policyViolationsSecurityTotal
        policyViolationsLicenseTotal
        policyViolationsOperationalTotal
    }
`

const INSTANCE_GQL_DATA = `
    uuid
    name
    instanceType
    namespace
    instances
    uri
    org
    properties {
        uuid
        type
        value
        namespace
        product
        productDetails {
            name
        }
        property {
            name
            dataType
            defaultValue
        }
    }
    releases {
        timeSent
        release
        deliverable
        namespace
        properties
        state
        partOf
        replicas {
            id
            state
        }
        isInError
        releaseDetails {
            ${MULTI_RELEASE_GQL_DATA}
        }
    }
    targetReleases {
        timeSent
        release
        deliverable
        namespace
        properties
        releaseDetails {
            ${MULTI_RELEASE_GQL_DATA}
        }
    }
    unmatchedReleases {
        image
        digestRecords {
            algo
            digest
            scope
        }
        namespace
        pod
        lastSeen
        state
        replicas {
            id
            state
        }
    }
    agentData
    environment
    productPlans {
        featureSet
        featureSetDetails {
            name
            componentDetails {
                uuid
                name
            }
            dependencies {
                uuid
                status
                branch
                release
            }
        }
        type
        namespace
        targetRelease
        targetReleaseDetails {
            version
            parentReleases {
                release
                releaseDetails {
                    uuid
                    version
                    componentDetails {
                        uuid
                        name
                    }
                }
            }
        }
        identifier
        configuration
        alertsEnabled
    }
    productActuals {
        featureSet
        featureSetDetails {
            name
            componentDetails {
                uuid
                name
            }
        }
        matchedRelease
        matchedReleaseDetails {
            version
        }
        namespace
        identifier
        notMatchingSince
    }
    notes
    status
    spawnType
`

const MULTI_INSTANCE_GQL_DATA = `
    uuid
    uri
    name
    instanceType
    namespace
    instances
    org
    releases {
        isInError
    }
    environment
    productPlans {
        featureSet
        featureSetDetails {
            name
            componentDetails {
                uuid
                name
            }
            dependencies {
                uuid
                status
                branch
                release
            }
        }
        type
        namespace
        targetRelease
        targetReleaseDetails {
            version
        }
        alertsEnabled
    }
    productActuals {
        featureSet
        featureSetDetails {
            name
            componentDetails {
                uuid
                name
            }
        }
        matchedRelease
        matchedReleaseDetails {
            version
        }
        namespace
        notMatchingSince
    }
    notes
    status
    spawnType
`
const INSTANCE_GQL = gql`
query FetchInstance($instanceUuid: ID!, $revision:Int, $stateType: InstanceStateType) {
    instance(instanceUuid: $instanceUuid, revision:$revision, stateType:$stateType) {
        ${INSTANCE_GQL_DATA}
    }
}`

const INSTANCES_GQL = gql`
query FetchInstances($orgUuid: ID!) {
    instancesOfOrganization(orgUuid: $orgUuid) {
        ${MULTI_INSTANCE_GQL_DATA}
    }
}`

const ARTIFACT_DETAIL_DATA_SINGLE = `
    uuid
    displayIdentifier
    buildId
    buildUri
    version
    cicdMeta
    digestRecords{
        algo
        digest
        scope
    }
    type
    bomFormat
    serializationFormat
    specVersion
    tags{
        key
        value
        removable
    }
    metrics {
        dependencyTrackFullUri
        lastScanned
        firstScanned
        dtrackSubmissionFailed
        dtrackSubmissionAttempts
        dtrackSubmissionFailureReason
        dtrackFetchStatus
        dtrackFetchFailureCount
        dtrackFetchFailureReason
        dtrackFetchSkipUntil
        critical
        high
        medium
        low
        unassigned
        policyViolationsSecurityTotal
        policyViolationsLicenseTotal
        policyViolationsOperationalTotal
    }
    downloadLinks{
        uri
        content
    }
    internalBom {
        id
        belongsTo
    }
    enrichmentStatus
    componentUuid
`
const RELEASE_COLLECTION_DATA = `
    uuid
    artifactComparison{
        changelog {
            added {
                purl
                version
            }
            removed {
                purl
                version
            }
        }
        comparedReleaseUuid
    }
    
`
const ARTIFACT_DETAIL_DATA = `
    uuid
    displayIdentifier
    createdDate
    buildId
    buildUri
    version
    cicdMeta
    digestRecords{
        algo
        digest
        scope
    }
    type
    bomFormat
    serializationFormat
    specVersion
    tags{
        key
        value
        removable
    }
    metrics {
        dependencyTrackFullUri
        dtrackSubmissionFailed
        dtrackSubmissionAttempts
        dtrackSubmissionFailureReason
        dtrackFetchStatus
        dtrackFetchFailureCount
        dtrackFetchFailureReason
        dtrackFetchSkipUntil
        lastScanned
        firstScanned
        critical
        high
        medium
        low
        unassigned
        policyViolationsSecurityTotal
        policyViolationsLicenseTotal
        policyViolationsOperationalTotal
    }
    downloadLinks{
        uri
        content
    }
    internalBom {
        id
        belongsTo
    }
    enrichmentStatus
    componentUuid
    artifacts
    artifactDetails {
        ${ARTIFACT_DETAIL_DATA_SINGLE}
    }
`
const DELIVERABLE_DETAIL_DATA = `
    uuid
    name
    org
    displayIdentifier
    identifiers {
        idType
        idValue
    }
    branch
    type
    notes
    tags{
            key
            value
        }
    version
    publisher
    group
    supportedOs
    supportedCpuArchitectures
    softwareMetadata {
        buildId
        buildUri
        cicdMeta
        digestRecords {
            algo
            digest
        }
        dateFrom
        dateTo
        duration
        packageType
        downloadLinks {
            uri
            content
        }
    }
    artifacts
    artifactDetails {
        ${ARTIFACT_DETAIL_DATA}
    }
`

const singleReleaseDataNoParent = `
    createdDate
    org
    artifacts
    artifactDetails {
        ${ARTIFACT_DETAIL_DATA}
    }
    inboundDeliverables
    inboundDeliverableDetails{
        ${DELIVERABLE_DETAIL_DATA}
    }
    orgDetails {
        uuid
        name
    }
    status
    lifecycle
    uuid
    version
    marketingVersion
    notes
    endpoint
    sourceCodeEntry
    sourceCodeEntryDetails {
        uuid
        commit
        commitMessage
        commitAuthor
        commitEmail
        dateActual
        vcsRepository {
            uri
        }
        vcsBranch
        vcsTag
        agent
        agentSession
        artifacts
        artifactDetails {
            ${ARTIFACT_DETAIL_DATA}
        }
        signature {
            state
            format
            signedByOwnerType
            signedByOwnerUuid
            keyFingerprint
            verifiedAt
        }
    }
    commitsDetails {
        uuid
        commit
        commitMessage
        commitAuthor
        commitEmail
        dateActual
        agent
        agentSession
        signature {
            state
            format
            signedByOwnerType
            signedByOwnerUuid
            keyFingerprint
            verifiedAt
        }
    }
    pullRequests {
        uuid
        identity
        state
        title
        sourceBranchName
        targetBranchName
        endpoint
        mergedDate
        closedDate
    }
    branch
    branchDetails {
        uuid
        name
        vcs
    }
    componentDetails {
        uuid
        name
        type
        resourceGroup
        versionType
        approvalPolicyDetails {
            uuid
            policyName
            approvalEntryDetails {
                uuid
                approvalName
                approvalRequirements {
                    allowedApprovalRoleIdExpanded {
                        id
                        displayView
                    }
                }
            }
        }
    }
    tags {
        key
        value
        removable
    }
    approvedEnvironments
    approvalEvents {
        approvalEntry
        approvalEntryName
        approvalRoleId
        state
        date
        comment
        wu {
            createdType
            lastUpdatedBy
        }
    }
    updateEvents {
        rus
        rua
        oldValue
        newValue
        objectId
        message
        date
        wu {
            createdType
            lastUpdatedBy
        }
    }
    variantDetails {
        uuid
        type
        outboundDeliverableDetails {
            ${DELIVERABLE_DETAIL_DATA}
        }
    }
    metrics {
        lastScanned
        firstScanned
        critical
        high
        medium
        low
        unassigned
        policyViolationsSecurityTotal
        policyViolationsLicenseTotal
        policyViolationsOperationalTotal
    }
    identifiers {
        idType
        idValue
    }
    sidComponentName
    preferredBomIdentifier
    releaseCollection {
        ${RELEASE_COLLECTION_DATA}
    }
    intermediateFailedReleases {
        releaseUuid
        releaseVersion
        releaseLifecycle
        releaseCreatedDate
        commits {
            uuid
            commit
            commitMessage
            commitAuthor
            commitEmail
            dateActual
            agent
            agentSession
            signature {
                state
                format
                signedByOwnerType
                signedByOwnerUuid
                keyFingerprint
                verifiedAt
            }
        }
    }
    sbomReconcilePending
`

const singleReleaseDataParentLast = `
    ${singleReleaseDataNoParent}
    parentReleases {
        release
        releaseDetails {
            ${singleReleaseDataNoParent}
        }
    }
`

const singleReleaseDataParentRecursion = `
    ${singleReleaseDataNoParent}
    parentReleases {
        release
        releaseDetails {
            ${singleReleaseDataParentLast}        
        }
    }
`

const SINGLE_RELEASE_GQL_DATA = `
    ${singleReleaseDataNoParent}
    parentReleases {
        release
        releaseDetails {
            ${singleReleaseDataParentRecursion}
        }
    }
    previousRelease {
        uuid
        version
        lifecycle
        createdDate
    }
    nextRelease {
        uuid
        version
        lifecycle
        createdDate
    }
`

const SINGLE_RELEASE_GQL_DATA_LIGHT = `
    createdDate
    org
    orgDetails {
        uuid
        name
    }
    parentReleases {
        release
    }
    status
    lifecycle
    uuid
    version
    marketingVersion
    notes
    branch
    branchDetails {
        uuid
        name
    }
    componentDetails {
        uuid
        name
        type
        resourceGroup
    }
    tags
`

const COMPONENT_FULL_DATA = `
    uuid
    name
    org
    resourceGroup
    type
    kind
    versionSchema
    marketingVersionSchema
    versionType
    featureBranchVersioning
    branchSuffixMode
    vcs
    repoPath
    vcsRepositoryDetails {
        uuid
        name
        uri
    }
    createdDate
    defaultConfig
    visibilitySetting
    approvalPolicy
    approvalPolicyDetails {
        approvalEntryDetails {
            uuid
            approvalName
        }
        globalInputEvents {
            uuid
            name
            celExpression
            outputEvents
            outputEventsOnFalse
            scope
            enabled
            preconditionCelExpression
        }
        globalOutputEvents {
            uuid
            name
            type
            toReleaseLifecycle
            integration
            users
            notificationMessage
            vcs
            eventType
            clientPayload
            schedule
            celClientPayload
            snapshotApprovalEntry
            snapshotLifecycle
            approvedEnvironment
            checkName
            scope
        }
    }
    outputTriggers {
        uuid
        name
        type
        toReleaseLifecycle
        integration
        users
        notificationMessage
        vcs
        eventType
        clientPayload
        schedule
        celClientPayload
        snapshotApprovalEntry
        snapshotLifecycle
        approvedEnvironment
        checkName
        scope
    }
    releaseInputTriggers {
        uuid
        name
        celExpression
        outputEvents
        outputEventsOnFalse
        scope
        enabled
        preconditionCelExpression
    }
    globalInputEventRefs {
        uuid
        disabled
        overrideOutputEventsLocally
        outputEventsOverride
    }
    identifiers {
        idType
        idValue
    }
    authentication {
        login
        password
        type
    }
    perspectiveDetails {
        uuid
        name
        type
        sidPurlOverride
        sidAuthoritySegments
    }
    sidPurlOverride
    sidAuthoritySegments
    isInternal
`

const BRANCH_GQL_DATA = `
    uuid
    name
    component
    componentDetails {
        uuid
        name
        type
        resourceGroup
        versionType
    }
    status
    type
    vcs
    vcsBranch
    org
    versionSchema
    marketingVersionSchema
    metadata
    autoIntegrate
    findingAnalyticsParticipation
    createdDate
    dependencies {
        uuid
        status
        branch
        release
        componentDetails {
            name
        }
        branchDetails {
            name
        }
        releaseDetails {
            version
        }
        isFollowVersion
    }
    dependencyPatterns {
        uuid
        pattern
        targetBranchName
        defaultStatus
        fallbackToBase
    }
    effectiveDependencies {
        component {
            uuid
            name
            type
        }
        branch {
            uuid
            name
        }
        status
        source
        releaseDetails {
            uuid
            version
        }
        isFollowVersion
    }
    vcsRepositoryDetails {
        uuid
        name
        uri
        type
        createdType
    }
`

const SINGLE_RELEASE_GQL = gql`
query FetchRelease($releaseID: ID!, $orgID: ID) {
    release(releaseUuid: $releaseID, orgUuid: $orgID) {
        ${SINGLE_RELEASE_GQL_DATA}
    }
}`

const SINGLE_RELEASE_GQL_LIGHT = gql`
query FetchRelease($releaseID: ID!, $orgID: ID) {
    release(releaseUuid: $releaseID, orgUuid: $orgID) {
        ${SINGLE_RELEASE_GQL_DATA_LIGHT}
    }
}`

const SINGLE_RELEASE_TYPE_DETECT_GQL = gql`
query FetchRelease($releaseID: ID!, $orgID: ID) {
    release(releaseUuid: $releaseID, orgUuid: $orgID) {
        uuid
        componentDetails {
            uuid
            type
        }
    }
}`

// Slim selection used by ReleaseView's lazy "Part of Products" tab.
// Stripped from the main SingleReleaseGql payload because the inProducts
// resolver walks every release that lists this one as a parent, which
// blows up page load for components that ship across many product releases.
//
// Field set mirrors what ComponentBranchesTable consumes after the
// (release → component → branch → releases) transform in
// ReleaseView.partOfProductsComponentRows — we group the flat inProducts
// list back into the 3-level shape the table renders.
const FETCH_RELEASE_IN_PRODUCTS_GQL = gql`
query FetchReleaseInProducts($releaseID: ID!, $orgID: ID) {
    release(releaseUuid: $releaseID, orgUuid: $orgID) {
        uuid
        inProducts {
            uuid
            version
            component
            createdDate
            lifecycle
            componentDetails {
                uuid
                name
                type
                versionSchema
            }
            branchDetails {
                uuid
                name
                status
                versionSchema
            }
            metrics {
                critical
                high
                medium
                low
                unassigned
                policyViolationsLicenseTotal
                policyViolationsSecurityTotal
                policyViolationsOperationalTotal
            }
        }
    }
}`

const singleReleaseProductNoParent = `
    createdDate
    org
    artifacts
    artifactDetails {
        ${ARTIFACT_DETAIL_DATA}
    }
    inboundDeliverables
    orgDetails {
        uuid
        name
    }
    status
    lifecycle
    uuid
    version
    marketingVersion
    notes
    branch
    branchDetails {
        uuid
        name
    }
    componentDetails {
        uuid
        name
        type
        resourceGroup
        versionType
        approvalPolicyDetails {
            uuid
            policyName
            approvalEntryDetails {
                uuid
                approvalName
                approvalRequirements {
                    allowedApprovalRoleIdExpanded {
                        id
                        displayView
                    }
                }
            }
        }
    }
    tags {
        key
        value
        removable
    }
    approvedEnvironments
    approvalEvents {
        approvalEntry
        approvalEntryName
        approvalRoleId
        state
        date
        comment
        wu {
            createdType
            lastUpdatedBy
        }
    }
    updateEvents {
        rus
        rua
        oldValue
        newValue
        objectId
        message
        date
        wu {
            createdType
            lastUpdatedBy
        }
    }
    variantDetails {
        uuid
        type
    }
    metrics {
        lastScanned
        firstScanned
        critical
        high
        medium
        low
        unassigned
        policyViolationsSecurityTotal
        policyViolationsLicenseTotal
        policyViolationsOperationalTotal
    }
    identifiers {
        idType
        idValue
    }
    releaseCollection {
        ${RELEASE_COLLECTION_DATA}
    }
`

const SINGLE_RELEASE_PRODUCT_GQL_DATA = `
    ${singleReleaseProductNoParent}
    parentReleases {
        release
        releaseDetails {
            uuid
            version
            lifecycle
            status
            createdDate
            org
            branch
            branchDetails {
                uuid
                name
            }
            componentDetails {
                uuid
                name
                type
            }
            metrics {
                lastScanned
                firstScanned
                critical
                high
                medium
                low
                unassigned
                policyViolationsSecurityTotal
                policyViolationsLicenseTotal
                policyViolationsOperationalTotal
            }
            tags {
                key
                value
                removable
            }
            parentReleases {
                release
                releaseDetails {
                    uuid
                    version
                    lifecycle
                    status
                    createdDate
                    org
                    branch
                    branchDetails {
                        uuid
                        name
                    }
                    componentDetails {
                        uuid
                        name
                        type
                    }
                    metrics {
                        lastScanned
                        firstScanned
                        critical
                        high
                        medium
                        low
                        unassigned
                        policyViolationsSecurityTotal
                        policyViolationsLicenseTotal
                        policyViolationsOperationalTotal
                    }
                    tags {
                        key
                        value
                        removable
                    }
                }
            }
        }
    }
    previousRelease {
        uuid
        version
        lifecycle
        createdDate
    }
    nextRelease {
        uuid
        version
        lifecycle
        createdDate
    }
`

const SINGLE_RELEASE_PRODUCT_GQL = gql`
query FetchRelease($releaseID: ID!, $orgID: ID) {
    release(releaseUuid: $releaseID, orgUuid: $orgID) {
        ${SINGLE_RELEASE_PRODUCT_GQL_DATA}
    }
}`

const BRANCHES_GQL = gql`
query FetchBranches($componentUuid: ID!) {
    branchesOfComponent(componentUuid: $componentUuid) {
        ${BRANCH_GQL_DATA}
    }
}`

const FEATURE_SETS_USING_COMPONENT_GQL = gql`
query FeatureSetsUsingComponent($componentUuid: ID!) {
    featureSetsUsingComponent(componentUuid: $componentUuid) {
        uuid
        name
        org
        type
        componentDetails { uuid name }
        dependencies { status branch componentDetails { uuid } }
        dependencyPatterns { pattern defaultStatus targetBranchName fallbackToBase }
    }
}`

const FEATURE_SETS_USING_BRANCH_GQL = gql`
query FeatureSetsUsingBranch($branchUuid: ID!) {
    featureSetsUsingBranch(branchUuid: $branchUuid) {
        uuid
        name
        org
        type
        componentDetails { uuid name }
        dependencies { status branch componentDetails { uuid } }
        dependencyPatterns { pattern defaultStatus targetBranchName fallbackToBase }
    }
}`

const BRANCH_GQL_MUTATE = gql`
mutation updateBranch($br: BranchInput!) {
    updateBranch(branch:$br) {
        ${BRANCH_GQL_DATA}
    }
}`

const COMPONENT_MUTATE = gql`
mutation updateComponent($component: UpdateComponentInput!) {
    updateComponent(component:$component) {
        ${COMPONENT_FULL_DATA}
    }
}`

const RELEASE_GQL_MUTATE = gql`
mutation updateRelease($rel: ReleaseInput!) {
    updateRelease(release:$rel) {
        ${SINGLE_RELEASE_GQL_DATA}
    }
}`

const RELEASE_TAGS_META_GQL_MUTATE = gql`
mutation updateReleaseTagsMeta($rel: ReleaseInput!) {
    updateReleaseTagsMeta(release:$rel) {
        ${SINGLE_RELEASE_GQL_DATA}
    }
}`

const UPDATE_ARTIFACT_TAGS_GQL_MUTATE = gql`
mutation updateArtifactTags($artifact: ID!, $tags: [TagRecordInput]!) {
    updateArtifactTags(artifact: $artifact, tags: $tags) {
        uuid
        tags {
            key
            value
            removable
        }
    }
}`

const APPROVE_RELEASE_GQL_MUTATE = gql`
mutation approveReleaseManual($release: ID!, $approvals: [ReleaseApprovalInput!]) {
    approveReleaseManual(release:$release, approvals: $approvals) {
        ${SINGLE_RELEASE_GQL_DATA}
    }
}`

const COMPONENT_SHORT_DATA = `
    uuid
    name
    org
    resourceGroup
    type
    versionSchema
    featureBranchVersioning
    vcs
    identifiers {
        idType
        idValue
    }
    effectiveLifecycle
`

const MARKETING_RELEASE_GQL_DATA = `
    uuid
    version
    status
    org
    orgDetails {
        uuid
        name
    }
    component
    componentDetails {
        uuid
        name
        type
    }
    notes
    tags {
        key
        value
    }
    lifecycle
    integrateType
    integrateBranch
    integrateBranchDetails {
        name
    }
    devReleasePointer
    devReleaseDetails {
        version
        metrics {
            lastScanned
            firstScanned
            critical
            high
            medium
            low
            unassigned
            policyViolationsSecurityTotal
            policyViolationsLicenseTotal
            policyViolationsOperationalTotal
        }
    }
    createdDate
    events {
        release
        releaseDetails {
            version
            marketingVersion
            createdDate
            status
        }
        lifecycle
        date
        wu {
            createdType
            lastUpdatedBy
        }
    }
`

const USER_GQL_DATA = `
    uuid
    name
    email
    allEmails {
        email
	    isPrimary
	    isVerified
	    isAcceptMarketing
    }
    organizations
    systemSealed
    githubId
    oauthId
    installationType
    permissions {
        permissions {
            org
            scope
            object
            type
            meta
            approvals
            functions
        }
    }
    publicSshKeys {
        uuid
        name
    }
    policiesAccepted
    isGlobalAdmin
    isLicenseValid
`

const ENVIRONMENT_TYPES_GQL = gql`
query EnvironmentTypes($orgUuid: ID!) {
    environmentTypes(orgUuid: $orgUuid, includeBuiltIn: true)
}`

const RELEASES_BY_DATE_RANGE_GQL = gql`
query releasesByDateRange($org: ID!, $startDate: DateTime!, $endDate: DateTime!, $limit: Int, $componentType: ComponentType) {
    releasesByDateRange(org: $org, startDate: $startDate, endDate: $endDate, limit: $limit, componentType: $componentType) {
        ${MULTI_RELEASE_GQL_DATA}
    }
}`

const RELEASES_BY_DATE_RANGE_AND_PERSPECTIVE_GQL = gql`
query releasesByDateRangeAndPerspective($perspectiveUuid: ID!, $startDate: DateTime!, $endDate: DateTime!, $limit: Int, $componentType: ComponentType) {
    releasesByDateRangeAndPerspective(perspectiveUuid: $perspectiveUuid, startDate: $startDate, endDate: $endDate, limit: $limit, componentType: $componentType) {
        ${MULTI_RELEASE_GQL_DATA}
    }
}`

export default {
    BranchGql: BRANCH_GQL_DATA,
    BranchesGql: BRANCHES_GQL,
    BranchGqlMutate: BRANCH_GQL_MUTATE,
    InstanceGql: INSTANCE_GQL,
    InstanceGqlData: INSTANCE_GQL_DATA,
    InstancesGql: INSTANCES_GQL,
    MultiReleaseGqlData: MULTI_RELEASE_GQL_DATA,
    SingleReleaseGql: SINGLE_RELEASE_GQL,
    SingleReleaseGqlData: SINGLE_RELEASE_GQL_DATA,
    SingleReleaseGqlLight: SINGLE_RELEASE_GQL_LIGHT,
    SingleReleaseProductGql: SINGLE_RELEASE_PRODUCT_GQL,
    SingleReleaseTypeDetectGql: SINGLE_RELEASE_TYPE_DETECT_GQL,
    FetchReleaseInProductsGql: FETCH_RELEASE_IN_PRODUCTS_GQL,
    ComponentFullData: COMPONENT_FULL_DATA,
    ComponentMutate: COMPONENT_MUTATE,
    ReleaseGqlMutate: RELEASE_GQL_MUTATE,
    ReleaseTagsMetaGqlMutate: RELEASE_TAGS_META_GQL_MUTATE,
    UpdateArtifactTagsGqlMutate: UPDATE_ARTIFACT_TAGS_GQL_MUTATE,
    ApproveReleaseGqlMutate: APPROVE_RELEASE_GQL_MUTATE,
    ComponentShortData: COMPONENT_SHORT_DATA,
    MarketingRelease: MARKETING_RELEASE_GQL_DATA,
    UserData: USER_GQL_DATA,
    EnvironmentTypesGql: ENVIRONMENT_TYPES_GQL,
    ReleasesByDateRangeGql: RELEASES_BY_DATE_RANGE_GQL,
    ReleasesByDateRangeAndPerspectiveGql: RELEASES_BY_DATE_RANGE_AND_PERSPECTIVE_GQL,
    FeatureSetsUsingComponentGql: FEATURE_SETS_USING_COMPONENT_GQL,
    FeatureSetsUsingBranchGql: FEATURE_SETS_USING_BRANCH_GQL,
}