import fs from "fs";
import path from "path";
import matter from "gray-matter";

export type PostFrontmatter = {
  title: string;
  date: string; // ISO date string
  ogImage?: string; // optional OG image from /public/blog_images/
};

export type Post = PostFrontmatter & {
  slug: string;
  content: string;
};

const postsDir = path.join(process.cwd(), "src", "content", "posts");
const newsDir = path.join(process.cwd(), "src", "content", "news");

function readPostsFromDir(dir: string): Post[] {
  if (!fs.existsSync(dir)) return [];
  const files = fs.readdirSync(dir).filter((f) => f.endsWith(".md"));
  const posts = files.map((file) => {
    const slug = file.replace(/\.md$/, "");
    const fullPath = path.join(dir, file);
    const raw = fs.readFileSync(fullPath, "utf8");
    const { data, content } = matter(raw);
    return {
      slug,
      title: (data?.title as string) || slug,
      date: (data?.date as string) || "",
      ogImage: (data?.ogImage as string) || undefined,
      content,
    };
  });
  posts.sort((a, b) => (a.date > b.date ? -1 : a.date < b.date ? 1 : 0));
  return posts;
}

function getSlugsFromDir(dir: string): string[] {
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir)
    .filter((f) => f.endsWith(".md"))
    .map((f) => f.replace(/\.md$/, ""));
}

function getPostFromDir(dir: string, slug: string): Post | undefined {
  const file = path.join(dir, `${slug}.md`);
  if (!fs.existsSync(file)) return undefined;
  const raw = fs.readFileSync(file, "utf8");
  const { data, content } = matter(raw);
  return {
    slug,
    title: (data?.title as string) || slug,
    date: (data?.date as string) || "",
    ogImage: (data?.ogImage as string) || undefined,
    content,
  };
}

// Blog
export function getAllPosts(): Post[] {
  return readPostsFromDir(postsDir);
}

export function getPostBySlug(slug: string): Post | undefined {
  return getPostFromDir(postsDir, slug);
}

export function getAllSlugs(): string[] {
  return getSlugsFromDir(postsDir);
}

// News
export function getAllNews(): Post[] {
  return readPostsFromDir(newsDir);
}

export function getNewsBySlug(slug: string): Post | undefined {
  return getPostFromDir(newsDir, slug);
}

export function getAllNewsSlugs(): string[] {
  return getSlugsFromDir(newsDir);
}
