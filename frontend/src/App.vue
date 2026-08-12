<template>
  <el-config-provider :locale="appStore.locale" :size="appStore.size">
    <router-view />
    <password-dialog ref="passwordDialogRef" />
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
});
</script>
