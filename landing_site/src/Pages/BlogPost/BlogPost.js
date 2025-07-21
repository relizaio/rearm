import React from 'react'
import ReactMarkdown from 'react-markdown'
import BasicLayout from '../../Layout/BasicLayout/BasicLayout'
import styles from "./BlogPost.module.css"
import LastContainer1 from '../../Components/LastContainer/LastContainer1'
import { Buffer } from 'buffer'
import posts from "../../utils/LoadPosts"
import { useParams, Link } from "react-router-dom"

window.Buffer = Buffer

const BlogPost = () => {
  const { slug } = useParams()
  const post = posts.find((p) => p.slug === slug)
  if (!post) return <p>Post not found. <Link to="/">Go back</Link></p>
  
  // Extract first paragraph or first 160 characters for description
  const getDescription = (content) => {
    const plainText = content.replace(/[#*`\[\]()]/g, '').replace(/\n/g, ' ')
    return plainText.length > 160 ? plainText.substring(0, 157) + '...' : plainText
  }
  
  const currentUrl = `${window.location.origin}/blog/${slug}`
  const description = getDescription(post.content)
  
  return (
    <article>
      {/* React 19 native metadata tags - automatically hoisted to <head> */}
      <title>{`${post.title} - ReARM by Reliza`}</title>
      <meta name="description" content={description} />
      
      {/* Open Graph tags for Facebook, LinkedIn, etc. */}
      <meta property="og:title" content={post.title} />
      <meta property="og:description" content={description} />
      <meta property="og:type" content="article" />
      <meta property="og:url" content={currentUrl} />
      <meta property="og:site_name" content="ReARM by Reliza" />
      <meta property="og:image" content={`${window.location.origin}/logo192.png`} />
      <meta property="article:published_time" content={post.date} />
      
      {/* Twitter Card tags */}
      <meta name="twitter:card" content="summary" />
      <meta name="twitter:title" content={post.title} />
      <meta name="twitter:description" content={description} />
      <meta name="twitter:url" content={currentUrl} />
      <meta name="twitter:image" content={`${window.location.origin}/logo192.png`} />
      
      {/* Additional meta tags */}
      <meta name="author" content="ReARM by Reliza" />
      <BasicLayout>
        <link rel="canonical" href={currentUrl} />
        <div className={`${styles.container1} container-fluid`}>
          <div className="p-8 max-w-2xl mx-auto">
            <h1 className={styles.C1_title1}>{post.title}</h1>
            <h3 className={styles.C1_text1}><em>{post.date}</em></h3>
            <div className={styles.C1_text1}>
              <ReactMarkdown components={{
                img: ({ node, ...props }) => (
                  <img
                    {...props}
                    style={{ maxWidth: '1000px', height: 'auto' }} // adjust as needed
                  />
                ),
              }}>
                {post.content}
              </ReactMarkdown>
            </div>
            <Link to="/blog">← Back to Blog</Link>
          </div>
        </div>
        <LastContainer1 />
      </BasicLayout >
    </article>
  )
}

export default BlogPost