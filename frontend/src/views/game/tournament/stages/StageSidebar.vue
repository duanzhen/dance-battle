<template>
  <div class="stage-sidebar">
    <!-- 头部 -->
    <div class="px-6 py-4 border-b border-neutral-800">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider">
        {{ isCreating ? '新建赛段' : '通用配置' }}
      </h3>
    </div>

    <!-- 步骤1: 选择赛段类型 -->
    <div v-if="isCreating && step === 1" class="p-6 space-y-6">
      <div>
        <label class="text-xs text-neutral-500 mb-3 block">选择赛段类型</label>
        <div class="grid grid-cols-2 gap-3">
          <button
            v-for="type in stageTypes"
            :key="type.mode"
            @click="selectStageType(type.mode)"
            class="type-button"
            :class="{ selected: selectedStageMode === type.mode }"
          >
            <component :is="type.icon" class="w-6 h-6 mb-2" />
            <div class="text-sm font-medium text-neutral-200">{{ type.label }}</div>
            <div class="text-xs text-neutral-500 mt-1">{{ type.description }}</div>
          </button>
        </div>
      </div>

      <div class="pt-4 border-t border-neutral-800">
        <button
          @click="cancelCreate"
          class="w-full py-2.5 text-sm font-medium rounded-lg border border-neutral-700 text-neutral-400 hover:bg-neutral-800 transition-colors"
        >
          取消
        </button>
      </div>
    </div>

    <!-- 步骤2: 配置赛段信息 -->
    <div v-else-if="isCreating && step === 2" class="p-6 space-y-6">
      <!-- 已选类型显示 -->
      <div class="bg-amber-500/10 border border-amber-500/20 rounded-lg p-3">
        <div class="text-xs text-amber-500 mb-1">已选择</div>
        <div class="text-sm font-medium text-amber-500">{{ getStageModeLabel(selectedStageMode) }}</div>
      </div>

      <!-- 赛段名称 -->
      <div>
        <label class="text-xs text-neutral-500 mb-2 block">赛段名称</label>
        <input
          v-model="newStageData.name"
          class="w-full bg-black border border-neutral-700 rounded-lg p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
          placeholder="输入赛段名称"
        />
      </div>

      <!-- 嵌入配置组件 (INIT 模式) -->
      <div class="mt-6 pt-6 border-t border-neutral-800">
        <component
          v-if="getStageConfigComponent(selectedStageMode)"
          :is="getStageConfigComponent(selectedStageMode)"
          :stage="tempStage"
          :mode="ConfigMode.INIT"
          @update="handleTempStageUpdate"
        />
      </div>

      <!-- 操作按钮 -->
      <div class="pt-4 border-t border-neutral-800 space-y-2">
        <button
          @click="completeCreate"
          :disabled="!canComplete"
          class="w-full py-2.5 text-sm font-medium rounded-lg bg-amber-500 text-white hover:bg-amber-600 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >
          完成配置
        </button>
        <button
          @click="goBack"
          class="w-full py-2.5 text-sm font-medium rounded-lg border border-neutral-700 text-neutral-400 hover:bg-neutral-800 transition-colors"
        >
          返回
        </button>
      </div>
    </div>

    <!-- 正常模式: 编辑已有赛段 -->
    <div v-else class="p-6 space-y-6">
      <!-- 赛段名称 -->
      <div>
        <label class="text-xs text-neutral-500 mb-2 block">赛段名称</label>
        <input
          v-model="localStage.name"
          class="w-full bg-black border border-neutral-700 rounded-lg p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
          placeholder="输入赛段名称"
          @input="handleUpdate"
        />
      </div>

      <!-- 赛段状态 -->
      <div>
        <label class="text-xs text-neutral-500 mb-2 block">赛段状态</label>
        <div class="relative">
          <select
            v-model="localStage.status"
            :disabled="!canEditStatus"
            class="w-full bg-black border border-neutral-700 rounded-lg p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors appearance-none disabled:opacity-50 disabled:cursor-not-allowed"
            @change="handleUpdate"
          >
            <option value="DRAFT">规划中</option>
            <option value="PENDING">未开始</option>
            <option value="GAMING">进行中</option>
            <option value="SETTLED">已结束</option>
            <option value="DISCARD">已取消</option>
          </select>
          <div class="absolute right-3 top-1/2 -translate-y-1/2 pointer-events-none text-neutral-500">
            <ChevronDown class="w-4 h-4" />
          </div>
        </div>
      </div>

      <!-- 赛段类型 (只读) -->
      <div>
        <label class="text-xs text-neutral-500 mb-2 block">赛段类型</label>
        <div class="bg-black border border-neutral-800 rounded-lg p-2.5 text-sm text-neutral-400">
          {{ getStageModeLabel(localStage.stageMode) }}
        </div>
      </div>

      <!-- INIT_DONE 模式: 显示初始化配置 -->
      <div class="mt-6 pt-6 border-t border-neutral-800">
        <div class="text-xs text-neutral-500 mb-3 flex items-center gap-2">
          <span class="w-2 h-2 rounded-full bg-green-500"></span>
          初始化配置
        </div>
        <component
          v-if="getStageConfigComponent(localStage.stageMode)"
          :is="getStageConfigComponent(localStage.stageMode)"
          :stage="localStage"
          :mode="ConfigMode.INIT_DONE"
          @update="handleUpdate"
        />
      </div>

      <!-- 队伍数量预览 -->
      <div class="bg-black/50 border border-neutral-800 rounded-lg p-3">
        <div class="text-xs text-neutral-500 mb-1">队伍数量</div>
        <div class="flex items-center justify-between text-sm">
          <span class="text-neutral-400">起始</span>
          <span class="text-white font-mono">{{ localStage.teamCountStart }}</span>
        </div>
        <div class="w-full h-px bg-neutral-800 my-2"></div>
        <div class="flex items-center justify-between text-sm">
          <span class="text-neutral-400">晋级</span>
          <span class="text-amber-500 font-mono">{{ localStage.teamCountEnd }}</span>
        </div>
      </div>

      <!-- 流程生命周期操作 -->
      <div class="pt-4 border-t border-neutral-800 space-y-2">
        <div class="text-xs text-neutral-500 mb-1">流程操作</div>
        <button
          v-if="localStage.status === 'DRAFT'"
          @click="doInitialize"
          :disabled="lifecycleLoading"
          class="w-full py-2.5 text-sm font-medium rounded-lg bg-amber-500 text-white hover:bg-amber-600 transition-colors disabled:opacity-50"
        >
          初始化赛段
        </button>
        <template v-if="localStage.status === 'PENDING'">
          <button
            @click="doGenerateMatches"
            :disabled="lifecycleLoading"
            class="w-full py-2.5 text-sm font-medium rounded-lg bg-amber-500 text-white hover:bg-amber-600 transition-colors disabled:opacity-50"
          >
            生成对阵
          </button>
          <button
            @click="doStart"
            :disabled="lifecycleLoading || !canStartStage"
            :title="!canStartStage ? '上一赛段结束后方可开始本赛段' : undefined"
            class="w-full py-2.5 text-sm font-medium rounded-lg border border-amber-500 text-amber-500 hover:bg-amber-500/10 transition-colors disabled:opacity-50"
          >
            开始赛段
          </button>
          <p v-if="!canStartStage" class="text-[10px] text-neutral-500 leading-relaxed">
            上一赛段「{{ prevStage?.name || '未知' }}」尚未结束，结束后方可开始本赛段。
          </p>
        </template>
        <button
          v-if="localStage.status === 'GAMING'"
          @click="doComplete"
          :disabled="lifecycleLoading"
          class="w-full py-2.5 text-sm font-medium rounded-lg bg-green-600 text-white hover:bg-green-700 transition-colors disabled:opacity-50"
        >
          完成结算
        </button>
        <button
          v-if="localStage.status === 'SETTLED'"
          @click="doCalculateAdvancement"
          :disabled="lifecycleLoading"
          class="w-full py-2.5 text-sm font-medium rounded-lg border border-neutral-700 text-neutral-300 hover:bg-neutral-800 transition-colors disabled:opacity-50"
        >
          计算晋级
        </button>
      </div>

      <!-- 裁判组 -->
      <div v-if="localStage.id" class="pt-4 border-t border-neutral-800 space-y-2">
        <div class="flex items-center justify-between">
          <span class="text-xs text-neutral-500">裁判组</span>
          <span class="text-[10px] text-neutral-600">{{ selectedRefereeIds.length }} 人</span>
        </div>
        <div v-if="refereeList.length === 0" class="text-[10px] text-neutral-600 py-1">暂无裁判，请先在裁判组中添加</div>
        <div v-else class="max-h-36 overflow-y-auto space-y-1 custom-scrollbar">
          <label
            v-for="ref in refereeList"
            :key="ref.id"
            class="flex items-center gap-2 px-2 py-1.5 rounded cursor-pointer hover:bg-neutral-800/50 transition-colors"
          >
            <input
              type="checkbox"
              :checked="selectedRefereeIds.includes(String(ref.id))"
              @change="toggleReferee(String(ref.id))"
              class="w-3.5 h-3.5 rounded border-neutral-600 bg-neutral-800 text-amber-500 focus:ring-0 focus:ring-offset-0"
            />
            <span class="text-xs text-neutral-300 truncate">{{ ref.name }}</span>
          </label>
        </div>
        <button
          v-if="refereeDirty"
          @click="saveRefereeAssignment"
          :disabled="savingReferees"
          class="w-full py-1.5 text-xs font-medium rounded bg-amber-500/10 text-amber-500 border border-amber-500/20 hover:bg-amber-500/20 transition-colors"
        >
          {{ savingReferees ? '保存中...' : '保存裁判分配' }}
        </button>
      </div>

      <!-- 删除按钮 -->
      <div class="pt-4 border-t border-neutral-800">
        <button
          @click="handleDelete"
          :disabled="!canDelete"
          class="w-full py-2.5 text-sm font-medium rounded-lg transition-colors border"
          :class="
            canDelete
              ? 'text-red-500 border-red-900/30 hover:bg-red-900/10 hover:border-red-900/50'
              : 'text-neutral-600 border-neutral-800 cursor-not-allowed opacity-50'
          "
        >
          {{ canDelete ? '删除此赛段' : '无法删除' }}
        </button>
        <p v-if="!canDelete" class="text-xs text-neutral-600 mt-2 text-center">
          {{ getDeleteDisabledReason() }}
        </p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, computed, markRaw } from 'vue';
