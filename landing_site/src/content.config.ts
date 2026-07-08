import { defineCollection, z } from 'astro:content';
import { glob } from 'astro/loaders';

// Filename (minus .md) is the id -> becomes the URL slug, preserving the
// date-prefixed permalinks of the previous site (/blog/2026-01-14-....../).
const postSchema = z.object({
  title: z.string(),
  date: z.string(),
  ogImage: z.string().optional(),
});

export const collections = {
  posts: defineCollection({
    loader: glob({ pattern: '**/*.md', base: './src/content/posts' }),
    schema: postSchema,
  }),
  news: defineCollection({
    loader: glob({ pattern: '**/*.md', base: './src/content/news' }),
    schema: postSchema,
  }),
};
