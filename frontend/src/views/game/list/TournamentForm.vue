<template>
  <el-dialog
    v-model="visible"
    :width="dialogWidth"
    :before-close="handleClose"
    destroy-on-close
    class="tournament-dialog"
    append-to-body
  >
    <template #header>
      <div class="flex items-center gap-3">
        <div class="w-8 h-8 rounded-lg bg-amber-500/15 border border-amber-500/30 flex items-center justify-center shrink-0">
          <svg class="w-4 h-4 text-amber-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
          </svg>
        </div>
        <div>
          <h3 class="text-base font-bold text-white tracking-tight">{{ title }}</h3>
          <p class="text-[11px] text-neutral-500 mt-0.5">填写赛事基础信息与模版</p>
        </div>
      </div>
    </template>

    <div class="space-y-3 py-1">
      <div>
        <label class="block text-sm font-medium text-neutral-400 mb-2">赛事封面</label>
        <div
          @click="!isUploadingCover && triggerFileInput()"
          @dragover.prevent="isDragging = true"
          @dragleave.prevent="isDragging = false"
          @drop.prevent="handleDrop"
          :class="[
            'relative h-24 w-full rounded-xl border-2 border-dashed transition-all duration-300 flex flex-col items-center justify-center cursor-pointer overflow-hidden group',
            isDragging ? 'border-amber-500 bg-amber-500/10' : 'border-neutral-700 bg-neutral-950 hover:border-neutral-500 hover:bg-neutral-800'
          ]"
        >
          <input type="file" ref="fileInput" class="hidden" accept="image/*" @change="handleFileChange" />

          <div v-if="previewUrl" class="absolute inset-0 w-full h-full">
            <img :src="previewUrl" class="w-full h-full object-cover" alt="Preview" />
            <div
              class="absolute inset-0 bg-black/60 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center text-white font-medium backdrop-blur-sm"
            >
              <svg class="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"
                />
              </svg>
              更换
            </div>
          </div>

          <div v-else class="text-center p-4">
            <div
              class="w-10 h-10 rounded-full bg-neutral-800 flex items-center justify-center mx-auto mb-2 group-hover:scale-110 transition-transform"
            >
              <svg
                class="w-5 h-5 text-neutral-400 group-hover:text-amber-500 transition-colors"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"
                />
              </svg>
            </div>
            <p class="text-xs text-neutral-400">{{ isUploadingCover ? '上传中...' : '点击或拖拽上传' }}</p>
          </div>
        </div>
      </div>

      <div>
        <label class="block text-sm font-medium text-neutral-400 mb-1.5"> 赛事名称 <span class="text-red-500">*</span> </label>
        <input
          v-model="form.name"
          type="text"
          placeholder="例如：2024 冬季王者争霸赛"
          class="w-full bg-neutral-950 border border-neutral-800 rounded-lg px-3 py-2.5 text-white placeholder-neutral-600 focus:outline-none focus:border-amber-500 focus:ring-1 focus:ring-amber-500 transition-all text-sm"
        />
      </div>

      <div>
        <label class="block text-sm font-medium text-neutral-400 mb-1.5">赛事状态</label>
        <div class="relative">
          <select
            v-model.number="form.status"
            class="w-full bg-neutral-950 border border-neutral-800 rounded-lg px-3 py-2.5 text-white focus:outline-none focus:border-amber-500 focus:ring-1 focus:ring-amber-500 transition-all appearance-none text-sm"
          >
            <option :value="0">筹备中</option>
            <option :value="1">进行中</option>
            <option :value="2">已结束</option>
          </select>
          <div class="pointer-events-none absolute inset-y-0 right-0 flex items-center px-2 text-neutral-500">
            <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
            </svg>
          </div>
        </div>
      </div>

      <div v-if="!props.tournament">
        <label class="block text-sm font-medium text-neutral-400 mb-1.5">裁判数量</label>
        <input
          v-model.number="form.refereeCount"
          type="number"
          min="0"
          max="100"
          placeholder="0"
          class="w-full bg-neutral-950 border border-neutral-800 rounded-lg px-3 py-2.5 text-white placeholder-neutral-600 focus:outline-none focus:border-amber-500 focus:ring-1 focus:ring-amber-500 transition-all text-sm"
        />
        <p class="text-[11px] text-neutral-600 mt-1">自动创建对应数量的裁判；按模版创建时会自动绑定到所有赛段</p>
      </div>

      <div v-if="!simple" class="grid grid-cols-2 gap-4">
        <div>
          <label class="block text-sm font-medium text-neutral-400 mb-1.5">设计稿宽度</label>
          <input
            v-model.number="form.logicalWidth"
            type="number"
            placeholder="1920"
            class="w-full bg-neutral-950 border border-neutral-800 rounded-lg px-3 py-2.5 text-white placeholder-neutral-600 focus:outline-none focus:border-amber-500 focus:ring-1 focus:ring-amber-500 transition-all text-sm"
          />
        </div>
        <div>
          <label class="block text-sm font-medium text-neutral-400 mb-1.5">设计稿高度</label>
          <input
            v-model.number="form.logicalHeight"
            type="number"
            placeholder="1080"
            class="w-full bg-neutral-950 border border-neutral-800 rounded-lg px-3 py-2.5 text-white placeholder-neutral-600 focus:outline-none focus:border-amber-500 focus:ring-1 focus:ring-amber-500 transition-all text-sm"
          />
        </div>
      </div>

      <div v-if="!simple">
        <label class="block text-sm font-medium text-neutral-400 mb-1.5">备注</label>
        <textarea
          v-model="form.remark"
          rows="3"
          placeholder="规则、奖金等..."
          class="w-full bg-neutral-950 border border-neutral-800 rounded-lg px-3 py-2.5 text-white placeholder-neutral-600 focus:outline-none focus:border-amber-500 focus:ring-1 focus:ring-amber-500 transition-all resize-none text-sm"
        ></textarea>
      </div>

      <!-- 按模版创建:自动生成赛段链 + 场景 + 对战树关联 -->
      <div v-if="!props.tournament" class="border-t border-neutral-800 pt-4">
        <label class="block text-sm font-medium text-neutral-400 mb-1.5">按模版创建</label>
        <p class="text-[11px] text-neutral-600 mb-3">点击模版先选中，再点底部「立即发布」即可创建赛段链（海选 + 淘汰赛）与两个场景（主视觉 / 对战），并自动关联对战树。</p>
        <div class="grid grid-cols-1 gap-2">
          <button
            v-for="tpl in templates"
            :key="tpl.code"
            @click="selectedTemplate = selectedTemplate === tpl.code ? null : tpl.code"
            :disabled="isSubmitting"
            :class="[
              'text-left w-full rounded-xl border transition-all px-3.5 py-3 group disabled:opacity-50 disabled:cursor-not-allowed',
              selectedTemplate === tpl.code
                ? 'border-amber-500 bg-amber-500/10 ring-1 ring-amber-500/40'
                : 'border-neutral-800 bg-neutral-950 hover:border-amber-500/50 hover:bg-neutral-900/80 active:scale-[0.99]'
            ]"
          >
            <div class="flex items-center justify-between mb-1.5">
              <span class="text-xs font-bold text-neutral-200 group-hover:text-amber-400 transition-colors">{{ tpl.label }}</span>
              <span class="flex items-center gap-1.5">
                <span
                  class="text-[9px] px-2 py-0.5 rounded-full transition-colors"
                  :class="selectedTemplate === tpl.code
                    ? 'bg-amber-500/15 text-amber-400'
                    : 'bg-neutral-800 text-neutral-400 group-hover:bg-amber-500/10 group-hover:text-amber-400'"
                >{{ tpl.tag }}</span>
                <svg
                  v-if="selectedTemplate === tpl.code"
                  class="w-4 h-4 text-amber-400"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M5 13l4 4L19 7" />
                </svg>
              </span>
            </div>
            <div class="flex items-center gap-1 text-[10px] text-neutral-500 font-mono flex-wrap">
              <template v-for="(s, i) in tpl.stages" :key="i">
                <span class="px-1.5 py-0.5 rounded bg-neutral-800/80">{{ s }}</span>
                <span v-if="i < tpl.stages.length - 1" class="text-neutral-700">→</span>
              </template>
            </div>
          </button>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2.5 pt-2">
        <button
          @click="handleClose"
          class="px-4 py-2 rounded-lg text-sm font-medium text-neutral-300 hover:text-neutral-200 bg-neutral-800 hover:bg-neutral-700 border border-neutral-700 hover:border-neutral-600 transition-all"
        >
          取消
        </button>
        <button
          @click="submitForm"
          :disabled="isSubmitting"
          class="px-5 py-2 bg-amber-600 hover:bg-amber-500 text-white text-sm rounded-lg font-bold shadow-lg shadow-amber-900/30 hover:shadow-amber-700/40 active:scale-[0.98] transition-all flex items-center disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:scale-100"
        >
          <svg v-if="isSubmitting" class="animate-spin -ml-1 mr-2 h-4 w-4 text-white" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
            <path
              class="opacity-75"
              fill="currentColor"
              d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
            ></path>
          </svg>
          {{ isSubmitting ? '提交中...' : props.tournament ? '保存' : '立即发布' }}
        </button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue';
