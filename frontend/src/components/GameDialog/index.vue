<template>
  <el-dialog
    :model-value="modelValue"
    :width="width"
    :before-close="beforeClose"
    :close-on-click-modal="closeOnClickModal"
    :close-on-press-escape="closeOnPressEscape"
    :show-close="showClose"
    :destroy-on-close="destroyOnClose"
    append-to-body
    class="game-dialog"
    :class="dialogClass"
    @update:model-value="emit('update:modelValue', $event)"
    @closed="emit('closed')"
  >
    <!-- 统一 header:图标 + 标题 + 副标题 -->
    <template #header>
      <div class="flex items-center gap-3">
        <div class="w-8 h-8 rounded-lg bg-amber-500/15 border border-amber-500/30 flex items-center justify-center shrink-0">
          <component :is="icon" v-if="icon" class="w-4 h-4 text-amber-400" />
        </div>
        <div>
          <h3 class="text-base font-bold text-white tracking-tight">{{ title }}</h3>
          <p class="text-[11px] text-neutral-500 mt-0.5">{{ subtitle }}</p>
        </div>
      </div>
    </template>

    <slot />

    <template #footer>
      <slot name="footer" />
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { defineProps, defineEmits, withDefaults } from 'vue';

const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    /** 弹窗宽度(必传,由调用方自定义,如 '600px' / 420 / '90%') */
    width: string | number;
    /** 头部主标题 */
    title: string;
    /** 头部副标题 */
    subtitle: string;
    /** 头部图标(lucide 组件) */
    icon?: unknown;
    /** 自定义 before-close(点击遮罩/关闭/ESC 时) */
    beforeClose?: (done: () => void) => void;
    /** 页面自定义类(如 tournament-dialog,保留局部样式选择器) */
    dialogClass?: string;
    closeOnClickModal?: boolean;
    closeOnPressEscape?: boolean;
    showClose?: boolean;
    destroyOnClose?: boolean;
  }>(),
  {
    closeOnClickModal: true,
    closeOnPressEscape: true,
    showClose: true,
    destroyOnClose: false
  }
);

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  closed: [];
}>();
</script>

<style>
/* 统一弹窗样式:所有 GameDialog 共用一份,避免每个页面重复定义 */
.el-overlay .game-dialog {
  background-color: #171717 !important;
  border: 1px solid #262626 !important;
  border-radius: 16px !important;
  overflow: hidden;
  box-shadow:
    0 25px 50px -12px rgba(0, 0, 0, 0.8),
    0 0 0 1px rgba(245, 158, 11, 0.04);
}

.game-dialog .el-dialog__header {
  padding: 14px 20px;
  margin-right: 0;
  border-bottom: 1px solid #262626;
  border-radius: 16px 16px 0 0;
  background: linear-gradient(180deg, rgba(245, 158, 11, 0.03) 0%, transparent 100%);
}

.game-dialog .el-dialog__body {
  padding: 12px 16px;
  color: #f5f5f5;
  background-color: #171717;
  max-height: 52vh;
  overflow-y: auto;
}

.game-dialog .el-dialog__footer {
  padding: 12px 20px;
  border-top: 1px solid #262626;
}

.game-dialog .el-dialog__headerbtn {
  top: 20px;
  right: 20px;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  transition: all 0.2s ease;
}

.game-dialog .el-dialog__headerbtn:hover {
  background-color: rgba(255, 255, 255, 0.06);
}

.game-dialog .el-dialog__close {
  color: #737373 !important;
  font-size: 18px;
}

.game-dialog .el-dialog__headerbtn:hover .el-dialog__close {
  color: #f5f5f5 !important;
}

.el-overlay:has(.game-dialog) {
  background-color: rgba(0, 0, 0, 0.7) !important;
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}

/* 兼容不支持 :has() 的浏览器 */
.game-dialog ~ .el-overlay {
  background-color: rgba(0, 0, 0, 0.7) !important;
}
</style>
