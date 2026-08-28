<template>
  <div class="stage-sidebar">
    <!-- 头部 -->
    <div class="px-6 py-4 border-b border-neutral-800">
      <div class="flex items-center gap-2">
        <Settings class="w-4 h-4 text-neutral-400" />
        <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider">通用配置</h3>
      </div>
    </div>

    <!-- 正常模式: 编辑已有赛段 -->
    <div class="p-6 space-y-6">
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

      <!-- 赛段状态:由流程操作自动流转,只读展示 -->
      <div>
        <label class="text-xs text-neutral-500 mb-2 block">赛段状态</label>
        <div class="bg-black border border-neutral-800 rounded-lg p-2.5 text-sm font-bold" :class="statusTextClass">
          {{ statusLabel }}
        </div>
      </div>

      <!-- 赛段类型 (只读) -->
      <div>
        <label class="text-xs text-neutral-500 mb-2 block">赛段类型</label>
        <div class="bg-black border border-neutral-800 rounded-lg p-2.5 text-sm text-neutral-400">
          {{ getStageModeLabel(localStage.stageMode) }}
        </div>
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
          v-if="localStage.status === 'DRAFT' || localStage.status === 'PENDING'"
          @click="doStart"
          :disabled="lifecycleLoading || !canStartStage"
          :title="!canStartStage ? '上一赛段结束后方可开始本赛段' : '将自动初始化并生成对阵'"
          class="w-full py-2.5 text-sm font-medium rounded-lg bg-amber-500 text-white hover:bg-amber-600 transition-colors disabled:opacity-50"
        >
          开始赛段
        </button>
        <p
          v-if="(localStage.status === 'DRAFT' || localStage.status === 'PENDING') && !canStartStage"
          class="text-[10px] text-neutral-500 leading-relaxed"
        >
          上一赛段「{{ prevStage?.name || '未知' }}」尚未结束，结束后方可开始本赛段。
        </p>
        <p v-else-if="localStage.status === 'DRAFT' || localStage.status === 'PENDING'" class="text-[10px] text-neutral-500 leading-relaxed">
          点击「开始赛段」将自动初始化并生成对阵。
        </p>
        <button
          v-if="localStage.status === 'GAMING'"
          @click="doComplete"
          :disabled="lifecycleLoading"
          class="w-full py-2.5 text-sm font-medium rounded-lg bg-green-600 text-white hover:bg-green-700 transition-colors disabled:opacity-50"
        >
          完成赛段
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
import { ref, watch, computed } from 'vue';
import { Settings } from 'lucide-vue-next';
import { ElMessage, ElMessageBox } from 'element-plus';
import { startStage, completeStage, calculateAdvancement } from '@/api/game/stage/lifecycle';
import { listReferee } from '@/api/game/referee';
import { getStageRefereeIds, assignStageReferees } from '@/api/game/refereeStage';
import { StageData, StageMode } from './types';

// Props
const props = defineProps<{
  stage: StageData | null;
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

// 赛段类型标签映射
const stageModeLabels: Record<string, string> = {
  [StageMode.KNOCKOUT]: '淘汰赛',
  [StageMode.GROUP]: '小组赛',
  [StageMode.AUDITION]: '海选赛',
  [StageMode.ARENA]: '擂台赛',
  [StageMode.RANK]: '排名赛'
};

// 赛段状态:由流程操作(开始赛段/完成赛段/计算晋级)自动流转,仅展示
const statusLabel = computed(() => {
  const map: Record<string, string> = {
    DRAFT: '规划中',
    PENDING: '未开始',
    GAMING: '进行中',
    SETTLED: '已结束',
    DISCARD: '已取消'
  };
  return map[localStage.value.status] || localStage.value.status || '—';
});
const statusTextClass = computed(() => {
  const map: Record<string, string> = {
    DRAFT: 'text-neutral-400',
    PENDING: 'text-amber-400',
    GAMING: 'text-green-400',
    SETTLED: 'text-neutral-300',
    DISCARD: 'text-red-400'
  };
  return map[localStage.value.status] || 'text-neutral-400';
});

// 是否可删除 (GAMING 和 SETTLED 禁用)
const canDelete = computed(() => {
  return localStage.value.status !== 'GAMING' && localStage.value.status !== 'SETTLED';
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
const doStart = async () => {
  if (!localStage.value?.id) return;
  try {
    await ElMessageBox.confirm(`确认开始赛段「${localStage.value.name}」？将自动初始化并生成对阵。`, '开始赛段', {
      type: 'warning',
      confirmButtonText: '确认开始',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  runLifecycle(() => startStage(localStage.value.id), 'GAMING', '赛段已开始');
};
const doComplete = async () => {
  if (!localStage.value?.id) {
    ElMessage.warning('请先保存赛段');
    return;
  }
  lifecycleLoading.value = true;
  try {
    const res: any = await completeStage(localStage.value.id);
    const status = res?.data?.status;
    if (status === 'SETTLED') {
      localStage.value.status = 'SETTLED';
      ElMessage.success('赛段已完成');
    } else {
      // 海选产生二海(同分加赛):后端保持赛段 GAMING,不允许结束,需完成二海判罚后再次结算
      ElMessage.warning(
        localStage.value.stageMode === 'AUDITION'
          ? '海选产生二海(同分加赛),完成二海判罚后才能结束赛段'
          : '赛段仍有未完成场次,完成全部判罚后才能结束赛段'
      );
    }
    emit('update', localStage.value);
    emit('refresh');
  } catch (e: any) {
    ElMessage.error(e?.msg || e?.message || '操作失败');
  } finally {
    lifecycleLoading.value = false;
  }
};
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
    refereeList.value = ((res.data as any)?.data || (res.data as any) || []).map((r: any) => ({ id: r.id, name: r.name }));
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
