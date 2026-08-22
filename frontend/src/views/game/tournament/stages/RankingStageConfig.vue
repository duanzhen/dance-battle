<template>
  <div class="stage-config">
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-6 flex items-center gap-2">
        <Trophy class="w-4 h-4" /> 排名赛配置
      </h3>

      <div class="space-y-6">
        <!-- INIT_DONE 模式: 只读展示 -->
        <template v-if="currentMode === ConfigMode.INIT_DONE">
          <div class="grid grid-cols-3 gap-4">
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">参赛人数</div>
              <div class="text-2xl font-bold text-white font-mono">{{ config.scale }}</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">晋级名额</div>
              <div class="text-2xl font-bold text-amber-500 font-mono">{{ config.advanceCount }}</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">分圈</div>
              <div class="text-2xl font-bold text-white font-mono">{{ config.circles || 1 }}</div>
            </div>
          </div>

          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4 space-y-2">
            <div class="text-xs text-neutral-500 mb-1">结果公布</div>
            <div class="text-sm text-neutral-200">
              {{ publishModeLabel }}
              <span v-if="config.publishScope === 'TOP_N'" class="text-neutral-500">(只公布前 {{ config.advanceCount }} 名)</span>
            </div>
            <div class="text-xs text-neutral-600">维度:{{ scoring.dimensions.length }} 个</div>
          </div>
        </template>

        <!-- INIT/NORMAL 模式: 可编辑 -->
        <template v-else>
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
            <label class="text-xs text-neutral-500 mb-2 block">参赛规模</label>
            <div class="grid grid-cols-3 gap-4">
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">参赛人数</label>
                <div class="relative">
                  <input
                    type="number"
                    v-model.number="config.scale"
                    :min="2"
                    :max="512"
                    class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                    @input="handleUpdate"
                  />
                  <div class="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-neutral-600">人</div>
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
                  <div class="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-neutral-600">人</div>
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
              分 {{ config.circles }} 圈并行进行,每圈约 {{ Math.ceil(config.scale / config.circles) }} 人、晋级 {{ Math.floor(config.advanceCount / config.circles) }} 人(请确保晋级名额可被圈数整除)
            </p>
          </div>

          <!-- 结果公布模式 -->
          <div>
            <label class="text-xs text-neutral-500 mb-2 block">结果公布</label>
            <div class="grid grid-cols-2 gap-4">
              <div>
                <select
                  v-model="config.publishMode"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @change="handleUpdate"
                >
                  <option value="AUTO">实时公布(边打边显示)</option>
                  <option value="MANUAL">手动公布(导播台确认后公布)</option>
                  <option value="BATCH">全部完成后一次性公布</option>
                </select>
              </div>
              <div v-if="config.publishMode === 'BATCH'">
                <select
                  v-model="config.publishScope"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @change="handleUpdate"
                >
                  <option value="ALL">公布全部排名</option>
                  <option value="TOP_N">只公布前 {{ config.advanceCount }} 名晋级名单</option>
                </select>
              </div>
            </div>
          </div>

          <!-- 打分维度配置 -->
          <div>
            <div class="flex items-center justify-between mb-2">
              <label class="text-xs text-neutral-500">评分维度(自定义)</label>
              <button @click="addDim" class="text-xs text-amber-500 hover:text-amber-400">+ 添加维度</button>
            </div>
            <div v-if="scoring.dimensions.length === 0" class="text-xs text-neutral-600">未配置维度(将按单一总分处理)</div>
            <div
              v-for="(d, i) in scoring.dimensions"
              :key="i"
              class="grid grid-cols-12 gap-2 mb-2 items-center"
            >
              <input
                v-model="d.key"
                placeholder="标识(TECH)"
                class="cfg-input col-span-3 bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                @input="handleUpdate"
              />
              <input
                v-model="d.name"
                placeholder="名称(技术)"
                class="cfg-input col-span-3 bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                @input="handleUpdate"
              />
              <input
                v-model.number="d.weight"
                type="number"
                step="0.1"
                min="0"
                placeholder="权重"
                class="cfg-input col-span-2 bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                @input="handleUpdate"
              />
              <input
                v-model.number="d.maxScore"
                type="number"
                min="1"
                max="1000"
                placeholder="满分"
                class="cfg-input col-span-2 bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                @input="handleUpdate"
              />
              <button
                @click="removeDim(i)"
                class="col-span-2 text-xs text-red-400 hover:text-red-300"
              >
                删除
              </button>
            </div>
            <div v-if="scoring.dimensions.length > 0" class="grid grid-cols-2 gap-4 mt-3">
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">裁判间汇总</label>
                <select
                  v-model="scoring.refereeAggregateRule"
                  class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                  @change="handleUpdate"
                >
                  <option value="AVG">平均</option>
                  <option value="SUM">求和</option>
                  <option value="TRIMMED_MEAN">去极值平均</option>
                </select>
              </div>
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">维度间汇总</label>
                <select
                  v-model="scoring.aggregateRule"
                  class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
                  @change="handleUpdate"
                >
                  <option value="SUM">求和</option>
                  <option value="AVG">平均</option>
                  <option value="WEIGHTED">加权(按维度权重)</option>
                  <option value="TRIMMED_MEAN">去极值平均</option>
                </select>
              </div>
            </div>
          </div>

          <!-- 预览 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">赛制预览</div>
            <div class="text-sm text-neutral-300">{{ config.scale }} 名选手参加排名赛</div>
            <div class="text-sm text-neutral-300 mt-1">按 {{ scoring.dimensions.length || '单一' }} 个维度打分,前 {{ config.advanceCount }} 名晋级</div>
            <div class="text-xs text-neutral-500 mt-2">晋级率:{{ ((config.advanceCount / config.scale) * 100).toFixed(1) }}%</div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue';
