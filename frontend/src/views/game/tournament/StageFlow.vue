<template>
  <div class="h-full flex flex-col bg-[#0a0a0a] text-neutral-200 font-sans overflow-visible">
    <div id="top" class="flex-none bg-neutral-900 border-b border-neutral-800 py-0 px-8 relative">
      <div class="overflow-x-auto !overflow-y-visible">
        <div class="flex items-center min-w-max px-2 pt-6 pb-6">
          <template v-for="(stage, index) in stages" :key="stage.id">
            <div
              @click="selectStage(stage.id)"
              class="relative z-10 w-48 h-28 flex flex-col items-center justify-center gap-2 rounded-xl border-2 cursor-pointer group bg-[#0a0a0a] hover:-translate-y-1 transition-all duration-200 ease-out"
              :class="[
                selection.type === 'STAGE' && selection.id === stage.id
                  ? 'border-amber-500 selected-glow'
                  : 'border-neutral-800 hover:border-neutral-600'
              ]"
            >
              <div
                class="absolute -top-3 left-1/2 -translate-x-1/2 px-2.5 py-0.5 text-[10px] font-bold rounded-full shadow-lg z-20 flex items-center gap-1"
                :class="{
                  'bg-gradient-to-r from-blue-600 to-blue-500 text-white shadow-blue-500/30': stage.status === 'DRAFT',
                  'bg-neutral-700 text-neutral-300': stage.status === 'PENDING',
                  'bg-gradient-to-r from-amber-600 to-amber-500 text-white shadow-amber-500/30': stage.status === 'GAMING',
                  'bg-gradient-to-r from-green-600 to-green-500 text-white shadow-green-500/30': stage.status === 'SETTLED',
                  'bg-gradient-to-r from-red-600 to-red-500 text-white shadow-red-500/30': stage.status === 'DISCARD'
                }"
              >
                {{ getStatusText(stage.status) }}
              </div>
              <span class="text-xs font-mono text-neutral-500 uppercase tracking-wider">赛段 {{ index + 1 }}</span>
              <h3
                class="text-sm font-bold text-center px-2 truncate w-full"
                :class="selection.type === 'STAGE' && selection.id === stage.id ? 'text-white' : 'text-neutral-400'"
              >
                {{ stage.name }}
              </h3>
              <div class="flex items-center gap-2 text-xs font-mono bg-neutral-900 px-2 py-1 rounded border border-neutral-800">
                <span class="text-neutral-500">{{ stage.teamCountStart }}</span>
                <ArrowRight class="w-3 h-3 text-neutral-600" />
                <span class="text-amber-500">{{ stage.teamCountEnd }}</span>
              </div>
              <div
                v-if="selection.type === 'STAGE' && selection.id === stage.id"
                class="absolute -bottom-3 w-4 h-4 bg-amber-500 rotate-45 border-b border-r border-amber-600 z-0"
              ></div>
            </div>

            <div v-if="index < stages.length - 1" class="relative flex items-center justify-center w-24">
              <div class="absolute w-full h-0.5 bg-neutral-800 -z-10"></div>

              <div
                v-if="selection.type === 'TRANSITION' && selection.id === index"
                class="absolute w-full h-0.5 bg-amber-500/50 shadow-[0_0_10px_rgba(245,158,11,0.5)] -z-0"
              ></div>

              <button
                @click="selectTransition(index)"
                class="w-8 h-8 rounded-full border-2 flex items-center justify-center transition-all bg-[#0a0a0a] z-10"
                :class="[
                  selection.type === 'TRANSITION' && selection.id === index
                    ? 'border-amber-500 text-amber-500 scale-110 shadow-[0_0_15px_rgba(245,158,11,0.4)]'
                    : 'border-neutral-700 text-neutral-600 hover:border-neutral-500 hover:text-neutral-400'
                ]"
                title="配置转场规则"
              >
                <SlidersHorizontal class="w-4 h-4" />
              </button>

              <!-- 插入赛段按钮 - 只有当前赛段不是已结束状态时才显示 -->
              <button
                v-if="stage.status !== 'SETTLED' && stage.nextStageId"
                @click.stop="insertStageAfter(stage.id)"
                class="absolute -bottom-8 w-6 h-6 rounded-full border border-dashed border-neutral-600 flex items-center justify-center transition-all bg-neutral-900 z-10 text-neutral-600 hover:scale-110 hover:border-amber-500 hover:text-amber-500"
                title="在此处插入赛段"
              >
                <Plus class="w-3 h-3" />
              </button>

              <span class="absolute -bottom-6 text-[10px] font-mono text-neutral-600 uppercase"> </span>
            </div>
          </template>

          <button
            @click="addStage"
            class="w-10 h-10 rounded-full border border-dashed border-neutral-700 flex items-center justify-center text-neutral-600 hover:text-amber-500 hover:border-amber-500 transition-all ml-8"
          >
            <Plus class="w-5 h-5" />
          </button>
        </div>
      </div>
    </div>

    <div class="flex-1 overflow-y-auto animate-fade-in bg-black/20">
      <!-- 新建赛段模式:类型选择 + 配置(中间区域) -->
      <div v-if="isCreatingStage" class="flex justify-center p-8">
        <div class="w-[900px] flex-shrink-0">
          <!-- 步骤1: 选择赛段类型 -->
          <div v-if="createStep === 1" class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
            <div class="flex items-center justify-between mb-4">
              <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider">选择赛段类型</h3>
              <span v-if="insertAfterStageId" class="text-[10px] text-amber-500">插入位置:{{ insertAfterName }} 之后</span>
            </div>
            <div class="grid grid-cols-2 gap-3">
              <button
                v-for="type in stageTypes"
                :key="type.mode"
                @click="selectCreateType(type.mode)"
                class="flex flex-col items-center justify-center gap-2 p-5 rounded-xl border-2 transition-all bg-neutral-800/40 hover:bg-neutral-800"
                :class="selectedCreateMode === type.mode ? 'border-amber-500' : 'border-neutral-700 hover:border-neutral-500'"
              >
                <component :is="type.icon" class="w-6 h-6 mb-1" />
                <div class="text-sm font-medium text-neutral-200">{{ type.label }}</div>
                <div class="text-xs text-neutral-500">{{ type.description }}</div>
              </button>
            </div>
            <div class="pt-4 border-t border-neutral-800 mt-4">
              <button
                @click="handleCancelCreate"
                class="w-full py-2.5 text-sm font-medium rounded-lg border border-neutral-700 text-neutral-400 hover:bg-neutral-800 transition-colors"
              >
                取消
              </button>
            </div>
          </div>

          <!-- 步骤2: 配置赛段 -->
          <div v-else class="space-y-4">
            <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
              <div class="flex items-center justify-between mb-4">
                <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider">配置{{ getStageModeLabel(selectedCreateMode) }}</h3>
                <span v-if="insertAfterStageId" class="text-[10px] text-amber-500">插入位置:{{ insertAfterName }} 之后</span>
              </div>
              <div>
                <label class="text-xs text-neutral-500 mb-2 block">赛段名称</label>
                <input
                  v-model="newStageData.name"
                  class="w-full bg-black border border-neutral-700 rounded-lg p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  placeholder="输入赛段名称"
                />
              </div>
              <div class="mt-6 pt-6 border-t border-neutral-800">
                <component
                  v-if="getStageConfigComponent(selectedCreateMode)"
                  :is="getStageConfigComponent(selectedCreateMode)"
                  :stage="tempStage"
                  :mode="ConfigMode.INIT"
                  @update="handleTempStageUpdate"
                />
              </div>
            </div>
            <div class="flex justify-end gap-3">
              <button
                @click="createStep = 1"
                class="px-6 py-2.5 text-sm font-medium rounded-lg border border-neutral-700 text-neutral-400 hover:bg-neutral-800 transition-colors"
              >
                返回
              </button>
              <button
                @click="completeCreate"
                :disabled="!canCompleteCreate"
                class="px-6 py-2.5 text-sm font-medium rounded-lg bg-amber-500 text-white hover:bg-amber-600 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                完成配置
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- 编辑已有赛段 -->
      <div v-else-if="selection.type === 'STAGE' && currentStage" class="flex justify-center p-8">
        <div class="flex gap-4">
          <!-- 左侧:专用配置 -->
          <div class="w-[900px] flex-shrink-0">
            <div class="mb-4">
              <StageCompetitorList
                :key="'comp-' + currentStage.id"
                :stage-id="currentStage.id"
                :stage-mode="currentStage.stageMode"
                :stage-status="currentStage.status"
                :is-initialized="currentStage.isInitialized"
              />
            </div>

            <component
              :key="'config-' + currentStage.id"
              :is="getStageConfigComponent(currentStage.stageMode)"
              :stage="currentStage as StageData"
              :mode="currentConfigMode"
              @update="handleStageUpdate"
            />
            <div v-if="!getStageConfigComponent(currentStage.stageMode)" class="bg-neutral-900 border border-neutral-800 rounded-xl p-8 text-center">
              <p class="text-neutral-500">该赛段类型暂无配置界面</p>
              <p class="text-xs text-neutral-600 mt-2">赛段类型: {{ currentStage?.stageMode }}</p>
            </div>
          </div>

          <!-- 右侧:通用配置 -->
          <div class="w-[320px] flex-shrink-0">
            <div class="bg-neutral-900 border-2 border-neutral-800 rounded-2xl shadow-2xl overflow-hidden">
              <StageSidebar
                :key="'sidebar-' + currentStage.id"
                :stage="currentStage as StageData"
                :stages="stages"
                @update="handleStageUpdate"
                @delete="handleDeleteStage"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- 空状态提示 -->
      <div v-else-if="selection.type === 'STAGE' && !currentStage" class="flex items-center justify-center h-full">
        <div class="text-center">
          <p class="text-neutral-500 mb-4">暂无赛段数据</p>
          <button @click="addStage" class="px-6 py-2.5 bg-amber-500 text-neutral-900 rounded-lg font-medium hover:bg-amber-600 transition-colors">
            创建第一个赛段
          </button>
        </div>
      </div>

      <div v-else-if="selection.type === 'TRANSITION'" class="max-w-4xl mx-auto animate-fade-in">
        <TransitionConfig
          :source-stage-id="stages[selection.id].id"
          :source-stage-name="stages[selection.id].name"
          :target-stage-id="stages[selection.id + 1].id"
          :target-stage-name="stages[selection.id + 1].name"
          :transition-index="selection.id"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, markRaw, onMounted } from 'vue';
