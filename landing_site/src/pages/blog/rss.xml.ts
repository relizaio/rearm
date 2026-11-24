// RSS feed for the blog at /blog/rss.xml, advertised site-wide by a
// <link rel="alternate" type="application/rss+xml"> tag in Base.astro so feed
// readers (Feedly, Inoreader, ...) find it from any page.
import rss from '@astrojs/rss';
import type { APIContext } from 'astro';
import { getCollection } from 'astro:content';

// Plain-text excerpt from a post's markdown: drop code blocks, images, HTML and
// markdown syntax, keep link text, and cut at a word boundary.
const excerpt = (md: string, max = 280): string => {
  const text = md
    .replace(/```[\s\S]*?```/g, ' ')
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

export async function GET(context: APIContext) {
  const posts = (await getCollection('posts')).sort((a, b) => (a.data.date > b.data.date ? -1 : 1));
  return rss({
    title: 'ReARM Blog',
    description: 'Release governance, SBOM/xBOM management and software supply chain security, from the ReARM team at Reliza.',
    site: context.site ?? 'https://rearmhq.com',
    items: posts.map((p) => ({
      title: p.data.title,
      pubDate: new Date(`${p.data.date}T00:00:00Z`),
      link: `/blog/${p.id}/`,
      description: excerpt(p.body ?? ''),
    })),
    customData: '<language>en</language>',
  });
}
