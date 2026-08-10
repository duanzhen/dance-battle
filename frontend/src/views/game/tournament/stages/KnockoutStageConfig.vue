<template>
  <div class="stage-config">
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-6 flex items-center gap-2"><Trophy class="w-4 h-4" /> 淘汰赛配置</h3>

      <div class="space-y-6">
        <!-- INIT_DONE 模式: 只读展示 -->
        <template v-if="currentMode === ConfigMode.INIT_DONE">
          <!-- 模板信息 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">淘汰赛模板</div>
            <div class="text-lg font-medium text-amber-500">{{ getTemplateLabel(config.template) }}</div>
            <div class="text-xs text-neutral-500 mt-1">{{ getTemplateDetail(config.template) }}</div>
          </div>

          <!-- 队伍数量 -->
          <div class="grid grid-cols-2 gap-4">
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">参赛队伍数</div>
              <div class="text-2xl font-bold text-white font-mono">{{ config.teamsCount }}</div>
              <div class="text-xs text-neutral-600 mt-1">支队伍</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">晋级队伍数</div>
              <div class="text-2xl font-bold text-amber-500 font-mono">{{ config.advanceCount }}</div>
              <div class="text-xs text-neutral-600 mt-1">支晋级</div>
            </div>
          </div>

          <!-- 比赛格式 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">比赛格式</div>
            <div class="text-lg font-medium text-white">{{ config.format }}</div>
          </div>

          <!-- 赛制预览 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">赛制概览</div>
            <div class="text-sm text-neutral-300">{{ config.teamsCount }} 支队伍 → 单败淘汰 → {{ config.advanceCount }} 支队伍晋级</div>
            <div class="text-xs text-neutral-500 mt-1">共需 {{ Math.ceil(Math.log2(config.teamsCount)) }} 轮比赛</div>
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
              <option value="BO5">BO5 (五局三胜)</option>
            </select>
          </div>

          <!-- 模板选择 (INIT-ONLY) -->
          <div v-if="currentMode === ConfigMode.INIT">
            <label class="text-xs text-neutral-500 mb-2 block">淘汰赛模板</label>
            <div class="grid grid-cols-4 gap-3">
              <div
                v-for="template in templates"
                :key="template.value"
                class="template-option"
                :class="{ selected: config.template === template.value }"
                @click="selectTemplate(template.value)"
              >
                <div class="template-label">{{ template.label }}</div>
                <div class="template-detail">{{ template.detail }}</div>
              </div>
            </div>
          </div>

          <!-- 队伍数量 (INIT-ONLY) -->
          <div v-if="currentMode === ConfigMode.INIT" class="grid grid-cols-2 gap-6">
            <div>
              <label class="text-xs text-neutral-500 mb-2 block">参赛队伍数</label>
              <div class="relative">
                <input
                  type="number"
                  v-model.number="config.teamsCount"
                  :disabled="config.template !== KnockoutTemplate.CUSTOM"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                  @input="handleUpdate"
                />
                <div class="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-neutral-600">支队伍</div>
              </div>
            </div>
            <div>
              <label class="text-xs text-neutral-500 mb-2 block">晋级队伍数</label>
              <div class="relative">
                <input
                  type="number"
                  v-model.number="config.advanceCount"
                  :disabled="config.template !== KnockoutTemplate.CUSTOM"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                  @input="handleUpdate"
                />
                <div class="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-neutral-600">支晋级</div>
              </div>
            </div>
          </div>

          <!-- 晋级规则 -->
          <div>
            <label class="text-xs text-neutral-500 mb-2 block">晋级规则</label>
            <div class="bg-black border border-neutral-700 rounded-lg p-4 space-y-3">
              <label class="flex items-center justify-between">
                <span class="text-sm text-neutral-300">启用种子队</span>
                <input type="checkbox" v-model="config.enableSeeding" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
              </label>
              <label class="flex items-center justify-between">
                <span class="text-sm text-neutral-300">第三名决赛</span>
                <input type="checkbox" v-model="config.playThirdPlace" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
              </label>
              <label class="flex items-center justify-between">
                <span class="text-sm text-neutral-300">单轮模式(每轮一赛段,胜者全晋级)</span>
                <input type="checkbox" v-model="config.singleRound" class="accent-amber-500 w-4 h-4" @change="handleUpdate" />
              </label>
              <div>
                <span class="text-sm text-neutral-300 block mb-2">配对模式</span>
                <select v-model="config.pairingMode" class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none" @change="handleUpdate">
                  <option value="SEQUENTIAL">顺序(1-2、3-4 相邻)</option>
                  <option value="SEED">种子对位(1-N、2-(N-1),首轮常用)</option>
                </select>
              </div>
              <div>
                <span class="text-sm text-neutral-300 block mb-2">结果公布模式</span>
                <select v-model="config.publishMode" class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none" @change="handleUpdate">
                  <option value="AUTO">自动公布(裁判判完即公布)</option>
                  <option value="MANUAL">手动公布(导播台确认后公布)</option>
                  <option value="DIRECTOR">导播台判定(裁判不判罚,导播台选胜负)</option>
                </select>
              </div>
            </div>
          </div>

          <!-- 打分与转场配置 -->
          <ScoringTransitionConfig
            :scoring="config.scoring"
            :transition="config.transition"
            @update:scoring="(v) => { config.scoring = v; handleUpdate(); }"
            @update:transition="(v) => { config.transition = v; handleUpdate(); }"
          />

          <!-- 预览 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">赛制预览</div>
            <div class="text-sm text-neutral-300">{{ config.teamsCount }} 支队伍 → 单败淘汰 → {{ config.advanceCount }} 支队伍晋级</div>
            <div class="text-xs text-neutral-500 mt-1">共需 {{ Math.ceil(Math.log2(config.teamsCount)) }} 轮比赛</div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue';
