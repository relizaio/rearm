import gql from 'graphql-tag'
import Swal from 'sweetalert2'
import graphqlClient from './graphql'

function translatePermissionName(type: string): string {
    switch (type) {
        case 'NONE': return 'None'
        case 'ESSENTIAL_READ': return 'Essential Read'
        case 'READ_ONLY': return 'Read Only'
        case 'READ_WRITE': return 'Read & Write'
        case 'ADMIN': return 'Administrator'
        default: return type
    }
}

function translateFunctionName(fn: string): string {
    switch (fn) {
        case 'RESOURCE': return 'Resource'
        case 'VULN_ANALYSIS': return 'Finding Analysis Read'
        case 'FINDING_ANALYSIS_READ': return 'Finding Analysis Read'
        case 'FINDING_ANALYSIS_WRITE': return 'Finding Analysis Write'
        case 'ARTIFACT_DOWNLOAD': return 'Artifact Download'
        case 'LIFECYCLE_UPDATE': return 'Lifecycle Update'
        case 'SBOM_PROBING': return 'SBOM Probing'
        case 'DEVOPS_READ': return 'DevOps Read'
        case 'DEVOPS_WRITE': return 'DevOps Write'
        case 'VERSION_FEATURESET': return 'Version Feature Set'
        case 'AGENT': return 'AI Agent'
        case 'DISTRIBUTION': return 'Distribution'
        default: return fn
    }
}

// Help-tooltip text for each permission function, keyed by the backend
// PermissionFunction enum value. Rendered by PermissionFunctionLabel next to
// the function's checkbox. Plain text; blank lines become paragraph breaks
// (the label renders with white-space: pre-line). Keep in sync with the
// PermissionFunction enum in the backend (model/UserPermission.java).
const PERMISSION_FUNCTION_DESCRIPTIONS: Record<string, string> = {
    FINDING_ANALYSIS_READ:
        'View existing finding analysis records - VEX statements, suppressions, and justifications on vulnerabilities and policy violations.',
    FINDING_ANALYSIS_WRITE:
        'Create and edit finding analysis (VEX / suppression / justification). Requires "Finding Analysis Read" to also view existing finding records.',
    ARTIFACT_DOWNLOAD:
        'Download release artifacts - SBOMs, attestations, and other stored files.',
    LIFECYCLE_UPDATE:
        'Advance a release through its lifecycle (e.g. Draft to Assembled to Rejected). Requires Read & Write permission to take effect - granting this function to a Read Only user will not allow them to change lifecycle.',
    SBOM_PROBING:
        'Upload a temporary SBOM to get vulnerability and policy stats on it, without creating a persistent Dependency-Track project or a release.',
    DEVOPS_READ:
        'View the DevOps surface - instances, clusters, and their current deployment state.',
    DEVOPS_WRITE:
        'Modify the DevOps surface - register instances and clusters and submit deployment state. Requires Read & Write permission to take effect.',
    VERSION_FEATURESET:
        'Create a new product feature set with selected dependency-branch overrides applied on top of the base feature set. Typically granted to a CI/CD (FREEFORM) key on the product.',
    AGENT:
        'The "I am an agent" marker - grants a key the right to act as an AI agent: register itself on first call, open / touch / close sessions, attach artifacts, and spawn sub-agents. Not needed for human users browsing the AI Agents dashboard or managing agent policies.\n\n' +
        'Carve-out: a key with this function (even Read Only) can also enrol its own SSH/GPG public signing key via "rearm agent enrollkey" - needed so an agent can sign its first commit without operator intervention. The backend enforces that the key being enrolled targets the calling key\'s own agent identity; cross-agent enrolment is rejected.',
    DISTRIBUTION:
        'Access the Distribution module - clients, sites, shipments, devices, and device events. Organization admins have this implicitly.',
}

function translateFunctionDescription(fn: string): string | null {
    return PERMISSION_FUNCTION_DESCRIPTIONS[fn] ?? null
}

function getUserPermission (org : string, myUser: any) {
    let userPermission = {
        org: '',
        spawn: ''
    }
    if (myUser && myUser.permissions) {
        Object.keys(myUser.permissions.permissions).forEach(key => {
            if (myUser.permissions.permissions[key]['org'] === org && myUser.permissions.permissions[key]['object'] === org && myUser.permissions.permissions[key]['scope'] === 'ORGANIZATION') {
                userPermission.org = myUser.permissions.permissions[key]['type']
            } else if (myUser.permissions.permissions[key]['org'] === org && myUser.permissions.permissions[key]['object'] === '00000000-0000-0000-0000-000000000002' && myUser.permissions.permissions[key]['scope'] === 'INSTANCE') {
                userPermission.spawn = myUser.permissions.permissions[key]['type']
            }
        })
    }
    return userPermission
}

