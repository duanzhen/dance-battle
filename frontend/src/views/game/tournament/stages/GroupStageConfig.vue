<template>
  <div class="stage-config">
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-6 flex items-center gap-2"><Users class="w-4 h-4" /> 小组赛配置</h3>

      <div class="space-y-6">
        <!-- INIT_DONE 模式: 只读展示 -->
        <template v-if="currentMode === ConfigMode.INIT_DONE">
          <!-- 分组信息 -->
          <div class="grid grid-cols-3 gap-4">
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">分组数量</div>
              <div class="text-2xl font-bold text-white font-mono">{{ config.groupCount }}</div>
              <div class="text-xs text-neutral-600 mt-1">个小组</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">每组队伍</div>
              <div class="text-2xl font-bold text-white font-mono">{{ config.teamsPerGroup }}</div>
              <div class="text-xs text-neutral-600 mt-1">支/组</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">每组晋级</div>
              <div class="text-2xl font-bold text-amber-500 font-mono">{{ config.advancePerGroup }}</div>
              <div class="text-xs text-neutral-600 mt-1">支/组</div>
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
            <div class="text-sm text-neutral-300">共 {{ config.groupCount }} 个小组，每组 {{ config.teamsPerGroup }} 支队伍</div>
            <div class="text-sm text-neutral-300 mt-1">总参赛：{{ totalTeams }} 支 → 晋级：{{ totalAdvance }} 支</div>
            <div class="text-xs text-neutral-500 mt-2">每队比赛：{{ config.teamsPerGroup - 1 }} 场</div>
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

          <!-- 分组设置 (INIT-ONLY) -->
          <div v-if="currentMode === ConfigMode.INIT">
            <label class="text-xs text-neutral-500 mb-2 block">分组设置</label>
            <div class="grid grid-cols-3 gap-6">
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">分组数量</label>
                <input
                  type="number"
                  v-model.number="config.groupCount"
                  :min="2"
                  :max="16"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @input="handleUpdate"
                />
              </div>
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">每组队伍数</label>
                <input
                  type="number"
                  v-model.number="config.teamsPerGroup"
                  :min="2"
                  :max="16"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @input="handleUpdate"
                />
              </div>
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">每组晋级数</label>
                <input
                  type="number"
                  v-model.number="config.advancePerGroup"
                  :min="1"
                  :max="config.teamsPerGroup"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @input="handleUpdate"
                />
              </div>
            </div>
          </div>

          <!-- 积分规则 -->
          <div>
            <label class="text-xs text-neutral-500 mb-2 block">积分规则</label>
            <div class="bg-black border border-neutral-700 rounded-lg p-4">
              <div class="grid grid-cols-3 gap-4">
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
                  <label class="text-xs text-neutral-600 mb-1 block">平积分</label>
                  <input
                    type="number"
                    v-model.number="config.drawPoints"
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

          <!-- 同分处理 -->
          <div>
            <label class="text-xs text-neutral-500 mb-2 block">同分处理规则</label>
            <div class="bg-black border border-neutral-700 rounded-lg p-4 space-y-3">
              <label class="flex items-center justify-between">
                <span class="text-sm text-neutral-300">胜负关系优先</span>
                <input type="checkbox" v-model="config.headToHeadFirst" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
              </label>
              <label class="flex items-center justify-between">
                <span class="text-sm text-neutral-300">加赛</span>
                <input type="checkbox" v-model="config.tiebreakerPlayoff" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
              </label>
            </div>
          </div>

          <!-- 打分与转场配置 -->
          <ScoringTransitionConfig
            :scoring="config.scoring"
            @update:scoring="
              (v) => {
                config.scoring = v;
                handleUpdate();
              }
            "
          />

          <!-- 预览 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">赛制预览</div>
            <div class="text-sm text-neutral-300">共 {{ config.groupCount }} 个小组，每组 {{ config.teamsPerGroup }} 支队伍</div>
            <div class="text-sm text-neutral-300 mt-1">总参赛：{{ totalTeams }} 支队伍 → 晋级：{{ totalAdvance }} 支队伍</div>
            <div class="text-xs text-neutral-500 mt-2">每队比赛场次：{{ config.teamsPerGroup - 1 }} 场</div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue';
import { Users } from 'lucide-vue-next';
import { GroupConfig, StageData, ConfigMode } from './types';
import ScoringTransitionConfig from './ScoringTransitionConfig.vue';

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
const config = ref<GroupConfig & { headToHeadFirst?: boolean; tiebreakerPlayoff?: boolean; scoring?: any; transition?: any }>({
  groupCount: 4,
  teamsPerGroup: 4,
  winPoints: 3,
  drawPoints: 1,
  lossPoints: 0,
  advancePerGroup: 2,
  format: 'BO1',
  headToHeadFirst: true,
  tiebreakerPlayoff: false,
  scoring: {
    type: 'WIN_LOSS_DRAW',
    matchMode: 'STANDARD',
    aggregateRule: 'SUM',
    refereeAggregateRule: 'AVG',
    trimRatio: 0.1,
    dimensions: [],
    outcomeRules: { winScore: 1, drawScore: 0.5, lossScore: 0 }
  },
  transition: {}
});

// 计算总队伍数
const totalTeams = computed(() => config.value.groupCount * config.value.teamsPerGroup);

// 计算总晋级数
const totalAdvance = computed(() => config.value.groupCount * config.value.advancePerGroup);

// 解析配置(兼容包裹式 RuleConfigHolder 与旧扁平结构)
const parseConfig = () => {
  try {
    if (!props.stage.ruleConfig) return;
    const parsed = JSON.parse(props.stage.ruleConfig);
    if (parsed.format) config.value.format = parsed.format;
    const g = parsed.group || parsed;
    if (g.groupCount !== undefined) config.value.groupCount = g.groupCount;
    if (g.teamsPerGroup !== undefined) config.value.teamsPerGroup = g.teamsPerGroup;
    if (g.winPoints !== undefined) config.value.winPoints = g.winPoints;
    if (g.drawPoints !== undefined) config.value.drawPoints = g.drawPoints;
    if (g.lossPoints !== undefined) config.value.lossPoints = g.lossPoints;
    if (g.advancePerGroup !== undefined) config.value.advancePerGroup = g.advancePerGroup;
    if (parsed.scoring) config.value.scoring = parsed.scoring;
    if (parsed.transition) config.value.transition = parsed.transition;
  } catch (e) {
    console.warn('Failed to parse ruleConfig:', e);
  }
};

// 序列化为包裹式 RuleConfigHolder(对齐后端)
const serializeConfig = () => {
  return JSON.stringify({
    mode: 'GROUP',
    format: config.value.format,
    group: {
      groupCount: config.value.groupCount,
      teamsPerGroup: config.value.teamsPerGroup,
      winPoints: config.value.winPoints,
      drawPoints: config.value.drawPoints,
      lossPoints: config.value.lossPoints,
      advancePerGroup: config.value.advancePerGroup
    },
    scoring: config.value.scoring,
    transition: config.value.transition
  });
};

// 更新处理
const handleUpdate = () => {
  localStage.value.ruleConfig = serializeConfig();
  localStage.value.teamCountStart = totalTeams.value;
  localStage.value.teamCountEnd = totalAdvance.value;
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
