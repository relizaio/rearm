import Link from "next/link";
import { getAllPosts } from "../../lib/posts";

const baseUrl = (process.env.NEXT_PUBLIC_BASE_URL ?? "https://rearmhq.com").replace(/\/$/, "");

export const metadata = {
  title: "ReARM Blog",
  description: "Articles and updates about ReARM, SBOM/xBOM, TEA, and supply chain security.",
  alternates: { canonical: `${baseUrl}/blog/` },
  openGraph: {
    title: "ReARM Blog",
    description: "Articles and updates about ReARM, SBOM/xBOM, TEA, and supply chain security.",
    url: `${baseUrl}/blog/`,
    type: "website",
    siteName: "ReARM - Release-Level Supply Chain Evidence Platform by Reliza",
  },
};

export default function BlogIndexPage() {
  const posts = getAllPosts();
  return (
    <main className="blogContainer">
      <div className="blogHeader">
        <h1 className="blogTitle">ReARM Blog</h1>
      </div>
      <ul className="blogPostList">
        {posts.map((p) => (
          <li key={p.slug} className="blogPostItem">
            <h2>
              <Link href={`/blog/${p.slug}`} className="blogPostTitle">{p.title}</Link>
            </h2>
            <p className="blogPostDate">{p.date}</p>
          </li>
        ))}
      </ul>
    </main>
  );
}
