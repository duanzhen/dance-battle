<template>
  <div class="h-full flex flex-col bg-neutral-900 rounded-xl border border-neutral-800 overflow-hidden">
    <!-- 头部 -->
    <div class="flex-none px-6 py-4 border-b border-neutral-800 flex items-center justify-between">
      <div class="flex items-center gap-2">
        <Users class="w-4 h-4 text-neutral-400" />
        <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider">参赛选手</h3>
      </div>
      <div class="flex items-center gap-3 text-xs">
        <!-- 海选赛:号码牌 / 分数排名 排序切换 -->
        <template v-if="isAudition">
          <button
            @click="sortMode = 'NUMBER'"
            class="px-2.5 py-1 rounded border text-[11px] font-bold transition-colors flex items-center gap-1"
            :class="sortBtnClass('NUMBER')"
            title="按签到时抽签的号码牌排序"
          >
            <Hash class="w-3.5 h-3.5" />
            号码排序
          </button>
          <button
            @click="sortMode = 'SCORE'"
            class="px-2.5 py-1 rounded border text-[11px] font-bold transition-colors flex items-center gap-1"
            :class="sortBtnClass('SCORE')"
            title="按分数排名排序"
          >
            <Trophy class="w-3.5 h-3.5" />
            分数排序
          </button>
          <button
            @click="handleExportAudition"
            :disabled="exporting"
            class="px-2.5 py-1 rounded border text-[11px] font-bold transition-colors bg-emerald-600/15 text-emerald-400 border-emerald-600/30 hover:bg-emerald-600/25 disabled:opacity-40 disabled:cursor-not-allowed flex items-center gap-1"
            title="导出 Excel:号码 / 选手名 / 各裁判分数 / 总分 / 排名"
          >
            <Download class="w-3.5 h-3.5" />
            {{ exporting ? '导出中...' : '导出结果' }}
          </button>
        </template>
        <span class="text-neutral-500">共</span>
        <span class="text-amber-500 font-mono">{{ competitors.length }}</span>
        <span class="text-neutral-500">名</span>
      </div>
    </div>

    <!-- 加载状态 -->
    <div v-if="loading" class="flex-1 flex items-center justify-center">
      <div class="text-neutral-500 text-sm">加载中...</div>
    </div>

    <!-- 空状态 -->
    <div v-else-if="competitors.length === 0" class="flex-1 flex items-center justify-center">
      <div class="text-center">
        <p class="text-neutral-500 text-sm mb-2">暂无参赛选手</p>
        <p class="text-neutral-600 text-xs">请先添加参赛选手</p>
      </div>
    </div>

    <!-- 选手列表 -->
    <div v-else class="flex-1 overflow-y-auto">
      <div v-if="canArrange" class="flex-none px-6 py-2 border-b border-neutral-800/50 text-[11px] text-neutral-500">
        按外部抽签结果拖动排序,保存后 seedRank 依次 1..n,生成对阵时按此顺序配对
      </div>
      <div class="divide-y divide-neutral-800/50">
        <div
          v-for="(competitor, index) in displayList"
          :key="competitor.id"
          class="px-6 py-4 transition-colors"
          :class="[
            canArrange ? 'cursor-grab' : '',
            'hover:bg-neutral-900/50',
            dragIndex === index ? 'bg-amber-500/5 border-y border-amber-500/20' : ''
          ]"
          :draggable="canArrange"
          @dragstart="onDragStart(index, $event)"
          @dragover="onDragOver(index, $event)"
          @drop="onDrop(index)"
          @dragend="onDragEnd"
        >
          <div class="flex items-center gap-4">
            <!-- 号码牌(海选直接显示抽到的号码) / 种子排名(其余赛制);均为赛前预排,不做金银铜高亮 -->
            <div class="flex-shrink-0 w-12 h-12 rounded-lg bg-neutral-800 text-neutral-400 flex items-center justify-center">
              <span class="text-lg font-bold">{{ isAudition ? competitor.number || '-' : competitor.seedRank || '-' }}</span>
            </div>

            <!-- 选手信息 -->
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 mb-1">
                <template v-if="editingId === competitor.id">
                  <input
                    v-model="editingName"
                    autofocus
                    maxlength="50"
                    class="w-44 bg-neutral-800 border border-amber-500/40 rounded-md px-2 py-1 text-sm text-white outline-none focus:border-amber-500"
                    @keydown.enter.prevent="saveRename"
                    @keydown.esc.prevent="cancelRename"
                  />
                  <button
                    class="flex-shrink-0 p-1 rounded text-green-500 hover:bg-green-500/10 disabled:opacity-40"
                    title="保存"
                    :disabled="savingRename"
                    @click="saveRename"
                  >
                    <Check class="w-3.5 h-3.5" />
                  </button>
                  <button
                    class="flex-shrink-0 p-1 rounded text-neutral-400 hover:bg-neutral-700/50 disabled:opacity-40"
                    title="取消"
                    :disabled="savingRename"
                    @click="cancelRename"
                  >
                    <X class="w-3.5 h-3.5" />
                  </button>
                </template>
                <template v-else>
                  <span class="text-sm font-medium text-white truncate">{{ competitor.name }}</span>
                  <button
                    class="flex-shrink-0 p-1 rounded text-neutral-500 hover:text-amber-400 hover:bg-amber-500/10"
                    title="改名"
                    @click="startRename(competitor)"
                  >
                    <Pencil class="w-3.5 h-3.5" />
                  </button>
                  <button
                    v-if="isArena && competitor.outcomeStatus !== 'WITHDRAWN'"
                    class="flex-shrink-0 px-1.5 py-0.5 rounded text-[10px] bg-red-500/10 text-red-400 border border-red-500/30 hover:bg-red-500/20 disabled:opacity-40"
                    title="弃权(不再参与排队,进行中作废并由下一位补位)"
                    :disabled="withdrawingId === competitor.id"
                    @click="handleWithdraw(competitor)"
                  >
                    弃权
                  </button>
                  <span
                    v-else-if="isArena && competitor.outcomeStatus === 'WITHDRAWN'"
                    class="flex-shrink-0 px-1.5 py-0.5 rounded text-[10px] bg-red-500/10 text-red-400 border border-red-500/30"
                  >
                    已弃权
                  </span>
                </template>
                <span
                  v-if="competitor.type === 1"
                  class="flex-shrink-0 px-1.5 py-0.5 rounded text-[10px] bg-blue-500/10 text-blue-500 border border-blue-500/20"
                >
                  队伍
                </span>
                <span v-else class="flex-shrink-0 px-1.5 py-0.5 rounded text-[10px] bg-green-500/10 text-green-500 border border-green-500/20">
                  个人
                </span>
                <span
                  v-if="isGuest(competitor)"
                  class="flex-shrink-0 px-1.5 py-0.5 rounded text-[10px] bg-amber-500/10 text-amber-500 border border-amber-500/30"
                >
                  GUEST
                </span>
                <span
                  v-if="isGuest(competitor) && !props.isInitialized"
                  class="flex-shrink-0 px-1.5 py-0.5 rounded text-[10px] bg-neutral-600/10 text-neutral-400 border border-neutral-600/30"
                >
                  待排位
                </span>
              </div>
              <div v-if="remarkText(competitor.remark) && !isGuest(competitor)" class="text-xs text-neutral-500 truncate">
                {{ remarkText(competitor.remark) }}
              </div>
            </div>

            <!-- 结果状态 -->
            <div v-if="competitor.outcomeStatus" class="flex-shrink-0">
              <span class="px-2 py-1 rounded text-xs font-medium" :class="getOutcomeStatusClass(competitor.outcomeStatus)">
                {{ getOutcomeStatusText(competitor.outcomeStatus) }}
              </span>
            </div>

            <!-- 最终排名(淘汰赛不展示) -->
            <div v-if="competitor.finalRank && !isKnockout" class="flex-shrink-0 w-16 text-center">
              <div class="text-xs text-neutral-500">排名</div>
              <div class="text-lg font-bold" :class="getFinalRankClass(competitor.finalRank)">
                {{ competitor.finalRank }}
              </div>
            </div>

            <!-- 分数(海选赛:按场次累计总分) -->
            <div v-if="isAudition" class="flex-shrink-0 w-20 text-center">
              <div class="text-xs text-neutral-500">分数</div>
              <div class="text-base font-bold font-mono" :class="scoreOf(competitor) == null ? 'text-neutral-600' : 'text-amber-400/90'">
                {{ scoreOf(competitor) == null ? '–' : Number(scoreOf(competitor)).toFixed(2) }}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Users, Pencil, Check, X, Hash, Trophy, Download } from 'lucide-vue-next';
