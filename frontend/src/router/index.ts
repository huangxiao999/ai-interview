import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'
import LoginPage from '../views/LoginPage.vue'
import WorkspacePage from '../views/WorkspacePage.vue'
import { safeReturnPath } from './returnPath'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/app' },
    { path: '/login', name: 'login', component: LoginPage },
    { path: '/app', name: 'workspace', component: WorkspacePage },
  ],
})

router.beforeEach(async (to, from) => {
  const session = useSessionStore()
  // A direct protected navigation rechecks the server. Login already confirmed identity with me.
  const state =
    to.name === 'workspace' && from.name !== 'login'
      ? await session.refreshIdentity()
      : await session.ensureIdentity()
  if (to.name === 'workspace' && state === 'anonymous') {
    return { name: 'login', query: { redirect: safeReturnPath(to.fullPath) } }
  }
  if (to.name === 'login' && state === 'authenticated') return { name: 'workspace' }
})
