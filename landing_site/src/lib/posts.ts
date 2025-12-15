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

export function getAllPosts(): Post[] {
  if (!fs.existsSync(postsDir)) return [];
  const files = fs.readdirSync(postsDir).filter((f) => f.endsWith(".md"));
  const posts = files.map((file) => {
    const slug = file.replace(/\.md$/, "");
    const fullPath = path.join(postsDir, file);
    const raw = fs.readFileSync(fullPath, "utf8");
    const { data, content } = matter(raw);
    return {
      slug,
      title: (data?.title as string) || slug,
      date: (data?.date as string) || "",
      content,
    };
  });
  posts.sort((a, b) => (a.date > b.date ? -1 : a.date < b.date ? 1 : 0));
  return posts;
}

export function getPostBySlug(slug: string): Post | undefined {
  const file = path.join(postsDir, `${slug}.md`);
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

export function getAllSlugs(): string[] {
  if (!fs.existsSync(postsDir)) return [];
  return fs
    .readdirSync(postsDir)
    .filter((f) => f.endsWith(".md"))
    .map((f) => f.replace(/\.md$/, ""));
}