import { listCompetitor, updateCompetitor } from '@/api/game/competitor';
import { setStageSeedOrder } from '@/api/game/stage';
import { exportAuditionResult, getAuditionResult } from '@/api/game/stage';
import { withdrawArenaCompetitor } from '@/api/game/stage/lifecycle';
import { CompetitorVO } from '@/api/game/competitor/types';
import { subscribeTournamentEvents, unsubscribeTournamentEvents } from '@/utils/tournamentEventSse';
import FileSaver from 'file-saver';

// Props
const props = defineProps<{
  tournamentId?: string | number | null;
  stageId: string | number;
  stageMode?: string;
  stageStatus?: string;
  isInitialized?: boolean;
}>();

// 状态
const loading = ref(false);
const competitors = ref<CompetitorVO[]>([]);
const exporting = ref(false);
/** 海选赛:competitorId -> 累计总分(t_match_participant.score_value 汇总) */
const scoreByCompetitor = ref<Record<string, number>>({});
/** 海选赛排序:号码牌 / 分数排名(默认号码牌) */
const sortMode = ref<'NUMBER' | 'SCORE'>('NUMBER');

const isAudition = computed(() => props.stageMode === 'AUDITION');
const isKnockout = computed(() => props.stageMode === 'KNOCKOUT');

