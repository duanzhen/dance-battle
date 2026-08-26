<template>
  <div class="stage-config">
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-6 flex items-center gap-2"><Mic class="w-4 h-4" /> 海选赛配置</h3>

      <div class="space-y-6">
        <!-- INIT_DONE 模式: 只读展示 -->
        <template v-if="currentMode === ConfigMode.INIT_DONE">
          <!-- 选拔规模 -->
          <div class="grid grid-cols-3 gap-4">
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">海选规模</div>
              <div class="text-2xl font-bold text-white font-mono">{{ config.scale }}</div>
              <div class="text-xs text-neutral-600 mt-1">支队伍</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">晋级名额</div>
              <div class="text-2xl font-bold text-amber-500 font-mono">{{ config.advanceCount }}</div>
              <div class="text-xs text-neutral-600 mt-1">支晋级</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">分圈</div>
              <div class="text-2xl font-bold text-white font-mono">{{ config.circles || 1 }}</div>
              <div class="text-xs text-neutral-600 mt-1">圈并行</div>
            </div>
          </div>

          <!-- 比赛格式 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">比赛格式</div>
            <div class="text-lg font-medium text-white">{{ config.format }}</div>
          </div>

          <!-- 赛制概览 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">赛制概览</div>
            <div class="text-sm text-neutral-300">{{ config.scale }} 支队伍参加选拔</div>
            <div class="text-sm text-neutral-300 mt-1">最终 {{ config.advanceCount }} 支队伍晋级</div>
            <div v-if="(config.circles || 1) > 1" class="text-sm text-neutral-400 mt-1">
              分 {{ config.circles }} 圈并行，每圈约 {{ Math.ceil(config.scale / config.circles) }} 人、晋级 {{ Math.floor(config.advanceCount / config.circles) }} 人
            </div>
            <div v-if="(config.circles || 1) > 1 && hasCircleQuotas" class="text-sm text-neutral-400 mt-1">
              每圈晋级：{{ config.circleAdvanceCounts.join(' / ') }}（合计 {{ totalQuota }} 人）
            </div>
            <div class="text-xs text-neutral-500 mt-2">晋级率：{{ ((config.advanceCount / config.scale) * 100).toFixed(1) }}%</div>
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

          <!-- 规模设置 (INIT-ONLY) -->
          <div v-if="currentMode === ConfigMode.INIT">
            <label class="text-xs text-neutral-500 mb-2 block">选拔规模</label>
            <div class="grid grid-cols-3 gap-4">
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">海选规模</label>
                <div class="relative">
                  <input
                    type="number"
                    v-model.number="config.scale"
                    :min="8"
                    :max="512"
                    class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                    @input="handleUpdate"
                  />
                  <div class="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-neutral-600">支队伍</div>
                </div>
              </div>
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">晋级名额</label>
                <div class="relative">
                  <input
                    type="number"
                    v-model.number="config.advanceCount"
                    :min="1"
                    :max="config.scale - 1"
                    class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                    @input="handleUpdate"
                  />
                  <div class="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-neutral-600">支晋级</div>
                </div>
              </div>
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">分圈数</label>
                <div class="relative">
                  <input
                    type="number"
                    v-model.number="config.circles"
                    :min="1"
                    :max="config.scale"
                    class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                    @input="handleUpdate"
                  />
                  <div class="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-neutral-600">圈</div>
                </div>
              </div>
            </div>
            <p v-if="(config.circles || 1) > 1" class="text-[11px] text-neutral-500 mt-2">
              分 {{ config.circles }} 圈并行进行，每圈约 {{ Math.ceil(config.scale / config.circles) }} 人，可分别配置每圈晋级人数
            </p>
          </div>

          <!-- 每圈晋级人数(分圈时可独立配置) -->
          <div v-if="(config.circles || 1) > 1" class="bg-black border border-neutral-700 rounded-lg p-4">
            <div class="flex items-center justify-between mb-2">
              <label class="text-xs text-neutral-500">每圈晋级人数</label>
              <span class="text-[11px] text-neutral-600">合计 {{ totalQuota }} / {{ config.advanceCount }}</span>
            </div>
            <div class="grid gap-2" :style="{ gridTemplateColumns: `repeat(${Math.min(circleCount, 4)}, minmax(0, 1fr))` }">
              <div v-for="(_, i) in circleCount" :key="i">
                <label class="text-[10px] text-neutral-600 block mb-1">第 {{ i + 1 }} 圈</label>
                <input
                  type="number"
                  v-model.number="config.circleAdvanceCounts[i]"
                  :min="0"
                  class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @input="handleUpdate"
                />
              </div>
            </div>
            <p v-if="totalQuota !== config.advanceCount" class="text-[11px] text-amber-500/80 mt-2">
              每圈合计与总名额不一致，结算按每圈配置为准
            </p>
            <p v-if="currentMode === ConfigMode.NORMAL && localStage.status === 'PENDING'" class="text-[11px] text-amber-500/80 mt-2">
              赛段已生成对阵，修改圈数/每圈名额后请在「流程操作」中重新点击「生成对阵」生效
            </p>
          </div>

          <!-- 晋级条件 -->
          <div>
            <label class="text-xs text-neutral-500 mb-2 block">晋级条件</label>
            <div class="bg-black border border-neutral-700 rounded-lg p-4 space-y-3">
              <label class="flex items-center justify-between">
                <span class="text-sm text-neutral-300">按评分晋级</span>
                <input type="checkbox" v-model="config.advanceByScore" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
              </label>
              <label class="flex items-center justify-between">
                <span class="text-sm text-neutral-300">按排名晋级</span>
                <input type="checkbox" v-model="config.advanceByRank" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
              </label>
              <label class="flex items-center justify-between">
                <span class="text-sm text-neutral-300">评委投票</span>
                <input type="checkbox" v-model="config.judgeVote" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
              </label>
            </div>
          </div>

          <!-- 评分规则 -->
          <div v-if="config.advanceByScore || config.judgeVote">
            <label class="text-xs text-neutral-500 mb-2 block">评分规则</label>
            <div class="grid grid-cols-2 gap-6">
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">满分</label>
                <input
                  type="number"
                  v-model.number="config.maxScore"
                  :min="1"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @input="handleUpdate"
                />
              </div>
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">及格分数线</label>
                <input
                  type="number"
                  v-model.number="config.passingScore"
                  :min="0"
                  :max="config.maxScore"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @input="handleUpdate"
                />
              </div>
            </div>
          </div>

          <!-- 预览 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">赛制预览</div>
            <div class="text-sm text-neutral-300">{{ config.scale }} 支队伍参加选拔</div>
            <div class="text-sm text-neutral-300 mt-1">最终 {{ config.advanceCount }} 支队伍晋级</div>
            <div v-if="(config.circles || 1) > 1" class="text-sm text-neutral-400 mt-1">
              分 {{ config.circles }} 圈并行，每圈约 {{ Math.ceil(config.scale / config.circles) }} 人、晋级 {{ Math.floor(config.advanceCount / config.circles) }} 人
            </div>
            <div v-if="(config.circles || 1) > 1 && hasCircleQuotas" class="text-sm text-neutral-400 mt-1">
              每圈晋级：{{ config.circleAdvanceCounts.join(' / ') }}（合计 {{ totalQuota }} 人）
            </div>
            <div class="text-xs text-neutral-500 mt-2">晋级率：{{ ((config.advanceCount / config.scale) * 100).toFixed(1) }}%</div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue';
