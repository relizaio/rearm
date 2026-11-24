// RSS feed for the blog at /blog/rss.xml, advertised site-wide by a
// <link rel="alternate" type="application/rss+xml"> tag in Base.astro so feed
// readers (Feedly, Inoreader, ...) find it from any page.
import type { APIContext } from 'astro';
import { collectionFeed } from '../../lib/rss';

export const GET = (context: APIContext) =>
  collectionFeed(context, {
    collection: 'posts',
    base: '/blog',
    title: 'ReARM Blog',
    description: 'Release governance, SBOM/xBOM management and software supply chain security, from the ReARM team at Reliza.',
  });
