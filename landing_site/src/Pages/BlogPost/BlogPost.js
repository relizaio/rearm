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

  return (
    <BasicLayout>
      <div className={`${styles.container1} container-fluid`}>
        <div className="p-8 max-w-2xl mx-auto">
          <h1 className={styles.C1_title1}>{post.title}</h1>
          <h3 className={styles.C1_text1}><em>{post.date}</em></h3>
          <div className={styles.C1_text1}>
            <ReactMarkdown>
              {post.content}
            </ReactMarkdown>
          </div>
          <Link to="/blog">← Back to Blog</Link>
        </div>
      </div>
      <LastContainer1 />
    </BasicLayout >
  )
}

export default BlogPost