import { Mic } from 'lucide-vue-next';
import { AuditionConfig, StageData, ConfigMode } from './types';

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
  scale: 64,
  advanceCondition: 'score',
  advanceCount: 16,
  circles: 1,
  format: 'BO1',
  advanceByScore: true,
  advanceByRank: false,
  judgeVote: false,
  maxScore: 100,
  passingScore: 60,
  circleAdvanceCounts: []
});

// 分圈数量
const circleCount = computed(() => Math.max(1, config.value.circles || 1));

// 是否已配置每圈晋级名额
const hasCircleQuotas = computed(
  () => circleCount.value > 1 && Array.isArray(config.value.circleAdvanceCounts)
    && config.value.circleAdvanceCounts.length === circleCount.value
);

// 每圈名额合计
const totalQuota = computed(() => {
  if (hasCircleQuotas.value) {
    return config.value.circleAdvanceCounts.reduce((s: number, n: number) => s + (Number(n) || 0), 0);
  }
  return config.value.advanceCount;
});

// 圈数变化时同步每圈名额数组(保留已配置值,新增位用均分值补齐)
const syncCircleQuotas = () => {
  const n = circleCount.value;
  if (n <= 1) {
    config.value.circleAdvanceCounts = [];
    return;
  }
  const current = Array.isArray(config.value.circleAdvanceCounts)
    ? [...config.value.circleAdvanceCounts]
    : [];
  const base = Math.max(0, Math.floor((config.value.advanceCount || 0) / n));
  const rem = Math.max(0, (config.value.advanceCount || 0) % n);
  if (current.length === 0) {
    for (let i = 0; i < n; i++) {
      current.push(base + (i < rem ? 1 : 0));
    }
  } else {
    while (current.length < n) {
      current.push(base);
    }
    current.length = n;
  }
  config.value.circleAdvanceCounts = current;
};

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
  if (circleCount.value > 1 && hasCircleQuotas.value) {
    localStage.value.teamCountEnd = totalQuota.value;
  } else {
    localStage.value.teamCountEnd = config.value.advanceCount;
  }
  localStage.value.ruleConfig = serializeConfig();
  localStage.value.teamCountStart = config.value.scale;
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

// 圈数变化:重建每圈名额数组并触发保存
watch(
  () => config.value.circles,
  () => {
    syncCircleQuotas();
    handleUpdate();
  }
);

// 初始化
onMounted(() => {
  parseConfig();
  syncCircleQuotas();
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
