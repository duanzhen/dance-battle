<template>
  <div
    class="bg-neutral-950 rounded-[4px] px-2 py-1.5 border border-neutral-800 flex items-center gap-2 focus-within:border-amber-500/50 focus-within:bg-neutral-900 transition-colors group relative"
    :class="disabled ? 'opacity-40 pointer-events-none' : ''"
  >
    <label
      class="text-[10px] text-neutral-500 font-bold select-none cursor-ew-resize hover:text-amber-500 transition-colors min-w-[12px]"
      @mousedown.prevent="handleDragStart"
      title="按住拖动调整数值"
    >
      {{ label }}
    </label>

    <input
      ref="inputRef"
      type="text"
      :value="displayValue"
      @input="handleInput"
      @keydown.up.prevent="adjustValue(step)"
      @keydown.down.prevent="adjustValue(-step)"
      @blur="handleBlur"
      class="w-full bg-transparent text-xs text-right text-neutral-200 focus:outline-none font-mono appearance-none"
    />

    <span v-if="suffix" class="text-[10px] text-neutral-600 group-focus-within:text-neutral-400 select-none">
      {{ suffix }}
    </span>

    <div v-if="isDragging" class="fixed inset-0 z-[9999] cursor-ew-resize"></div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue';

const props = defineProps({
  label: { type: String, required: true },
  modelValue: { type: [Number, String], default: 0 },
  suffix: { type: String, default: '' },
  step: { type: Number, default: 1 }, // 每次调整的步进
  min: { type: Number, default: -Infinity },
  max: { type: Number, default: Infinity },
  disabled: { type: Boolean, default: false }
});

const emit = defineEmits(['update:modelValue']);

const inputRef = ref(null);
const isDragging = ref(false);
const startX = ref(0);
const startValue = ref(0);

// --- 显示逻辑 ---
// 如果是小数，保留2位；如果是整数，直接显示
const displayValue = computed(() => {
  const num = Number(props.modelValue);
  if (isNaN(num)) return props.modelValue;
  return Number.isInteger(num) ? num : parseFloat(num.toFixed(2));
});

// --- 基础输入逻辑 ---
const handleInput = (e) => {
  const val = e.target.value;
  // 允许输入负号和小数点，但在 emit 时尝试转数字
  // 只有当它是有效数字时才 emit，否则这里不做处理等待 blur 修正
  const num = parseFloat(val);
  if (!isNaN(num)) {
    emit('update:modelValue', num);
  }
};

const handleBlur = (e) => {
  // 失焦时，强制格式化回有效数字
  let num = parseFloat(e.target.value);
  if (isNaN(num)) num = 0;
  num = Math.min(Math.max(num, props.min), props.max);
  emit('update:modelValue', num);
  // 强制刷新 input 显示（处理如 007 -> 7 的情况）
  if (inputRef.value) inputRef.value.value = displayValue.value;
};

const adjustValue = (delta) => {
  let newVal = (Number(props.modelValue) || 0) + delta;
  newVal = Math.min(Math.max(newVal, props.min), props.max);
  emit('update:modelValue', newVal);
};

// --- 拖拽调整逻辑 (核心 Feature) ---
const handleDragStart = (e) => {
  isDragging.value = true;
  startX.value = e.clientX;
  startValue.value = Number(props.modelValue) || 0;

  window.addEventListener('mousemove', handleDragMove);
  window.addEventListener('mouseup', handleDragEnd);
};

const handleDragMove = (e) => {
  if (!isDragging.value) return;

  const dx = e.clientX - startX.value;
  // 拖动灵敏度：按住 Shift 键变慢(微调)，否则正常
  const multiplier = e.shiftKey ? 0.1 : 1;

  // 计算新值
  let newVal = startValue.value + dx * props.step * multiplier;

  // 限制范围
  newVal = Math.min(Math.max(newVal, props.min), props.max);

  // 取整逻辑：如果 step 是 1，就取整；如果是小数，保留2位
  if (props.step >= 1) {
    newVal = Math.round(newVal);
  } else {
    newVal = parseFloat(newVal.toFixed(2));
  }

  emit('update:modelValue', newVal);
};

const handleDragEnd = () => {
  isDragging.value = false;
  window.removeEventListener('mousemove', handleDragMove);
  window.removeEventListener('mouseup', handleDragEnd);
};
</script>

<style scoped>
/* 隐藏浏览器默认的 number input 箭头 */
input::-webkit-outer-spin-button,
input::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}
input[type='number'] {
  -moz-appearance: textfield;
}
</style>
