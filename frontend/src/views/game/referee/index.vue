<template>
  <div class="fixed inset-0 bg-neutral-950 text-neutral-200 flex items-center justify-center">
    <div class="text-center">
      <div class="w-12 h-12 mx-auto mb-3 rounded-full bg-amber-500/10 border border-amber-500/30 flex items-center justify-center">
        <span class="text-amber-400 font-bold text-lg">R</span>
      </div>
      <p class="text-sm text-neutral-400 mb-2">正在跳转到裁判判罚界面...</p>
      <p v-if="error" class="text-xs text-red-400">{{ error }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const error = ref('');

onMounted(() => {
  const authKey = route.query.authKey;
  if (!authKey || Array.isArray(authKey)) {
    error.value = '缺少认证密钥，请从裁判管理页扫描二维码进入';
    return;
  }
  // 旧入口统一跳转到真实裁判打分页
  router.replace({
    path: '/tournament/referee-scoring',
    query: { authKey: authKey as string }
  });
});
</script>

<style scoped>
</style>
