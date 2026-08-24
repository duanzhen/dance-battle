<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑选手' : '添加选手'"
    :width="dialogWidth"
    :before-close="handleClose"
    destroy-on-close
    class="player-dialog"
    append-to-body
  >
    <div class="space-y-5 py-2">
      <!-- 头像上传区域 -->
      <div>
        <label class="block text-sm font-medium text-neutral-400 mb-2">选手头像</label>
        <PortraitMatting ref="mattingRef" v-model="formData.avatar" />
      </div>

      <!-- 选手名称 -->
      <div>
        <label class="block text-sm font-medium text-neutral-400 mb-1.5"> 选手名称 <span class="text-red-500">*</span> </label>
        <input
          v-model="formData.name"
          type="text"
          placeholder="请输入选手名称"
          maxlength="50"
          class="w-full bg-neutral-950 border border-neutral-800 rounded-lg px-3 py-2.5 text-white placeholder-neutral-600 focus:outline-none focus:border-amber-500 focus:ring-1 focus:ring-amber-500 transition-all text-sm"
        />
      </div>

      <!-- 身份标识 -->
      <div>
        <label class="block text-sm font-medium text-neutral-400 mb-1.5">身份标识</label>
        <input
          v-model="formData.idCard"
          type="text"
          placeholder="请输入身份证号/唯一标识"
          maxlength="30"
          class="w-full bg-neutral-950 border border-neutral-800 rounded-lg px-3 py-2.5 text-white placeholder-neutral-600 focus:outline-none focus:border-amber-500 focus:ring-1 focus:ring-amber-500 transition-all text-sm"
        />
      </div>

      <!-- 标签 -->
      <div>
        <label class="block text-sm font-medium text-neutral-400 mb-1.5">标签</label>
        <div class="relative">
          <select
            v-model="selectedTag"
            @change="handleTagAdd"
            class="w-full bg-neutral-950 border border-neutral-800 rounded-lg px-3 py-2.5 text-white focus:outline-none focus:border-amber-500 focus:ring-1 focus:ring-amber-500 transition-all appearance-none text-sm"
          >
            <option value="">选择标签添加</option>
            <option v-for="tag in commonTags" :key="tag" :value="tag">{{ tag }}</option>
          </select>
          <div class="pointer-events-none absolute inset-y-0 right-0 flex items-center px-2 text-neutral-500">
            <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
            </svg>
          </div>
        </div>
        <!-- 已选标签展示 -->
        <div v-if="formData.tags.length > 0" class="flex flex-wrap gap-2 mt-2">
          <span
            v-for="(tag, index) in formData.tags"
            :key="index"
            class="inline-flex items-center gap-1 px-2.5 py-1 rounded-md bg-amber-500/20 text-amber-400 text-xs font-medium"
          >
            {{ tag }}
            <button @click="removeTag(index)" class="hover:text-amber-300 transition-colors">
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </span>
        </div>
      </div>

      <!-- 备注 -->
      <div>
        <label class="block text-sm font-medium text-neutral-400 mb-1.5">备注</label>
        <textarea
          v-model="formData.remark"
          rows="3"
          placeholder="请输入备注信息"
          maxlength="200"
          class="w-full bg-neutral-950 border border-neutral-800 rounded-lg px-3 py-2.5 text-white placeholder-neutral-600 focus:outline-none focus:border-amber-500 focus:ring-1 focus:ring-amber-500 transition-all resize-none text-sm"
        ></textarea>
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-3 pt-2">
        <button @click="handleClose" class="px-4 py-2 rounded-lg text-sm text-neutral-400 hover:text-white hover:bg-neutral-800 transition-all">
          取消
        </button>
        <button
          @click="handleSubmit"
          :disabled="submitting"
          class="px-5 py-2 bg-amber-600 hover:bg-amber-500 text-white text-sm rounded-lg font-bold shadow-lg shadow-amber-900/20 transition-all flex items-center disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <svg v-if="submitting" class="animate-spin -ml-1 mr-2 h-4 w-4 text-white" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
            <path
              class="opacity-75"
              fill="currentColor"
              d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
            ></path>
          </svg>
          {{ submitting ? '提交中...' : isEdit ? '保存' : '添加' }}
        </button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import { ElMessage } from 'element-plus';
import { PlayerVO } from '@/api/game/player/types';
import PortraitMatting from './PortraitMatting.vue';

