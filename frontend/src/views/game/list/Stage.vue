<template>
  <div class="space-y-6">
    <div class="flex justify-between items-center">
      <div>
        <h2 class="text-xl font-bold text-neutral-100">流程编排</h2>
        <p class="text-xs text-neutral-500 mt-1">定义比赛的各个阶段与预估时长</p>
      </div>
      <button
        @click="addStep"
        class="px-3 py-1.5 bg-neutral-800 hover:bg-neutral-700 text-amber-500 hover:text-amber-400 text-xs font-bold rounded border border-neutral-700 hover:border-amber-500/50 transition-all flex items-center gap-2"
      >
        <Plus class="w-3 h-3" />
        添加阶段
      </button>
    </div>

    <div class="relative min-h-[200px]">
      <div class="absolute left-6 top-6 bottom-6 w-px bg-neutral-800 z-0"></div>

      <TransitionGroup name="list" tag="div" class="space-y-3">
        <div
          v-for="(step, index) in modelValue"
          :key="step.id"
          class="relative z-10 flex items-center gap-4 bg-neutral-900 border border-neutral-800 p-4 rounded-xl group hover:border-neutral-600 transition-all duration-300"
        >
          <div
            class="flex-none w-12 h-12 rounded-lg bg-neutral-950 border border-neutral-800 flex items-center justify-center text-neutral-500 font-mono text-sm group-hover:text-amber-500 group-hover:border-amber-500/50 transition-colors shadow-inner"
          >
            {{ (index + 1).toString().padStart(2, '0') }}
          </div>

          <div class="flex-1 grid grid-cols-12 gap-6 items-center">
            <div class="col-span-6">
              <label class="text-[10px] text-neutral-600 uppercase font-bold tracking-wider mb-1 block"> Stage Name </label>
              <div class="flex items-center gap-2">
                <Flag class="w-4 h-4 text-neutral-600 group-hover:text-neutral-400 transition-colors" />
                <input
                  v-model="step.name"
                  class="w-full bg-transparent border-b border-transparent focus:border-amber-500 text-neutral-200 font-bold focus:outline-none py-1 transition-colors placeholder-neutral-700"
                  placeholder="输入阶段名称..."
                />
              </div>
            </div>

            <div class="col-span-4">
              <label class="text-[10px] text-neutral-600 uppercase font-bold tracking-wider mb-1 block"> Duration (Min) </label>
              <div class="flex items-center gap-2">
                <Clock class="w-4 h-4 text-neutral-600 group-hover:text-neutral-400 transition-colors" />
                <input
                  type="number"
                  v-model="step.duration"
                  class="w-full bg-transparent border-b border-transparent focus:border-amber-500 text-neutral-200 font-mono focus:outline-none py-1 transition-colors"
                />
              </div>
            </div>

            <div class="col-span-2 flex justify-end">
              <button
                @click="removeStep(index)"
                class="p-2 text-neutral-600 hover:text-red-500 hover:bg-red-500/10 rounded-lg transition-colors opacity-0 group-hover:opacity-100"
                title="删除此阶段"
              >
                <Trash2 class="w-4 h-4" />
              </button>
            </div>
          </div>
        </div>
      </TransitionGroup>

      <div v-if="modelValue.length === 0" class="text-center py-10 text-neutral-600 text-sm">点击右上角添加比赛阶段</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Plus, Trash2, Clock, Flag } from 'lucide-vue-next';

// 定义数据接口
export interface MatchStep {
  id: number;
  name: string;
  duration: number;
}

// 接收 Props
const props = defineProps<{
  modelValue: MatchStep[];
}>();

// 定义 Emits
const emit = defineEmits<{
  (e: 'update:modelValue', value: MatchStep[]): void;
}>();

// 逻辑方法
const addStep = () => {
  const newStep: MatchStep = {
    id: Date.now(), // 使用时间戳作为简单ID
    name: '',
    duration: 15
  };
  // 触发更新，保持单向数据流原则
  emit('update:modelValue', [...props.modelValue, newStep]);
};

const removeStep = (index: number) => {
  const newList = [...props.modelValue];
  newList.splice(index, 1);
  emit('update:modelValue', newList);
};
</script>

<style scoped>
/* 列表动画 */
.list-enter-active,
.list-leave-active {
  transition: all 0.3s ease;
}
.list-enter-from,
.list-leave-to {
  opacity: 0;
  transform: translateX(-20px);
}
</style>