import { Trophy } from 'lucide-vue-next';
import { StageData, ConfigMode } from './types';

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
  mode: 'RANK',
  scale: 32,
  advanceCount: 16,
  circles: 1,
  format: 'BO1',
  publishMode: 'AUTO',
  publishScope: 'ALL'
});

// 打分配置(独立维护,序列化时并入 ruleConfig.scoring)
const scoring = ref<any>({
  type: 'MULTI_DIM',
  matchMode: 'RANKING',
  refereeAggregateRule: 'AVG',
  aggregateRule: 'SUM',
  trimRatio: 0.1,
  dimensions: [
    { key: 'TECH', name: '技术', weight: 0.4, maxScore: 100 },
    { key: 'SHOW', name: '表现力', weight: 0.3, maxScore: 100 },
    { key: 'CREAT', name: '创意', weight: 0.3, maxScore: 100 }
  ]
});

const publishModeLabel = computed(() => {
  const map: Record<string, string> = {
    AUTO: '实时公布',
    MANUAL: '手动公布',
    BATCH: '全部完成后一次性公布'
  };
  return map[config.value.publishMode] || config.value.publishMode;
});

// 解析配置:兼容旧数据(顶层扁平结构或嵌套 scoring)
const parseConfig = () => {
  try {
    if (props.stage.ruleConfig) {
      const parsed = JSON.parse(props.stage.ruleConfig);
      config.value = { ...config.value, ...parsed };
      if (parsed.scoring) {
        scoring.value = {
          ...scoring.value,
          ...parsed.scoring,
          dimensions: parsed.scoring.dimensions?.length ? parsed.scoring.dimensions : scoring.value.dimensions
        };
      }
    }
  } catch (e) {
    console.warn('Failed to parse ruleConfig:', e);
  }
};

// 序列化配置
const serializeConfig = () => {
  return JSON.stringify({
    ...config.value,
    scoring: scoring.value
  });
};

const addDim = () => {
  scoring.value.dimensions.push({ key: '', name: '', weight: 0, maxScore: 100 });
  handleUpdate();
};

const removeDim = (i: number) => {
  scoring.value.dimensions.splice(i, 1);
  handleUpdate();
};

// 更新处理
const handleUpdate = () => {
  localStage.value.ruleConfig = serializeConfig();
  localStage.value.teamCountStart = config.value.scale;
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
