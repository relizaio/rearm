// @ts-check
import { defineConfig } from 'astro/config';

// Static marketing site. Two hard compatibility requirements with the
// previous Next.js landing_site (permalink + rendering preservation):
//  - trailingSlash 'always' + directory build format reproduce the
//    Next `trailingSlash: true` URL shape (/blog/<slug>/).
//  - gfm/smartypants OFF: the old pipeline was react-markdown WITHOUT
//    remark-gfm (CommonMark + raw HTML via rehype-raw). Astro defaults
//    both ON, which would silently change rendering of tables,
//    strikethrough, autolinks and quotes in existing posts.
export default defineConfig({
  site: process.env.SITE_URL ?? 'https://rearmhq.com',
  trailingSlash: 'always',
  build: { format: 'directory' },
  markdown: {
    gfm: false,
    smartypants: false,
  },
});
