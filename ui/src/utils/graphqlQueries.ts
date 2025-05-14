import gql from 'graphql-tag'

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
    resourceGroup
    releases {
        timeSent
        release
        artifact
        namespace
        properties
        state
        partOf
        replicas {
            id
            state
        }
        isInError
    }
    targetReleases {
        timeSent
        release
        artifact
        namespace
        properties
    }
    agentData
    environment
    products {
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
        matchedRelease
        matchedReleaseDetails {
            version
        }
        namespace
        targetRelease
        targetReleaseDetails {
            version
        }
        encryptedIdentifier
        configuration
        notMatchingSince
        alertsEnabled
    }
    deploymentType
    notes
    status
    spawnType
    owner
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
    products {
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
        matchedRelease
        matchedReleaseDetails {
            version
        }
        namespace
        targetRelease
        targetReleaseDetails {
            version
        }
        notMatchingSince
        alertsEnabled
    }
    deploymentType
    notes
    status
    spawnType
    owner
`
const INSTANCE_GQL = gql`
query FetchInstance($instanceUuid: ID!, $revision:Int) {
    instance(instanceUuid: $instanceUuid, revision:$revision) {
        ${INSTANCE_GQL_DATA}
    }
}`

const INSTANCES_GQL = gql`
query FetchInstances($orgUuid: ID!) {
    instancesOfOrganization(orgUuid: $orgUuid) {
        ${MULTI_INSTANCE_GQL_DATA}
    }
}`

const ARTIFACT_DETAIL_DATA = `
    uuid
    displayIdentifier
    buildId
    buildUri
    cicdMeta
    digests
    type
    bomFormat
    tags{
        key
        value
    }
    metrics {
        dependencyTrackFullUri
        lastScanned
        critical
        high
        medium
        low
        unassigned
        policyViolationsSecurityTotal
        policyViolationsLicenseTotal
        policyViolationsOperationalTotal
    }
    reboms
    identities{
        identity
        identityType
    }
    downloadLinks{
        uri
        content
    }
    internalBom {
        id
        belongsTo
    }    
    componentUuid
`
const DELIVERABLE_DETAIL_DATA = `
    uuid
    name
    org
    displayIdentifier
    identities {
        identityType
        identity
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
        digests
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
        reboms
        artifacts
        artifactDetails {
            ${ARTIFACT_DETAIL_DATA}
        }
    }
    commitsDetails {
        uuid
        commit
        commitMessage
        commitAuthor
        commitEmail
        dateActual
    }
    branch
    branchDetails {
        uuid
        name
        pullRequests {
            endpoint
            number
            commits
            title
        }
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
    inProducts {
        uuid
        version
        component
        componentDetails {
            uuid
            name
        }
        branchDetails {
            name
        }
        lifecycle
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
    approvalEvents {
        approvalEntry
        approvalRoleId
        state
        date
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
        date
        wu {
            createdType
            lastUpdatedBy
        }
    }
    reboms
    variantDetails {
        uuid
        type
        outboundDeliverableDetails {
            ${DELIVERABLE_DETAIL_DATA}
        }
    }
    metrics {
        lastScanned
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
        pullRequests {
            endpoint
            number
            commits
            title
        }
    }
    componentDetails {
        uuid
        name
        type
        resourceGroup
    }
    tags
`

const MULTI_RELEASE_GQL_DATA = `
    createdDate
    org
    artifacts
    artifactDetails {
        uuid
        displayIdentifier
        digests
        type
        tags {
            key
            value
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
        reboms
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

const COMPONENT_FULL_DATA = `
    uuid
    name
    org
    resourceGroup
    type
    versionSchema
    marketingVersionSchema
    versionType
    featureBranchVersioning
    vcs
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
    }
    releaseInputTriggers {
        uuid
        name
        conditionGroup {
            matchOperator
            conditionGroups {
                matchOperator
                conditions {
                    type
	                approvalEntry
	                approvalState
	                possibleLifecycles
	                possibleBranchTypes
	                metricsType
	                comparisonSign
	                metricsValue
                }
            }
        }
        outputEvents
    }
    identifiers {
        idType
        idValue
    }
`

const COMPONENT_TITLE_GQL_DATA = `
    uuid
    name
    org
    firstRelease {
        uuid
        version
    }
    lastRelease {
        uuid
        version
    }
`

const CHANGES_GQL_DATA = `
    changes {
        changeType
        commitRecords {
            linkifiedText
            rawText
            commitAuthor
            commitEmail
        }
    }
`

const CHANGELOG_GQL_DATA = `
    ${COMPONENT_TITLE_GQL_DATA}
    branches {
        uuid
        name
        ${CHANGES_GQL_DATA}
        releases {
            uuid
            version
            ${CHANGES_GQL_DATA}
        }
        tickets {
            ticketSubject
            ${CHANGES_GQL_DATA}
        }
    }
   
    components {
        uuid
        name
        branches {
            uuid
            name
            ${CHANGES_GQL_DATA}
            releases {
                uuid
                version
                ${CHANGES_GQL_DATA}
            }
            tickets {
                ticketSubject
                ${CHANGES_GQL_DATA}
            }
        }
        components {
            uuid
            name
            org
            branches {
                uuid
                name
                releases {
                    uuid
                    version
                    ${CHANGES_GQL_DATA}
                }
            }
            releases {
                uuid
                version
                ${CHANGES_GQL_DATA}
            }
        }
    }
    components {
        ${COMPONENT_TITLE_GQL_DATA}
        ${CHANGES_GQL_DATA}
    }
    components {
        ${COMPONENT_TITLE_GQL_DATA}
        tickets {
            ticketSubject
            ${CHANGES_GQL_DATA}
        }
    }
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
    pullRequests{
        title
        targetBranch
        endpoint
        number
        state
        createdDate
        closedDate
        mergedDate
        commits
    }
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

const BRANCHES_GQL = gql`
query FetchBranches($componentUuid: ID!) {
    branchesOfComponent(componentUuid: $componentUuid) {
        ${BRANCH_GQL_DATA}
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
    identifiers {
        idType
        idValue
    }
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
        }
    }
    publicSshKeys {
        uuid
        name
    }
    policiesAccepted
    isGlobalAdmin
`

export default {
    BranchGql: BRANCH_GQL_DATA,
    BranchesGql: BRANCHES_GQL,
    BranchGqlMutate: BRANCH_GQL_MUTATE,
    ChangeLogGqlData: CHANGELOG_GQL_DATA,
    InstanceGql: INSTANCE_GQL,
    InstanceGqlData: INSTANCE_GQL_DATA,
    InstancesGql: INSTANCES_GQL,
    MultiReleaseGqlData: MULTI_RELEASE_GQL_DATA,
    SingleReleaseGql: SINGLE_RELEASE_GQL,
    SingleReleaseGqlData: SINGLE_RELEASE_GQL_DATA,
    SingleReleaseGqlLight: SINGLE_RELEASE_GQL_LIGHT,
    ComponentFullData: COMPONENT_FULL_DATA,
    ComponentMutate: COMPONENT_MUTATE,
    ReleaseGqlMutate: RELEASE_GQL_MUTATE,
    ApproveReleaseGqlMutate: APPROVE_RELEASE_GQL_MUTATE,
    ComponentShortData: COMPONENT_SHORT_DATA,
    MarketingRelease: MARKETING_RELEASE_GQL_DATA,
    UserData: USER_GQL_DATA,
}