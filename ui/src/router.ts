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
        // Notifications surfaces were folded into Org Settings in Phase 2c:
        // subscriptions + channel groups became Integrations sub-tabs,
        // delivery history moved under the Audit tab, and the inbox is now
        // a nav-level drawer. Redirect the retired standalone route to the
        // Integrations → Subscriptions pill so old bookmarks/links resolve.
        path: '/notificationsOfOrg/:orguuid',
        name: 'NotificationsOfOrg',
        redirect: (to) => ({
            name: 'OrgSettings',
            params: { orguuid: to.params.orguuid },
            query: { tab: 'integrations', integrationsTab: 'subscriptions' },
        }),
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

// After a backend pod roll the browser's loaded index.js still references
// the previous chunk hashes; the new pod serves index.html for the missing
// asset, the browser refuses it on MIME type and the UI freezes mid-nav.
// Reload once on these errors so the user lands on the fresh bundle.
const STALE_CHUNK_PATTERNS = [
    /Failed to fetch dynamically imported module/i,
    /Loading chunk [\w-]+ failed/i,
    /Loading CSS chunk [\w-]+ failed/i,
    /Unable to preload CSS/i,
    /error loading dynamically imported module/i,
    /disallowed MIME type/i,
]

const isStaleChunkError = (err: unknown): boolean => {
    const msg = err instanceof Error ? err.message : String(err ?? '')
    return STALE_CHUNK_PATTERNS.some(p => p.test(msg))
}

const RELOAD_GUARD_KEY = 'rearm-stale-chunk-reload-at'

function showReloadToast (): void {
    if (document.getElementById('rearm-stale-chunk-toast')) return
    const el = document.createElement('div')
    el.id = 'rearm-stale-chunk-toast'
    el.textContent = 'App updated — reloading…'
    Object.assign(el.style, {
        position: 'fixed',
        bottom: '24px',
        right: '24px',
        background: '#2080f0',
        color: '#fff',
        padding: '10px 16px',
        borderRadius: '6px',
        fontSize: '14px',
        boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
        zIndex: '9999',
        fontFamily: 'system-ui, -apple-system, sans-serif',
    } as Partial<CSSStyleDeclaration>)
    document.body.appendChild(el)
}

const reloadOnce = () => {
    const last = Number(sessionStorage.getItem(RELOAD_GUARD_KEY) || 0)
    if (Date.now() - last < 10_000) return
    sessionStorage.setItem(RELOAD_GUARD_KEY, String(Date.now()))
    showReloadToast()
    setTimeout(() => window.location.reload(), 800)
}

Router.onError((err) => {
    if (isStaleChunkError(err)) reloadOnce()
})

window.addEventListener('error', (e) => {
    if (isStaleChunkError(e.error ?? e.message)) reloadOnce()
})
window.addEventListener('unhandledrejection', (e) => {
    if (isStaleChunkError(e.reason)) reloadOnce()
})

export default {
    name: 'Router',
    // mode: 'history',
    // base: process.env.BASE_URL,
    Router
}