import { ChevronDown, Trophy, Mic, Target } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import { initializeStage, generateMatches, startStage, completeStage, calculateAdvancement } from '@/api/game/stage/lifecycle';
import { listReferee } from '@/api/game/referee';
import { getStageRefereeIds, assignStageReferees } from '@/api/game/refereeStage';
import { StageData, StageMode, ConfigMode } from './types';
import KnockoutStageConfig from './KnockoutStageConfig.vue';
import GroupStageConfig from './GroupStageConfig.vue';
import FFStageConfig from './FFStageConfig.vue';
import AuditionStageConfig from './AuditionStageConfig.vue';
import ArenaStageConfig from './ArenaStageConfig.vue';

// Props
const props = defineProps<{
  stage: StageData | null;
  isCreating?: boolean;
  stages?: Array<{
    id: number | string;
    name: string;
    status: string;
    prevStageId?: string | number | null;
  }>;
}>();

// Emits
const emit = defineEmits<{
  update: [stage: StageData];
  delete: [];
  create: [stageMode: StageMode, name: string, status: string, ruleConfig: string];
  cancel: [];
  refresh: [];
}>();

// 本地数据
const localStage = ref<StageData>(props.stage || ({} as StageData));

// 上一赛段:由父级传入的有序赛段列表反查(流程规范:上一赛段未结束不可开始下一赛段)
const prevStage = computed(() => {
  const cur = props.stages?.find((s) => String(s.id) === String(localStage.value.id));
  if (!cur?.prevStageId) return null;
  return props.stages?.find((s) => String(s.id) === String(cur.prevStageId)) || null;
});
const canStartStage = computed(() => !prevStage.value || prevStage.value.status === 'SETTLED');

