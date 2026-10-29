<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { safeReturnPath } from '../router/returnPath'
import { useSessionStore } from '../stores/session'

const router = useRouter()
const route = useRoute()
const session = useSessionStore()
const { status, message, busy } = storeToRefs(session)
const loginName = ref('')
const password = ref('')
const showPassword = ref(false)
const inputError = ref('')
const loginNameInput = ref<HTMLInputElement | null>(null)
const passwordInput = ref<HTMLInputElement | null>(null)

async function submit(): Promise<void> {
  inputError.value = ''
  if (!/^[A-Za-z0-9._-]{3,64}$/.test(loginName.value)) {
    inputError.value = '账号须为 3～64 位英文字母、数字、点、下划线或连字符'
    loginNameInput.value?.focus()
    return
  }
  if (password.value.length === 0) {
    inputError.value = '请输入密码'
    passwordInput.value?.focus()
    return
  }
  if (await session.login(loginName.value, password.value)) password.value = ''
}

async function retryIdentity(): Promise<void> {
  await session.refreshIdentity()
}

watch([status, busy], ([current, pending]) => {
  if (current === 'authenticated' && !pending) {
    void router.replace(safeReturnPath(route.query.redirect))
  }
})

onBeforeUnmount(() => {
  password.value = ''
})
</script>

<template>
  <main class="auth-layout">
    <header class="brand-header">
      <span class="brand-mark" aria-hidden="true">AI</span>
      <span>智能面试教练</span>
    </header>

    <section class="auth-card" aria-labelledby="login-heading">
      <p class="eyebrow">邀请试用</p>
      <h1 id="login-heading">登录你的练习空间</h1>
      <p class="subtle">使用维护者提供的试用账号登录。</p>

      <div v-if="status === 'checking'" class="notice" role="status">正在核实登录状态…</div>
      <div v-else-if="status === 'uncertain'" class="notice notice-error" role="alert">
        <p>{{ message || '暂时无法核实登录状态，请重试' }}</p>
        <button type="button" class="button-secondary" @click="retryIdentity">重新核实</button>
      </div>
      <form v-else class="auth-form" @submit.prevent="submit">
        <label for="login-name">账号</label>
        <input
          id="login-name"
          ref="loginNameInput"
          v-model="loginName"
          name="username"
          type="text"
          autocomplete="username"
          maxlength="64"
          :disabled="busy"
          aria-describedby="login-name-help"
        />
        <p id="login-name-help" class="field-help">区分大小写，不能包含空格。</p>

        <label for="login-password">密码</label>
        <div class="password-row">
          <input
            id="login-password"
            ref="passwordInput"
            v-model="password"
            name="password"
            :type="showPassword ? 'text' : 'password'"
            autocomplete="current-password"
            :disabled="busy"
          />
          <button
            type="button"
            class="button-secondary"
            :disabled="busy"
            @click="showPassword = !showPassword"
          >
            {{ showPassword ? '隐藏' : '显示' }}
          </button>
        </div>
        <p v-if="inputError || message" class="form-error" role="alert">
          {{ inputError || message }}
        </p>
        <button type="submit" class="button-primary" :disabled="busy">
          {{ busy ? '正在登录…' : '登录' }}
        </button>
      </form>

      <p class="auth-note">
        本产品采用邀请试用，不开放注册。需要重置密码时，请联系试用账号维护者。
      </p>
    </section>
  </main>
</template>
