import Link from "next/link";
import { getAllNews } from "../../lib/posts";

const baseUrl = (process.env.NEXT_PUBLIC_BASE_URL ?? "https://rearmhq.com").replace(/\/$/, "");

export const metadata = {
  title: "ReARM News",
  description: "News and updates about ReARM, SBOM/xBOM, TEA, and supply chain security.",
  alternates: { canonical: `${baseUrl}/news/` },
  openGraph: {
    title: "ReARM News",
    description: "News and updates about ReARM, SBOM/xBOM, TEA, and supply chain security.",
    url: `${baseUrl}/news/`,
    type: "website",
    siteName: "ReARM - Release-Level Supply Chain Evidence Platform by Reliza",
  },
};

export default function NewsIndexPage() {
  const posts = getAllNews();
  return (
    <main className="blogContainer">
      <div className="blogHeader">
        <h1 className="blogTitle">ReARM News</h1>
      </div>
      <ul className="blogPostList">
        {posts.map((p) => (
          <li key={p.slug} className="blogPostItem">
            <h2>
              <Link href={`/news/${p.slug}`} className="blogPostTitle">{p.title}</Link>
            </h2>
            <p className="blogPostDate">{p.date}</p>
          </li>
        ))}
      </ul>
    </main>
  );
}
