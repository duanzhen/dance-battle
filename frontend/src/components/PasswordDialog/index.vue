<template>
  <GameDialog
    v-model="visible"
    width="420px"
    :title="force ? proxy.$t('passwordDialog.forceTitle') : proxy.$t('passwordDialog.title')"
    :subtitle="proxy.$t('passwordDialog.subtitle')"
    :icon="Lock"
    destroy-on-close
    dialog-class="password-dialog"
    :show-close="!force"
    :close-on-press-escape="!force"
    :close-on-click-modal="false"
    @closed="resetForm"
  >
    <div v-if="force" class="mb-4 flex items-start gap-2.5 rounded-xl border border-amber-500/30 bg-amber-500/10 px-3.5 py-3">
      <svg class="mt-0.5 h-4 w-4 shrink-0 text-amber-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path
          stroke-linecap="round"
          stroke-linejoin="round"
          stroke-width="2"
          d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z"
        />
      </svg>
      <span class="text-sm leading-5 text-amber-200">{{ proxy.$t('passwordDialog.forceTip') }}</span>
    </div>

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item v-if="!force" :label="proxy.$t('passwordDialog.oldPassword')" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password autocomplete="off">
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
      <el-form-item :label="proxy.$t('passwordDialog.newPassword')" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" show-password autocomplete="off">
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
      <el-form-item :label="proxy.$t('passwordDialog.confirmPassword')" prop="confirmPassword">
        <el-input v-model="form.confirmPassword" type="password" show-password autocomplete="off">
          <template #prefix>
            <svg class="h-4 w-4 text-neutral-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z"
              />
            </svg>
          </template>
        </el-input>
      </el-form-item>
    </el-form>
    <template #footer>
      <div class="flex justify-end gap-3">
        <button
          v-if="!force"
          type="button"
          class="rounded-lg border border-neutral-700 bg-neutral-800 px-5 py-2 text-sm font-medium text-neutral-300 transition-all hover:border-neutral-600 hover:bg-neutral-700 hover:text-neutral-200"
          @click="visible = false"
        >
          {{ proxy.$t('passwordDialog.cancel') }}
        </button>
        <button
          type="button"
          :disabled="submitting"
          class="flex items-center gap-2 rounded-lg bg-amber-600 px-5 py-2 text-sm font-bold text-white shadow-lg shadow-amber-900/30 transition-all hover:bg-amber-500 hover:shadow-amber-700/40 active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-50"
          @click="handleSubmit"
        >
          <svg v-if="submitting" class="h-4 w-4 animate-spin text-white" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
            <path
              class="opacity-75"
              fill="currentColor"
              d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
            ></path>
          </svg>
          {{ proxy.$t('passwordDialog.confirm') }}
        </button>
      </div>
    </template>
  </GameDialog>
</template>

<script setup lang="ts">
import { Lock } from 'lucide-vue-next';
import GameDialog from '@/components/GameDialog/index.vue';
import { changePassword } from '@/api/login';
import { ChangePasswordData } from '@/api/types';
import { useUserStore } from '@/store/modules/user';

const visible = ref(false);
const submitting = ref(false);
const force = ref(false);
const formRef = ref<ElFormInstance>();
const form = ref({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
});
const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const userStore = useUserStore();

const validateNewPassword = (_rule: any, value: string, callback: (error?: Error) => void) => {
  if (!force.value && value && value === form.value.oldPassword) {
    callback(new Error(proxy.$t('passwordDialog.sameAsOld')));
  } else if (value && !/^(?=.*[A-Za-z])(?=.*\d).+$/.test(value)) {
    callback(new Error(proxy.$t('passwordDialog.needLetterNumber')));
  } else {
    callback();
  }
};

const validateConfirmPassword = (_rule: any, value: string, callback: (error?: Error) => void) => {
  if (value !== form.value.newPassword) {
    callback(new Error(proxy.$t('passwordDialog.confirmNotMatch')));
  } else {
    callback();
  }
};

const rules: ElFormRules = {
  oldPassword: [{ required: true, trigger: 'blur', message: proxy.$t('passwordDialog.oldRequired') }],
  newPassword: [
    { required: true, trigger: 'blur', message: proxy.$t('passwordDialog.newRequired') },
    { min: 8, max: 32, trigger: 'blur', message: proxy.$t('passwordDialog.newLength') },
    { validator: validateNewPassword, trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, trigger: 'blur', message: proxy.$t('passwordDialog.confirmRequired') },
    { validator: validateConfirmPassword, trigger: 'blur' }
  ]
};

const resetForm = () => {
  form.value = {
    oldPassword: '',
    newPassword: '',
    confirmPassword: ''
  };
  formRef.value?.clearValidate();
};

const open = (isForce = false) => {
  force.value = isForce;
  resetForm();
  visible.value = true;
};

const handleSubmit = () => {
  formRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    submitting.value = true;
    try {
      const payload: ChangePasswordData = { newPassword: form.value.newPassword };
      if (!force.value) {
        payload.oldPassword = form.value.oldPassword;
      }
      await changePassword(payload);
      visible.value = false;
      ElMessage.success(proxy.$t('passwordDialog.success'));
      // 改密后不踢下线:登录凭证为无状态 JWT,改密后依然有效,
      // 直接留在当前页面,并同步"已非默认密码"状态,避免强制改密弹窗再次弹出
      userStore.setDefaultPassword(false);
    } catch (e) {
      // 失败提示已由响应拦截器统一弹出,此处保持弹窗打开以便重试
    } finally {
      submitting.value = false;
    }
  });
};

defineExpose({
  open
});
</script>

<style scoped>
/* 表单标签与输入框:暗色化,聚焦琥珀高亮(与登录页一致) */
:global(.password-dialog .el-form-item__label) {
  color: #a3a3a3;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.4;
}

:global(.password-dialog .el-input__wrapper) {
  background-color: rgb(23 23 23 / 0.8);
  box-shadow: 0 0 0 1px #404040 inset;
  border-radius: 0.65rem;
  transition: box-shadow 0.2s;
}

:global(.password-dialog .el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #525252 inset;
}

:global(.password-dialog .el-input__wrapper.is-focus) {
  box-shadow:
    0 0 0 1px rgb(245 158 11) inset,
    0 0 12px rgb(245 158 11 / 0.15);
}

:global(.password-dialog .el-input__inner) {
  color: #e5e5e5;
  caret-color: #f59e0b;
}

:global(.password-dialog .el-input__inner::placeholder) {
  color: #737373;
}

:global(.password-dialog .el-input__prefix) {
  display: flex;
  align-items: center;
}

:global(.password-dialog .el-input__clear),
:global(.password-dialog .el-input__password) {
  color: #737373;
}

:global(.password-dialog .el-form-item__error) {
  padding-top: 4px;
  color: #f87171;
}
</style>
