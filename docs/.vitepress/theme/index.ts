import { VPCarbon } from 'vitepress-carbon'
import { h } from 'vue'
import HomeCodeShowcase from './components/HomeCodeShowcase.vue'
import './custom.css'

export default {
  extends: VPCarbon,
  Layout() {
    return h(VPCarbon.Layout!, null, {
      'home-hero-after': () => h(HomeCodeShowcase),
    })
  },
}