import { Plus, ArrowRight, SlidersHorizontal, Trophy, Mic, Target, ListOrdered } from 'lucide-vue-next';
import { useRoute } from 'vue-router';
import { listStage, addStage as addStageApi, updateStage as updateStageApi, delStage as delStageApi } from '@/api/game/stage';
import { StageVO, StageForm } from '@/api/game/stage/types';
import StageSidebar from './stages/StageSidebar.vue';
import TransitionConfig from './TransitionConfig.vue';
import KnockoutStageConfig from './stages/KnockoutStageConfig.vue';
import GroupStageConfig from './stages/GroupStageConfig.vue';
import AuditionStageConfig from './stages/AuditionStageConfig.vue';
import ArenaStageConfig from './stages/ArenaStageConfig.vue';
import RankingStageConfig from './stages/RankingStageConfig.vue';
import StageCompetitorList from './stages/StageCompetitorList.vue';
import { StageMode, StageData, ConfigMode } from './stages/types';

// --- 类型定义 ---
interface Stage {
  id: string;
  name: string;
  stageMode: string;
  format: string;
  teamCountStart: number;
  teamCountEnd: number;
  status: 'DRAFT' | 'PENDING' | 'GAMING' | 'SETTLED' | 'DISCARD';
  ruleConfig: string;
  prevStageId: string | null; // 上一赛段ID
  nextStageId: string | null; // 下一赛段ID
  remark?: string; // 备注
  isInitialized?: boolean; // 是否已完成初始化配置
  tournamentId?: string; // 赛事ID
}

