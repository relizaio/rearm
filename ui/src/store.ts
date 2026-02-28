import { createStore } from 'vuex'
import { reactive } from 'vue'
import axios from './utils/axios'
import gql from 'graphql-tag'
import constants from './utils/constants'
import graphqlClient from './utils/graphql'
import graphqlQueries from './utils/graphqlQueries'
import VcsReposOfOrg from './components/VcsReposOfOrg.vue'

const storeObject : any = {
    state () {
        return {
            resourceGroups: [],
            organizations: [],
            components: [],
            branches: [],
            releases: [],
            marketingReleases: [],
            vcsRepos: [],
            sourceCodeEntries: [],
            artifacts: [],
            instances: [],
            changes: [],
            properties: [],
            users: [],
            iam: {
                user: {},
                orgUuid: '',
                appUuid: '00000000-0000-0000-0000-000000000000',
                name: '',
                perspectiveUuid: 'default'
            },
            perspectives: [],
            instancePagination: () => {
                const instancePagination = reactive({
                    page: 1,
                    pageSize: 10,
                    onChange: (page: number) => {
                        instancePagination.page = page
                    }
                })
                return instancePagination
            }
        }
    },
    getters: {
        allResourceGroups (state: any) {
            return state.resourceGroups.slice()
        },
        allOrganizations (state: any) {
            return state.organizations.slice()
        },
        allUsers (state: any) {
            return state.users.slice()
        },
        artifactById (state: any) {
            return (uuid: any) => state.artifacts.find((art: any) => art.uuid === uuid)
        },
        branchById (state: any) {
            return (uuid: string) => state.branches.find((b: any) => b.uuid === uuid)
        },
        branchesOfComponent (state : any) {
            return (uuid: string) => state.branches.filter((b: any) => (b.component === uuid))
        },
        myorg (state : any) {
            for (let i = 0; i < state.organizations.length; i++) {
                let org = state.organizations[i]
                if (org.uuid === state.iam.orgUuid) {
                    return org
                }
            }
            return null
        },
        myuser (state : any) {
            return Object.assign({}, state.iam.user)
        },
        myperspective (state : any) {
            return state.iam.perspectiveUuid
        },
        allPerspectives (state: any) {
            return state.perspectives.slice()
        },
        perspectivesOfOrg (state: any) {
            return (orguuid: string) => state.perspectives.filter((p: any) => p.org === orguuid)
        },
        releaseById (state : any) {
            return (uuid : string) => state.releases.find((rl : any) => rl.uuid === uuid)
        },
        orgById (state : any) {
            return (uuid : string) => state.organizations.find((org : any) => org.uuid === uuid)
        },
        componentById (state : any) {
            return (uuid: string) => state.components.find((b: any) => b.uuid === uuid)
        },
        sourceCodeEntryById (state: any) {
            return (uuid: string) => state.sourceCodeEntries.find((sce: any) => sce.uuid === uuid)
        },
        vcsRepoById (state : any) {
            return (uuid: string) => state.vcsRepos.find((repo: any) => repo === uuid || repo.uuid === uuid)
        },
        vcsReposByOrg (state: any) {
            return (orguuid: string) => state.vcsRepos.filter((repo: any) => repo.org === orguuid)
        },
        componentsOfOrg (state : any) {
            return (uuid: string) => {
                const comps = state.components.filter((p: any) => (p.org === uuid) && (p.type === 'COMPONENT'))
                if (comps && comps.length) {
                    comps.sort((a: any, b: any) => {
                        if (a.name.toLowerCase() < b.name.toLowerCase()) {
                            return -1
                        } else if (a.name.toLowerCase() > b.name.toLowerCase()) {
                            return 1
                        } else {
                            return 0
                        }
                    })
                }
                return comps
            }
        },
        propertiesOfOrg (state: any) {
            return (uuid: string) => state.properties.filter((p: any) => (p.org === uuid))
        },
        externalComponents (state: any) {
            return (uuid: string) => state.components.filter((p: any) => (p.org === constants.ExternalPublicComponentsOrg))
        },
        productsOfOrg (state: any) {
            return (uuid: string) => state.components.filter((p: any) => (p.org === uuid) && (p.type === 'PRODUCT'))
        },
        vcsReposOfOrg (state: any) {
            return (uuid: string) => state.vcsRepos.filter((vcs: any) => (vcs.org === uuid))
        },
        releasesOfBranch (state: any) {
            return (uuid: string) => state.releases.filter((rlz: any) => (rlz.branch === uuid))
        },
        releasesOfBranchPr (state: any) {
            return (uuid: string, sces: any[]) => state.releases.filter((rlz: any): boolean => {
                return sces && sces.length && rlz.branch === uuid && rlz.sourceCodeEntryDetails && sces.includes(rlz.sourceCodeEntryDetails.uuid)
            })
        },
        instancesOfOrg (state: any) {
            return (uuid: string) => state.instances.filter((inst: any) => (inst.org === uuid))
        },
        currentOrginvitees (state: any) {
            const currentOrg = state.organizations.find((org: any) => org.uuid === state.iam.orgUuid)
            return currentOrg ? currentOrg.invitees : null
        },
        instanceById (state: any) {
            return (uuid: string, revision: string) => state.instances.find((inst: any) => (inst.uuid === uuid && inst.revision === revision))
        },
        instancePagination (state: any) {
            return state.instancePagination()
        }
    },
    mutations: {
        ADD_ARTIFACT (state: any, artifact: any) {
            state.artifacts = state.artifacts.filter((art: any) => (art.uuid !== artifact.uuid))
            state.artifacts.push(artifact)
        },
        ADD_BRANCH (state : any, branch : any) {
            let foundBranch = false
            for (let i = 0; i < state.branches.length; i++) {
                if (state.branches[i].uuid === branch.uuid) {
                    state.branches[i] = branch
                    foundBranch = true
                    break
                }
            }
            if (!foundBranch) {
                state.branches.push(branch)
            }
        },
        ADD_INSTANCE (state: any, instance: any) {
            if (!instance.revision) instance.revision = -1
            state.instances = state.instances.filter((inst: any) => (!(inst.uuid === instance.uuid && inst.revision === instance.revision)))
            state.instances.push(instance)
        },
        ADD_ORGANIZATION (state: any, org: any) {
            const newOrgs = state.organizations.slice()
            newOrgs.push(org)
            state.organizations = newOrgs
        },
        ADD_RELEASE (state : any, release : any) {
            let ind = state.releases.findIndex((r : any) => (r.uuid === release.uuid))
            if (ind === 0 || (ind && ind > -1)) {
                state.releases[ind] = release
            } else {
                state.releases.unshift(release)
            }
        },
        ADD_RELEASES (state: any, releases: any[]) {
            releases.forEach(rl => {
                state.releases = state.releases.filter((srl: any) => (srl.uuid !== rl.uuid))
                state.releases.push(rl)
            })
        },
        ADD_PROPERTY (state: any, property: any) {
            state.properties = state.properties.filter((p: any) => (p.uuid !== property.uuid))
            state.properties.push(property)
        },
        ADD_COMPONENTS (state: any, components: any[]) {
            components.forEach(p => {
                state.components = state.components.filter((sp: any) => (sp.uuid !== p.uuid))
                state.components.push(p)
            })
        },
        SET_COMPONENTS_OF_ORG (state: any, payload: { orgUuid: string, componentType: string, components: any[] }) {
            // Remove all components of this org and type, then add the fresh list
            state.components = state.components.filter((c: any) => !(c.org === payload.orgUuid && c.type === payload.componentType))
            payload.components.forEach((c: any) => state.components.push(c))
        },
        ADD_UPDATE_COMPONENT (state: any, component: any) {
            const compIndex = state.components.findIndex((c: any) => (c.uuid === component.uuid))
            if (compIndex > -1) {
                state.components[compIndex] = component
            } else {
                state.components.push(component)
            }
        },
        ADD_UPDATE_MARKETING_RELEASE (state: any, marketingRelease: any) {
            state.marketingReleases = state.marketingReleases.filter((mr: any) => (mr.uuid !== marketingRelease.uuid))
            state.marketingReleases.push(marketingRelease)
        },
        ADD_SOURCE_CODE_ENTRY (state: any, sceToAdd: any) {
            state.sourceCodeEntries = state.sourceCodeEntries.filter((sce: any) => (sce.uuid !== sceToAdd.uuid))
            state.sourceCodeEntries.push(sceToAdd)
        },
        SET_ORGANIZATIONS (state : any, organizations : any[]) {
            state.organizations = organizations
        },
        SET_RESOURCE_GROUPS (state : any, resourceGroups : any[]) {
            state.resourceGroups = resourceGroups
        },
        UPDATE_ORGANIZATION (state: any, organization: any) {
            state.organizations = state.organizations.filter((org: any) => (org.uuid !== organization.uuid))
            state.organizations.push(organization)
        },
        UPDATE_RESOURCE_GROUP (state : any, resourceGroup : any) {
            state.resourceGroups = state.resourceGroups.filter((app: any) => (app.uuid !== resourceGroup.uuid))
            state.resourceGroups.push(resourceGroup)
        },
        SET_BRANCHES_OF_COMP (state : any, setObj : any) {
            let newBranches = state.branches.filter((b : any) => (b.componentUuid !== setObj.id && b.component !== setObj.id))
            newBranches.push(...setObj.data)
            state.branches = newBranches
        },
        SET_MY_USER (state : any, user : any) {
            state.iam.user = Object.assign({}, user)
        },
        SET_VCS_REPOS (state : any, vcsRepos : any[]) {
            state.vcsRepos = vcsRepos
        },
        REMOVE_VCS_REPO (state: any, vcsUuid: string) {
            state.vcsRepos = state.vcsRepos.filter((repo: any) => repo.uuid !== vcsUuid)
        },
        SET_COMPONENTS (state: any, components: any[]) {
            state.components = components
        },
        UPDATE_MY_ORG (state : any, uuid : string) {
            state.iam.orgUuid = uuid
        },
        UPDATE_MY_PERSPECTIVE (state : any, uuid : string) {
            state.iam.perspectiveUuid = uuid
        },
        SET_PERSPECTIVES (state: any, perspectives: any[]) {
            state.perspectives = perspectives
        },
        ADD_VCS_REPO (state: any, vcsRepo: any) {
            if (vcsRepo) {
                state.vcsRepos = state.vcsRepos.filter((vcs: any) => (vcs.uuid !== vcsRepo.uuid))
                state.vcsRepos.push(vcsRepo)
            }
        },
        SET_INSTANCES (state: any, instances: any[]) {
            state.instances = instances
        },
        SET_PROPERTIES (state: any, properties: any[]) {
            state.properties = properties
        },
        ADD_RESOURCE_GROUP (state: any, app: any) {
            state.resourceGroups = [...state.resourceGroups, app]
        },
        SET_USERS (state:any, users: any) {
            state.users = users
        },
        SET_RELEASES (state: any, releases: any) {
            state.releases = releases
        }
    },
    actions: {
        async acceptMyUserPolicies (context: any, obj: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                        mutation acceptUserPolicies($tosAccepted: Boolean!, $marketingAccepted: Boolean!, $email: String) {
                            acceptUserPolicies(tosAccepted: $tosAccepted, marketingAccepted: $marketingAccepted, email: $email) {
                                ${graphqlQueries.UserData}
                            }
                        }`,
                variables: obj
            })
            context.commit('SET_MY_USER', data.data.acceptUserPolicies)
            return data.data.acceptUserPolicies
        },
        async archiveBranch (context: any, params: any) {
            const archived = await graphqlClient.mutate({
                mutation: gql`
                    mutation archiveBranch($branchUuid: ID!) {
                        archiveBranch(branchUuid: $branchUuid)
                    }`,
                variables: {
                    branchUuid: params.branchUuid
                }
            })
            if (archived) {
                // reload branches
                store.dispatch('fetchBranches', { componentId: params.componentUuid, forceRefresh: true })
            }
        },
        async cloneBranch (context: any, brProps: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation cloneBranch($branchUuid: ID!, $name: String!, $branchType: BranchType, $versionSchema: String) {
                        cloneBranch(branchUuid: $branchUuid, name: $name, branchType: $branchType, versionSchema: $versionSchema) {
                            ${graphqlQueries.BranchGql}
                        }
                    }`,
                variables: {
                    branchUuid: brProps.branchUuid,
                    name: brProps.name,
                    branchType: brProps.branchType,
                    versionSchema: brProps.versionSchema
                }
            })
            context.commit('ADD_BRANCH', data.data.cloneBranch)
            return data.data.cloneBranch
        },
        async createArtifact(context: any, artProps: any){
            console.log(artProps)
            const resp = await graphqlClient.mutate({
                mutation: gql`
                    mutation createArtifact($artifact: ArtifactInput!) {
                        createArtifact(artifact: $artifact) {
                            displayIdentifier
                            uuid
                        }
                    }`,
                variables: {
                    'artifact': artProps.artifacts
                }
            })
            context.commit('ADD_ARTIFACT', resp.data.createArtifact)
            return resp.data.createArtifact
        },
        // async addArtifactManual(context: any, artProps: any){
        //     const data = await graphqlClient.mutate({
        //         mutation: gql`
        //             mutation addArtifactManual($input: AddArtifactInput) {
        //                 addArtifactManual(artifactInput: $input) {
        //                     uuid
        //                 }
        //             }`,
        //         variables: {
        //             'input': artProps
        //         }
        //     })
        // },
        async createBranch (context : any, brProps : any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation createBranch($componentUuid: ID!, $name: String!, $versionSchema: String) {
                        createBranch(componentUuid: $componentUuid, name: $name, versionSchema: $versionSchema) {
                            ${graphqlQueries.BranchGql}
                        }
                    }`,
                variables: {
                    name: brProps.name,
                    versionSchema: brProps.versionSchema,
                    componentUuid: brProps.component
                }
            })
            context.commit('ADD_BRANCH', data.data.createBranch)
            return data.data.createBranch
        },
        async createComponent (context: any, projProps: any) {
            if (projProps.defaultBranch === '') {
                delete projProps.defaultBranch
            } else {
                projProps.defaultBranch = projProps.defaultBranch.toUpperCase()
            }
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation createComponent($component: CreateComponentInput!) {
                        createComponent(component: $component) {
                            ${graphqlQueries.ComponentFullData}
                        }
                    }`,
                variables: {
                    component: projProps
                }
            })
            context.commit('ADD_UPDATE_COMPONENT', data.data.createComponent)
            return data.data.createComponent
        },
        async createMarketingRelease (context: any, mrktRlz: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation addMarketingReleaseManual($marketingRelease: MarketingReleaseInput!) {
                        addMarketingReleaseManual(marketingRelease: $marketingRelease) {
                            ${graphqlQueries.MarketingRelease}
                        }
                    }`,
                variables: {
                    marketingRelease: mrktRlz
                }
            })
            context.commit('ADD_UPDATE_MARKETING_RELEASE', data.data.addMarketingReleaseManual)
            return data.data.addMarketingReleaseManual
        },
        async createProperty (context: any, propertyProps: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation createProperty($prop: PropertyInput!) {
                        createProperty(property: $prop) {
                            uuid
                            name
                            org
                            dataType
                            targetType
                        }
                    }`,
                variables: {
                    'prop': propertyProps
                }
            })
            context.commit('ADD_PROPERTY', data.data.createProperty)
            return data.data.createProperty
        },
        async createRelease (context: any, release: any) {
            delete release.namespace
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation AddRelease($rel: ReleaseInput!) {
                        addReleaseManual(release: $rel) {
                            ${graphqlQueries.SingleReleaseGqlData}
                        }
                    }`,
                variables: {
                    'rel': release
                }
            })
            context.commit('ADD_RELEASE', data.data.addReleaseManual)
            return data.data.addReleaseManual
        },
        async createSourceCodeEntry (context: any, sceProps: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation createSourceCodeEntry($sourceCodeEntry: SourceCodeEntryInput!) {
                        createSourceCodeEntry(sourceCodeEntry: $sourceCodeEntry) {
                            uuid
                            branch
                            commit
                            commits
                        }
                    }`,
                variables: {
                    sourceCodeEntry: sceProps
                }
            })
            context.commit('ADD_SOURCE_CODE_ENTRY', data.data.createSourceCodeEntry)
            return data.data.createSourceCodeEntry
        },
        async fetchMyUser (context : any) {
            try {
                const data = await graphqlClient.query({
                    query: gql`
                        query user {
                            user {
                                ${graphqlQueries.UserData}
                            }
                        }`
                })
                context.commit('SET_MY_USER', data.data.user)
                return data.data.user
            } catch (error : any) {
                console.error(error)
                if (error.response && error.response.data && error.response.data.message && error.response.data.message.startsWith('redirect_')) {
                    console.log('Auth details expired, redirecting to auth provider...')
                    window.localStorage.setItem(constants.RelizaRedirectLocalStorage, window.location.href)
                    window.location.href = error.response.data.message.replace('redirect_', '')
                } else {
                    console.error(error)
                }
            }
        },
        async fetchMyOrganizations (context: any) : Promise<string> {
            let myOrg = ''
            try {
                const data = await graphqlClient.query({
                    query: gql`
                        query organizations {
                            organizations {
                                uuid
                                name
                                approvalRoles {
                                    id
                                    displayView
                                }
                                terminology {
                                    featureSetLabel
                                }
                                ignoreViolation {
                                    licenseViolationRegexIgnore
                                    securityViolationRegexIgnore
                                    operationalViolationRegexIgnore
                                }
                                settings {
                                    justificationMandatory
                                }
                            }
                        }`,
                    fetchPolicy: 'no-cache'
                })
                const orgs = data.data.organizations
                context.commit('SET_ORGANIZATIONS', orgs)
                if (orgs.length) {
                    const storedOrg = window.localStorage.getItem('relizaOrgUuid')
                    let resolved = false
                    for (let i = 0; !resolved && i < orgs.length; i++) {
                        if (orgs[i].uuid === storedOrg) {
                            myOrg = orgs[i].uuid
                            context.commit('UPDATE_MY_ORG', myOrg)
                        }
                    }
                    if (!myOrg) {
                        myOrg = orgs[0].uuid
                        context.commit('UPDATE_MY_ORG', myOrg)
                    }
                    
                    // Load perspectives for the organization
                    await context.dispatch('fetchPerspectives', myOrg)
                    
                    // Load stored perspective
                    const storedPerspective = window.localStorage.getItem('relizaPerspectiveUuid')
                    const availablePerspectives = context.state.perspectives || []
                    const hasStoredPerspective = !!storedPerspective && availablePerspectives.some((p: any) => p.uuid === storedPerspective)
                    if (storedPerspective === 'default' || hasStoredPerspective) {
                        context.commit('UPDATE_MY_PERSPECTIVE', storedPerspective)
                    } else {
                        context.commit('UPDATE_MY_PERSPECTIVE', 'default')
                    }
                } else {
                    console.error('Error fetching user organizations')
                }
            } catch (err) {
                console.error(err)
            }
            return myOrg
        },
        async fetchResourceGroups (context : any, org : string) {
            const response = await graphqlClient.query({
                query: gql`
                    query resourceGroups($orgUuid: ID!) {
                        resourceGroups(orgUuid: $orgUuid) {
                            uuid
                            org
                            name
                            protectedEnvironments
                        }
                    }`,
                variables: {
                    'orgUuid': org
                }
            })
            context.commit('SET_RESOURCE_GROUPS', response.data.resourceGroups)
        },
        async fetchBranch (context: any, uuid: string) {
            const response = await graphqlClient.query({
                query: gql`
                    query FetchBranch($branchUuid: ID!) {
                        branch(branchUuid: $branchUuid) {
                            ${graphqlQueries.BranchGql}
                        }
                    }`
                ,
                variables: { branchUuid: uuid },
                fetchPolicy: 'no-cache'
            })
            context.commit('ADD_BRANCH', response.data.branch)
            return response.data.branch
        },
        async fetchBranches (context : any, payload: string | { componentId: string, forceRefresh?: boolean }) {
            // Support both string componentId and object payload for backwards compatibility
            const componentId = typeof payload === 'string' ? payload : payload.componentId
            const forceRefresh = typeof payload === 'object' ? payload.forceRefresh : false
            
            const response = await graphqlClient.query({
                query: graphqlQueries.BranchesGql,
                variables: { componentUuid: componentId },
                fetchPolicy: forceRefresh ? 'network-only' : 'cache-first'
            })
            const branches = response.data.branchesOfComponent
            const setObj = {
                data: branches,
                id: componentId
            }
            context.commit('SET_BRANCHES_OF_COMP', setObj)
            return branches
        },
        async fetchComponents (context : any, payload: string | { orgid: string, forceRefresh?: boolean }) : Promise<any[]> {
            // Support both string orgid and object payload for backwards compatibility
            const orgid = typeof payload === 'string' ? payload : payload.orgid
            const forceRefresh = typeof payload === 'object' ? payload.forceRefresh : false
            
            const perspectiveUuid = context.state.iam.perspectiveUuid
            const variables: any = { 
                orgUuid: orgid,
                componentType: 'COMPONENT'
            }
            
            // Add perspective parameter if non-default perspective is selected
            if (perspectiveUuid && perspectiveUuid !== 'default') {
                variables.perspective = perspectiveUuid
            }
            
            const response = await graphqlClient.query({
                query: gql`
                    query FetchComponents($orgUuid: ID!, $componentType: ComponentType!, $perspective: ID) {
                        components(orgUuid: $orgUuid, componentType: $componentType, perspective: $perspective) {
                            ${graphqlQueries.ComponentShortData}
                        }
                    }`,
                variables,
                fetchPolicy: forceRefresh ? 'network-only' : 'cache-first'
            })
            context.commit('SET_COMPONENTS_OF_ORG', { orgUuid: orgid, componentType: 'COMPONENT', components: response.data.components })
            return response.data.components
        },
        async fetchProducts (context : any, payload: string | { orgid: string, forceRefresh?: boolean }) : Promise<any[]> {
            // Support both string orgid and object payload for backwards compatibility
            const orgid = typeof payload === 'string' ? payload : payload.orgid
            const forceRefresh = typeof payload === 'object' ? payload.forceRefresh : false
            
            const perspectiveUuid = context.state.iam.perspectiveUuid
            const variables: any = { 
                orgUuid: orgid,
                componentType: 'PRODUCT'
            }
            
            // Add perspective parameter if non-default perspective is selected
            if (perspectiveUuid && perspectiveUuid !== 'default') {
                variables.perspective = perspectiveUuid
            }
            
            const response = await graphqlClient.query({
                query: gql`
                    query FetchComponents($orgUuid: ID!, $componentType: ComponentType!, $perspective: ID) {
                        components(orgUuid: $orgUuid, componentType: $componentType, perspective: $perspective) {
                            ${graphqlQueries.ComponentShortData}
                        }
                    }`,
                variables,
                fetchPolicy: forceRefresh ? 'network-only' : 'cache-first'
            })
            context.commit('SET_COMPONENTS_OF_ORG', { orgUuid: orgid, componentType: 'PRODUCT', components: response.data.components })
            return response.data.components
        },
        async fetchComponent (context: any, uuid: any) {
            const response = await graphqlClient.query({
                query: gql`
                    query FetchComponent($componentID: ID!) {
                        component(componentUuid: $componentID) {
                            ${graphqlQueries.ComponentShortData}
                        }
                    }`,
                variables: { componentID: uuid },
                fetchPolicy: 'no-cache'
            })
            context.commit('ADD_UPDATE_COMPONENT', response.data.component)
            return response.data.component
        },
        async fetchComponentFull (context: any, uuid: string) {
            const response = await graphqlClient.query({
                query: gql`
                    query FetchComponent($componentID: ID!) {
                        component(componentUuid: $componentID) {
                            ${graphqlQueries.ComponentFullData}
                        }
                    }`,
                variables: { componentID: uuid },
                fetchPolicy: 'no-cache'
            })
            context.commit('ADD_UPDATE_COMPONENT', response.data.component)
            return response.data.component
        },
        async fetchReleases (context: any, params: any) {
            const response = await graphqlClient.query({
                query: gql`
                    query FetchReleases($branchID: ID!) {
                        releases(branchFilter: $branchID) {
                            ${graphqlQueries.MultiReleaseGqlData}
                        }
                    }`,
                variables: { branchID: params.branch },
                fetchPolicy: 'no-cache'
            })
            context.commit('ADD_RELEASES', response.data.releases)
            return response.data.releases
        },
        updateMyOrg (context : any, orgUuid : string) {
            // set local browser storage
            window.localStorage.setItem('relizaOrgUuid', orgUuid)
            context.commit('UPDATE_MY_ORG', orgUuid)
            // Reset perspective to default when changing org
            context.dispatch('updateMyPerspective', 'default')
        },
        updateMyPerspective (context : any, perspectiveUuid : string) {
            // set local browser storage
            window.localStorage.setItem('relizaPerspectiveUuid', perspectiveUuid)
            context.commit('UPDATE_MY_PERSPECTIVE', perspectiveUuid)
        },
        async fetchPerspectives (context : any, orgUuid : string) {
            try {
                const response = await graphqlClient.query({
                    query: gql`
                        query perspectives($org: ID!) {
                            perspectives(org: $org) {
                                uuid
                                name
                                org
                                type
                            }
                        }`,
                    variables: {
                        org: orgUuid
                    },
                    fetchPolicy: 'no-cache'
                })
                context.commit('SET_PERSPECTIVES', response.data.perspectives || [])
            } catch (error) {
                console.error('Error fetching perspectives:', error)
                context.commit('SET_PERSPECTIVES', [])
            }
        },   
        async fetchReleaseById (context : any, params : any) {
            const gqlQ = (!params.light) ? graphqlQueries.SingleReleaseGql : graphqlQueries.SingleReleaseGqlLight
            const response = await graphqlClient.query({
                query: gqlQ,
                variables: { releaseID: params.release, orgID: params.org },
                fetchPolicy: 'no-cache'
            })
            context.commit('ADD_RELEASE', response.data.release)
            return response.data.release
        },
        async updateBranch (context: any, brProps: any) {
            let dependencies = []
            if (brProps.dependencies && brProps.dependencies.length) {
                dependencies = brProps.dependencies.map((p: any) => {
                    return {
                        uuid: p.uuid,
                        branch: p.branch,
                        status: p.status,
                        release: p.release,
                        isFollowVersion: p.isFollowVersion
                    }
                })
            }
            let dependencyPatterns = []
            if (brProps.dependencyPatterns && brProps.dependencyPatterns.length) {
                dependencyPatterns = brProps.dependencyPatterns.map((p: any) => {
                    return {
                        uuid: p.uuid,
                        pattern: p.pattern,
                        targetBranchName: p.targetBranchName,
                        defaultStatus: p.defaultStatus,
                        fallbackToBase: p.fallbackToBase
                    }
                })
            }
            const branchUpdObject = {
                uuid: brProps.uuid,
                name: brProps.name,
                vcs: brProps.vcs,
                vcsBranch: brProps.vcsBranch,
                versionSchema: brProps.versionSchema,
                metadata: brProps.metadata,
                dependencies: dependencies,
                dependencyPatterns: dependencyPatterns,
                autoIntegrate: brProps.autoIntegrate,
                type: brProps.type,
                marketingVersionSchema: brProps.marketingVersionSchema,
                findingAnalyticsParticipation: brProps.findingAnalyticsParticipation
            }
            const data = await graphqlClient.mutate({
                mutation: graphqlQueries.BranchGqlMutate,
                variables: {
                    'br': branchUpdObject
                }
            })
            context.commit('ADD_BRANCH', data.data.updateBranch)
            return data.data.updateBranch
        },
        async updateInstance (context: any, instanceProps: any) {
            let instProducts = []
            if (instanceProps.productPlans && instanceProps.productPlans.length) {
                instProducts = instanceProps.productPlans.map((p: any) => {
                    return {
                        featureSet: p.featureSet,
                        type: p.type,
                        release: p.matchedRelease,
                        namespace: p.namespace,
                        targetRelease: p.targetRelease,
                        identifier: p.identifier,
                        configuration: p.configuration,
                        alertsEnabled: p.alertsEnabled
                    }
                })
            }
            if (instanceProps.updProducts && instanceProps.updProducts.length) {
                let instProductsAdd = instanceProps.updProducts.map((p: any) => {
                    return {
                        featureSet: p.featureSet,
                        type: p.type,
                        release: p.matchedRelease,
                        namespace: p.namespace,
                        targetRelease: p.targetRelease,
                        identifier: p.identifier,
                        configuration: p.configuration,
                        alertsEnabled: p.alertsEnabled
                    }
                })
                instProducts = instProducts.concat(instProductsAdd)
            }
            let instComponents = []
            if (instanceProps.releases && instanceProps.releases.length) {
                instComponents = instanceProps.releases.map((r: any) => {
                    return {
                        timeSent: r.timeSent,
                        release: r.release,
                        artifact: r.artifact,
                        type: r.type,
                        namespace: r.namespace,
                        properties: r.properties,
                        state: r.state,
                        replicas: r.replicas ? r.replicas.map((rep: any) => ({ id: rep.id, state: rep.state })) : r.replicas,
                        partOf: r.partOf
                    }
                })
            }
            let instTargetReleases = []
            if (instanceProps.targetReleases && instanceProps.targetReleases.length) {
                instTargetReleases = instanceProps.targetReleases.map((r: any) => {
                    return {
                        timeSent: r.timeSent,
                        release: r.release,
                        artifact: r.artifact,
                        type: r.type,
                        namespace: r.namespace,
                        properties: r.properties,
                        state: r.state,
                        replicas: r.replicas ? r.replicas.map((rep: any) => ({ id: rep.id, state: rep.state })) : r.replicas
                    }
                })
            }
            let instProperties: any[] = []
            if (instanceProps.properties && instanceProps.properties.length) {
                instProperties = instanceProps.properties.map((p: any) => {
                    return {
                        uuid: p.uuid,
                        type: p.type,
                        value: p.value,
                        namespace: p.namespace,
                        product: p.product
                    }
                })
            }
            let instUpdObject = {
                uuid: instanceProps.uuid,
                uri: instanceProps.uri,
                org: instanceProps.org,
                properties: instProperties,
                agentData: instanceProps.agentData,
                releases: instComponents,
                targetReleases: instTargetReleases,
                environment: instanceProps.environment,
                products: instProducts,
                notes: instanceProps.notes,
                name: instanceProps.name
            }
            let data = await graphqlClient.mutate({
                mutation: gql`
                    mutation updateInstance($inst: InstanceInput!) {
                        updateInstance(instance:$inst) {
                            ${graphqlQueries.InstanceGqlData}
                        }
                    }`,
                variables: {
                    'inst': instUpdObject
                }
            })
            context.commit('ADD_INSTANCE', data.data.updateInstance)
            return data.data.updateInstance
        },
        async updateComponent (context : any, component : any) {
            const compUpdObject = {
                uuid: component.uuid,
                name: component.name,
                versionSchema: component.versionSchema,
                vcs: component.vcs,
                repoPath: component.repoPath,
                featureBranchVersioning: component.featureBranchVersioning,
                marketingVersionSchema: component.marketingVersionSchema,
                versionType: component.versionType,
                approvalPolicy: component.approvalPolicy,
                outputTriggers: component.outputTriggers,
                releaseInputTriggers: component.releaseInputTriggers,
                identifiers: component.identifiers?.map(({ idType, idValue }: any) => ({ idType, idValue }))
            }
            console.log(compUpdObject)
            const data = await graphqlClient.mutate({
                mutation: graphqlQueries.ComponentMutate,
                variables: {
                    'component': compUpdObject
                }
            })
            context.commit('ADD_UPDATE_COMPONENT', data.data.updateComponent)
            return data.data.updateComponent
        },
        async updateComponentResourceGroup (context : any, component : any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation updateComponentResourceGroup($componentUuid: ID!, $resourceGroup: ID!) {
                        updateComponentResourceGroup(componentUuid: $componentUuid, resourceGroup: $resourceGroup) {
                            ${graphqlQueries.ComponentFullData}
                        }
                    }`,
                variables: {
                    componentUuid: component.uuid,
                    resourceGroup: component.resourceGroup
                }
            })
            context.commit('ADD_UPDATE_COMPONENT', data.data.updateComponentResourceGroup)
            return data.data.updateComponentResourceGroup
        },
        async setComponentVisibility (context : any, component : any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation setComponentVisibility($componentUuid: ID!, $visibility: VisibilitySetting!) {
                        setComponentVisibility(componentUuid: $componentUuid, visibility: $visibility) {
                            ${graphqlQueries.ComponentFullData}
                        }
                    }`,
                variables: {
                    componentUuid: component.uuid,
                    visibility: component.visibilitySetting
                }
            })
            context.commit('ADD_UPDATE_COMPONENT', data.data.setComponentVisibility)
            return data.data.setComponentVisibility
        },
        async archiveOrganization (context: any, params: any) {
            const archived = await graphqlClient.mutate({
                mutation: gql`
                    mutation archiveOrganization($orgUuid: ID!) {
                        archiveOrganization(orgUuid: $orgUuid)
                    }`,
                variables: {
                    orgUuid: params.orgUuid
                }
            })
            return archived
        },
        async archiveInstance (context: any, params: any) {
            const archived = await graphqlClient.mutate({
                mutation: gql`
                    mutation archiveInstance($instanceUuid: ID!) {
                        archiveInstance(instanceUuid: $instanceUuid)
                    }`,
                variables: {
                    instanceUuid: params.instanceUuid,
                    orgUuid: params.orgUuid
                }
            })
            if (archived) {
                // reload instances
                await store.dispatch('fetchInstances', params.orgUuid)
            }
            return archived
        },
        async archiveComponent (context: any, params:any) {
            const archived = await graphqlClient.mutate({
                mutation: gql`
                    mutation archiveComponent($componentUuid: ID!) {
                        archiveComponent(componentUuid: $componentUuid)
                    }`,
                variables: {
                    componentUuid: params.componentUuid,
                    orgUuid: params.orgUuid
                }
            })
            if (archived) {
                // reload components and products
                await store.dispatch('fetchComponents', params.orgUuid)
                await store.dispatch('fetchProducts', params.orgUuid)
            }
            return archived
        },
        async fetchVcsRepos (context: any, payload: string | { org: string, forceRefresh?: boolean }) {
            // Support both string org and object payload for backwards compatibility
            const org = typeof payload === 'string' ? payload : payload.org
            const forceRefresh = typeof payload === 'object' ? payload.forceRefresh : false
            
            const response = await graphqlClient.query({
                query: gql`
                    query listVcsReposOfOrganization($orgUuid: ID!) {
                        listVcsReposOfOrganization(orgUuid: $orgUuid) {
                            uuid
                            name
                            org
                            uri
                            type
                        }
                    }`,
                variables: {
                    orgUuid: org
                },
                fetchPolicy: forceRefresh ? 'network-only' : 'cache-first'
            })
            context.commit('SET_VCS_REPOS', response.data.listVcsReposOfOrganization)
            return response.data.listVcsReposOfOrganization
        },
        async fetchVcsRepo (context: any, vcs: NonNullable<string>) {
            const response = await graphqlClient.query({
                query: gql`
                    query vcsRepository($vcs: ID!) {
                        vcsRepository(vcs: $vcs) {
                            uuid
                            name
                            org
                            uri
                            type
                        }
                    }`,
                variables: {
                    vcs
                },
                fetchPolicy: 'no-cache'
            })
            context.commit('ADD_VCS_REPO', response.data.vcsRepository)
            return response.data.vcsRepository
        },
        async updateVcsRepo (context: any, vcsRepoProps: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation updateVcsRepository($vcsUuid: ID!, $name: String, $uri: String){
                        updateVcsRepository(vcsUuid: $vcsUuid, name: $name, uri: $uri) {
                            uuid
                            name
                            org
                            uri
                            type
                        }
                    }`,
                variables: {
                    vcsUuid: vcsRepoProps.uuid,
                    name: vcsRepoProps.name,
                    uri: vcsRepoProps.uri
                }
            })
            context.commit('ADD_VCS_REPO', data.data.updateVcsRepository)
            return data.data.updateVcsRepository
        },
        async archiveVcsRepo (context: any, vcsRepoProps: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation archiveVcsRepository($vcsUuid: ID!){
                        archiveVcsRepository(vcsUuid: $vcsUuid)
                    }`,
                variables: {
                    vcsUuid: vcsRepoProps.uuid
                }
            })
            if (data.data.archiveVcsRepository) {
                context.commit('REMOVE_VCS_REPO', vcsRepoProps.uuid)
            }
            return data.data.archiveVcsRepository
        },
        async createInstance (context: any, instanceProps: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation createInstance($orgUuid: ID!, $uri: String!, $name: String, $instanceType: InstanceType!, $namespace: String, $environment: String, $clusterId: ID) {
                        createInstance(orgUuid: $orgUuid, uri: $uri, name: $name, instanceType: $instanceType, namespace: $namespace, environment: $environment, clusterId: $clusterId) {
                            ${graphqlQueries.InstanceGqlData}
                        }
                    }`,
                variables: {
                    orgUuid: instanceProps.org,
                    uri: instanceProps.uri,
                    environment: instanceProps.environment,
                    name: instanceProps.name,
                    instanceType: instanceProps.instanceType,
                    namespace: instanceProps.namespace,
                    clusterId: instanceProps.clusterId
                }
            })
            data.data.createInstance.revision = -1
            context.commit('ADD_INSTANCE', data.data.createInstance)
            return data.data.createInstance
        },
        async createInstanceFromDto (context: any, instanceProps: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation createInstanceFromDto($inst: CreateInstanceInput!) {
                        createInstanceFromDto(instance: $inst) {
                            ${graphqlQueries.InstanceGqlData}
                        }
                    }`,
                variables: {
                    inst: instanceProps   
                }
            })
            data.data.createInstanceFromDto.revision = -1
            context.commit('ADD_INSTANCE', data.data.createInstanceFromDto)
            return data.data.createInstanceFromDto
        },
        async spawnInstance (context: any, instInput: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation spawnInstance($instance: InstanceSpawnInput!) {
                        spawnInstance(instance: $instance) {
                            ${graphqlQueries.InstanceGqlData}
                        }
                    }`,
                variables: {
                    'instance': instInput
                }
            })
            data.data.spawnInstance.revision = -1
            context.commit('ADD_INSTANCE', data.data.spawnInstance)
            return data.data.spawnInstance
        },
        async createOrganization (context: any, name: string) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation createOrganization($name: String!) {
                        createOrganization(name: $name) {
                            uuid
                            name
                            approvalRoles {
                                id
                                displayView
                            }
                        }
                    }`,
                variables: {
                    name
                }
            })
            context.commit('ADD_ORGANIZATION', data.data.createOrganization)
            return data.data.createOrganization
        },
        async createVcsRepo (context: any, vcsProps: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation createVcsRepository($vcsRepository: VcsRepositoryInput!) {
                        createVcsRepository(vcsRepository: $vcsRepository) {
                            uuid
                            name
                            uri
                            type
                        }
                    }`,
                variables: {
                    vcsRepository: vcsProps
                }
            })
            context.commit('ADD_VCS_REPO', data.data.createVcsRepository)
            return data.data.createVcsRepository
        },
        async fetchInstance (context: any, instFetchProps: any) {
            if (!instFetchProps.revision) instFetchProps.revision = -1
            const response = await graphqlClient.query({
                query: graphqlQueries.InstanceGql,
                variables: { instanceUuid: instFetchProps.id, revision: instFetchProps.revision },
                fetchPolicy: 'no-cache'
            })
            response.data.instance.revision = instFetchProps.revision
            context.commit('ADD_INSTANCE', response.data.instance)
            return response.data.instance
        },
        async fetchInstances (context: any, id: string) {
            const response = await graphqlClient.query({
                query: graphqlQueries.InstancesGql,
                variables: { orgUuid: id },
                fetchPolicy: 'no-cache'
            })
            let instances = response.data.instancesOfOrganization
            instances.forEach((x: any) => {
                if (!x.revision) x.revision = -1
            })
            context.commit('SET_INSTANCES', instances)
            return instances
        },
        async fetchReleasesByOrgUuids (context: any, params: any) {
            if (params.releases && params.releases.length) {
                const response = await graphqlClient.query({
                    query: gql`
                        query FetchReleasesByOrgUuids($orgId: ID!, $releaseIds: [ID]) {
                            releases(orgFilter: $orgId, releaseFilter: $releaseIds) {
                                ${graphqlQueries.MultiReleaseGqlData}
                            }
                        }`,
                    variables: {
                        orgId: params.org,
                        releaseIds: params.releases
                    }
                })
                context.commit('ADD_RELEASES', response.data.releases)
                return response.data.releases
            } else {
                return []
            }
        },
        async fetchProperties (context: any, uuid: string) {
            const response = await graphqlClient.query({
                query: gql`
                    query propertiesOfOrg($orgUuid: ID!) {
                        propertiesOfOrg(orgUuid: $orgUuid) {
                            uuid
                            name
                            org
                            dataType
                            targetType
                            defaultValue
                            createdType
                            lastUpdatedBy
                            createdDate
                        }
                    }`,
                variables: {
                    orgUuid: uuid
                }
            })
            context.commit('SET_PROPERTIES', response.data.propertiesOfOrg)
            return response.data.propertiesOfOrg
        },
        async createResourceGroup (context: any, appObj: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation createResourceGroup($orgUuid: ID!, $name: String!) {
                        createResourceGroup(orgUuid: $orgUuid, name: $name) {
                            uuid
                            org
                            name
                            protectedEnvironments
                        }
                    }`,
                variables: {
                    orgUuid: appObj.org,
                    name: appObj.name
                }
            })
            context.commit('ADD_RESOURCE_GROUP', data.data.createResourceGroup)
            return data.data.createResourceGroup
        },
        async saveProtectedEnvironments (context: any, appObj: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation setProtectedEnvironments($orgUuid: ID!, $uuid: ID!, $protectedEnvironments: [String]) {
                        setProtectedEnvironments(orgUuid: $orgUuid, uuid: $uuid, protectedEnvironments: $protectedEnvironments) {
                            uuid
                            org
                            name
                            protectedEnvironments
                        }
                    }`,
                variables: {
                    orgUuid: appObj.org,
                    uuid: appObj.uuid,
                    protectedEnvironments: appObj.protectedEnvironments
                }
            })
            context.commit('UPDATE_RESOURCE_GROUP', data.data.setProtectedEnvironments)
           
            return data.data.setProtectedEnvironments
        },
        async fetchChangelogBetweenReleases (context: any, params: any) {
            const { fetchComponentChangelog } = await import('./utils/changelogQueries')
            return await fetchComponentChangelog({
                release1: params.release1,
                release2: params.release2,
                org: params.org,
                aggregated: params.aggregated || 'AGGREGATED',
                timeZone: params.timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone
            })
        },
        async updateRelease (context: any, release: any) {
            let rlzUpdObject: any = {
                approvals: release.approvals,
                artifacts: release.artifacts,
                branch: release.branch,
                notes: release.notes,
                org: release.org,
                sourceCodeEntry: release.sourceCodeEntry,
                uuid: release.uuid,
                version: release.version,
                identifiers: release.identifiers || []
            }
            if (release.parentReleases && release.parentReleases.length) {
                console.log(release.parentReleases)
                rlzUpdObject.parentReleases = release.parentReleases.map((x: any) => {
                    console.log(x)
                    return {release: x.release}
                })
                console.log(rlzUpdObject.parentReleases)
            } else {
                rlzUpdObject.parentReleases = []
            }
            if (release.tags && release.tags.length) {
                rlzUpdObject.tags = release.tags.map((x:any) => ({key: x.key, value: x.value}))
            } else {
                rlzUpdObject.tags = []
            }
            const data = await graphqlClient.mutate({
                mutation: graphqlQueries.ReleaseGqlMutate,
                variables: {
                    'rel': rlzUpdObject
                }
            })
            context.commit('ADD_RELEASE', data.data.updateRelease)
            if(data.errors){
                return data.errors
            }
            return data.data.updateRelease
        },
        async updateReleaseTagsMeta (context: any, release: any) {
            const rlzUpdObject: any = {
                notes: release.notes,
                uuid: release.uuid,
                org: release.org
            }
            if (release.tags && release.tags.length) {
                rlzUpdObject.tags = release.tags.map((x:any) => ({key: x.key, value: x.value}))
            } else {
                rlzUpdObject.tags = []
            }
            const data = await graphqlClient.mutate({
                mutation: graphqlQueries.ReleaseTagsMetaGqlMutate,
                variables: {
                    'rel': rlzUpdObject
                }
            })
            context.commit('ADD_RELEASE', data.data.updateReleaseTagsMeta)
            if(data.errors){
                return data.errors
            }
            return data.data.updateReleaseTagsMeta
        },
        async updateArtifactTags (context: any, payload: any) {
            const tags = (payload.tags || []).map((x: any) => ({key: x.key, value: x.value}))
            const data = await graphqlClient.mutate({
                mutation: graphqlQueries.UpdateArtifactTagsGqlMutate,
                variables: {
                    'artifact': payload.artifactUuid,
                    'tags': tags
                }
            })
            if(data.errors){
                throw new Error(data.errors[0]?.message || 'Failed to update artifact tags')
            }
            return data.data.updateArtifactTags
        },
        async updateReleaseLifecycle (context: any, release: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation updateReleaseLifecycle($release: ID!, $newLifecycle: ReleaseLifecycleEnum!) {
                        updateReleaseLifecycle(release: $release, newLifecycle: $newLifecycle) {
                            ${graphqlQueries.SingleReleaseGqlData}
                        }
                    }
                `,
                variables: {
                    release: release.uuid,
                    newLifecycle: release.lifecycle
                }
            })
            context.commit('ADD_RELEASE', data.data.updateReleaseLifecycle)
            if(data.errors){
                return data.errors
            }
            return data.data.updateReleaseLifecycle
        },
        async fetchUsers (context: any, orgUuid: any) {
            const response = await graphqlClient.query({
                query: gql`
                    query FetchUsers($orgUuid: ID!) {
                        users(orgUuid: $orgUuid) {
                            uuid
                            name
                            email
                            githubId
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
                        }
                    }
                `,
                variables: { orgUuid },
                fetchPolicy: 'no-cache'
            })
            context.commit('SET_USERS', response.data.users)
            return response.data.users
        },
        async updateUserName (context: any, name: string) {
            const response = await graphqlClient.mutate({
                mutation: gql`
                    mutation updateUserName($name: String!) {
                        updateUserName(name: $name) {
                            ${graphqlQueries.UserData}
                        }
                    }`,
                variables: {
                    name
                }
            })
            context.commit('SET_MY_USER', response.data.updateUserName)
            return response.data.updateUserName
        },
        async approveRelease (context: any, approveProps: any) {
            return new Promise((resolve, reject) => {
                graphqlClient.mutate({
                    mutation: graphqlQueries.ApproveReleaseGqlMutate,
                    variables: {
                        'release': approveProps.release,
                        'approvals': approveProps.approvals
                    }
                }).then(data => {
                    context.commit('ADD_RELEASE', data.data.approveReleaseManual)
                    resolve(data)
                }).catch(err => {
                    reject(err)
                })
            })
        },
        async approveReleaseLegacy (context: any, approveProps: any) {
            return new Promise((resolve, reject) => {
                let constructedApprovals: any = {} // no nulls
                Object.keys(approveProps.approvals).forEach(k => {
                    if (approveProps.approvals[k] === true || approveProps.approvals[k] === false) {
                        constructedApprovals[k] = approveProps.approvals[k]
                    }
                })
                let approvePropsCopy = {
                    uuid: approveProps.uuid,
                    approvals: constructedApprovals,
                    productsForDisapproval: approveProps.productsForDisapproval
                }
                graphqlClient.mutate({
                    mutation: graphqlQueries.ApproveReleaseGqlMutate,
                    variables: {
                        'rel': approvePropsCopy.uuid,
                        'appr': approvePropsCopy.approvals,
                        'productFilter': approvePropsCopy.productsForDisapproval
                    }
                }).then(data => {
                    context.commit('ADD_RELEASE', data.data.approveReleaseManual)
                    resolve(data)
                }).catch(err => {
                    reject(err)
                })
            })
        },
        async searchReleasesByTags (context: any, params: any) {
            const variables: any = {
                orgUuid: params.org,
                tagKey: params.tagKey,
                tagValue: params.tagValue
            }
            if (params.perspectiveUuid && params.perspectiveUuid !== 'default') {
                variables.perspectiveUuid = params.perspectiveUuid
            }
            const response = await graphqlClient.query({
                query: gql`
                    query releasesByTags($orgUuid: ID!, $tagKey: String!, $tagValue: String, $perspectiveUuid: ID) {
                        releasesByTags(orgUuid: $orgUuid, tagKey: $tagKey, tagValue: $tagValue, perspectiveUuid: $perspectiveUuid) {
                            ${graphqlQueries.MultiReleaseGqlData}
                        }
                    }`,
                variables,
                fetchPolicy: 'no-cache'
            })
            context.commit('SET_RELEASES', response.data.releasesByTags)
            return response.data.releasesByTags
        },
        async addApprovalRole (context: any, updObj: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation addApprovalRole($orgUuid: ID!, $approvalRole: ApprovalRoleInput!) {
                        addApprovalRole(orgUuid: $orgUuid, approvalRole: $approvalRole) {
                            uuid
                            name
                            approvalRoles {
                                id
                                displayView
                            }
                        }
                    }`,
                variables: {
                    orgUuid: updObj.orgUuid,
                    approvalRole: updObj.approvalRole
                }
            })
            const updOrg = data.data.addApprovalRole
            context.commit('UPDATE_ORGANIZATION', updOrg)
            return updOrg
        },
        async deleteApprovalRole (context: any, updObj: any) {
            const data = await graphqlClient.mutate({
                mutation: gql`
                    mutation deleteApprovalRole($orgUuid: ID!, $approvalRoleId: String!) {
                        deleteApprovalRole(orgUuid: $orgUuid, approvalRoleId: $approvalRoleId) {
                            uuid
                            name
                            approvalRoles {
                                id
                                displayView
                            }
                        }
                    }`,
                variables: {
                    orgUuid: updObj.orgUuid,
                    approvalRoleId: updObj.approvalRoleId
                }
            })
            const updOrg = data.data.deleteApprovalRole
            context.commit('UPDATE_ORGANIZATION', updOrg)
            return updOrg
        },  
    },
}

const store = createStore(storeObject)

export default {
    name: 'Store',
    store
}