import Link from "next/link";
import ReactMarkdown from "react-markdown";
import { getAllSlugs, getPostBySlug } from "../../../lib/posts";
import type { Metadata } from "next";

export async function generateStaticParams() {
  const slugs = getAllSlugs();
  return slugs.map((slug) => ({ slug }));
}

export async function generateMetadata({ params }: { params: any }): Promise<Metadata> {
  const resolved = params && typeof params.then === "function" ? await params : params;
  const { slug } = resolved as { slug: string };
  const post = getPostBySlug(slug);
  const title = post ? `${post.title} - ReARM by Reliza` : "Post - ReARM by Reliza";
  const description = post ? getDescription(post.content) : "Blog post on ReARM by Reliza.";
  const url = `${process.env.NEXT_PUBLIC_BASE_URL ?? "https://rearmhq.com"}/blog/${slug}`;
  return {
    title,
    description,
    alternates: { canonical: url },
    openGraph: { 
      title, 
      description, 
      url, 
      type: "article", 
      siteName: "ReARM - SBOM, xBOM, Security Artifacts, Release Management - by Reliza",
      images: [
        {
          url: "/rearm.png",
          width: 1200,
          height: 630,
          alt: "ReARM - Supply Chain Security",
        },
      ],
    },
    twitter: { 
      card: "summary_large_image", 
      title, 
      description,
      images: ["/rearm.png"], 
    },
  };
}

function getDescription(content: string): string {
  const plain = content.replace(/[#*`\[\]()]/g, "").replace(/\n/g, " ");
  return plain.length > 160 ? `${plain.substring(0, 157)}...` : plain;
}

export default async function BlogPostPage({ params }: { params: any }) {
  const resolved = params && typeof params.then === "function" ? await params : params;
  const { slug } = resolved as { slug: string };
  const post = getPostBySlug(slug);
  if (!post) {
    return (
      <main className="mx-auto max-w-3xl p-6">
        <p>Post not found. <Link href="/">Go back</Link></p>
      </main>
    );
  }
  return (
    <main className="blogPostContainer">
      <div className="blogPostHeader">
        <h1 className="blogPostMainTitle">{post.title}</h1>
        <p className="blogPostMainDate"><em>{post.date}</em></p>
      </div>
      <div className="blogPostContent">
        <ReactMarkdown
          components={{
            img: (props: any) => (
              // eslint-disable-next-line @next/next/no-img-element
              <img {...props} style={{ maxWidth: "100%", height: "auto" }} />
            ),
          }}
        >
          {post.content}
        </ReactMarkdown>
      </div>
      <div>
        <Link href="/blog" className="blogBackLink">← Back to Blog</Link>
      </div>
    </main>
  );
}