function isWritable (org : string, myUser : any, type : string, objectId?: string, clusterId?: string) : boolean {
    const userPermission = getUserPermission(org, myUser)
    if(userPermission.org === 'ADMIN')
        return true
    let isWritable: boolean = (userPermission.org === 'READ_WRITE' || userPermission.org === 'ADMIN')
    if (type === 'INSTANCE' && objectId && objectId.length && myUser.permissions) {
        Object.keys(myUser.permissions.permissions).forEach(key => {
            if (myUser.permissions.permissions[key]['org'] === org && myUser.permissions.permissions[key]['object'] === objectId && myUser.permissions.permissions[key]['scope'] === 'INSTANCE') {
                const userInstPermission = myUser.permissions.permissions[key]['type']
                if (userInstPermission === 'READ_WRITE') isWritable = true
                else if (userInstPermission === 'READ_ONLY') isWritable = false
            }
        })
    }
    if (!isWritable && type === 'INSTANCE' && objectId && objectId.length && myUser.permissions && clusterId && clusterId.length ) {
        Object.keys(myUser.permissions.permissions).forEach(key => {
            if (myUser.permissions.permissions[key]['org'] === org && myUser.permissions.permissions[key]['object'] === clusterId && myUser.permissions.permissions[key]['scope'] === 'INSTANCE') {
                const userInstPermission = myUser.permissions.permissions[key]['type']
                if (userInstPermission === 'READ_WRITE') isWritable = true
            }
        })
    }
    return isWritable
}

function isAdmin (org : string, myUser : any ) : boolean {
    const userPermission = getUserPermission(org, myUser)
    const isAdmin: boolean = userPermission.org === 'ADMIN'
    return isAdmin
}


function parseGraphQLError (err: string): string {
    const knownPrefixes = ['BOM processing failed: ', 'BOM validation failed: ', 'Rebom error: ']
    let cleaned = err
    for (const prefix of knownPrefixes) {
        if (cleaned.startsWith(prefix)) {
            cleaned = cleaned.substring(prefix.length)
        }
    }
    return cleaned
}

function extractGraphQLErrorMessage (error: any): string {
    if (!error) return 'Unknown error'

    const messages: string[] = []
    const graphQlErrors = error?.graphQLErrors || error?.networkError?.result?.errors || error?.errors

    if (Array.isArray(graphQlErrors)) {
        graphQlErrors.forEach((e: any) => {
            if (e?.message) messages.push(e.message)
        })
    }

    if (messages.length === 0 && typeof error?.message === 'string') {
        messages.push(error.message)
    }

    if (messages.length === 0) return 'Unknown error'

    const cleaned = messages
        .map((m: string) => m.replace(/^GraphQL error:\s*/i, '').trim())
        .filter((m: string) => m.length > 0)

    return cleaned.length ? Array.from(new Set(cleaned)).join('; ') : 'Unknown error'
}

function deepCopy (obj: any) {
    return JSON.parse(JSON.stringify(obj))
}

/**
 * Serialize objects with sorted keys for stable comparison.
 * Useful when comparing objects that may have different key ordering
 * (e.g., GraphQL responses vs locally modified copies).
 */
function stableStringify(obj: any): string {
    return JSON.stringify(obj, (key, value) => {
        if (value && typeof value === 'object' && !Array.isArray(value)) {
            return Object.keys(value).sort().reduce((sorted: any, k) => {
                sorted[k] = value[k]
                return sorted
            }, {})
        }
        return value
    })
}
function dateDisplay (zonedDate: any) {
    let date = new Date(zonedDate)
    // return date.toLocaleString('en-CA', { timeZone: 'UTC' })
    return date.toLocaleString('en-CA')
}
function linkifyCommit (uri: string, commit: string): string {
    let linkifiedCommit = commit
    
    if (!uri || !commit) {
        return linkifiedCommit
    }
    
    try {
        // Ensure a scheme for URL parsing
        let input = uri.trim()
        if (!/^https?:\/\//i.test(input)) {
            input = `https://${input}`
        }
        
        const url = new URL(input)
        const encodedCommit = encodeURIComponent(commit)
        
        if (url.hostname === 'bitbucket.org') {
            linkifiedCommit = `${url.origin}${url.pathname}/commits/${encodedCommit}`
        } else if (url.hostname === 'github.com') {
            linkifiedCommit = `${url.origin}${url.pathname}/commit/${encodedCommit}`
        } else if (url.hostname === 'gitlab.com') {
            linkifiedCommit = `${url.origin}${url.pathname}/-/commit/${encodedCommit}`
        } else if (url.hostname === 'dev.azure.com' || url.hostname.endsWith('.visualstudio.com')) {
            linkifiedCommit = `${url.origin}${url.pathname}/commit/${encodedCommit}`
        }
    } catch (e) {
        // If URL parsing fails, return the original commit value
        return linkifiedCommit
    }
    
    return linkifiedCommit
}
function genUuid () {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        let r = Math.random() * 16 | 0
        let v = c === 'x' ? r : (r & 0x3 | 0x8)
        return v.toString(16)
    })
}

export type SwalData = {
    questionText: string
    successTitle: string,
    successText: string,
    dismissText: string
}

async function confirmUnsavedChanges(): Promise<boolean> {
    const result = await Swal.fire({
        title: 'Unsaved Changes',
        text: 'You have unsaved changes. Are you sure you want to close?',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, close',
        cancelButtonText: 'No, stay'
    })
    return result.isConfirmed
}