// 行内改名状态
const editingId = ref<string | number | null>(null);
const editingName = ref('');
const savingRename = ref(false);
const withdrawingId = ref<string | number | null>(null);

// 擂台赛:支持参赛选手弃权(弃权后不再参与排队,进行中作废并由下一位补位)
const isArena = computed(() => props.stageMode === 'ARENA');

// GUEST 可加入/可排位窗口:赛段未初始化且处于规划/未开始态(DRAFT/PENDING)
const canArrange = computed(() => !props.isInitialized && (props.stageStatus === 'DRAFT' || props.stageStatus === 'PENDING'));

/** 号码牌数值(空/非数字排最后) */
const numOf = (c: CompetitorVO) => {
  const n = parseInt(String(c.number ?? ''), 10);
  return Number.isNaN(n) ? Number.MAX_SAFE_INTEGER : n;
};
/** 参赛方累计总分;无分数返回 null */
const scoreOf = (c: CompetitorVO): number | null => {
  const v = scoreByCompetitor.value[String(c.id)];
  return v == null ? null : v;
};
/** 排序按钮样式 */
const sortBtnClass = (mode: 'NUMBER' | 'SCORE') =>
  sortMode.value === mode
    ? 'bg-amber-500/15 text-amber-400 border-amber-500/40'
    : 'bg-neutral-800 text-neutral-400 border-neutral-700 hover:text-neutral-200 hover:border-neutral-600';
/** 展示列表:海选赛未初始化时保持种子顺序(供拖拽排位);初始化/结算后按所选方式排序 */
const displayList = computed(() => {
  if (!isAudition.value || canArrange.value) {
    return competitors.value;
  }
  const list = [...competitors.value];
  if (sortMode.value === 'SCORE') {
    list.sort((a, b) => (scoreOf(b) ?? -1) - (scoreOf(a) ?? -1) || numOf(a) - numOf(b));
  } else {
    list.sort((a, b) => numOf(a) - numOf(b) || String(a.number || '').localeCompare(String(b.number || '')));
  }
  return list;
});

// GUEST 标记:remark == GUEST(由后端 addGuest 写入)
const isGuest = (competitor: CompetitorVO) => competitor.remark === 'GUEST';