// --- 路由和数据加载 ---
const route = useRoute();
const tournamentId = ref<string | number>('');
const loading = ref(false);

// --- 状态数据 ---
const stages = ref<Stage[]>([]);

// --- 辅助函数 ---
// 安全的 ID 转换，过滤对象和无效值
const safeId = (id: any): string | null => {
  if (!id || typeof id === 'object') return null;
  return String(id);
};

// --- 加载赛段数据 ---
const loadStages = async (keepSelection: boolean = false) => {
  const id = route.query.id;
  if (!id || Array.isArray(id)) {
    console.warn('⚠️ URL 中未找到 id 参数');
    return;
  }

  // 保存当前选中状态
  const currentSelection = keepSelection ? selection.value : null;

  tournamentId.value = String(id);
  loading.value = true;

  try {
    const { data } = await listStage({ tournamentId: tournamentId.value } as any);
    const loadedStages = (data || []).map((item: StageVO) => ({
      id: String(item.id),
      name: item.name,
      stageMode: item.stageMode,
      format: item.format,
      teamCountStart: item.teamCountStart,
      teamCountEnd: item.teamCountEnd,
      status: item.status as Stage['status'],
      ruleConfig: item.ruleConfig,
      prevStageId: safeId(item.prevStageId),
      nextStageId: safeId(item.nextStageId),
      remark: item.remark,
      isInitialized: item.isInitialized === 1,
      tournamentId: String(item.tournamentId || '')
    }));

    // 按照双向链表顺序排序
    if (loadedStages.length > 0) {
      // 创建 ID 到 Stage 的映射
      const stageMap = new Map<string, Stage>();
      loadedStages.forEach((stage) => stageMap.set(stage.id, stage));

      // 找到头节点（prevStageId 为 null 的节点）
      let headStage: Stage | null = null;
      for (const stage of loadedStages) {
        if (!stage.prevStageId) {
          headStage = stage;
          break;
        }
      }

      // 如果找不到头节点，可能链表有问题，使用原始顺序
      if (!headStage && loadedStages.length > 0) {
        console.warn('⚠️ 未找到链表头节点，使用原始顺序');
        stages.value = loadedStages;
      } else if (headStage) {
        // 从头节点开始遍历链表，按顺序排列
        const sortedStages: Stage[] = [];
        let currentStage: Stage | null = headStage;
        const visited = new Set<string>();

        while (currentStage && !visited.has(currentStage.id)) {
          sortedStages.push(currentStage);
          visited.add(currentStage.id);

          // 通过 nextStageId 找下一个节点
          if (currentStage.nextStageId) {
            currentStage = stageMap.get(currentStage.nextStageId) || null;
          } else {
            currentStage = null;
          }
        }

        // 如果有节点没有被访问到（链表不完整），追加到末尾
        if (sortedStages.length < loadedStages.length) {
          console.warn('⚠️ 链表不完整，部分节点使用原始顺序');
          for (const stage of loadedStages) {
            if (!visited.has(stage.id)) {
              sortedStages.push(stage);
            }
          }
        }

        stages.value = sortedStages;
      }
    } else {
      stages.value = loadedStages;
    }

    // 需要保留选中状态时:赛段按 id 保持,转场按索引保持(仅当索引仍有效)
    if (keepSelection && currentSelection) {
      if (currentSelection.type === 'STAGE') {
        const stillExists = stages.value.some((s) => String(s.id) === currentSelection.id);
        if (stillExists) {
          selection.value = currentSelection;
        } else if (stages.value.length > 0) {
          selection.value = { type: 'STAGE', id: String(stages.value[0].id) };
        }
      } else if (currentSelection.type === 'TRANSITION' && currentSelection.id < stages.value.length - 1) {
        selection.value = currentSelection;
      } else if (stages.value.length > 0) {
        selection.value = { type: 'STAGE', id: String(stages.value[0].id) };
      }
    } else if (stages.value.length > 0) {
      // 默认选中第一个赛段
      selection.value = { type: 'STAGE', id: String(stages.value[0].id) };
    }
  } catch (error) {
    console.error('❌ 加载赛段数据失败:', error);
  } finally {
    loading.value = false;
  }
};

