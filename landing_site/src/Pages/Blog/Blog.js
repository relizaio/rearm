import React from 'react'
import ReactMarkdown from 'react-markdown'
import { useEffect, useState } from 'react'
import grayMatter from 'gray-matter'
import rearmHistoryPost from '../../posts/rearm-history.md'
import BasicLayout from '../../Layout/BasicLayout/BasicLayout'
import styles from "./Blog.module.css"
import about1 from "../../Assets/AboutUs/about1.png"
import about2_1 from "../../Assets/AboutUs/about2_1.png"
import about2_2 from "../../Assets/AboutUs/about2_2.png"
import LastContainer1 from '../../Components/LastContainer/LastContainer1'
import Experience from '../../Components/Experience/Experience'
import { Buffer } from 'buffer'
import posts from "../../utils/LoadPosts"

window.Buffer = Buffer

// const Post = ({ postName }) => {
//   const [content, setContent] = useState('');
//   const [meta, setMeta] = useState({});

//   useEffect(() => {
//       const filePath = path.join('src/posts', `${postName}.md`);
//       const fileContent = fs.readFileSync(filePath, 'utf8');
//       const { data, content } = grayMatter(fileContent);
//       setMeta(data);
//       setContent(content);
//   }, [postName]);

const Blog = () => {
  const { data, content } = grayMatter(rearmHistoryPost)

  return (
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
  )
}

export default Blog