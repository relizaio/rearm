import { onMounted, watch, nextTick } from 'vue'
import { useRoute } from 'vitepress'
import mediumZoom from 'medium-zoom'

export function useImageZoom() {
  const route = useRoute()

  const initZoom = () => {
    mediumZoom('.main img', { background: 'var(--vp-c-bg)' })
  }

  onMounted(() => {
    initZoom()
  })

  watch(
    () => route.path,
    () => nextTick(initZoom)
  )
}