// 选中状态管理
type SelectionType = { type: 'STAGE'; id: string } | { type: 'TRANSITION'; id: number };
const selection = ref<SelectionType>({ type: 'STAGE', id: '' });

// 新建赛段模式
const isCreatingStage = ref(false);

// --- 新建赛段状态(中间区域两步流程) ---
const createStep = ref<1 | 2>(1);
const selectedCreateMode = ref<StageMode>(StageMode.KNOCKOUT);
const newStageData = ref({ name: '' });
const insertAfterStageId = ref<string | null>(null);
const insertAfterName = computed(() => {
  if (!insertAfterStageId.value) return '';
  return stages.value.find((s) => s.id === insertAfterStageId.value)?.name || '';
});

// 默认配置与类型列表(新建赛段时使用)
const defaultConfigs: Record<StageMode, any> = {
  [StageMode.KNOCKOUT]: { template: 'ROUND_16', format: 'BO3', teamsCount: 16, advanceCount: 8 },
  [StageMode.GROUP]: { groupCount: 4, teamsPerGroup: 4, format: 'BO1', winPoints: 3, drawPoints: 1, lossPoints: 0, advancePerGroup: 2 },
  [StageMode.AUDITION]: { scale: 32, format: 'BO1', advanceCondition: 'score', advanceCount: 16 },
  [StageMode.ARENA]: { format: 'BO1', defenderTeamId: '', challengerCount: 4, maxChallenges: 2, challengeOrder: 'RANDOM' },
  [StageMode.RANK]: {
    mode: 'RANK',
    scale: 32,
    advanceCount: 16,
    circles: 1,
    publishMode: 'AUTO',
    publishScope: 'ALL',
    showScore: true,
    scoreDisplay: 'TOTAL',
    scoring: {
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
    }
  }
};