import { addTournament, updateTournament, createTournamentByTemplate } from '@/api/game/tournament';
import request from '@/utils/request';
import { ElMessage } from 'element-plus';

const props = defineProps({
  modelValue: Boolean,
  // 编辑模式传入的赛事数据
  tournament: {
    type: Object,
    default: null
  },
  // 精简模式:只允许编辑封面/名称/状态
  simple: {
    type: Boolean,
    default: false
  }
});

const emit = defineEmits(['update:modelValue', 'submit-success']);

// 标题（创建/编辑）
const title = computed(() => (props.tournament ? '编辑赛事' : '创建新赛事'));

// 控制显示隐藏
const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
});

// 响应式宽度 (模拟 Tailwind md 断点)
const dialogWidth = ref('560px');
const updateWidth = () => {
  dialogWidth.value = window.innerWidth < 640 ? '90%' : '560px';
};
onMounted(() => {
  window.addEventListener('resize', updateWidth);
  updateWidth();
});
onUnmounted(() => window.removeEventListener('resize', updateWidth));

// --- 表单数据（后端字段）---
const form = reactive({
  name: '', // 赛事名称
  coverImage: '', // 封面图片 URL
  status: 0, // 0:筹备 1:进行中 2:结束
  logicalWidth: 1920, // 设计稿宽度
  logicalHeight: 1080, // 设计稿高度
  themeConfig: '', // 主题配置 JSON 字符串
  remark: '', // 备注
  refereeCount: 0 // 自动创建裁判数量
});

