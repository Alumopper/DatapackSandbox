import type { Theme } from 'vitepress'
import { VPCarbon } from 'vitepress-carbon'
import { defineAsyncComponent, h } from 'vue'
import HomeCodeShowcase from './components/HomeCodeShowcase.vue'
import './custom.css'

const PlaygroundDemo = defineAsyncComponent(() => import('./components/PlaygroundDemo.vue'))
const CellDemo = defineAsyncComponent(() => import('./components/CellDemo.vue'))

export default {
  extends: VPCarbon,
  enhanceApp({ app }) {
    app.component('PlaygroundDemo', PlaygroundDemo)
    app.component('CellDemo', CellDemo)
  },
  Layout() {
    return h(VPCarbon.Layout!, null, {
      'home-hero-after': () => h(HomeCodeShowcase),
    })
  },
} satisfies Theme
