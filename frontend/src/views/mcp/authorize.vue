<template>
  <div class="authorize-page">
    <div class="authorize-card">
      <div class="authorize-mark">MCP</div>

      <template v-if="phase === 'checking'">
        <h1>正在检查登录状态</h1>
        <p>请稍候，系统正在确认当前管理员会话。</p>
        <div class="authorize-loader"></div>
      </template>

      <template v-else-if="phase === 'redirecting'">
        <h1>授权成功</h1>
        <p>正在返回 MCP 客户端，请稍候。</p>
        <div class="authorize-loader"></div>
      </template>

      <template v-else>
        <h1>MCP 授权失败</h1>
        <p class="authorize-error">{{ errorMessage }}</p>
        <el-button type="primary" class="authorize-button" @click="goToLogin">重新登录</el-button>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { approveMcpAuthorization, McpOAuthAuthorizeParams } from '@/api/mcp';
import { getToken, removeToken } from '@/utils/auth';

const route = useRoute();
const router = useRouter();
const phase = ref<'checking' | 'redirecting' | 'error'>('checking');
const errorMessage = ref('');

const REQUIRED_PARAMS = [
  'response_type',
  'client_id',
  'redirect_uri',
  'code_challenge',
  'code_challenge_method'
] as const;

const queryString = (name: string): string => {
  const value = route.query[name];
  return typeof value === 'string' ? value : '';
};

const goToLogin = async () => {
  removeToken();
  await router.replace({
    path: '/login',
    query: { redirect: route.fullPath }
  });
};

const authorizeParams = (): McpOAuthAuthorizeParams => {
  const missing = REQUIRED_PARAMS.filter((name) => !queryString(name));
  if (missing.length > 0) {
    throw new Error(`授权请求缺少参数：${missing.join(', ')}`);
  }
  return {
    response_type: queryString('response_type'),
    client_id: queryString('client_id'),
    redirect_uri: queryString('redirect_uri'),
    code_challenge: queryString('code_challenge'),
    code_challenge_method: queryString('code_challenge_method'),
    scope: queryString('scope') || undefined,
    resource: queryString('resource') || undefined,
    state: queryString('state') || undefined
  };
};

const completeAuthorization = async () => {
  if (!getToken()) {
    await goToLogin();
    return;
  }

  try {
    const response = await approveMcpAuthorization(authorizeParams());
    const redirectUrl = response.data?.redirectUrl;
    if (!redirectUrl) {
      throw new Error('授权服务未返回客户端回调地址');
    }
    phase.value = 'redirecting';
    window.location.replace(redirectUrl);
  } catch (error: any) {
    const message = error?.message || '授权请求处理失败';
    if (message.includes('认证失败') || message.includes('重新登录')) {
      await goToLogin();
      return;
    }
    phase.value = 'error';
    errorMessage.value = message;
  }
};

onMounted(completeAuthorization);
</script>

<style scoped>
.authorize-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  color: #f5f5f4;
  background:
    radial-gradient(circle at 20% 15%, rgba(245, 158, 11, 0.16), transparent 34%),
    radial-gradient(circle at 85% 80%, rgba(59, 130, 246, 0.12), transparent 30%),
    #0c0a09;
}

.authorize-card {
  width: min(440px, 100%);
  padding: 42px 36px;
  text-align: center;
  border: 1px solid rgba(120, 113, 108, 0.35);
  border-radius: 24px;
  background: rgba(28, 25, 23, 0.92);
  box-shadow: 0 30px 80px rgba(0, 0, 0, 0.45);
}

.authorize-mark {
  width: 64px;
  height: 64px;
  display: grid;
  place-items: center;
  margin: 0 auto 22px;
  border-radius: 18px;
  font-size: 17px;
  font-weight: 800;
  letter-spacing: 0.08em;
  color: #1c1917;
  background: linear-gradient(135deg, #fbbf24, #f59e0b);
}

h1 {
  margin: 0 0 12px;
  font-size: 24px;
}

p {
  margin: 0;
  color: #a8a29e;
  line-height: 1.7;
}

.authorize-error {
  color: #fca5a5;
  word-break: break-word;
}

.authorize-button {
  width: 100%;
  margin-top: 24px;
}

.authorize-loader {
  width: 28px;
  height: 28px;
  margin: 28px auto 0;
  border: 3px solid rgba(245, 158, 11, 0.2);
  border-top-color: #f59e0b;
  border-radius: 999px;
  animation: authorize-spin 0.8s linear infinite;
}

@keyframes authorize-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
