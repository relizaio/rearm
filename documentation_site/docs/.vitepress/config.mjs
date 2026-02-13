import { defineConfig  } from 'vitepress'

function navbar() {
    return [
        { text: 'Home', link: '/' },
        { text: 'Concepts', link: '/concepts/' },
        { text: 'Getting Started', link: '/get-started/' },
        { text: 'Configure ReARM', link: '/configure/' },
        { text: 'Integrations', link: '/integrations/' },
        { text: 'Tutorials', link: '/tutorials/' },
    ]
}

function sidebar() {
    return [
        {text: 'Getting Started', link: '/get-started/'},
        {text: 'Installation', link: '/installation/'},
        {text: 'Concepts', link: '/concepts/'},        
        {text: 'Bundling', link: '/bundling/'},
        {text: 'Configure', link: '/configure/'},
        {text: 'Transparency Exchange API', link: '/tea/' },
        {text: 'Integrations', link: '/integrations/',
          items: [
            {text: 'ReARM CLI', link: '/integrations/rearmcli'},
            {text: 'GitHub Actions', link: '/integrations/githubActions',
              items: [
                {text: 'Build Pipelines', link: '/integrations/githubActionsBuild'},
                {text: 'Cosign and Sigstore', link: '/integrations/githubActionsCosign'},
                {text: 'Workflow Triggers', link: '/integrations/githubActionsTriggers'}
              ]
            },
            {text: 'GitLab', link: '/integrations/gitlab',
              items: [
                {text: 'Trigger GitLab CI/CD', link: '/integrations/gitlabTrigger'}
              ]
            },
            {text: 'Azure DevOps', link: '/integrations/ado',
              items: [
                {text: 'Build Container Pipeline', link: '/integrations/adoPipeline'},
                {text: 'Trigger Pipeline', link: '/integrations/adoTrigger'}
              ]
            },
            {text: 'Jenkins', link: '/integrations/jenkins',
              items: [
                {text: 'Trigger Jenkins Pipeline', link: '/integrations/jenkinsTrigger'}
              ]
            },
            {text: 'Slack', link: '/integrations/slack'},
            {text: 'Microsoft Teams', link: '/integrations/msteams'},
            {text: 'Dependency-Track', link: '/integrations/dtrack'},
            {text: 'Identity Providers', link: '/integrations/identityProviders',
              items: [
                {text: 'Microsoft', link: '/integrations/identityProviders/microsoft'}
              ]
            },
          ]
        },
        {text: 'Video Learning Series', link: '/learning-series/' },
        {text: 'Tutorials', link: '/tutorials/', items: [
          {text: 'Upload First BOM', link: '/tutorials/first-bom'},
          {text: 'Docker on GitHub Actions', link: '/tutorials/github-actions-docker'},
          {text: 'ReARM as Version Manager', link: '/tutorials/using-rearm-as-version-manager'},
          {text: 'Search by SBOM Components', link: '/tutorials/search-releases-by-sbom-components'}
        ]}
    ]
}

export default defineConfig ({
  lang: 'en-CA',
  title: 'ReARM',
  titleTemplate: ':title | ReARM Documentation',
  description: 'ReARM - System to Manage Releases, SBOMs, xBOMs',
  head: [
    ['meta', { name: 'robots', content: 'index, follow' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'ReARM Documentation' }],
    ['meta', { property: 'og:title', content: 'ReARM Documentation - Release-Level Supply Chain Evidence Platform' }],
    ['meta', { property: 'og:description', content: 'SBOMs, xBOMs and every other artifact - stored for 10+ years, versioned and audit-ready' }],
    ['meta', { property: 'og:url', content: 'https://docs.rearmhq.com' }],
    ['meta', { property: 'og:image', content: 'https://docs.rearmhq.com/logo.png' }],
    ['link', { rel: 'canonical', href: 'https://docs.rearmhq.com' }],
  ],
  themeConfig: {
    nav: navbar(),
    sidebar: sidebar(),
    contributors: false,
    footer: {
      copyright: 'Copyright © 2019-2026. Reliza Incorporated.'
    },
    search: {
      provider: 'local'
    },
    socialLinks: [
      {icon: 'github', link: 'https://github.com/relizaio/rearm'}
    ],
    logo: { src: '/logo.png' }
  },
  sitemap: {
    hostname: 'https://docs.rearmhq.com'
  }
})