<template>
  <div class="login relative flex min-h-screen items-center justify-center overflow-hidden bg-neutral-950 px-4 font-sans text-neutral-100 selection:bg-amber-500 selection:text-white">
    <!-- 背景氛围光效 -->
    <div class="pointer-events-none absolute inset-0">
      <div class="absolute -left-40 -top-40 h-[28rem] w-[28rem] rounded-full bg-amber-500/10 blur-3xl"></div>
      <div class="absolute -bottom-40 -right-40 h-[28rem] w-[28rem] rounded-full bg-amber-500/10 blur-3xl"></div>
      <div class="absolute inset-0 bg-[radial-gradient(circle_at_50%_-10%,rgba(245,158,11,0.10),transparent_55%)]"></div>
      <div
        class="absolute inset-0 opacity-[0.04]"
        style="background-image: linear-gradient(rgba(255,255,255,0.6) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.6) 1px, transparent 1px); background-size: 48px 48px"
      ></div>
    </div>

    <!-- 登录卡片 -->
    <div class="login-card relative z-10 w-full max-w-md">
      <div class="rounded-2xl border border-neutral-800 bg-neutral-900/70 p-8 shadow-2xl shadow-black/50 backdrop-blur-xl sm:p-10">
        <!-- Logo 与标题 -->
        <div class="mb-10 flex flex-col items-center gap-5">
          <div class="flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-b from-amber-400 to-amber-600 shadow-lg shadow-amber-500/30">
            <svg class="h-8 w-8 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
            </svg>
          </div>
          <div class="text-center">
            <h1 class="text-2xl font-bold tracking-tight text-white">{{ title }}</h1>
            <p class="mt-2 text-sm text-neutral-400">无败 · 街舞赛事管理平台</p>
          </div>
        </div>

        <el-form ref="loginRef" :model="loginForm" :rules="loginRules" size="large" @keyup.enter="handleLogin">
          <el-form-item prop="username" class="login-item">
            <el-input v-model="loginForm.username" placeholder="用户名" clearable autocomplete="off">
              <template #prefix>
                <svg class="h-4 w-4 text-neutral-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"
                  />
                </svg>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item prop="password" class="login-item">
            <el-input v-model="loginForm.password" type="password" placeholder="密码" show-password autocomplete="off">
              <template #prefix>
                <svg class="h-4 w-4 text-neutral-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
                  />
                </svg>
              </template>
            </el-input>
          </el-form-item>

          <div class="mb-6 flex items-center justify-between">
            <el-checkbox v-model="loginForm.rememberMe" class="remember-check">记住密码</el-checkbox>
          </div>

          <el-button class="login-btn group relative w-full !h-12 overflow-hidden rounded-xl text-base font-bold" :loading="loading" @click="handleLogin">
            <span class="absolute inset-0 bg-gradient-to-r from-transparent via-white/20 to-transparent -translate-x-full transition-transform duration-700 group-hover:translate-x-full"></span>
            <span v-if="!loading">登 录</span>
            <span v-else>登录中...</span>
          </el-button>
        </el-form>
      </div>

      <p class="mt-8 text-center text-xs tracking-wider text-neutral-600">© 2026 无败 · 街舞赛事管理系统</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useUserStore } from '@/store/modules/user';
import { LoginData } from '@/api/types';
import { to } from 'await-to-js';

const title = import.meta.env.VITE_APP_TITLE;
const userStore = useUserStore();
const router = useRouter();

const loginForm = ref<LoginData>({
  username: '',
  password: '',
  rememberMe: false
} as LoginData);

const loginRules: ElFormRules = {
  username: [{ required: true, trigger: 'blur', message: '请输入用户名' }],
  password: [{ required: true, trigger: 'blur', message: '请输入密码' }]
};

const loading = ref(false);
const redirect = ref('/');
const loginRef = ref<ElFormInstance>();

