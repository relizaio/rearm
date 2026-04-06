import { onMounted, watch, nextTick } from 'vue'
import { useRoute } from 'vitepress'

const OVERLAY_ID = 'vp-img-lightbox'

function getOrCreateOverlay() {
  let overlay = document.getElementById(OVERLAY_ID)
  if (overlay) return overlay

  overlay = document.createElement('div')
  overlay.id = OVERLAY_ID
  Object.assign(overlay.style, {
    display: 'none',
    position: 'fixed',
    top: '0',
    left: '0',
    width: '100%',
    height: '100%',
    background: 'rgba(0,0,0,0.85)',
    zIndex: '9999',
    cursor: 'zoom-out',
  })

  const inner = document.createElement('div')
  Object.assign(inner.style, {
    position: 'absolute',
    inset: '0',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  })

  const img = document.createElement('img')
  img.id = 'vp-img-lightbox-img'
  Object.assign(img.style, {
    maxWidth: '90vw',
    maxHeight: '90vh',
    objectFit: 'contain',
    boxShadow: '0 0 40px rgba(0,0,0,0.5)',
    cursor: 'zoom-out',
  })

  const closeBtn = document.createElement('button')
  closeBtn.textContent = '✕'
  Object.assign(closeBtn.style, {
    position: 'absolute',
    top: '16px',
    right: '20px',
    background: 'none',
    border: 'none',
    color: '#fff',
    fontSize: '28px',
    lineHeight: '1',
    cursor: 'pointer',
    zIndex: '10000',
  })

  const close = () => {
    overlay.style.display = 'none'
    img.src = ''
  }

  closeBtn.addEventListener('click', close)
  overlay.addEventListener('click', close)

  img.addEventListener('click', (e) => {
    e.stopPropagation()
    close()
  })

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') close()
  })

  inner.appendChild(img)
  overlay.appendChild(inner)
  overlay.appendChild(closeBtn)
  document.body.appendChild(overlay)

  return overlay
}

export function useImageZoom() {
  const route = useRoute()

  const initZoom = () => {
    const overlay = getOrCreateOverlay()
    const img = overlay.querySelector('#vp-img-lightbox-img')

    const imgs = document.querySelectorAll('.vp-doc img')
    imgs.forEach(el => {
      if (el._vpZoomHandler) {
        el.removeEventListener('click', el._vpZoomHandler)
      }
      el.style.cursor = 'zoom-in'
      el._vpZoomHandler = (e) => {
        e.stopPropagation()
        img.src = el.src
        overlay.style.display = 'block'
      }
      el.addEventListener('click', el._vpZoomHandler)
    })
  }

  onMounted(() => {
    nextTick(initZoom)
  })

  watch(
    () => route.path,
    () => nextTick(initZoom)
  )
}
