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

const posts = importAll(require.context("../posts", false, /\.md$/))

posts.sort((a, b) => {
    if (a.date > b.date) {
        return -1
    } else if (a.date < b.date) {
        return 1
    }
    return 0
})

export default posts