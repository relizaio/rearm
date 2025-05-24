import matter from "gray-matter"

const context = require.context("../posts", false, /\.md$/)

export function getPosts() {
  return context.keys().map((key) => {
    const raw = context(key).default || context(key)
    const { data, content } = matter(raw)
    const slug = key.replace(/^.\//, "").replace(/\.md$/, "")
    return { slug, ...data, content }
  })
}