// 新建赛段相关
const step = ref<1 | 2>(1);
const selectedStageMode = ref<StageMode>(StageMode.KNOCKOUT);
const newStageData = ref({
  name: '',
  status: 'DRAFT'
});

// 赛段配置组件映射
const stageConfigComponents = {
  [StageMode.KNOCKOUT]: markRaw(KnockoutStageConfig),
  [StageMode.GROUP]: markRaw(GroupStageConfig),
  [StageMode.FFA]: markRaw(FFStageConfig),
  [StageMode.AUDITION]: markRaw(AuditionStageConfig),
  [StageMode.ARENA]: markRaw(ArenaStageConfig)
};

// 获取赛段配置组件
const getStageConfigComponent = (stageMode: StageMode) => {
  return stageConfigComponents[stageMode] || null;
};

// 默认配置
const defaultConfigs: Record<StageMode, any> = {
  [StageMode.KNOCKOUT]: { template: 'ROUND_16', format: 'BO3', teamsCount: 16, advanceCount: 8 },
  [StageMode.GROUP]: { groupCount: 4, teamsPerGroup: 4, format: 'BO1', winPoints: 3, drawPoints: 1, lossPoints: 0, advancePerGroup: 2 },
  [StageMode.FFA]: { teamsCount: 8, matchCount: 3, format: 'BO1', winPoints: 3, lossPoints: 0, advanceCount: 4 },
  [StageMode.AUDITION]: { scale: 32, format: 'BO1', advanceCondition: 'score', advanceCount: 16 },
  [StageMode.ARENA]: { format: 'BO1', defenderTeamId: '', challengerCount: 4, maxChallenges: 2, challengeOrder: 'RANDOM' }
};

