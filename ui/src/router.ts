import { createRouter, createWebHistory, Router } from 'vue-router'
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
        path: '/componentsOfOrg/:orguuid/:compuuid?/:branchuuid?/:prnumber?',
        name: 'ComponentsOfOrg',
        component: () => import('@/components/ComponentsOfOrg.vue')
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
        path: '/vulnerabilityAnalysis/:orguuid',
        name: 'VulnerabilityAnalysis',
        component: () => import('@/components/VulnerabilityAnalysis.vue')
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