import { defineConfig  } from 'vitepress'

function navbar() {
    return [
        { text: 'Home', link: '/' },
        { text: 'Concepts', link: '/concepts/' },
        { text: 'Getting Started', link: '/get-started/' },
        { text: 'Configure ReARM', link: '/configure/' },
        { text: 'Integrations', link: '/integrations/' },
        { text: 'Tutorials', link: '/tutorials/' }
    ]
}

function sidebar() {
    return [
        {text: 'Getting Started', link: '/get-started/'},
        {text: 'Concepts', link: '/concepts/'},        
        {text: 'Bundling', link: '/bundling/'},
        {text: 'Configure', link: '/configure/'},
        {text: 'Integrations', link: '/integrations/',
          items: [
            {text: 'ReARM CLI', link: '/integrations/rearmcli'},
            {text: 'GitHub Actions', link: '/integrations/githubActions',
              items: [
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
            {text: 'Dependency Track', link: '/integrations/dtrack'},
            {text: 'Identity Providers', link: '/integrations/identityProviders',
              items: [
                {text: 'Microsoft', link: '/integrations/identityProviders/microsoft'}
              ]
            },
          ]
        },
        {text: 'Tutorials', link: '/tutorials/'}
    ]
}

export default defineConfig ({
  lang: 'en-CA',
  title: 'ReARM',
  description: 'ReARM - System to Manage Releases, SBOMs, xBOMs',
  themeConfig: {
    nav: navbar(),
    sidebar: sidebar(),
    contributors: false,
    footer: {
      copyright: 'Copyright © 2019-present. Reliza Incorporated.'
    },
    search: {
      provider: 'local'
    }
  }
})