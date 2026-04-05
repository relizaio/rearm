import DefaultTheme from 'vitepress/theme'
import { useImageZoom } from './useImageZoom'
import './custom.css'

export default {
  extends: DefaultTheme,
  setup() {
    useImageZoom()
  }
}
