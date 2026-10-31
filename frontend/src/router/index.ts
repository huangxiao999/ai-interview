import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { useMaterialsStore } from '../stores/materials'
import LoginPage from '../views/LoginPage.vue'
import WorkspacePage from '../views/WorkspacePage.vue'
import KnowledgePage from '../views/KnowledgePage.vue'
import KnowledgeIndexPage from '../views/KnowledgeIndexPage.vue'
import { safeReturnPath } from './returnPath'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/app' },
    { path: '/login', name: 'login', component: LoginPage },
    { path: '/app', name: 'workspace', component: WorkspacePage, meta: { requiresAuth: true } },
    {
      path: '/app/knowledge-index',
      name: 'knowledgeIndex',
      component: KnowledgeIndexPage,
      meta: { requiresAuth: true },
    },
    {
      path: '/app/knowledge',
      name: 'knowledge',
      component: KnowledgePage,
      meta: { requiresAuth: true },
    },
    {
      path: '/app/resumes/:resumeId',
      name: 'material',
      component: WorkspacePage,
      meta: { requiresAuth: true },
    },
  ],
})

let pendingRoute: RouteLocationNormalized | null = null

router.beforeEach(async (to, from) => {
  const materials = useMaterialsStore()
  pendingRoute = to
  materials.routeNavigating = true
  materials.invalidateDetailRead()
  const session = useSessionStore()
  // A direct protected navigation rechecks the server. Login already confirmed identity with me.
  const state =
    to.meta.requiresAuth && from.name !== 'login'
      ? await session.refreshIdentity()
      : await session.ensureIdentity()
  if (to.meta.requiresAuth && state === 'anonymous') {
    return { name: 'login', query: { redirect: safeReturnPath(to.fullPath) } }
  }
  if (to.name === 'login' && state === 'authenticated') return { name: 'workspace' }
})

function finishNavigation(to: RouteLocationNormalized): void {
  // 被后续导航取代的回调不能提前开放新导航的详情读取。
  if (pendingRoute !== to) return
  pendingRoute = null
  useMaterialsStore().routeNavigating = false
}

router.afterEach((to) => finishNavigation(to))
router.onError((_error, to) => finishNavigation(to))