const stageTypes = [
  { mode: StageMode.KNOCKOUT, label: '淘汰赛', description: '单败淘汰制', icon: Trophy },
  { mode: StageMode.AUDITION, label: '海选赛', description: '海选晋级', icon: Mic },
  { mode: StageMode.ARENA, label: '擂台赛', description: 'SEVEN TO SMOKE', icon: Target },
  { mode: StageMode.RANK, label: '排名赛', description: '多维度打分排名', icon: ListOrdered }
];

const stageModeLabels: Record<string, string> = {
  [StageMode.KNOCKOUT]: '淘汰赛',
  [StageMode.GROUP]: '小组赛',
  [StageMode.AUDITION]: '海选赛',
  [StageMode.ARENA]: '擂台赛',
  [StageMode.RANK]: '排名赛'
};
const getStageModeLabel = (mode: string) => stageModeLabels[mode] || mode;

const tempStage = ref<StageData>({
  id: 'temp',
  name: '',
  stageMode: StageMode.KNOCKOUT,
  status: 'DRAFT',
  ruleConfig: JSON.stringify(defaultConfigs[StageMode.KNOCKOUT]),
  teamCountStart: 0,
  teamCountEnd: 0
});

// 赛段配置组件映射
const stageConfigComponents = {
  [StageMode.KNOCKOUT]: markRaw(KnockoutStageConfig),
  [StageMode.GROUP]: markRaw(GroupStageConfig),
  [StageMode.AUDITION]: markRaw(AuditionStageConfig),
  [StageMode.ARENA]: markRaw(ArenaStageConfig),
  [StageMode.RANK]: markRaw(RankingStageConfig)
};

