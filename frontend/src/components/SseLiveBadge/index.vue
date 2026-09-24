<!--
  SSE 连接状态标识(导播台/裁判端等页面右上角统一使用)。

  三态与颜色一一对应:
  - open       :已连上且最近 20s 内收到过心跳 → 绿色实心;
  - connecting :正在建连 / 等待首次心跳 / 断开后等待重试 → 黄色呼吸;
  - error      :通道已关闭、不再重连(订阅方全部取消)→ 红色实心。

  状态本身由 @/utils/sseChannel 统一推导,本组件只负责呈现,保证各页面一致。
  默认带文字(导播台/裁判端);dot-only 只渲染一个圆点(屏幕监视器等不需要文字的位置)。
-->
<template>
  <!-- 只要圆点:仅靠颜色 + 黄色呼吸指示状态 -->
  <div
    v-if="dotOnly"
    class="w-[3px] h-[3px] rounded-full"
    :class="[state.dot, state.glow, status === 'connecting' ? 'sse-breathing' : '']"
    :title="state.label"
  ></div>
  <!-- 默认:圆点 + 文字 -->
  <div v-else class="flex items-center gap-1.5 px-2 py-1 rounded text-[10px] font-bold border" :class="state.badge">
    <span class="w-1.5 h-1.5 rounded-full" :class="[state.dot, status === 'connecting' ? 'sse-breathing' : '']"></span>
    {{ state.label }}
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { SseStatus } from '@/utils/sseChannel';

const props = withDefaults(defineProps<{ status: SseStatus; dotOnly?: boolean }>(), {
  dotOnly: false
});

const STATE: Record<SseStatus, { label: string; badge: string; dot: string; glow: string }> = {
  open: {
    label: 'LIVE',
    badge: 'bg-green-600/20 text-green-400 border-green-600/30',
    dot: 'bg-green-500',
    glow: 'shadow-[0_0_4px_rgba(34,197,94,0.6)]'
  },
  connecting: {
    label: '连接中',
    badge: 'bg-amber-500/20 text-amber-400 border-amber-500/30',
    dot: 'bg-amber-400',
    glow: 'shadow-[0_0_4px_rgba(251,191,36,0.6)]'
  },
  error: {
    label: '连接失败',
    badge: 'bg-red-600/20 text-red-400 border-red-600/30',
    dot: 'bg-red-500',
    glow: 'shadow-[0_0_4px_rgba(239,68,68,0.6)]'
  }
};

const state = computed(() => STATE[props.status] ?? STATE.error);
</script>

<style scoped>
/* 黄色呼吸:透明度 + 缩放的往复动画,表示"正在尝试连接" */
@keyframes sse-breathing {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.3;
    transform: scale(0.75);
  }
}

.sse-breathing {
  animation: sse-breathing 1.2s ease-in-out infinite;
}
</style>
