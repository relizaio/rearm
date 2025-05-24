// import matter from "gray-matter"

// const context = require.context("../posts", false, /\.md$/)

// export function getPosts() {
//   return context.keys().map((key) => {
//     const raw = context(key).default || context(key)
//     const { data, content } = matter(raw)
//     const slug = key.replace(/^.\//, "").replace(/\.md$/, "")
//     return { slug, ...data, content }
//   })
// }

import { Buffer } from 'buffer'
import matter from "gray-matter"

window.Buffer = Buffer

function importAll(r) {
  return r.keys().map((fileName) => {
    const slug = fileName.replace("./", "").replace(".md", "")
    const raw = r(fileName).default
    const { data, content } = matter(raw)
    return {
      slug,
      ...data, content
    }
  })
}

// webpack's require.context - grab all .md files
const posts = importAll(require.context("../posts", false, /\.md$/))

export default posts