<template>
  <div class="w-full h-full">
    <!-- 查看模式: 显示倒计时 -->
    <div v-if="mode !== 'edit'" class="w-full h-full bg-black overflow-hidden select-none relative group">
      <div class="w-full h-full bg-neutral-700/30">
        <div :style="timerStyle" class="w-full h-full flex items-center justify-center pointer-events-none">
          {{ formattedTime }}
        </div>
      </div>

      <!-- 播放控制栏 -->
      <div
        class="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/80 to-transparent p-3 opacity-0 group-hover:opacity-100 transition-opacity duration-300 pointer-events-auto z-50"
      >
        <div class="flex items-center justify-center gap-3">
          <!-- 开始/暂停按钮 -->
          <button @click="toggleTimer" class="flex-shrink-0 text-white hover:text-amber-500 transition-colors" :title="isRunning ? '暂停' : '开始'">
            <svg v-if="isRunning" class="w-6 h-6" fill="currentColor" viewBox="0 0 24 24">
              <path d="M6 4h4v16H6V4zm8 0h4v16h-4V4z" />
            </svg>
            <svg v-else class="w-6 h-6" fill="currentColor" viewBox="0 0 24 24">
              <path d="M8 5v14l11-7z" />
            </svg>
          </button>

          <!-- 重置按钮 -->
          <button @click="resetTimer" class="flex-shrink-0 text-white hover:text-amber-500 transition-colors" title="重置">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
              />
            </svg>
          </button>
        </div>
      </div>
    </div>

    <!-- 编辑模式: 显示属性表单 -->
    <div v-else class="timer-editor space-y-4 px-2 py-4">
      <section>
        <span class="section-title">倒计时属性</span>

        <TextInput label="标题" :model-value="title" @update:model-value="$emit('update:title', $event)" placeholder="倒计时" />

        <div class="grid grid-cols-2 gap-3">
          <TextInput
            label="小时 (H)"
            :model-value="String(hours || 0)"
            @update:model-value="$emit('update:hours', parseInt($event) || 0)"
            placeholder="0"
          />

          <TextInput
            label="分钟 (M)"
            :model-value="String(minutes || 0)"
            @update:model-value="$emit('update:minutes', parseInt($event) || 0)"
            placeholder="0"
          />

          <TextInput
            label="秒 (S)"
            :model-value="String(seconds || 0)"
            @update:model-value="$emit('update:seconds', parseInt($event) || 0)"
            placeholder="0"
          />

          <TextInput
            label="毫秒 (ms)"
            :model-value="String(milliseconds || 0)"
            @update:model-value="$emit('update:milliseconds', parseInt($event) || 0)"
            placeholder="0"
          />
        </div>

        <TextInput label="字体大小" :model-value="fontSize" @update:model-value="$emit('update:fontSize', $event)" placeholder="48px" />

        <ColorInput label="文字颜色" :model-value="color" @update:model-value="$emit('update:color', $event)" />

        <SelectInput
          label="字体粗细"
          :model-value="String(fontWeight || 'bold')"
          @update:model-value="$emit('update:fontWeight', $event)"
          :options="[
            { value: 'normal', label: '正常' },
            { value: 'bold', label: '粗体' },
            { value: '100', label: 'Thin' },
            { value: '300', label: 'Light' },
            { value: '500', label: 'Medium' },
            { value: '700', label: 'Bold' },
            { value: '900', label: 'Black' }
          ]"
        />

        <ButtonGroup
          label="对齐方式"
          :model-value="textAlign || 'center'"
          @update:model-value="$emit('update:textAlign', $event)"
          :options="[
            { value: 'left', label: '左对齐' },
            { value: 'center', label: '居中' },
            { value: 'right', label: '右对齐' }
          ]"
        />

        <CheckboxGroup
          label="选项"
          :model-value="{ showTitle, showMilliseconds }"
          :options="[
            { key: 'showTitle', label: '显示标题' },
            { key: 'showMilliseconds', label: '显示毫秒' }
          ]"
          @update:item="handleOptionUpdate"
        />
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import TextInput from './common/TextInput.vue';
import ColorInput from './common/ColorInput.vue';
import SelectInput from './common/SelectInput.vue';
import ButtonGroup from './common/ButtonGroup.vue';
import CheckboxGroup from './common/CheckboxGroup.vue';

const props = defineProps<{
  title?: string;
  hours?: number;
  minutes?: number;
  seconds?: number;
  milliseconds?: number;
  fontSize?: string;
  color?: string;
  fontWeight?: string | number;
  textAlign?: 'left' | 'center' | 'right';
  showTitle?: boolean;
  showMilliseconds?: boolean;
  mode?: 'view' | 'edit';
}>();