import { Trophy } from 'lucide-vue-next';
import { KnockoutTemplate, KnockoutConfig, StageData, ConfigMode } from './types';
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
const config = ref<KnockoutConfig & { enableSeeding?: boolean; playThirdPlace?: boolean; singleRound?: boolean; pairingMode?: string; publishMode?: string; scoring?: any; transition?: any }>({
  template: KnockoutTemplate.ROUND_32,
  format: 'BO3',
  teamsCount: 32,
  advanceCount: 16,
  enableSeeding: false,
  playThirdPlace: false,
  singleRound: false,
  pairingMode: 'SEQUENTIAL',
  publishMode: 'AUTO',
  scoring: { type: 'WIN_LOSS_DRAW', matchMode: 'STANDARD', aggregateRule: 'SUM', refereeAggregateRule: 'AVG', trimRatio: 0.1, dimensions: [], outcomeRules: { winScore: 1, drawScore: 0.5, lossScore: 0 } },
  transition: { mode: 'AUTO', reshuffle: false }
});

// 模板列表
const templates = [
  { value: KnockoutTemplate.FINAL, label: '决赛', detail: '2进1' },
  { value: KnockoutTemplate.SEMI_FINAL, label: '半决赛', detail: '4进2' },
  { value: KnockoutTemplate.QUARTER_FINAL, label: '1/4决赛', detail: '8进4' },
  { value: KnockoutTemplate.ROUND_16, label: '16进8', detail: '16支队伍' },
  { value: KnockoutTemplate.ROUND_32, label: '32进16', detail: '32支队伍' },
  { value: KnockoutTemplate.ROUND_64, label: '64进32', detail: '64支队伍' },
  { value: KnockoutTemplate.CUSTOM, label: '自定义', detail: '手动设置' }
];

// 解析配置(兼容包裹式 RuleConfigHolder 与旧扁平结构)
const parseConfig = () => {
  try {
    if (!props.stage.ruleConfig) {
      // 无 ruleConfig:用赛段权威字段兜底
      if (props.stage.teamCountStart) config.value.teamsCount = props.stage.teamCountStart;
      if (props.stage.teamCountEnd) config.value.advanceCount = props.stage.teamCountEnd;
      config.value.template = inferTemplate(config.value.teamsCount, config.value.advanceCount);
      return;
    }
    const parsed = JSON.parse(props.stage.ruleConfig);
    if (parsed.format) config.value.format = parsed.format;
    const ko = parsed.knockout || parsed;
    if (ko.template !== undefined) config.value.template = ko.template;
    if (ko.teamsCount !== undefined) config.value.teamsCount = ko.teamsCount;
    else if (props.stage.teamCountStart) config.value.teamsCount = props.stage.teamCountStart;
    if (ko.advanceCount !== undefined) config.value.advanceCount = ko.advanceCount;
    else if (props.stage.teamCountEnd) config.value.advanceCount = props.stage.teamCountEnd;
    // 老数据 ruleConfig 未写 template 时,按参赛/晋级人数反推模版,避免残留默认「32进16」
    if (ko.template === undefined) {
      config.value.template = inferTemplate(config.value.teamsCount, config.value.advanceCount);
    }
    if (ko.singleRound !== undefined) config.value.singleRound = ko.singleRound;
    if (ko.pairingMode !== undefined) config.value.pairingMode = ko.pairingMode;
    if (ko.publishMode !== undefined) config.value.publishMode = ko.publishMode;
    if (parsed.scoring) config.value.scoring = parsed.scoring;
    if (parsed.transition) config.value.transition = parsed.transition;
  } catch (e) {
    console.warn('Failed to parse ruleConfig:', e);
  }
};