watch(
  () => router.currentRoute.value,
  (newRoute: any) => {
    redirect.value = newRoute.query && newRoute.query.redirect && decodeURIComponent(newRoute.query.redirect);
  },
  { immediate: true }
);

const handleLogin = () => {
  loginRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    loading.value = true;
    if (loginForm.value.rememberMe) {
      localStorage.setItem('username', String(loginForm.value.username));
      localStorage.setItem('password', String(loginForm.value.password));
      localStorage.setItem('rememberMe', String(loginForm.value.rememberMe));
    } else {
      localStorage.removeItem('username');
      localStorage.removeItem('password');
      localStorage.removeItem('rememberMe');
    }
    const [err] = await to(userStore.login(loginForm.value));
    loading.value = false;
    if (err) {
      ElMessage.error('用户名或密码错误');
      return;
    }
    const redirectUrl = redirect.value || '/';
    await router.push(redirectUrl);
  });
};

const getLoginData = () => {
  const username = localStorage.getItem('username');
  const password = localStorage.getItem('password');
  const rememberMe = localStorage.getItem('rememberMe');
  loginForm.value = {
    username: username === null ? String(loginForm.value.username) : username,
    password: password === null ? String(loginForm.value.password) : String(password),
    rememberMe: rememberMe === null ? false : Boolean(rememberMe)
  } as LoginData;
};

onMounted(() => {
  getLoginData();
});
</script>

<style lang="scss" scoped>
// 登录卡片入场动画
.login-card {
  animation: login-in 0.5s ease-out both;
}

@keyframes login-in {
  from {
    opacity: 0;
    transform: translateY(16px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

// Element Plus 输入框暗黑化
:deep(.el-input__wrapper) {
  background-color: rgb(23 23 23 / 0.8);
  box-shadow: 0 0 0 1px rgb(64 64 64) inset;
  border-radius: 0.65rem;
  transition: box-shadow 0.2s;

  &:hover {
    box-shadow: 0 0 0 1px rgb(82 82 82) inset;
  }

  &.is-focus {
    box-shadow: 0 0 0 1px rgb(245 158 11) inset, 0 0 12px rgb(245 158 11 / 0.15);
  }
}

:deep(.el-input__inner) {
  color: #e5e5e5;
  caret-color: #f59e0b;

  &::placeholder {
    color: #737373;
  }
}

:deep(.el-input__prefix) {
  display: flex;
  align-items: center;
}

:deep(.el-input__clear),
:deep(.el-input__password) {
  color: #737373;
}

// 表单校验错误间距与文字
.login-item {
  margin-bottom: 22px;

  :deep(.el-form-item__error) {
    padding-top: 4px;
    color: #f87171;
  }
}

// 记住密码
:deep(.remember-check) {
  --el-checkbox-text-color: #a3a3a3;
  --el-checkbox-checked-text-color: #e5e5e5;
  --el-checkbox-checked-bg-color: #f59e0b;
  --el-checkbox-checked-border-color: #f59e0b;
  --el-checkbox-input-border-color-hover: #f59e0b;
  height: 24px;
}

// 登录按钮(琥珀渐变,与赛事大厅按钮风格一致)
.login-btn {
  background-image: linear-gradient(to bottom, #fbbf24, #d97706);
  border: none;
  color: #fff;
  letter-spacing: 0.3em;
  text-indent: 0.3em;
  box-shadow: 0 8px 24px rgb(245 158 11 / 0.25);
  transition: all 0.2s;

  &:hover:not(.is-disabled) {
    background-image: linear-gradient(to bottom, #fbbf24, #f59e0b);
    color: #fff;
    transform: translateY(-1px);
    box-shadow: 0 12px 28px rgb(245 158 11 / 0.35);
  }

  &:active:not(.is-disabled) {
    transform: translateY(0) scale(0.98);
  }

  &.is-loading {
    background-image: linear-gradient(to bottom, #fbbf24, #d97706);
  }
}
</style>
