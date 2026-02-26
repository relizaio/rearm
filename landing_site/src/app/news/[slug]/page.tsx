import Link from "next/link";
import ReactMarkdown from "react-markdown";
import { getAllNewsSlugs, getNewsBySlug } from "../../../lib/posts";
import type { Metadata } from "next";
import Script from "next/script";

const baseUrl = (process.env.NEXT_PUBLIC_BASE_URL ?? "https://rearmhq.com").replace(/\/$/, "");

export async function generateStaticParams() {
  const slugs = getAllNewsSlugs();
  return slugs.map((slug) => ({ slug }));
}

export async function generateMetadata({ params }: { params: any }): Promise<Metadata> {
  const resolved = params && typeof params.then === "function" ? await params : params;
  const { slug } = resolved as { slug: string };
  const post = getNewsBySlug(slug);
  const title = post ? `${post.title} - ReARM by Reliza` : "News - ReARM by Reliza";
  const description = post ? getDescription(post.content) : "News update on ReARM by Reliza";
  const url = `${baseUrl}/news/${slug}/`;
  const ogImageUrl = post?.ogImage ? `/blog_images/${post.ogImage}` : "/rearm.png";
  return {
    title,
    description,
    alternates: { canonical: url },
    openGraph: {
      title,
      description,
      url,
      type: "article",
      siteName: "ReARM - Release-Level Supply Chain Evidence Platform by Reliza",
      images: [
        {
          url: ogImageUrl,
          width: 1200,
          height: 630,
          alt: post?.title || "ReARM - Supply Chain Security",
        },
      ],
    },
    twitter: {
      card: "summary_large_image",
      title,
      description,
      images: [ogImageUrl],
    },
  };
}

function getDescription(content: string): string {
  const plain = content.replace(/[#*`\[\]()]/g, "").replace(/\n/g, " ");
  return plain.length > 160 ? `${plain.substring(0, 157)}...` : plain;
}

export default async function NewsPostPage({ params }: { params: any }) {
  const resolved = params && typeof params.then === "function" ? await params : params;
  const { slug } = resolved as { slug: string };
  const post = getNewsBySlug(slug);
  if (!post) {
    return (
      <main className="mx-auto max-w-3xl p-6">
        <p>Post not found. <Link href="/">Go back</Link></p>
      </main>
    );
  }
  const articleJsonLd = {
    "@context": "https://schema.org",
    "@type": "Article",
    headline: post.title,
    datePublished: post.date,
    dateModified: post.date,
    author: {
      "@type": "Organization",
      name: "Reliza",
      url: "https://reliza.io",
    },
    publisher: {
      "@type": "Organization",
      name: "ReARM by Reliza",
      url: baseUrl,
      logo: {
        "@type": "ImageObject",
        url: `${baseUrl}/rearm.png`,
      },
    },
    mainEntityOfPage: {
      "@type": "WebPage",
      "@id": `${baseUrl}/news/${slug}/`,
    },
    description: getDescription(post.content),
  };

  return (
    <main className="blogPostContainer">
      <Script
        id="article-jsonld"
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(articleJsonLd) }}
      />
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
        <Link href="/news" className="blogBackLink">← Back to News</Link>
      </div>
    </main>
  );
}
