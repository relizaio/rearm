import gql from 'graphql-tag'
import Swal from 'sweetalert2'
import graphqlClient from './graphql'
import graphqlQueries from './graphqlQueries'

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
    let isWritable: boolean = (userPermission.org !== '' && userPermission.org !== 'READ_ONLY')
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


async function fetchChangelogBetweenReleases (params : any) {
    const response = await graphqlClient.query({
        query: gql`
            query FetchChangelogBetweenReleases($release1: ID!, $release2: ID!, $org: ID!, $aggregated: AggregationType, $timeZone: String) {
                getChangelogBetweenReleases(release1: $release1, release2: $release2, orgUuid: $org, aggregated: $aggregated, timeZone: $timeZone) {
                    ${graphqlQueries.ChangeLogGqlData}
                }
            }`,
        variables: {
            release1: params.release1,
            release2: params.release2,
            org: params.org,
            aggregated: params.aggregated,
            timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone
        }
    })
    return response.data.getChangelogBetweenReleases
}

async function fetchComponentChangeLog (params : any) {
    const response = await graphqlClient.query({
        query: gql`
            query FetchComponentChangelog($componentId: ID!, $branchId: ID!, $orgId: ID!, $aggregated: AggregationType, $timeZone: String) {
                getComponentChangeLog(componentUuid: $componentId, branchUuid: $branchId, orgUuid: $orgId, aggregated: $aggregated, timeZone: $timeZone) {
                    ${graphqlQueries.ChangeLogGqlData}
                }
            }`,
        variables: {
            componentId: params.componentId,
            orgId: params.orgId,
            branchId: params.branchId,
            aggregated: params.aggregated,
            timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone
        },
        fetchPolicy: 'no-cache'
    })
    return response.data.getComponentChangeLog
}

function parseGraphQLError (err: string): string {
    return err.split(': ')[err.split(': ').length - 1]
}

function deepCopy (obj: any) {
    return JSON.parse(JSON.stringify(obj))
}
function dateDisplay (zonedDate: any) {
    let date = new Date(zonedDate)
    // return date.toLocaleString('en-CA', { timeZone: 'UTC' })
    return date.toLocaleString('en-CA')
}
function linkifyCommit (uri: string, commit: string): string {
    let linkifiedCommit = commit
    if (uri && uri.toLowerCase().includes('bitbucket.org/')) {
        let repoPart = uri.toLowerCase().split('bitbucket.org/')[1]
        linkifiedCommit = 'https://bitbucket.org/' + repoPart + '/commits/' + commit
    } else if (uri &&  uri.toLowerCase().includes('github.com/')) {
        let repoPart = uri.toLowerCase().split('github.com/')[1]
        linkifiedCommit = 'https://github.com/' + repoPart + '/commit/' + commit
    } else if (uri &&  uri.toLowerCase().includes('gitlab.com/')) {
        let repoPart = uri.toLowerCase().split('gitlab.com/')[1]
        linkifiedCommit = 'https://gitlab.com/' + repoPart + '/-/commit/' + commit
    } else if (uri && uri.toLowerCase().includes('azure.com/')) {
        // remove @ if present
        let repoPart
        let repoUserArr = uri.toLowerCase().split('@')
        if (repoUserArr.length === 2) {
            repoPart = repoUserArr[1]
        } else {
            repoPart = repoUserArr[0]
        }
        linkifiedCommit = `https://${repoPart}/commit/${commit}`
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

async function swalWrapper (wrappedFunction: Function, swalData: SwalData) {
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
        Swal.fire(
            swalData.successTitle,
            swalData.successText,
            'success'
        )
    // For more information about handling dismissals please visit
    // https://sweetalert2.github.io/#handling-dismissals
    } else if (swalResp.dismiss === Swal.DismissReason.cancel) {
        Swal.fire(
            'Cancelled',
            swalData.dismissText,
            'error'
        )
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

function resolveWords (isComponent: boolean) {
    return {
        branchFirstUpper: isComponent ? 'Branch' : 'Feature Set',
        branch: isComponent ? 'branch' : 'feature set',
        componentFirstUpper: isComponent ? 'Component' : 'Product',
        component: isComponent ? 'component' : 'product',
        componentsFirstUpper: isComponent ? 'Components' : 'Products'
    }
}


export default {
    getUserPermission,
    deepCopy,
    fetchChangelogBetweenReleases,
    fetchComponentChangeLog,
    isAdmin,
    isWritable,
    linkifyCommit,
    parseGraphQLError,
    genUuid,
    dateDisplay,
    swalWrapper,
    resolveWords
}