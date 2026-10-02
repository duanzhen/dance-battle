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
        v-if="canControl"
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
import { cssSize } from '@/utils/cssSize';

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
  mode?: 'view' | 'edit' | 'render' | 'thumbnail';
  /**
   * 是否展示开始/暂停/重置控制。只有管理端编辑画布传 true;大屏投射端只读,未传或 false
   * 时只显示倒计时。
   */
  canControl?: boolean;
  /** 计时中:结束时间戳(ms)。开始计时时写入配置,刷新/换窗口后据此续跑 */
  endAt?: number | string | null;
  /** 暂停时保存的剩余毫秒(不改动 hours/minutes/seconds 的计划时长) */
  remainMs?: number | string | null;
  /** 组件ID:由 SceneRenderer 下发,用于把计时状态写回配置 */
  widgetId?: string | number;
}>();

const emit = defineEmits<{
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
  /** 计时状态回写:endAt=结束时间戳(暂停/重置传 null) */
  'update:endAt': [value: number | null];
  /** 剩量回写:暂停时保存剩余毫秒(计时中/重置传 null) */
  'update:remainMs': [value: number | null];
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

// 计划时长(毫秒):配置里的 H/M/S/MS,任何计时操作都不改动它
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
/** 当前这一轮的绝对结束时间戳(ms):计时中才有值,刷新后仍用它算剩余,不依赖 tick 次数 */
let tickingEndAt = 0;

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
  // 同文本组件:数字字号必须补单位,否则 font-size 非法被浏览器忽略
  fontSize: cssSize(props.fontSize, '48px'),
  color: remainingTime.value < 0 ? '#ef4444' : props.color || '#ffffff',
  fontWeight: props.fontWeight || 'bold',
  textAlign: props.textAlign || 'center',
  padding: '8px',
  wordBreak: 'break-word' as const,
  overflowWrap: 'break-word' as const
}));

/** 清掉本地 tick(不落库),用于"从配置重建状态"这类场景 */
const clearTicking = () => {
  if (timerInterval) {
    clearInterval(timerInterval);
    timerInterval = null;
  }
  isRunning.value = false;
};

/**
 * 回写计时状态。只有管理端编辑画布(canControl=true)才允许发写入事件;
 * 大屏投射端是公开只读面,即使倒计时走到 0 也只在本地显示,不落库。
 */
const emitTimerState = (endAt: number | null, remainMs: number | null) => {
  if (!props.canControl) {
    return;
  }
  emit('update:endAt', endAt);
  emit('update:remainMs', remainMs);
};

/** 按绝对结束时间推进:10ms 一跳只是为了显示毫秒,剩余时间始终由 endAt 推出来 */
const tick = () => {
  remainingTime.value = Math.max(0, tickingEndAt - Date.now());
  if (remainingTime.value <= 0) {
    // 走完:清掉结束时间(否则刷新后又会"续跑"一个已经结束的计时)
    remainingTime.value = 0;
    clearTicking();
    emitTimerState(null, 0);
    console.log('Timer finished!');
  }
};

const runUntil = (endAt: number) => {
  clearTicking();
  tickingEndAt = endAt;
  remainingTime.value = Math.max(0, endAt - Date.now());
  if (remainingTime.value <= 0) {
    return;
  }
  isRunning.value = true;
  timerInterval = window.setInterval(tick, 10); // 10ms 更新一次以支持毫秒显示
};

// 启动倒计时:结束时间写进配置,刷新/换设备后据此续跑
const startTimer = () => {
  if (isRunning.value) {
    return;
  }
  // 剩余为 0(已走完/从配置恢复成 0)时,从计划时长重新开始
  const remain = remainingTime.value > 0 ? remainingTime.value : totalTime.value;
  if (remain <= 0) {
    return;
  }
  runUntil(Date.now() + remain);
  emitTimerState(tickingEndAt, null);
};

/**
 * 停止/暂停倒计时。
 *
 * <p>暂停要落两层配置:清掉 endAt(不再"正在计时"),并把<b>剩余</b>时长写进 remainMs,
 * 刷新后接着走;计划时长(hours/minutes/seconds/milliseconds)不受影响。</p>
 */
const stopTimer = (persist = true) => {
  clearTicking();
  if (persist) {
    emitTimerState(null, Math.max(0, Math.round(remainingTime.value)));
  }
};

// 重置倒计时:回到计划时长,并清掉计时/暂停状态
const resetTimer = () => {
  stopTimer(false);
  remainingTime.value = totalTime.value;
  emitTimerState(null, null);
};

// 切换倒计时状态
const toggleTimer = () => {
  if (isRunning.value) {
    stopTimer();
  } else {
    startTimer();
  }
};

/**
 * 从配置恢复现场状态:
 * 有未到期的 endAt = 正在计时 → 直接续跑;否则用暂停剩余时长;都没有则用计划时长。
 */
const restoreFromConfig = () => {
  const end = Number(props.endAt);
  if (props.endAt != null && Number.isFinite(end) && end > Date.now()) {
    runUntil(end);
    return;
  }
  const paused = Number(props.remainMs);
  remainingTime.value = props.remainMs != null && Number.isFinite(paused)
    ? Math.max(0, paused)
    : totalTime.value;
  clearTicking();
};

/** 配置里 endAt/remainMs 被外部改写(其它窗口开始/暂停):跟着同步,不回写避免回环 */
watch(
  () => [props.endAt, props.remainMs],
  () => {
    const end = Number(props.endAt);
    if (props.endAt != null && Number.isFinite(end) && end > Date.now()) {
      if (!isRunning.value || tickingEndAt !== end) {
        runUntil(end);
      }
      return;
    }
    if (isRunning.value) {
      const paused = Number(props.remainMs);
      clearTicking();
      remainingTime.value = Number.isFinite(paused) ? Math.max(0, paused) : remainingTime.value;
    }
  }
);

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
    const wasRunning = isRunning.value;
    clearTicking();
    remainingTime.value = totalTime.value;
    // 计划时长改了:计时状态一并复位(旧 endAt/暂停剩余都不能再留着),
    // 原本在跑的就按新时长重新开始
    emitTimerState(null, null);
    if (wasRunning) {
      startTimer();
    }
  }
);

// 挂载时按配置恢复:配置里有未到期的 endAt 说明本来就在计时,直接续跑
onMounted(restoreFromConfig);

onUnmounted(() => {
  // 卸载只清本地 tick,不回写:页面刷新本来就不会走这里,而切场景再回来要按 endAt 继续走
  clearTicking();
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
