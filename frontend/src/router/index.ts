import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'
import LoginPage from '../views/LoginPage.vue'
import WorkspacePage from '../views/WorkspacePage.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/app' },
    { path: '/login', name: 'login', component: LoginPage },
    { path: '/app', name: 'workspace', component: WorkspacePage },
  ],
})

router.beforeEach(async (to) => {
  const session = useSessionStore()
  const state = await session.ensureIdentity()
  if (to.name === 'workspace' && state === 'anonymous') {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && state === 'authenticated') return { name: 'workspace' }
})