// 获取赛段配置组件
const getStageConfigComponent = (stageMode: string) => {
  return stageConfigComponents[stageMode as StageMode] || null;
};

// --- Computed Helpers ---

const currentStage = computed(() => {
  if (selection.value.type !== 'STAGE') return null;
  return stages.value.find((s) => s.id === selection.value.id);
});

// 计算当前配置模式
const currentConfigMode = computed<ConfigMode>(() => {
  if (isCreatingStage.value) {
    return ConfigMode.INIT;
  }

  if (currentStage.value?.isInitialized) {
    return ConfigMode.INIT_DONE;
  }

  return ConfigMode.NORMAL;
});

// --- Action Methods ---

const getStatusText = (status: Stage['status']) => {
  const statusMap = {
    'DRAFT': '规划中',
    'PENDING': '未开始',
    'GAMING': '进行中',
    'SETTLED': '已结束',
    'DISCARD': '已取消'
  };
  return statusMap[status];
};

const selectStage = (id: string) => {
  // 如果处于新建模式，点击已有赛段会取消新建
  if (isCreatingStage.value) {
    isCreatingStage.value = false;
  }
  selection.value = { type: 'STAGE', id: String(id) };
};

const selectTransition = (index: number) => {
  // 如果处于新建模式，点击转场配置会取消新建
  if (isCreatingStage.value) {
    isCreatingStage.value = false;
  }
  selection.value = { type: 'TRANSITION', id: index };
};

const addStage = () => {
  // 进入新建赛段模式
  isCreatingStage.value = true;
  createStep.value = 1;
  insertAfterStageId.value = null;
  selectedCreateMode.value = StageMode.KNOCKOUT;
  newStageData.value = { name: '' };
  tempStage.value = {
    id: 'temp',
    name: '',
    stageMode: StageMode.KNOCKOUT,
    status: 'DRAFT',
    ruleConfig: JSON.stringify(defaultConfigs[StageMode.KNOCKOUT]),
    teamCountStart: 0,
    teamCountEnd: 0
  };
};

// 选择赛段类型:进入第二步配置
const selectCreateType = (mode: StageMode) => {
  selectedCreateMode.value = mode;
  const typeInfo = stageTypes.find((t) => t.mode === mode);
  newStageData.value.name = typeInfo?.label || '';
  tempStage.value = {
    id: 'temp',
    name: newStageData.value.name,
    stageMode: mode,
    status: 'DRAFT',
    ruleConfig: JSON.stringify(defaultConfigs[mode]),
    teamCountStart: 0,
    teamCountEnd: 0
  };
  createStep.value = 2;
};

// 处理临时赛段更新(配置组件回写)
const handleTempStageUpdate = (updated: StageData) => {
  tempStage.value = { ...tempStage.value, ...updated };
};

// 验证是否可以完成创建
const canCompleteCreate = computed(() => {
  if (!newStageData.value.name.trim()) return false;
  try {
    const parsed = JSON.parse(tempStage.value.ruleConfig);
    if (selectedCreateMode.value === StageMode.KNOCKOUT) {
      const k = parsed.knockout || parsed;
      return k.teamsCount > 0 && k.advanceCount > 0;
    }
    if (selectedCreateMode.value === StageMode.GROUP) {
      const g = parsed.group || parsed;
      return g.groupCount >= 2 && g.teamsPerGroup >= 2 && g.advancePerGroup >= 1;
    }
    if (selectedCreateMode.value === StageMode.AUDITION) {
      return parsed.scale > 0 && parsed.advanceCount > 0;
    }
    if (selectedCreateMode.value === StageMode.ARENA) {
      return parsed.challengerCount > 0 && parsed.maxChallenges > 0;
    }
    if (selectedCreateMode.value === StageMode.RANK) {
      return parsed.scale > 0 && parsed.advanceCount > 0 && parsed.scoring?.dimensions?.length > 0;
    }
    return true;
  } catch (e) {
    return false;
  }
});

