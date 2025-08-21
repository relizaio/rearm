import type { MetadataRoute } from "next";
import { getAllSlugs } from "@/lib/posts";
import fs from "fs";
import path from "path";

const baseUrl = (process.env.NEXT_PUBLIC_BASE_URL ?? "https://rearmhq.com").replace(/\/$/, "");

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const now = new Date();

  const staticRoutes: MetadataRoute.Sitemap = [
    {
      url: `${baseUrl}/`,
      lastModified: now,
      changeFrequency: "weekly",
      priority: 1,
    },
    {
      url: `${baseUrl}/pricing/`,
      lastModified: now,
      changeFrequency: "monthly",
      priority: 0.7,
    },
    {
      url: `${baseUrl}/blog/`,
      lastModified: now,
      changeFrequency: "weekly",
      priority: 0.7,
    },
  ];

  // Dynamic blog posts
  const slugs = getAllSlugs();
  const postsDir = path.join(process.cwd(), "src", "content", "posts");
  const blogRoutes: MetadataRoute.Sitemap = slugs.map((slug) => {
    const filePath = path.join(postsDir, `${slug}.md`);
    let lastModified: Date | undefined = undefined;
    try {
      const stat = fs.statSync(filePath);
      lastModified = stat.mtime;
    } catch {
      // ignore if stats not available in some build environments
    }
    return {
      url: `${baseUrl}/blog/${slug}/`,
      lastModified,
      changeFrequency: "monthly",
      priority: 0.6,
    };
  });

  return [...staticRoutes, ...blogRoutes];
}
