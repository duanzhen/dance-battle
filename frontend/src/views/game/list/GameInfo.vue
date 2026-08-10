<template>
  <div class="space-y-8 animate-fade-in">
    <div class="bg-neutral-900/50 border border-neutral-800 rounded-xl p-6">
      <div class="flex items-center justify-between mb-6">
        <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider flex items-center gap-2"><Info class="w-4 h-4" /> 基础概况</h3>
        <!-- 右上角操作区(如编辑按钮),由使用方通过 actions 插槽注入 -->
        <slot name="actions" />
      </div>

      <!-- 只读展示赛事信息:编辑请使用右上角「编辑」弹窗 -->
      <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div class="space-y-1">
          <label class="text-xs text-neutral-500 font-semibold">赛事名称</label>
          <p class="text-base font-bold text-neutral-100 truncate">{{ internalValue.title || '未命名' }}</p>
        </div>
        <div class="space-y-1">
          <label class="text-xs text-neutral-500 font-semibold">赛事状态</label>
          <div class="pt-1">
            <span class="inline-block text-xs font-bold px-2 py-0.5 rounded-full" :class="statusClass">{{ statusText }}</span>
          </div>
        </div>
        <div class="space-y-1">
          <label class="text-xs text-neutral-500 font-semibold">备注</label>
          <p class="text-sm text-neutral-300 break-all">{{ internalValue.remark || '-' }}</p>
        </div>
      </div>

      <!-- 卡片底部扩展区(如裁判横排),由使用方通过 footer 插槽注入 -->
      <div v-if="$slots.footer" class="mt-4 pt-4 border-t border-neutral-800">
        <slot name="footer" />
      </div>
    </div>

    
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { Info } from 'lucide-vue-next';

// 定义新的数据接口结构
export interface MatchInfo {
  title: string;
  startTime: string;
  location: string;
  /** 封面图片URL */
  coverImage?: string;
  /** 0:筹备 1:进行中 2:结束 */
  status?: number;
  /** 备注 */
  remark?: string;
  /** 设计稿宽度 */
  logicalWidth?: number;
  /** 设计稿高度 */
  logicalHeight?: number;
  // 新增 stats 对象
  stats: {
    prizePool: string;
    attendance: number;
    onlineHeat: string;
    progress: number;
  };
}

const props = defineProps<{
  modelValue: MatchInfo;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: MatchInfo): void;
}>();

const internalValue = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
});

const statusText = computed(() => {
  const map: Record<number, string> = { 0: '筹备中', 1: '进行中', 2: '已结束' };
  return map[internalValue.value.status ?? 0] || '筹备中';
});

const statusClass = computed(() => {
  const status = internalValue.value.status ?? 0;
  if (status === 1) return 'bg-green-500/15 text-green-400 border border-green-500/30';
  if (status === 2) return 'bg-neutral-800 text-neutral-400 border border-neutral-700';
  return 'bg-amber-500/10 text-amber-400 border border-amber-500/20';
});
</script>

<style scoped>
/* 简单的加载动画 */
.animate-fade-in {
  animation: fadeIn 0.4s ease-out;
}
@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
