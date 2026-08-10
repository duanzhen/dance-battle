<template>
  <div class="stage-config">
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-6 flex items-center gap-2"><Waves class="w-4 h-4" /> 海选赛配置</h3>

      <div class="space-y-6">
        <!-- INIT_DONE 模式: 只读展示 -->
        <template v-if="currentMode === ConfigMode.INIT_DONE">
          <!-- 轮次信息 -->
          <div class="grid grid-cols-2 gap-4">
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">海选轮数</div>
              <div class="text-2xl font-bold text-white font-mono">{{ config.roundsCount }}</div>
              <div class="text-xs text-neutral-600 mt-1">轮</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">晋级名额</div>
              <div class="text-2xl font-bold text-amber-500 font-mono">{{ config.advanceQuota }}</div>
              <div class="text-xs text-neutral-600 mt-1">支晋级</div>
            </div>
          </div>

          <!-- 比赛格式 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">比赛设置</div>
            <div class="text-lg font-medium text-white mb-1">{{ config.format }}</div>
            <div class="text-sm text-neutral-300">每轮淘汰 {{ config.eliminationRate }}% 队伍</div>
          </div>

          <!-- 赛制概览 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">赛制概览</div>
            <div class="text-sm text-neutral-300">共 {{ config.roundsCount }} 轮海选，每轮淘汰 {{ config.eliminationRate }}% 队伍</div>
            <div class="text-sm text-neutral-300 mt-1">{{ config.advanceByScore ? '达到分数线' : `前 ${config.advanceQuota} 名` }} 队伍晋级</div>
          </div>
        </template>

        <!-- INIT/NORMAL 模式: 可编辑 -->
        <template v-else>
          <!-- 基本信息 -->
          <div>
            <label class="text-xs text-neutral-500 mb-2 block">比赛格式</label>
            <select
              v-model="config.format"
              class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
              @change="handleUpdate"
            >
              <option value="BO1">BO1 (单局决胜)</option>
              <option value="BO3">BO3 (三局两胜)</option>
            </select>
          </div>

          <!-- 轮次设置 (INIT-ONLY) -->
          <div v-if="currentMode === ConfigMode.INIT">
            <label class="text-xs text-neutral-500 mb-2 block">轮次设置</label>
            <div class="grid grid-cols-2 gap-6">
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">海选轮数</label>
                <input
                  type="number"
                  v-model.number="config.roundsCount"
                  :min="1"
                  :max="10"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @input="handleUpdate"
                />
              </div>
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">每轮淘汰率 (%)</label>
                <input
                  type="number"
                  v-model.number="config.eliminationRate"
                  :min="10"
                  :max="90"
                  :step="5"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @input="handleUpdate"
                />
              </div>
            </div>
          </div>

          <!-- 晋级规则 (INIT-ONLY: advanceQuota) -->
          <div>
            <label class="text-xs text-neutral-500 mb-2 block">晋级规则</label>
            <div class="bg-black border border-neutral-700 rounded-lg p-4 space-y-3">
              <label class="flex items-center justify-between">
                <span class="text-sm text-neutral-300">按分数晋级</span>
                <input type="checkbox" v-model="config.advanceByScore" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
              </label>

              <div v-if="config.advanceByScore" class="mt-3">
                <label class="text-xs text-neutral-600 mb-1 block">晋线分数</label>
                <input
                  type="number"
                  v-model.number="config.advanceThreshold"
                  :min="0"
                  class="w-full bg-neutral-900 border border-neutral-800 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @input="handleUpdate"
                />
              </div>

              <div v-else class="mt-3">
                <label class="text-xs text-neutral-600 mb-1 block">晋级名额</label>
                <input
                  type="number"
                  v-model.number="config.advanceQuota"
                  :min="1"
                  class="w-full bg-neutral-900 border border-neutral-800 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @input="handleUpdate"
                />
              </div>
            </div>
          </div>

          <!-- 淘汰机制 -->
          <div>
            <label class="text-xs text-neutral-500 mb-2 block">淘汰机制</label>
            <div class="bg-black border border-neutral-700 rounded-lg p-4 space-y-3">
              <label class="flex items-center justify-between">
                <span class="text-sm text-neutral-300">累积淘汰</span>
                <input type="checkbox" v-model="config.cumulativeElimination" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
              </label>
              <label class="flex items-center justify-between">
                <span class="text-sm text-neutral-300">每轮重置分数</span>
                <input type="checkbox" v-model="config.resetScoreEachRound" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
              </label>
            </div>
          </div>

          <!-- 预览 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">赛制预览</div>
            <div class="text-sm text-neutral-300">共 {{ config.roundsCount }} 轮海选，每轮淘汰 {{ config.eliminationRate }}% 队伍</div>
            <div class="text-sm text-neutral-300 mt-1">{{ config.advanceByScore ? '达到分数线' : `前 ${config.advanceQuota} 名` }} 队伍晋级</div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue';
import { Waves } from 'lucide-vue-next';
import { SurvivalConfig, StageData, ConfigMode } from './types';

// Props
const props = defineProps<{
  stage: StageData;
  mode?: ConfigMode;
}>();

// Emits
const emit = defineEmits<{
  update: [stage: StageData];
}>();

// 本地赛段数据
const localStage = ref<StageData>({ ...props.stage });

// 当前模式
const currentMode = computed(() => props.mode || ConfigMode.NORMAL);

// 配置对象
const config = ref<any>({
  roundsCount: 3,
  eliminationRate: 50,
  advanceThreshold: 100,
  advanceByScore: false,
  advanceQuota: 32,
  format: 'BO1',
  cumulativeElimination: false,
  resetScoreEachRound: true
});

// 解析配置
const parseConfig = () => {
  try {
    if (props.stage.ruleConfig) {
      const parsed = JSON.parse(props.stage.ruleConfig);
      config.value = { ...config.value, ...parsed };
    }
  } catch (e) {
    console.warn('Failed to parse ruleConfig:', e);
  }
};

// 序列化配置
const serializeConfig = () => {
  return JSON.stringify(config.value);
};

// 更新处理
const handleUpdate = () => {
  localStage.value.ruleConfig = serializeConfig();
  localStage.value.teamCountStart = config.value.advanceQuota * 2; // 估算
  localStage.value.teamCountEnd = config.value.advanceQuota;
  emit('update', localStage.value);
};

// 监听 props 变化
watch(
  () => props.stage,
  () => {
    localStage.value = { ...props.stage };
    parseConfig();
  },
  { deep: true }
);

// 初始化
onMounted(() => {
  parseConfig();
});
</script>

<style scoped>
.stage-config {
  max-width: 900px;
  margin: 0 auto;
}

input[type='number']::-webkit-inner-spin-button,
input[type='number']::-webkit-outer-spin-button {
  opacity: 1;
}
</style>