defineEmits<{
  'update:title': [value: string];
  'update:hours': [value: number];
  'update:minutes': [value: number];
  'update:seconds': [value: number];
  'update:milliseconds': [value: number];
  'update:fontSize': [value: string];
  'update:color': [value: string];
  'update:fontWeight': [value: string | number];
  'update:textAlign': [value: 'left' | 'center' | 'right'];
  'update:showTitle': [value: boolean];
  'update:showMilliseconds': [value: boolean];
}>();

// 内部选项状态
const options = ref({
  showTitle: props.showTitle ?? false,
  showMilliseconds: props.showMilliseconds ?? false
});

const handleOptionUpdate = (key: string, value: boolean) => {
  options.value[key as keyof typeof options.value] = value;
  emit(`update:${key}` as any, value);
};

// 计算总倒计时时间(毫秒)
const totalTime = computed(() => {
  const h = (props.hours || 0) * 3600 * 1000;
  const m = (props.minutes || 0) * 60 * 1000;
  const s = (props.seconds || 0) * 1000;
  const ms = props.milliseconds || 0;
  return h + m + s + ms;
});

// 剩余时间
const remainingTime = ref(totalTime.value);
const isRunning = ref(false);

let timerInterval: number | null = null;

// 格式化时间显示
const formattedTime = computed(() => {
  const remaining = remainingTime.value;
  const isNegative = remaining < 0;

  const absRemaining = Math.abs(remaining);

  const h = Math.floor(absRemaining / (3600 * 1000));
  const m = Math.floor((absRemaining % (3600 * 1000)) / (60 * 1000));
  const s = Math.floor((absRemaining % (60 * 1000)) / 1000);
  const ms = absRemaining % 1000;

  let timeStr = '';

  if (options.value.showTitle && props.title) {
    timeStr += props.title + ' ';
  }

  const parts = [];
  if (h > 0 || props.hours) parts.push(`${h.toString().padStart(2, '0')}:`);
  parts.push(`${m.toString().padStart(2, '0')}:`);
  parts.push(`${s.toString().padStart(2, '0')}`);

  if (options.value.showMilliseconds) {
    parts.push(`.${ms.toString().padStart(3, '0')}`);
  }

  timeStr += parts.join('');

  if (isNegative) {
    timeStr = '-' + timeStr;
  }

  return timeStr;
});

// 计算样式
const timerStyle = computed(() => ({
  fontSize: props.fontSize || '48px',
  color: remainingTime.value < 0 ? '#ef4444' : props.color || '#ffffff',
  fontWeight: props.fontWeight || 'bold',
  textAlign: props.textAlign || 'center',
  padding: '8px',
  wordBreak: 'break-word' as const,
  overflowWrap: 'break-word' as const
}));

// 启动倒计时
const startTimer = () => {
  if (timerInterval) return;

  isRunning.value = true;

  const endTime = Date.now() + remainingTime.value;

  timerInterval = window.setInterval(() => {
    const now = Date.now();
    remainingTime.value = endTime - now;

    // 倒计时结束
    if (remainingTime.value <= 0) {
      remainingTime.value = 0;
      stopTimer();
      // 可以在这里触发倒计时结束事件
      console.log('Timer finished!');
    }
  }, 10); // 10ms 更新一次以支持毫秒显示
};

// 停止倒计时
const stopTimer = () => {
  if (timerInterval) {
    clearInterval(timerInterval);
    timerInterval = null;
  }
  isRunning.value = false;
};

// 重置倒计时
const resetTimer = () => {
  stopTimer();
  remainingTime.value = totalTime.value;
};

// 切换倒计时状态
const toggleTimer = () => {
  if (isRunning.value) {
    stopTimer();
  } else {
    startTimer();
  }
};

// 暴露方法
defineExpose({
  start: startTimer,
  stop: stopTimer,
  reset: resetTimer,
  isRunning: () => isRunning.value
});

// 监听时间变化,重置倒计时
watch(
  () => [props.hours, props.minutes, props.seconds, props.milliseconds],
  () => {
    remainingTime.value = totalTime.value;
    if (isRunning.value) {
      stopTimer();
      startTimer();
    }
  }
);

onUnmounted(() => {
  stopTimer();
});
</script>

<style scoped>
.section-title {
  @apply text-xs font-bold text-neutral-400 block mb-3;
}

.label {
  @apply text-[10px] font-bold text-neutral-500 uppercase tracking-wider block mb-1;
}

.input-base {
  @apply w-full bg-neutral-950 border border-neutral-800 rounded px-2 py-2 text-xs text-white focus:border-amber-500 focus:outline-none transition-colors;
}
</style>
