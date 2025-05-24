import React from 'react'
import ReactMarkdown from 'react-markdown'
import { useEffect, useState } from 'react'
import grayMatter from 'gray-matter'
import rearmHistoryPost from '../../posts/rearm-history.md'
import BasicLayout from '../../Layout/BasicLayout/BasicLayout'
import styles from "./BlogPost.module.css"
import about1 from "../../Assets/AboutUs/about1.png"
import about2_1 from "../../Assets/AboutUs/about2_1.png"
import about2_2 from "../../Assets/AboutUs/about2_2.png"
import LastContainer1 from '../../Components/LastContainer/LastContainer1'
import Experience from '../../Components/Experience/Experience'
import { Buffer } from 'buffer'
import posts from "../../utils/LoadPosts"
import { useParams, Link } from "react-router-dom"

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

const BlogPost = () => {
  const { slug } = useParams()
  const post = posts.find((p) => p.slug === slug)
  console.log(posts)
  if (!post) return <p>Post not found. <Link to="/">Go back</Link></p>
  // const { data, content } = grayMatter(rearmHistoryPost)

  return (
    <BasicLayout>
      <div className="p-8 max-w-2xl mx-auto">
        <Link to="/">← Back to posts</Link>
        <h1>{post.title}</h1>
        <p><em>{post.date}</em></p>
        <ReactMarkdown>{post.content}</ReactMarkdown>
      </div>
      <div className={`${styles.container1} container-fluid`}>
        <div className='row'>
          <div className={`col-12 col-sm-7`}>
            <div className={`mainPaddingContainer_sm_7`}>
              <h3 className={styles.C1_title1}>About Us</h3>
              <h3 className={styles.C1_title2}>Reliza - Future of DevOps</h3>
              <p className={styles.C1_text1}>Reliza was established in 2019 to help companies navigate modern DevOps practices and transition to GoalOps. Our mission is to connect whole tech organization - from Developers to Marketing and Sales - around the common Goal. <hr style={{ height: "0", margin: "5px 0" }} />Reliza Hub is currently working in a public preview mode free of charge! You may start using it here - no registration required to try! Reliza Hub is a GoalOps SaaS platform that provides single pane of glass view for your releases, instances and deployments. Whether you're following a mono-repo or multi-repo, monolith or microservices, Git or SVN, Reliza Hub is there to help organize everything.</p>
            </div>
          </div>
          <div className='col-12 col-sm-5 p-0'>
            <div className={styles.C1_right}>
              <div className={styles.C1_right1}><img src={about1} alt='' style={{ width: "100%" }} /></div>
              <div className={styles.C1_right2}><img src={about2_1} alt='' style={{ width: "100%" }} /></div>
              <div className={styles.C1_right3}><img src={about2_2} alt='' style={{ width: "100%" }} /></div>
            </div>
          </div>
        </div>
      </div>
      <div className="mainPaddingContainer">
        <div className={`container-fluid ${styles.container2}`}>
          <Experience />
        </div>
        <div className={`container-fluid ${styles.container3}`}>
          <div className='row'>
            <div className='col-12 col-md-6'>
              <h3 className={styles.C1_title1}>Why Choose Us</h3>
              <h3 className={styles.C1_title2}>A Tool For Futur Of Developer Work</h3>
            </div>
          </div>
        </div>
      </div>
      <LastContainer1 />
    </BasicLayout >
  )
}

export default BlogPost