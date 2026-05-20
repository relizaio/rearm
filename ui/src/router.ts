import { createRouter, createWebHistory, Router } from 'vue-router'
import type { RouteLocationNormalized } from 'vue-router'
import AppHome from '@/components/AppHome.vue'
import UserProfile from '@/components/UserProfile.vue'

const routes : any[] = [
    {
        path: '/',
        name: 'home',
        component: AppHome
    },
    {
        path: '/profile',
        name: 'profile',
        component: UserProfile
    },
    {
        path: '/sysSettings',
        name: 'systemSettings',
        component: () => import('@/components/SystemSettings.vue')
    },
    {
        path: '/componentsOfOrg/:orguuid/:compuuid?/:branchuuid?',
        name: 'ComponentsOfOrg',
        component: () => import('@/components/ComponentsOfOrg.vue')
    },
    {
        path: '/pullRequestsOfOrg/:orguuid',
        name: 'PullRequestsOfOrg',
        component: () => import('@/components/PullRequestsOfOrg.vue')
    },
    {
        path: '/pullRequest/show/:uuid',
        name: 'PullRequestView',
        component: () => import('@/components/PullRequestView.vue')
    },
    {
        path: '/vcsRepository/:uuid',
        name: 'VcsRepository',
        component: () => import('@/components/VcsRepository.vue')
    },
    {
        path: '/productsOfOrg/:orguuid/:compuuid?/:branchuuid?',
        name: 'ProductsOfOrg',
        component: () => import('@/components/ComponentsOfOrg.vue')
    },
    {
        path: '/vcsReposOfOrg/:orguuid',
        name: 'VcsReposOfOrg',
        component: () => import('@/components/VcsReposOfOrg.vue')
    },
    {
        path: '/aiAgentsOfOrg/:orguuid',
        name: 'AiAgentsOfOrg',
        component: () => import('@/components/AiAgentsOfOrg.vue')
    },
    {
        path: '/aiAgentsTableOfOrg/:orguuid',
        name: 'AiAgentsTableOfOrg',
        component: () => import('@/components/AiAgentsTableOfOrg.vue')
    },
    {
        path: '/aiAgent/:uuid',
        name: 'AiAgentView',
        component: () => import('@/components/AiAgentView.vue')
    },
    {
        path: '/aiAgentSession/:uuid',
        name: 'AiAgentSessionView',
        component: () => import('@/components/AiAgentSessionView.vue')
    },
    {
        // AI Agent Policies list moved under Org Settings → Policies → inner
        // "AI Agent Policies" tab. Keep the standalone path as a redirect so
        // bookmarks and the AiAgents "Manage policies →" link continue to
        // work. The individual policy editor (/aiAgentPolicy/:uuid) is still
        // a full-page route.
        path: '/aiAgentPolicies/:orguuid',
        name: 'AiAgentPoliciesOfOrg',
        redirect: (to: RouteLocationNormalized) => ({
            name: 'OrgSettings',
            params: { orguuid: to.params.orguuid },
            query: { tab: 'policies', policyTab: 'agentPolicies' },
        }),
    },
    {
        path: '/aiAgentPolicy/:uuid',
        name: 'AiAgentPolicyView',
        component: () => import('@/components/AiAgentPolicyView.vue')
    },
    {
        // Committers list moved under Org Settings → Committers tab. Redirect
        // preserves any bookmarks; individual committer view stays a route.
        path: '/committersOfOrg/:orguuid',
        name: 'CommittersOfOrg',
        redirect: (to: RouteLocationNormalized) => ({
            name: 'OrgSettings',
            params: { orguuid: to.params.orguuid },
            query: { tab: 'committers' },
        }),
    },
    {
        path: '/committer/:uuid',
        name: 'CommitterView',
        component: () => import('@/components/CommitterView.vue')
    },
    {
        path: '/secretsOfOrg/:orguuid',
        name: 'SecretsOfOrg',
        component: () => import('@/components/SecretsOfOrg.vue')
    },
    {
        path: '/analytics/:orguuid',
        name: 'AnalyticsOfOrg',
        component: () => import('@/components/AnalyticsOfOrg.vue')
    },
    {
        path: '/findingsOverTime/:orguuid',
        name: 'FindingsOverTime',
        component: () => import('@/components/FindingsOverTimePage.vue')
    },
    {
        path: '/releasesPerDay/:orguuid',
        name: 'ReleasesPerDay',
        component: () => import('@/components/ReleasesPerDayPage.vue')
    },
    {
        path: '/mostRecentReleases/:orguuid',
        name: 'MostRecentReleases',
        component: () => import('@/components/MostRecentReleasesPage.vue')
    },
    {
        path: '/vulnerabilityAnalysis/:orguuid',
        name: 'VulnerabilityAnalysis',
        component: () => import('@/components/FindingAnalysisPage.vue')
    },
    {
        path: '/orgSettings/:orguuid',
        name: 'OrgSettings',
        component: () => import('@/components/OrgSettings.vue')
    },
    {
        path: '/instancesOfOrg/:orguuid',
        name: 'InstancesOfOrg',
        component: () => import('@/components/InstancesOfOrg.vue')
    },
    {
        path: '/instancesOfOrg/:orguuid/:instuuid',
        name: 'Instance',
        component: () => import('@/components/InstancesOfOrg.vue')
    },
    {
        path: '/instancesOfOrg/:orguuid/:instuuid/:subinstuuid?',
        name: 'Instance',
        component: () => import('@/components/InstancesOfOrg.vue')
    },
    {
        path: '/release/show/:uuid',
        name: 'ReleaseView',
        component: () => import('@/components/ReleaseView.vue')
    },
    {
        path: '/release/:releaseUuid/sbomComponentGraph/:sbomComponentUuid?',
        name: 'SbomComponentGraph',
        component: () => import('@/components/ReleaseSbomComponentGraph.vue'),
        props: (route: RouteLocationNormalized) => ({
            releaseUuid: route.params.releaseUuid as string,
            sbomComponentUuid: (route.params.sbomComponentUuid as string) || '',
            purl: (route.query.purl as string) || '',
            orgUuid: (route.query.org as string) || ''
        })
    },
    {
        path: '/changelog/:orgprop/:release1prop/:release2prop/:componenttypeprop/:isrouterlink',
        name: 'ChangelogView',
        component: () => import('@/components/ChangelogView.vue'),
        props: true
    },
    {
        path: '/verifyEmail/:secret',
        name: 'VerifyEmail',
        component: () => import('@/components/VerifyEmail.vue')
    },
    {
        path: '/joinOrganization/:secret',
        name: 'JoinOrganization',
        component: () => import('@/components/JoinOrganization.vue')
    },
    {
        path: '/downloadArtifact/:arttype/:artuuid',
        name: 'DownloadTeaArtifact',
        component: () => import('@/components/DownloadTeaArtifactView.vue')
    },
    {
        // Retired in 2026-05: VEX Proposals now lives as a tab inside the Finding Analysis page.
        // Redirect preserves any bookmarks/links from the v1.2 dogfood period.
        path: '/vexProposalsOfOrg/:orguuid',
        redirect: (to: RouteLocationNormalized) => ({
            name: 'VulnerabilityAnalysis',
            params: { orguuid: to.params.orguuid },
            query: { tab: 'vex' }
        })
    },
    {
        path: '/vexProposal/:orguuid/:uuid',
        name: 'VexProposalReview',
        component: () => import('@/components/VexProposalReview.vue')
    },
    {
        // Same as above — Mitigation Attestations is now a tab in Finding Analysis.
        path: '/mitigationAttestationsOfOrg/:orguuid',
        redirect: (to: RouteLocationNormalized) => ({
            name: 'VulnerabilityAnalysis',
            params: { orguuid: to.params.orguuid },
            query: { tab: 'attestations' }
        })
    },
    {
        path: '/mitigationAttestation/:orguuid/:uuid',
        name: 'MitigationAttestationReview',
        component: () => import('@/components/MitigationAttestationReview.vue')
    },
    // {
    //     path: '/jira-integration',
    //     name: 'JiraIntegration',
    //     component: JiraIntegration,
    //     props: true
    // }
]

const Router : Router = createRouter({
    history: createWebHistory(),
    routes
})

export default {
    name: 'Router',
    // mode: 'history',
    // base: process.env.BASE_URL,
    Router
}