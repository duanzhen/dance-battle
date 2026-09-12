<template>
  <el-config-provider :locale="appStore.locale" :size="appStore.size">
    <router-view />
    <!-- <password-dialog ref="passwordDialogRef" /> -->

    <!-- 微信内置浏览器提示蒙层 -->
    <div
      v-if="showWeChatTip"
      class="fixed inset-0 flex items-center justify-center bg-neutral-950/70 backdrop-blur-sm px-6"
      style="z-index: 99999"
    >
      <div class="w-full max-w-sm rounded-2xl border border-neutral-800 bg-neutral-900 p-8 text-center shadow-2xl shadow-black/60">
        <div class="mx-auto mb-5 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-b from-amber-400 to-amber-600 shadow-lg shadow-amber-500/30">
          <svg class="h-8 w-8 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607zM10.5 7.5v6m3-3h-6"
            />
          </svg>
        </div>
        <h2 class="text-lg font-bold text-white">请用默认浏览器打开</h2>
        <p class="mt-3 text-sm leading-6 text-neutral-400">
          当前为微信内置浏览器，部分功能可能无法正常使用。请点击右上角
          <span class="font-mono text-amber-400">「···」</span>
          选择
          <span class="font-bold text-amber-400">「在浏览器打开」</span>
        </p>
      </div>
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import { useSettingsStore } from '@/store/modules/settings';
import { handleThemeStyle } from '@/utils/theme';
import { useAppStore } from '@/store/modules/app';
import { useUserStore } from '@/store/modules/user';
import PasswordDialog from '@/components/PasswordDialog/index.vue';

const appStore = useAppStore();
const passwordDialogRef = ref<InstanceType<typeof PasswordDialog>>();
const showWeChatTip = ref(false);

// 检测微信内置浏览器:微信 UA 固定包含 MicroMessenger
const detectWeChatBrowser = (): boolean => {
  if (typeof navigator === 'undefined') {
    return false;
  }
  return /MicroMessenger/i.test(navigator.userAgent);
};

// 首次登录仍在使用默认密码时,全局强制弹出修改密码弹窗(不可关闭)
watch(
  () => useUserStore().defaultPassword,
  (value) => {
    if (value) {
      nextTick(() => {
        passwordDialogRef.value?.open(true);
      });
    }
  },
  { immediate: true }
);

onMounted(() => {
  nextTick(() => {
    // 初始化主题样式
    handleThemeStyle(useSettingsStore().theme);
  });
  // 微信内置浏览器:弹出蒙层提示改用默认浏览器打开
  showWeChatTip.value = detectWeChatBrowser();
});
</script>
