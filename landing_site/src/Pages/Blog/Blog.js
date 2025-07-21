import React from 'react'
import BasicLayout from '../../Layout/BasicLayout/BasicLayout'
import styles from "./Blog.module.css"
import LastContainer1 from '../../Components/LastContainer/LastContainer1'
import { Buffer } from 'buffer'
import posts from "../../utils/LoadPosts"

window.Buffer = Buffer

const Blog = () => {
  return (
    <article>
      <title>{`Blog - ReARM by Reliza`}</title>
      <meta name="description" content="ReARM Blog Entry Page containing available blog posts" />
      <BasicLayout>
        <div className={`${styles.containerHeader} container-fluid`}>
          <div className='row'>
            <div className={`col-12 col-sm-7`}>
              <div className={`mainPaddingContainer_sm_7`}>
                <h3 className={styles.C1_title2}>ReARM Blog</h3>
              </div>
            </div>
          </div>
        </div>
        <div className={`${styles.container1} container-fluid`}>
          {posts.map((post) => (
            <div className='row'>
              <div className={`col-12 col-sm-7`}>
                <div className={`mainPaddingContainer_sm_7`}>
                  <a href={`/blog/${post.slug}`}><h3 className={styles.C1_title1}>{post.title}</h3></a>
                  <h3 className={styles.C1_text1}>{post.date}</h3>
                </div>
              </div>
            </div>
          ))}  
        </div>
        <LastContainer1 />
      </BasicLayout >
    </article>
  )
}

export default Blog