// 文件上传相关
const fileInput = ref(null);
const previewUrl = ref(null);
const isDragging = ref(false);
const isSubmitting = ref(false);
const isUploadingCover = ref(false);
// 当前选中的模版编码(仅选中,点击「立即发布」才提交创建)
const selectedTemplate = ref(null);

// 赛事模版:编码 → 展示信息(后端按编码创建赛段链/场景/对战树)
const templates = [
  {
    code: 'AUDITION_32',
    label: '32人淘汰赛',
    tag: '推荐',
    stages: ['海选', '32强', '16强', '8强', '半决赛', '决赛']
  },
  {
    code: 'AUDITION_16',
    label: '16人淘汰赛',
    tag: '精简',
    stages: ['海选', '16强', '8强', '半决赛', '决赛']
  },
  {
    code: 'AUDITION_ARENA',
    label: '32人擂台赛',
    tag: '擂台',
    stages: ['海选', '32强', '16强', '擂台赛']
  }
];

// 触发文件选择
const triggerFileInput = () => fileInput.value.click();

// 处理文件选择:本地预览 + 上传到服务器
const handleFile = async (file) => {
  if (!file || !file.type.startsWith('image/')) {
    ElMessage.warning('封面仅支持图片文件');
    return;
  }
  // 先本地预览,上传成功后再替换为服务器地址
  previewUrl.value = URL.createObjectURL(file);
  isUploadingCover.value = true;
  try {
    const formData = new FormData();
    formData.append('file', file);
    const res = await request({
      url: '/resource/oss/upload',
      method: 'post',
      data: formData
    });
    if (res.code === 200 && res.data?.url) {
      form.coverImage = res.data.url;
      previewUrl.value = res.data.url;
    } else {
      ElMessage.error(res?.msg || '封面上传失败');
      previewUrl.value = null;
    }
  } catch (error) {
    console.error('封面上传失败:', error);
    ElMessage.error('封面上传失败');
    previewUrl.value = null;
  } finally {
    isUploadingCover.value = false;
    if (fileInput.value) {
      fileInput.value.value = '';
    }
  }
};

const handleFileChange = (e) => handleFile(e.target.files[0]);
const handleDrop = (e) => {
  isDragging.value = false;
  handleFile(e.dataTransfer.files[0]);
};

// 重置表单
const resetForm = () => {
  Object.assign(form, {
    name: '',
    coverImage: '',
    status: 0,
    logicalWidth: 1920,
    logicalHeight: 1080,
    themeConfig: '',
    remark: '',
    refereeCount: 0
  });
  if (previewUrl.value && !previewUrl.value.startsWith('http')) {
    URL.revokeObjectURL(previewUrl.value);
  }
  previewUrl.value = null;
  selectedTemplate.value = null;
};