/** 展示备注:过滤内部使用的临时弃权标记(ARENA_SKIP:xxx) */
const remarkText = (remark?: string) =>
  (remark || '')
    .split(';')
    .filter((s) => !s.startsWith('ARENA_SKIP:'))
    .join(';');

// 拖拽排序:按外部抽签结果调整参赛方种子顺序
const dragIndex = ref<number | null>(null);
const savingOrder = ref(false);

const onDragStart = (index: number, e: DragEvent) => {
  dragIndex.value = index;
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
};

const onDragOver = (index: number, e: DragEvent) => {
  e.preventDefault();
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
};

const onDrop = async (index: number) => {
  if (dragIndex.value === null || dragIndex.value === index) {
    onDragEnd();
    return;
  }
  const from = dragIndex.value;
  const list = [...competitors.value];
  const [moved] = list.splice(from, 1);
  list.splice(index, 0, moved);
  competitors.value = list;
  onDragEnd();
  await saveSeedOrder();
};

const onDragEnd = () => {
  dragIndex.value = null;
};

const startRename = (competitor: CompetitorVO) => {
  editingId.value = competitor.id;
  editingName.value = competitor.name || '';
};

const cancelRename = () => {
  editingId.value = null;
  editingName.value = '';
};

const saveRename = async () => {
  const name = editingName.value.trim();
  if (!name) {
    ElMessage.warning('名称不能为空');
    return;
  }
  if (editingId.value === null) return;
  savingRename.value = true;
  try {
    // 赛段配置内单独改名:不联动同步名下唯一选手档案
    await updateCompetitor({ id: editingId.value, name, syncPlayerName: false });
    ElMessage.success('已保存');
    cancelRename();
    await loadCompetitors();
  } catch (error) {
    console.error('修改名称失败:', error);
    ElMessage.error((error as any)?.msg || (error as any)?.message || '修改名称失败');
  } finally {
    savingRename.value = false;
  }
};

const handleWithdraw = async (competitor: CompetitorVO) => {
  try {
    await ElMessageBox.confirm(`确认「${competitor.name}」弃权?弃权后不可撤销,不再参与擂台排队;若正在对决,该场作废并由队列下一位补位。`, '确认弃权', {
      type: 'warning',
      confirmButtonText: '确认弃权',
      cancelButtonText: '取消'
    });
  } catch {
    return; // 取消
  }
  withdrawingId.value = competitor.id;
  try {
    await withdrawArenaCompetitor(props.stageId, competitor.id);
    ElMessage.success('已标记弃权');
    await loadCompetitors();
  } catch (e) {
    console.error('弃权失败:', e);
    ElMessage.error((e as any)?.msg || (e as any)?.message || '弃权失败');
  } finally {
    withdrawingId.value = null;
  }
};

const saveSeedOrder = async () => {
  if (!props.stageId || competitors.value.length === 0 || savingOrder.value) return;
  savingOrder.value = true;
  try {
    await setStageSeedOrder(
      props.stageId,
      competitors.value.map((c) => c.id)
    );
    ElMessage.success('已按抽签结果保存顺序');
    await loadCompetitors();
  } catch (error) {
    console.error('保存种子顺序失败:', error);
    ElMessage.error((error as any)?.msg || (error as any)?.message || '保存顺序失败');
    await loadCompetitors();
  } finally {
    savingOrder.value = false;
  }
};

// 加载海选赛分数:统一消费后端 audition-result(原始海选分,不含二海;二海分仅用于决出晋级)
const loadScores = async () => {
  if (!isAudition.value) {
    scoreByCompetitor.value = {};
    return;
  }
  try {
    const ar: any = await getAuditionResult(props.stageId);
    const data = ar?.data?.data || ar?.data || ar;
    const map: Record<string, number> = {};
    (data?.competitors || []).forEach((c: any) => {
      if (c.competitorId != null && c.score != null) {
        map[String(c.competitorId)] = Number(c.score);
      }
    });
    scoreByCompetitor.value = map;
  } catch (e) {
    console.error('加载海选赛分数失败:', e);
    scoreByCompetitor.value = {};
  }
};