// 临时赛段数据 (用于步骤2配置预览)
const tempStage = ref<StageData>({
  id: 'temp',
  name: '',
  stageMode: StageMode.KNOCKOUT,
  status: 'DRAFT',
  ruleConfig: JSON.stringify(defaultConfigs[StageMode.KNOCKOUT]),
  teamCountStart: 0,
  teamCountEnd: 0
});

// 赛段类型配置
const stageTypes = [
  { mode: StageMode.KNOCKOUT, label: '淘汰赛', description: '单败淘汰制', icon: Trophy },
  { mode: StageMode.AUDITION, label: '选拔赛', description: '海选晋级', icon: Mic },
  { mode: StageMode.ARENA, label: '擂台赛', description: 'SEVEN TO SMOKE', icon: Target }
];

// 赛段类型标签映射
const stageModeLabels: Record<string, string> = {
  [StageMode.KNOCKOUT]: '淘汰赛',
  [StageMode.GROUP]: '小组赛',
  [StageMode.FFA]: '自由对抗赛',
  [StageMode.AUDITION]: '选拔赛',
  [StageMode.ARENA]: '擂台赛'
};

// 是否可编辑状态 (规划中/未开始可编辑)
const canEditStatus = computed(() => {
  return localStage.value.status === 'DRAFT' || localStage.value.status === 'PENDING';
});

// 是否可删除 (GAMING 和 SETTLED 禁用)
const canDelete = computed(() => {
  return localStage.value.status !== 'GAMING' && localStage.value.status !== 'SETTLED';
});

