<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'

const router = useRouter()
const session = useSessionStore()
const { status, identity, message, busy } = storeToRefs(session)

async function signOut(): Promise<void> {
  if (await session.logout()) await router.replace('/login')
}

async function retryIdentity(): Promise<void> {
  if ((await session.refreshIdentity()) === 'anonymous') await router.replace('/login')
}
</script>

<template>
  <main class="workspace-layout">
    <header class="workspace-header">
      <div class="brand-header">
        <span class="brand-mark" aria-hidden="true">AI</span>
        <span>智能面试教练</span>
      </div>
      <div v-if="status === 'authenticated' && identity" class="account-actions">
        <span class="account-name">{{ identity.loginName }}</span>
        <button type="button" class="button-secondary" :disabled="busy" @click="signOut">
          {{ busy ? '正在退出…' : '退出登录' }}
        </button>
      </div>
    </header>

    <section v-if="status === 'checking'" class="workspace-panel" role="status">
      正在核实登录状态…
    </section>
    <section v-else-if="status === 'uncertain'" class="workspace-panel" role="alert">
      <h1>暂时无法核实登录状态</h1>
      <p>{{ message || '请重试身份核实。' }}</p>
      <button type="button" class="button-primary" @click="retryIdentity">重新核实</button>
    </section>
    <section v-else-if="status === 'anonymous'" class="workspace-panel">
      <h1>登录已失效</h1>
      <p>请重新登录后继续。</p>
      <RouterLink to="/login" class="button-link">前往登录</RouterLink>
    </section>
    <section v-else class="workspace-panel" aria-labelledby="workspace-heading">
      <p class="eyebrow">我的练习空间</p>
      <h1 id="workspace-heading">已登录</h1>
      <p v-if="identity?.authorities.includes('TRIAL_USER')">
        账号身份已核实。简历、岗位 JD、面试计划、模拟面试和复盘报告尚未开发。
      </p>
      <p v-else>账号身份已核实。当前身份没有试用训练权限。</p>
      <p class="subtle">本页仅验证登录与受保护入口，不展示模拟记录或其他用户资料。</p>
      <p v-if="message" class="form-error" role="alert">{{ message }}</p>
    </section>
  </main>
</template>
