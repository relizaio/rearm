// RSS feed for news at /news/rss.xml, advertised site-wide in Base.astro.
import type { APIContext } from 'astro';
import { collectionFeed } from '../../lib/rss';

export const GET = (context: APIContext) =>
  collectionFeed(context, {
    collection: 'news',
    base: '/news',
    title: 'ReARM News',
    description: 'ReARM releases, announcements and events.',
  });
