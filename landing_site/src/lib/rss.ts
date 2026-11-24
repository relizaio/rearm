// Shared helpers for the site's RSS feeds (/blog/rss.xml, /news/rss.xml).
import rss from '@astrojs/rss';
import type { APIContext } from 'astro';
import { getCollection } from 'astro:content';

// Plain-text excerpt from a post's markdown: drop code blocks, figures (with
// their captions), images, HTML and
// markdown syntax, keep link text, and cut at a word boundary.
export const excerpt = (md: string, max = 280): string => {
  const text = md
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/<figure[\s\S]*?<\/figure>/gi, ' ')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/<[^>]+>/g, ' ')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/[*_`>|]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  if (text.length <= max) return text;
  const cut = text.slice(0, max);
  return cut.slice(0, cut.lastIndexOf(' ')).replace(/[,;:.\s]+$/, '') + '…';
};

// One feed per content collection: newest first, each item with title, date,
// link and a plain-text excerpt.
export async function collectionFeed(
  context: APIContext,
  opts: { collection: 'posts' | 'news'; base: string; title: string; description: string },
) {
  const entries = (await getCollection(opts.collection)).sort((a, b) => (a.data.date > b.data.date ? -1 : 1));
  return rss({
    title: opts.title,
    description: opts.description,
    site: context.site ?? 'https://rearmhq.com',
    items: entries.map((e) => ({
      title: e.data.title,
      pubDate: new Date(`${e.data.date}T00:00:00Z`),
      link: `${opts.base}/${e.id}/`,
      description: excerpt(e.body ?? ''),
    })),
    customData: '<language>en</language>',
  });
}