/** 按参赛/晋级人数反推模版(ruleConfig 未显式写 template 时使用) */
const inferTemplate = (teams: number, advance: number): KnockoutTemplate => {
  if (teams === 2 && advance === 1) return KnockoutTemplate.FINAL;
  if (teams === 4 && advance === 2) return KnockoutTemplate.SEMI_FINAL;
  if (teams === 8 && advance === 4) return KnockoutTemplate.QUARTER_FINAL;
  if (teams === 16 && advance === 8) return KnockoutTemplate.ROUND_16;
  if (teams === 32 && advance === 16) return KnockoutTemplate.ROUND_32;
  if (teams === 64 && advance === 32) return KnockoutTemplate.ROUND_64;
  return KnockoutTemplate.CUSTOM;
};

// 序列化为包裹式 RuleConfigHolder(对齐后端)
const serializeConfig = () => {
  return JSON.stringify({
    mode: 'KNOCKOUT',
    format: config.value.format,
    knockout: {
      template: config.value.template,
      teamsCount: config.value.teamsCount,
      advanceCount: config.value.advanceCount,
      singleRound: config.value.singleRound,
      pairingMode: config.value.pairingMode,
      publishMode: config.value.publishMode
    },
    scoring: config.value.scoring,
    transition: config.value.transition
  });
};

// 选择模板
const selectTemplate = (template: KnockoutTemplate) => {
  config.value.template = template;

  // 根据模板自动设置队伍数
  const templateMap: Record<KnockoutTemplate, { teams: number; advance: number }> = {
    [KnockoutTemplate.FINAL]: { teams: 2, advance: 1 },
    [KnockoutTemplate.SEMI_FINAL]: { teams: 4, advance: 2 },
    [KnockoutTemplate.QUARTER_FINAL]: { teams: 8, advance: 4 },
    [KnockoutTemplate.ROUND_16]: { teams: 16, advance: 8 },
    [KnockoutTemplate.ROUND_32]: { teams: 32, advance: 16 },
    [KnockoutTemplate.ROUND_64]: { teams: 64, advance: 32 },
    [KnockoutTemplate.CUSTOM]: { teams: config.value.teamsCount, advance: config.value.advanceCount }
  };

  if (template !== KnockoutTemplate.CUSTOM) {
    config.value.teamsCount = templateMap[template].teams;
    config.value.advanceCount = templateMap[template].advance;
  }

  handleUpdate();
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

// 获取模板标签
const getTemplateLabel = (template: KnockoutTemplate) => {
  const t = templates.find((item) => item.value === template);
  return t?.label || template;
};

// 获取模板详情
const getTemplateDetail = (template: KnockoutTemplate) => {
  const t = templates.find((item) => item.value === template);
  return t?.detail || '';
};

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

.template-option {
  background: #0a0a0a;
  border: 2px solid #262626;
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.2s;
  text-align: center;
}

.template-option:hover {
  border-color: #404040;
}

.template-option.selected {
  border-color: #f59e0b;
  background: rgba(245, 158, 11, 0.1);
}

.template-label {
  font-weight: 600;
  color: #e5e5e5;
  font-size: 13px;
}

.template-detail {
  font-size: 11px;
  color: #737373;
  margin-top: 2px;
}

input[type='number']::-webkit-inner-spin-button,
input[type='number']::-webkit-outer-spin-button {
  opacity: 1;
}
</style>