// 完成创建:调用创建接口(末尾追加或中间插入)
const completeCreate = () => {
  if (!canCompleteCreate.value) return;
  handleCreateStage(selectedCreateMode.value, newStageData.value.name, 'DRAFT', tempStage.value.ruleConfig);
};

// 处理新建赛段
const handleCreateStage = async (stageMode: StageMode, name: string, status: string, ruleConfig?: string) => {
  // 解析配置(如果有)或使用默认配置
  let config: any;
  if (ruleConfig) {
    try {
      config = JSON.parse(ruleConfig);
    } catch (e) {
      console.warn('Failed to parse ruleConfig, using default');
      config = {};
    }
  } else {
    // 默认配置(向后兼容)
    const defaultConfigs: Record<string, any> = {
      [StageMode.KNOCKOUT]: { template: 'ROUND_16', format: 'BO3', teamsCount: 16, advanceCount: 8 },
      [StageMode.GROUP]: { groupCount: 4, teamsPerGroup: 4, format: 'BO1', winPoints: 3, drawPoints: 1, lossPoints: 0, advancePerGroup: 2 },
      [StageMode.AUDITION]: { scale: 32, format: 'BO1', advanceCondition: 'score', advanceCount: 16 },
      [StageMode.ARENA]: { format: 'BO1', defenderTeamId: '', challengerCount: 4, maxChallenges: 2, challengeOrder: 'RANDOM' },
      [StageMode.RANK]: {
        mode: 'RANK',
        scale: 32,
        advanceCount: 16,
        circles: 1,
        publishMode: 'AUTO',
        publishScope: 'ALL',
        showScore: true,
        scoreDisplay: 'TOTAL',
        scoring: {
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
        }
      }
    };
    config = defaultConfigs[stageMode] || {};
  }

  // 插入模式:在指定赛段后插入(与正常新增一致,只是带上前后指针)
  let prevStageId: string | null = null;
  let nextStageId: string | null = null;

  if (insertAfterStageId.value) {
    const insertAfterStage = stages.value.find((s) => String(s.id) === insertAfterStageId.value);
    if (insertAfterStage) {
      prevStageId = safeId(insertAfterStage.id);
      nextStageId = safeId(insertAfterStage.nextStageId);
    }
  } else {
    // 追加模式：添加到末尾
    if (stages.value.length > 0) {
      const lastStage = stages.value[stages.value.length - 1];
      prevStageId = safeId(lastStage.id);
    }
  }

  try {
    // 构建表单数据 - 只在值存在时才传递指针字段
    const formData: StageForm = {
      tournamentId: String(tournamentId.value),
      name: name,
      stageMode: stageMode,
      format: config.format || 'BO3',
      teamCountStart: config.teamsCount || config.scale || 0,
      teamCountEnd: config.advanceCount || config.advanceQuota || 0,
      status: status,
      ruleConfig: ruleConfig || JSON.stringify(config),
      isInitialized: ruleConfig ? 1 : 0
    };

    // 只在值存在时才添加指针字段，避免传递 null
    if (prevStageId) {
      formData.prevStageId = prevStageId;
    }
    if (nextStageId) {
      formData.nextStageId = nextStageId;
    }

    const { data } = await addStageApi(formData);
    const newId = String(data);

    // 重新加载赛段数据，确保所有链表指针都是正确的
    await loadStages();

    // 退出新建模式
    isCreatingStage.value = false;
    createStep.value = 1;
    insertAfterStageId.value = null;

    // 选中新赛段（确保新赛段已在列表中）
    const newStageExists = stages.value.some((s) => String(s.id) === newId);
    if (newStageExists) {
      selectStage(newId);
    } else {
      // 如果新赛段不在列表中，选中第一个
      if (stages.value.length > 0) {
        selectStage(stages.value[0].id);
      }
    }
  } catch (error) {
    console.error('❌ 创建赛段失败:', error);
  }
};

