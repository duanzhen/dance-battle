<template>
  <el-dialog
    v-model="visible"
    :title="proxy.$t('passwordDialog.title')"
    width="420px"
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
    @closed="resetForm"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item :label="proxy.$t('passwordDialog.oldPassword')" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password autocomplete="off" />
      </el-form-item>
      <el-form-item :label="proxy.$t('passwordDialog.newPassword')" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" show-password autocomplete="off" />
      </el-form-item>
      <el-form-item :label="proxy.$t('passwordDialog.confirmPassword')" prop="confirmPassword">
        <el-input v-model="form.confirmPassword" type="password" show-password autocomplete="off" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">{{ proxy.$t('passwordDialog.cancel') }}</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">
        {{ proxy.$t('passwordDialog.confirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { changePassword } from '@/api/login';
import { useUserStore } from '@/store/modules/user';
import router from '@/router';

const visible = ref(false);
const submitting = ref(false);
const formRef = ref<ElFormInstance>();
const form = ref({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
});
const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const userStore = useUserStore();

const validateNewPassword = (_rule: any, value: string, callback: (error?: Error) => void) => {
  if (value && value === form.value.oldPassword) {
    callback(new Error(proxy.$t('passwordDialog.sameAsOld')));
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
    { min: 6, max: 32, trigger: 'blur', message: proxy.$t('passwordDialog.newLength') },
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

const open = () => {
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
      await changePassword({
        oldPassword: form.value.oldPassword,
        newPassword: form.value.newPassword
      });
      visible.value = false;
      ElMessage.success(proxy.$t('passwordDialog.success'));
      await userStore.logout();
      router.replace({
        path: '/login',
        query: {
          redirect: encodeURIComponent(router.currentRoute.value.fullPath || '/')
        }
      });
      proxy?.$tab.closeAllPage();
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
