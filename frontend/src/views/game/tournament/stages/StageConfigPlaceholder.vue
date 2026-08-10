<template>
  <div class="stage-config-placeholder">
    <div
      class="bg-neutral-900 border-2 border-dashed border-neutral-800 rounded-xl p-12 text-center h-full flex flex-col items-center justify-center"
    >
      <!-- 图标 -->
      <div class="w-16 h-16 rounded-full bg-neutral-800 flex items-center justify-center mb-6">
        <Settings class="w-8 h-8 text-neutral-600" />
      </div>

      <!-- 标题 -->
      <h3 class="text-lg font-bold text-neutral-400 mb-2">准备配置赛段</h3>

      <!-- 提示文本 -->
      <p class="text-sm text-neutral-500 max-w-md mb-6">请先选择赛段类型,系统将自动加载对应的配置选项</p>

      <!-- 引导提示 -->
      <div class="flex items-center gap-2 text-xs text-neutral-600 bg-neutral-800/50 px-4 py-2 rounded-lg">
        <ArrowRight class="w-4 h-4" />
        <span>在右侧配置通用信息</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Settings, ArrowRight } from 'lucide-vue-next';
</script>

<style scoped>
.stage-config-placeholder {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