// 验证是否可以完成创建
const canComplete = computed(() => {
  // 必须填写赛段名称
  if (!newStageData.value.name.trim()) return false;

  // 验证配置组件数据
  try {
    const parsed = JSON.parse(tempStage.value.ruleConfig);

    // 根据不同赛段类型验证（兼容扁平默认格式与嵌套序列化格式）
    if (selectedStageMode.value === StageMode.KNOCKOUT) {
      const k = parsed.knockout || parsed;
      return k.teamsCount > 0 && k.advanceCount > 0;
    }
    if (selectedStageMode.value === StageMode.GROUP) {
      const g = parsed.group || parsed;
      return g.groupCount >= 2 && g.teamsPerGroup >= 2 && g.advancePerGroup >= 1;
    }
    if (selectedStageMode.value === StageMode.FFA) {
      return parsed.teamsCount > 0 && parsed.matchCount > 0 && parsed.advanceCount > 0;
    }
    if (selectedStageMode.value === StageMode.AUDITION) {
      return parsed.scale > 0 && parsed.advanceCount > 0;
    }
    if (selectedStageMode.value === StageMode.ARENA) {
      return parsed.challengerCount > 0 && parsed.maxChallenges > 0;
    }

    return true;
  } catch (e) {
    return false;
  }
});

// 获取删除禁用原因
const getDeleteDisabledReason = () => {
  if (localStage.value.status === 'GAMING') return '赛段进行中,无法删除';
  if (localStage.value.status === 'SETTLED') return '赛段已结束,无法删除';
  return '';
};

// 获取赛段类型标签
const getStageModeLabel = (mode: string) => {
  return stageModeLabels[mode] || mode;
};

// 选择赛段类型
const selectStageType = (mode: StageMode) => {
  selectedStageMode.value = mode;
  step.value = 2;

  // 设置默认名称
  const typeInfo = stageTypes.find((t) => t.mode === mode);
  if (typeInfo) {
    newStageData.value.name = typeInfo.label;
  }

  // 初始化临时赛段数据
  tempStage.value = {
    id: 'temp',
    name: newStageData.value.name,
    stageMode: mode,
    status: newStageData.value.status as any,
    ruleConfig: JSON.stringify(defaultConfigs[mode]),
    teamCountStart: 0,
    teamCountEnd: 0
  };
};

// 返回上一步
const goBack = () => {
  step.value = 1;
};

// 取消创建
const cancelCreate = () => {
  emit('cancel');
};

// 处理临时赛段更新
const handleTempStageUpdate = (updated: StageData) => {
  tempStage.value = { ...tempStage.value, ...updated };
};

// 完成创建
const completeCreate = () => {
  if (!canComplete.value) return;

  // 发出创建事件，状态固定为 DRAFT
  emit('create', selectedStageMode.value, newStageData.value.name, 'DRAFT', tempStage.value.ruleConfig);

  // 重置表单
  step.value = 1;
  selectedStageMode.value = StageMode.KNOCKOUT;
  newStageData.value = {
    name: '',
    status: 'DRAFT'
  };
};

// 更新处理
const handleUpdate = () => {
  if (localStage.value) {
    emit('update', localStage.value);
  }
};

// 删除处理
const handleDelete = () => {
  if (canDelete.value) {
    emit('delete');
  }
};

// ===== 流程生命周期操作 =====
const lifecycleLoading = ref(false);
const runLifecycle = async (fn: () => Promise<any>, successStatus?: string, successMsg?: string) => {
  if (!localStage.value?.id) {
    ElMessage.warning('请先保存赛段');
    return;
  }
  lifecycleLoading.value = true;
  try {
    await fn();
    if (successStatus) {
      localStage.value.status = successStatus as any;
    }
    ElMessage.success(successMsg || '操作成功');
    emit('update', localStage.value);
    emit('refresh');
  } catch (e: any) {
    ElMessage.error(e?.msg || e?.message || '操作失败');
  } finally {
    lifecycleLoading.value = false;
  }
};
const doInitialize = () => runLifecycle(() => initializeStage({ stageId: localStage.value.id }), 'PENDING', '初始化成功');
const doGenerateMatches = () => runLifecycle(() => generateMatches({ stageId: localStage.value.id }), undefined, '对阵已生成');
const doStart = () => runLifecycle(() => startStage(localStage.value.id), 'GAMING', '赛段已开始');
const doComplete = () => runLifecycle(() => completeStage(localStage.value.id), 'SETTLED', '赛段已结算');
const doCalculateAdvancement = () => runLifecycle(() => calculateAdvancement(localStage.value.id), undefined, '晋级已计算');