// 监听 props 变化，编辑时回填数据
watch(
  () => props.tournament,
  (newTournament) => {
    if (newTournament) {
      Object.assign(form, {
        name: newTournament.name || '',
        coverImage: newTournament.coverImage || '',
        status: newTournament.status ?? 0,
        logicalWidth: newTournament.logicalWidth || 1920,
        logicalHeight: newTournament.logicalHeight || 1080,
        themeConfig: newTournament.themeConfig || '',
        remark: newTournament.remark || ''
      });
      // 设置预览图
      previewUrl.value = newTournament.coverImage || null;
    } else {
      // 重置表单
      resetForm();
    }
  },
  { immediate: true }
);

// 提交表单
const submitForm = async () => {
  // 验证必填字段
  if (!form.name) {
    ElMessage.warning('请输入赛事名称');
    return;
  }

  isSubmitting.value = true;

  try {
    // 构建主题配置 JSON
    const themeConfig = form.themeConfig ? JSON.parse(form.themeConfig) : { bgColor: '#000000', fontFamily: 'Roboto' };

    const submitData = {
      ...form,
      themeConfig: JSON.stringify(themeConfig)
    };

    if (props.tournament?.id) {
      // 编辑模式
      await updateTournament({ ...submitData, id: props.tournament.id });
      ElMessage.success('赛事更新成功');
    } else if (selectedTemplate.value) {
      // 按模版创建:自动生成赛段链 + 场景 + 对战树关联
      await createTournamentByTemplate({
        name: form.name,
        templateCode: selectedTemplate.value,
        remark: form.remark,
        refereeCount: form.refereeCount || 0
      });
      ElMessage.success('赛事模版创建成功');
    } else {
      // 创建模式
      await addTournament(submitData);
      ElMessage.success('赛事创建成功');
    }

    // 通知父组件刷新列表
    emit('submit-success');

    // 重置并关闭
    visible.value = false;
    resetForm();
  } catch (error) {
    console.error('提交失败:', error);
    ElMessage.error(props.tournament ? '更新赛事失败' : '创建赛事失败');
  } finally {
    isSubmitting.value = false;
  }
};

const handleClose = () => {
  visible.value = false;
};
</script>

<style scoped>
/* --- 核心：覆盖 Element Plus 默认样式 (使用 :global 配合 class 选择器更可靠) --- */

/* 1. 弹窗主体背景 + 边框 + 圆角 */
:global(.el-overlay .tournament-dialog) {
  background-color: #171717 !important;
  border: 1px solid #262626 !important;
  border-radius: 16px !important;
  overflow: hidden;
  box-shadow:
    0 25px 50px -12px rgba(0, 0, 0, 0.8),
    0 0 0 1px rgba(245, 158, 11, 0.04);
}

/* 2. 头部区域 */
:global(.tournament-dialog .el-dialog__header) {
  padding: 14px 20px;
  margin-right: 0;
  border-bottom: 1px solid #262626;
  background: linear-gradient(180deg, rgba(245, 158, 11, 0.03) 0%, transparent 100%);
}

/* 3. 内容区域 */
:global(.tournament-dialog .el-dialog__body) {
  padding: 12px 16px;
  color: #f5f5f5;
  background-color: #171717;
  max-height: 52vh;
  overflow-y: auto;
}

/* 4. 底部区域 */
:global(.tournament-dialog .el-dialog__footer) {
  padding: 12px 20px;
  border-top: 1px solid #262626;
}

/* 5. 关闭按钮:默认灰色,hover 时高亮 */
:global(.tournament-dialog .el-dialog__headerbtn) {
  top: 20px;
  right: 20px;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  transition: all 0.2s ease;
}

:global(.tournament-dialog .el-dialog__headerbtn:hover) {
  background-color: rgba(255, 255, 255, 0.06);
}

:global(.tournament-dialog .el-dialog__close) {
  color: #737373 !important;
  font-size: 18px;
}

:global(.tournament-dialog .el-dialog__headerbtn:hover .el-dialog__close) {
  color: #f5f5f5 !important;
}

/* 6. 遮罩层:更深的背景 + 轻微模糊 */
:global(.el-overlay:has(.tournament-dialog)) {
  background-color: rgba(0, 0, 0, 0.7) !important;
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}

/* 兼容不支持 :has() 的浏览器 */
:global(.tournament-dialog ~ .el-overlay) {
  background-color: rgba(0, 0, 0, 0.7) !important;
}
</style>