async function swalWrapper (wrappedFunction: Function, swalData: SwalData, notifyFunction: Function) {
    const swalResp = await Swal.fire({
        title: 'Are you sure?',
        text: swalData.questionText,
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes!',
        cancelButtonText: 'No!'
    })
    
    if (swalResp.value) {
        await wrappedFunction()
        notifyFunction('success', swalData.successTitle, swalData.successText)
    // For more information about handling dismissals please visit
    // https://sweetalert2.github.io/#handling-dismissals
    } else if (swalResp.dismiss === Swal.DismissReason.cancel) {
        notifyFunction('error', 'Cancelled', swalData.dismissText)
    }
}

async function setInstanceApiKey (instanceUuid : string) {
    const response = await graphqlClient.query({
        query: gql`
            query setInstanceApiKey(instanceUuid: ID!) {
                setInstanceApiKey(instanceUuid: $instanceUuid)
            }`,
        variables: {
            $instanceUuid: instanceUuid
        }
    })
    return response.data.setInstanceApiKey
}

export interface OrgTerminology {
    featureSetLabel?: string
}

const DEFAULT_FEATURE_SET_LABEL = 'Feature Set'

function resolveWords (isComponent: boolean, orgTerminology?: OrgTerminology) {
    const featureSetLabel = orgTerminology?.featureSetLabel || DEFAULT_FEATURE_SET_LABEL
    const featureSetLabelLower = featureSetLabel.toLowerCase()
    return {
        branchFirstUpper: isComponent ? 'Branch' : featureSetLabel,
        branch: isComponent ? 'branch' : featureSetLabelLower,
        componentFirstUpper: isComponent ? 'Component' : 'Product',
        component: isComponent ? 'component' : 'product',
        componentsFirstUpper: isComponent ? 'Components' : 'Products'
    }
}

function getGeneratedApiKeyHTML(responseData: any) {
    return `
            <div style="text-align: left;">
            <p>Please record these data as you will see API key only once (although you can re-generate it at any time):</p>
                <table style="width: 95%;">
                    <tr>
                        <td>
                            <strong>API ID:</strong>
                        </td>
                        <td>
                            <textarea style="width: 100%;" disabled>${responseData.id}</textarea>
                        </td>
                    </tr>
                        <td>
                            <strong>API Key:</strong>
                        </td>
                        <td>
                            <textarea style="width: 100%;" disabled>${responseData.apiKey}</textarea>
                        </td>
                    <tr>
                        <td>
                            <strong>Header:</strong>
                        </td>
                        <td>
                            <textarea style="width: 100%;" disabled rows="4">${responseData.authorizationHeader}</textarea>
                        </td>
                    </tr>
                </table>
            </div>
        `
}

function isCycloneDXBomArtifact (art: any) {
    return art.bomFormat
        && art.type
        && art.bomFormat === 'CYCLONEDX'
        && (
            art.type === 'BOM'
            || art.type === 'VDR'
            || art.type === 'VEX'
            || art.type === 'ATTESTATION'
        );
}

/**
 * Formats spec version enum to readable text
 * e.g. CYCLONEDX_1_6 -> "CycloneDX 1.6", SPDX_2_3 -> "SPDX 2.3"
 */
function formatSpecVersion(specVersion: string): string {
    if (!specVersion) return ''
    // Replace underscores with dots for version numbers, handle format name
    const parts = specVersion.split('_')
    if (parts.length < 2) return specVersion
    
    // First part is the format name (CYCLONEDX, SPDX)
    let formatName = parts[0]
    if (formatName === 'CYCLONEDX') {
        formatName = 'CycloneDX'
    }
    
    // Remaining parts are version numbers
    const version = parts.slice(1).join('.')
    return `${formatName} ${version}`
}

async function handleFetchUserResult(fetchUserResult: any): Promise<void> {
    if (!fetchUserResult) return
    if (fetchUserResult._unauthorized) {
        await Swal.fire({
            title: 'Account Inactive',
            text: 'Your account is inactive. Please contact your System Administrator or support at info@reliza.io',
            icon: 'error',
            allowOutsideClick: false,
            showConfirmButton: false,
            showCancelButton: false
        })
    } else if (fetchUserResult._offline) {
        await Swal.fire({
            title: 'System Offline',
            text: 'System offline. Please contact your System Administrator or support at info@reliza.io',
            icon: 'error',
            allowOutsideClick: false,
            showConfirmButton: false,
            showCancelButton: false
        })
    }
}

export default {
    getGeneratedApiKeyHTML,
    getUserPermission,
    translatePermissionName,
    translateFunctionName,
    translateFunctionDescription,
    deepCopy,
    isAdmin,
    isWritable,
    linkifyCommit,
    parseGraphQLError,
    extractGraphQLErrorMessage,
    genUuid,
    dateDisplay,
    swalWrapper,
    resolveWords,
    isCycloneDXBomArtifact,
    formatSpecVersion,
    stableStringify,
    confirmUnsavedChanges,
    handleFetchUserResult
}