// ===== 裁判分配 =====
const refereeList = ref<{ id: string | number; name: string }[]>([]);
const selectedRefereeIds = ref<string[]>([]);
const originalRefereeIds = ref<string[]>([]);
const refereeDirty = computed(() => {
  const sorted = [...selectedRefereeIds.value].sort();
  const orig = [...originalRefereeIds.value].sort();
  return sorted.join(',') !== orig.join(',');
});
const savingReferees = ref(false);

const loadRefereeList = async (tournamentId: string | number) => {
  try {
    const res = await listReferee({ tournamentId } as any);
    refereeList.value = (res.data?.data || res.data || []).map((r: any) => ({ id: r.id, name: r.name }));
  } catch {
    refereeList.value = [];
  }
};

const loadRefereeAssignments = async (stageId: string | number) => {
  try {
    const res = await getStageRefereeIds(stageId);
    const ids = (res.data || []).map(String);
    selectedRefereeIds.value = ids;
    originalRefereeIds.value = [...ids];
  } catch {
    selectedRefereeIds.value = [];
    originalRefereeIds.value = [];
  }
};

const toggleReferee = (refereeId: string) => {
  const idx = selectedRefereeIds.value.indexOf(refereeId);
  if (idx >= 0) {
    selectedRefereeIds.value.splice(idx, 1);
  } else {
    selectedRefereeIds.value.push(refereeId);
  }
};

const saveRefereeAssignment = async () => {
  if (!localStage.value.id) return;
  savingReferees.value = true;
  try {
    const tournamentId = localStage.value.tournamentId;
    if (!tournamentId) {
      ElMessage.warning('无法获取赛事ID');
      return;
    }
    await assignStageReferees({
      stageId: localStage.value.id,
      tournamentId,
      refereeIds: selectedRefereeIds.value
    });
    originalRefereeIds.value = [...selectedRefereeIds.value];
    ElMessage.success('裁判分配已保存');
  } catch (e: any) {
    ElMessage.error(e?.msg || e?.message || '保存失败');
  } finally {
    savingReferees.value = false;
  }
};

// 监听 props 变化
watch(
  () => props.stage,
  () => {
    if (props.stage) {
      localStage.value = { ...props.stage };
    }
  },
  { deep: true }
);

// 监听 isCreating 变化，进入新建模式时重置状态
watch(
  () => props.isCreating,
  (isCreating) => {
    if (isCreating) {
      // 重置到第一步
      step.value = 1;
      // 重置选中的赛段类型为默认值
      selectedStageMode.value = StageMode.KNOCKOUT;
      // 重置表单数据
      newStageData.value = {
        name: '',
        status: 'DRAFT'
      };
    }
  }
);

// 监听赛段变化，加载裁判分配
watch(
  () => localStage.value.id,
  (newId) => {
    if (newId && localStage.value.tournamentId) {
      loadRefereeList(localStage.value.tournamentId);
      loadRefereeAssignments(newId);
    }
  },
  { immediate: true }
);
</script>

<style scoped>
.stage-sidebar {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.type-button {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 16px 12px;
  background: #0a0a0a;
  border: 2px solid #262626;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  text-align: center;
}

.type-button:hover {
  border-color: #404040;
  background: #171717;
}

.type-button.selected {
  border-color: #f59e0b;
  background: rgba(245, 158, 11, 0.1);
}
</style>