// 取消新建赛段
const handleCancelCreate = () => {
  isCreatingStage.value = false;
  createStep.value = 1;
  insertAfterStageId.value = null;

  // 如果有赛段,选中第一个;否则保持空状态
  if (stages.value.length > 0) {
    selectStage(stages.value[0].id);
  }
};

// 处理赛段更新
const handleStageUpdate = async (updatedStage: StageData) => {
  const index = stages.value.findIndex((s) => String(s.id) === String(updatedStage.id));
  if (index === -1) return;

  const existingStage = stages.value[index];

  try {
    const formData: StageForm = {
      id: updatedStage.id,
      tournamentId: tournamentId.value,
      name: updatedStage.name,
      stageMode: updatedStage.stageMode,
      format: existingStage.format,
      teamCountStart: updatedStage.teamCountStart,
      teamCountEnd: updatedStage.teamCountEnd,
      status: updatedStage.status,
      ruleConfig: updatedStage.ruleConfig,
      isInitialized: updatedStage.isInitialized ? 1 : 0
    };

    // 只在值存在时才添加指针字段，避免传递 null
    const prevId = safeId(existingStage.prevStageId);
    const nextId = safeId(existingStage.nextStageId);
    if (prevId) {
      formData.prevStageId = prevId;
    }
    if (nextId) {
      formData.nextStageId = nextId;
    }

    await updateStageApi(formData);
    stages.value[index] = { ...stages.value[index], ...updatedStage } as Stage;
  } catch (error) {
    console.error('❌ 更新赛段失败:', error);
  }
};

const deleteStage = async () => {
  if (selection.value.type !== 'STAGE') return;

  const stageId = String(selection.value.id);
  const idx = stages.value.findIndex((s) => String(s.id) === stageId);
  if (idx === -1) return;

  try {
    // 删除赛段（后端会自动维护链表指针）
    await delStageApi(stageId);
    stages.value.splice(idx, 1);

    // 选中下一个可用赛段
    if (stages.value.length > 0) {
      const nextIndex = Math.min(idx, stages.value.length - 1);
      selection.value = { type: 'STAGE', id: stages.value[nextIndex].id };
    } else {
      selection.value = { type: 'STAGE', id: '' };
    }
  } catch (error) {
    console.error('❌ 删除赛段失败:', error);
  }
};

// 供侧边栏使用的删除处理器
const handleDeleteStage = () => {
  deleteStage();
};

// 在指定赛段后插入新赛段:与正常新增一致,只是记录插入位置(创建时带上 prev/next 指针)
const insertStageAfter = (stageId: string) => {
  const stage = stages.value.find((s) => s.id === stageId);
  if (!stage || !stage.nextStageId) return;
  isCreatingStage.value = true;
  createStep.value = 1;
  insertAfterStageId.value = String(stage.id);
  selectedCreateMode.value = StageMode.KNOCKOUT;
  newStageData.value = { name: '' };
  tempStage.value = {
    id: 'temp',
    name: '',
    stageMode: StageMode.KNOCKOUT,
    status: 'DRAFT',
    ruleConfig: JSON.stringify(defaultConfigs[StageMode.KNOCKOUT]),
    teamCountStart: 0,
    teamCountEnd: 0
  };
};

// --- 生命周期 ---
onMounted(() => {
  loadStages();
});
</script>

<style scoped>
.custom-scrollbar::-webkit-scrollbar {
  height: 6px;
}
.custom-scrollbar::-webkit-scrollbar-track {
  background: #171717;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: #404040;
  border-radius: 3px;
}

.animate-fade-in {
  animation: fadeIn 0.3s ease-out;
}
@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(5px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.selected-glow {
  filter: drop-shadow(0 0 8px rgba(245, 158, 11, 0.15));
}
</style>
