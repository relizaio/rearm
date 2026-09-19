// @ts-check
import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';

// Static marketing site. Two hard compatibility requirements with the
// previous Next.js landing_site (permalink + rendering preservation):
//  - trailingSlash 'always' + directory build format reproduce the
//    Next `trailingSlash: true` URL shape (/blog/<slug>/).
//  - smartypants OFF: the old pipeline was react-markdown WITHOUT
//    remark-gfm (CommonMark + raw HTML via rehype-raw), and typographic
//    quote substitution would silently change existing posts.
//  - gfm ON (since 2026-09): posts use GFM tables, which CommonMark
//    renders as literal pipes. Audited by building with gfm off/on and
//    diffing every page: the only other effect is bare URLs and email
//    addresses becoming links on three pages, which is wanted.
export default defineConfig({
  integrations: [sitemap()],
  site: process.env.SITE_URL ?? 'https://rearmhq.com',
  trailingSlash: 'always',
  build: { format: 'directory' },
  markdown: {
    gfm: true,
    smartypants: false,
  },
});