const props = defineProps<{
  modelValue: boolean;
  player?: PlayerVO | null;
  tournamentId?: string | number;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  submit: [data: Omit<typeof formData.value, 'tags'> & { tags?: string | string[] }];
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
});

const isEdit = computed(() => !!props.player?.id);

// 响应式宽度
const dialogWidth = ref('610px');
const updateWidth = () => {
  dialogWidth.value = window.innerWidth < 640 ? '90%' : '610px';
};
onMounted(() => {
  window.addEventListener('resize', updateWidth);
  updateWidth();
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateWidth);
});

const submitting = ref(false);
const showImageViewer = ref(false);
const selectedTag = ref('');
const mattingRef = ref<InstanceType<typeof PortraitMatting> | null>(null);

const formData = ref<{
  tournamentId?: string | number;
  id?: string | number;
  name: string;
  avatar: string;
  idCard: string | number;
  tags: string[];
  remark: string;
}>({
  tournamentId: props.tournamentId,
  name: '',
  avatar: '',
  idCard: '',
  tags: [],
  remark: ''
});

const commonTags = ['种子', 'GUEST', '队长', '替补', '新人'];

// 标签操作
const handleTagAdd = () => {
  if (selectedTag.value && !formData.value.tags.includes(selectedTag.value)) {
    formData.value.tags.push(selectedTag.value);
  }
  selectedTag.value = '';
};

const removeTag = (index: number) => {
  formData.value.tags.splice(index, 1);
};

const resetForm = () => {
  formData.value = {
    tournamentId: props.tournamentId,
    name: '',
    avatar: '',
    idCard: '',
    tags: [],
    remark: ''
  };
  selectedTag.value = '';
};

const handleClose = () => {
  resetForm();
  visible.value = false;
};

const handleSubmit = async () => {
  // 验证必填字段
  if (!formData.value.name || formData.value.name.trim().length < 2) {
    ElMessage.warning('请输入选手名称（至少2个字符）');
    return;
  }

  submitting.value = true;

  try {
    // 先调用 PortraitMatting 的 exportImage 方法，等待上传完成
    if (mattingRef.value?.hasProcessedImg) {
      const avatarUrl = await mattingRef.value.exportImage();
      if (!avatarUrl) {
        ElMessage.error('头像上传失败，请重试');
        return;
      }
      formData.value.avatar = avatarUrl;
    }

    emit('submit', formData.value);
  } finally {
    submitting.value = false;
  }
};

watch(
  () => props.player,
  (player) => {
    if (player) {
      formData.value = {
        id: player.id,
        tournamentId: player.tournamentId,
        name: player.name,
        avatar: player.avatar,
        idCard: player.idCard,
        tags: player.tags ? (typeof player.tags === 'string' ? JSON.parse(player.tags) : player.tags) : [],
        remark: player.remark
      };
    } else {
      resetForm();
    }
  },
  { immediate: true }
);

watch(
  () => props.tournamentId,
  (tournamentId) => {
    formData.value.tournamentId = tournamentId;
  }
);

// 监听对话框关闭，重置表单
watch(visible, (val) => {
  if (!val) {
    resetForm();
  }
});

defineExpose({
  resetForm
});
</script>

<style>
/* --- 核心：覆盖 Element Plus 默认样式和全局样式 --- */
/* 这些样式必须是全局的（非 scoped），因为 el-dialog 使用 append-to-body */

/* 针对 player-dialog 的弹窗样式，覆盖全局样式 */
.el-overlay .player-dialog {
  background-color: #171717 !important;
  border: 1px solid #262626 !important;
  border-radius: 16px !important;
  box-shadow: 0 25px 50px -12px rgb(0 0 0 / 0.25) !important;
  overflow: hidden !important;
}

.el-overlay .player-dialog .el-dialog__header {
  padding: 24px !important;
  padding-bottom: 24px !important;
  border-bottom: 1px solid #262626 !important;
  margin-right: 0 !important;
  background-color: #171717 !important;
}

.el-overlay .player-dialog .el-dialog__body {
  padding: 24px !important;
  padding-top: 16px !important;
  padding-bottom: 16px !important;
  color: #f5f5f5 !important;
  background-color: #171717 !important;
}

.el-overlay .player-dialog .el-dialog__footer {
  padding: 16px 24px !important;
  border-top: 1px solid #262626 !important;
  background-color: rgba(9, 9, 9, 0.3) !important;
}
</style>