// 导出海选结果 Excel(号码/选手名/各裁判分数/总平均分/排名)
const handleExportAudition = async () => {
  if (!props.stageId || exporting.value) return;
  exporting.value = true;
  try {
    const blob = (await exportAuditionResult(props.stageId)) as unknown as Blob;
    FileSaver.saveAs(blob, '海选结果.xlsx');
    ElMessage.success('导出成功');
  } catch (e) {
    console.error('导出海选结果失败:', e);
    ElMessage.error('导出失败');
  } finally {
    exporting.value = false;
  }
};

// 加载参赛选手列表
const loadCompetitors = async () => {
  if (!props.stageId) {
    competitors.value = [];
    return;
  }

  loading.value = true;
  editingId.value = null;
  editingName.value = '';
  try {
    const { data } = await listCompetitor({ stageId: props.stageId, pageNum: 1, pageSize: 1000 });
    competitors.value = data || [];
    // 海选赛直接按号码牌排序(号码即上场顺序,不依赖重建的 seedRank 索引);
    // 其余赛制按种子排名排序;可拖拽排位时保持后端种子顺序供抽签调整
    if (isAudition.value && !canArrange.value) {
      competitors.value.sort((a, b) => numOf(a) - numOf(b) || String(a.number || '').localeCompare(String(b.number || '')));
    } else {
      competitors.value.sort((a, b) => (a.seedRank || 999) - (b.seedRank || 999));
    }
    await loadScores();
  } catch (error) {
    console.error('加载参赛选手失败:', error);
    competitors.value = [];
  } finally {
    loading.value = false;
  }
};

// 获取结果状态样式
const getOutcomeStatusClass = (status: string) => {
  const statusMap: Record<string, string> = {
    'ADVANCE': 'bg-green-500/10 text-green-500 border border-green-500/20',
    'ELIMINATED': 'bg-red-500/10 text-red-500 border border-red-500/20',
    'PENDING': 'bg-neutral-500/10 text-neutral-400 border border-neutral-500/20',
    'WITHDRAWN': 'bg-neutral-600/10 text-neutral-500 border border-neutral-600/20'
  };
  return statusMap[status] || 'bg-neutral-500/10 text-neutral-400 border border-neutral-500/20';
};

// 获取结果状态文本
const getOutcomeStatusText = (status: string) => {
  const statusMap: Record<string, string> = {
    'ADVANCE': '晋级',
    'ELIMINATED': '淘汰',
    'PENDING': '进行中',
    'WITHDRAWN': '退赛'
  };
  return statusMap[status] || status;
};

// 获取最终排名样式
const getFinalRankClass = (rank: number) => {
  if (rank === 1) return 'text-amber-500';
  if (rank === 2) return 'text-neutral-300';
  if (rank === 3) return 'text-orange-500';
  return 'text-neutral-400';
};

// 监听 stageId 变化
watch(
  () => props.stageId,
  () => {
    loadCompetitors();
  },
  { immediate: true }
);

// 组件挂载时加载数据
onMounted(() => {
  loadCompetitors();
  // 订阅赛事事件:签到/弃权/顶替等参赛方变化实时刷新选手列表
  if (props.tournamentId != null) {
    subscribeTournamentEvents(props.tournamentId, handleTournamentEvent);
  }
});

onUnmounted(() => {
  if (props.tournamentId != null) {
    unsubscribeTournamentEvents(props.tournamentId, handleTournamentEvent);
  }
});

/** 赛事事件回调:重连补偿(null)或事件属于本赛段(签到/参赛方变化等)时刷新 */
const handleTournamentEvent = (data: any) => {
  if (!data || data.stageId == null || String(data.stageId) === String(props.stageId)) {
    loadCompetitors();
  }
};
</script>

<style scoped>
/* 自定义滚动条 */
.overflow-y-auto::-webkit-scrollbar {
  width: 6px;
}
.overflow-y-auto::-webkit-scrollbar-track {
  background: #0a0a0a;
}
.overflow-y-auto::-webkit-scrollbar-thumb {
  background: #262626;
  border-radius: 3px;
}
.overflow-y-auto::-webkit-scrollbar-thumb:hover {
  background: #404040;
}
</style>
