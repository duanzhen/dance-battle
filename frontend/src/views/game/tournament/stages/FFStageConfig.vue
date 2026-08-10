<template>
  <div class="stage-config">
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-6 flex items-center gap-2">
        <Sword class="w-4 h-4" /> 自由对抗赛配置
      </h3>

      <div class="space-y-6">
        <!-- INIT_DONE 模式: 只读展示 -->
        <template v-if="currentMode === ConfigMode.INIT_DONE">
          <!-- 队伍信息 -->
          <div class="grid grid-cols-2 gap-4">
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">参赛队伍总数</div>
              <div class="text-2xl font-bold text-white font-mono">{{ config.teamsCount }}</div>
              <div class="text-xs text-neutral-600 mt-1">支队伍</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">晋级名额</div>
              <div class="text-2xl font-bold text-amber-500 font-mono">{{ config.advanceCount }}</div>
              <div class="text-xs text-neutral-600 mt-1">支晋级</div>
            </div>
          </div>

          <!-- 比赛设置 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">比赛设置</div>
            <div class="text-lg font-medium text-white mb-1">{{ config.format }}</div>
            <div class="text-sm text-neutral-300">每队进行 {{ config.matchCount }} 场比赛</div>
          </div>

          <!-- 赛制概览 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">赛制概览</div>
            <div class="text-sm text-neutral-300">{{ config.teamsCount }} 支队伍，每队进行 {{ config.matchCount }} 场比赛</div>
            <div class="text-sm text-neutral-300 mt-1">按积分/排名排名，前 {{ config.advanceCount }} 支队伍晋级</div>
            <div class="text-xs text-neutral-500 mt-2">总比赛场次：约 {{ Math.ceil((config.teamsCount * config.matchCount) / 2) }} 场</div>
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

          <!-- 队伍设置 (INIT-ONLY) -->
          <div v-if="currentMode === ConfigMode.INIT">
            <label class="text-xs text-neutral-500 mb-2 block">队伍设置</label>
            <div class="grid grid-cols-2 gap-6">
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">参赛队伍总数</label>
                <input
                  type="number"
                  v-model.number="config.teamsCount"
                  :min="4"
                  :max="64"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @input="handleUpdate"
                />
              </div>
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">每队比赛场次</label>
                <input
                  type="number"
                  v-model.number="config.matchCount"
                  :min="1"
                  :max="10"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @input="handleUpdate"
                />
              </div>
            </div>
          </div>

          <!-- 晋级设置 (INIT-ONLY: advanceCount) -->
          <div v-if="currentMode === ConfigMode.INIT">
            <label class="text-xs text-neutral-500 mb-2 block">晋级设置</label>
            <div class="bg-black border border-neutral-700 rounded-lg p-4">
              <div class="grid grid-cols-2 gap-4 mb-4">
                <div>
                  <label class="text-xs text-neutral-600 mb-1 block">晋级名额</label>
                  <input
                    type="number"
                    v-model.number="config.advanceCount"
                    :min="1"
                    :max="config.teamsCount - 1"
                    class="w-full bg-neutral-900 border border-neutral-800 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                    @input="handleUpdate"
                  />
                </div>
                <div>
                  <label class="text-xs text-neutral-600 mb-1 block">晋级方式</label>
                  <select
                    v-model="config.advanceMethod"
                    class="w-full bg-neutral-900 border border-neutral-800 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                    @change="handleUpdate"
                  >
                    <option value="rank">按排名</option>
                    <option value="score">按积分</option>
                  </select>
                </div>
              </div>
            </div>
          </div>

          <!-- 积分规则 -->
          <div>
            <label class="text-xs text-neutral-500 mb-2 block">积分规则</label>
            <div class="bg-black border border-neutral-700 rounded-lg p-4">
              <div class="grid grid-cols-2 gap-4">
                <div>
                  <label class="text-xs text-neutral-600 mb-1 block">胜积分</label>
                  <input
                    type="number"
                    v-model.number="config.winPoints"
                    :min="0"
                    class="w-full bg-neutral-900 border border-neutral-800 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                    @input="handleUpdate"
                  />
                </div>
                <div>
                  <label class="text-xs text-neutral-600 mb-1 block">负积分</label>
                  <input
                    type="number"
                    v-model.number="config.lossPoints"
                    :min="0"
                    class="w-full bg-neutral-900 border border-neutral-800 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                    @input="handleUpdate"
                  />
                </div>
              </div>
            </div>
          </div>

          <!-- 预览 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">赛制预览</div>
            <div class="text-sm text-neutral-300">{{ config.teamsCount }} 支队伍，每队进行 {{ config.matchCount }} 场比赛</div>
            <div class="text-sm text-neutral-300 mt-1">按积分/排名排名，前 {{ config.advanceCount }} 支队伍晋级</div>
            <div class="text-xs text-neutral-500 mt-2">总比赛场次：约 {{ Math.ceil((config.teamsCount * config.matchCount) / 2) }} 场</div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue';
import { Sword } from 'lucide-vue-next';
import { FFAConfig, StageData, ConfigMode } from './types';

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
  teamsCount: 8,
  matchCount: 3,
  winPoints: 3,
  lossPoints: 0,
  advanceCount: 4,
  format: 'BO1',
  advanceMethod: 'rank'
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
  localStage.value.teamCountStart = config.value.teamsCount;
  localStage.value.teamCountEnd = config.value.advanceCount;
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
