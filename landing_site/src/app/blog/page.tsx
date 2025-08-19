import Link from "next/link";
import { getAllPosts } from "../../lib/posts";

export const metadata = {
  title: "Blog - ReARM - SBOM, xBOM, Security Artifacts, Release Management - by Reliza",
  description: "Articles and updates about ReARM, SBOM/xBOM, TEA, and supply chain security.",
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
