<template>
  <div class="space-y-1">
    <!-- 第一行:标题 + 统计(左) / 主操作(右) -->
    <div class="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
      <div class="flex flex-wrap items-center gap-2 min-w-0">
        <h2 class="text-xl font-bold text-neutral-100 flex items-center gap-2 flex-shrink-0"><Users class="w-5 h-5 text-amber-500" /> 参赛阵容</h2>
        <span class="text-xs text-neutral-500 hidden lg:inline">管理选手档案与参赛名单</span>
        <span
          class="px-2 py-0.5 bg-neutral-800 border border-neutral-700 rounded text-[10px] text-neutral-300 flex-shrink-0"
          title="选手总数 / 已签到人数"
        >
          选手 {{ playerStats.total }} · 已签到 {{ playerStats.checkedIn }}
        </span>
        <span
          v-if="auditionGaming"
          class="px-1.5 py-0.5 bg-amber-500/10 border border-amber-500/30 rounded text-[10px] text-amber-500 flex items-center gap-1 flex-shrink-0"
        >
          <span class="w-1.5 h-1.5 rounded-full bg-amber-500 animate-pulse"></span>
          海选进行中，签到后自动加入场次
        </span>
      </div>

      <div class="flex flex-wrap items-center gap-1.5 toolbar-buttons">
        <el-button type="warning" size="small" :icon="Plus" @click="handleAdd" class="amber-button">添加选手</el-button>
        <!-- 测试工具:一键把所有未签到选手签到(仅开发/测试构建可见,见 VITE_ENABLE_DEV_TOOLS) -->
        <el-button
          v-if="devToolsEnabled"
          size="small"
          :icon="UserRoundCheck"
          :loading="bulkCheckingIn"
          :disabled="playerStats.total === playerStats.checkedIn"
          title="测试用:把所有未签到选手一键签到(海选按圈轮转分配)"
          @click="handleBulkCheckIn"
        >
          一键全签到
        </el-button>
        <el-button size="small" @click="showImportDialog = true" class="import-button">批量导入</el-button>
        <el-button size="small" circle :icon="RefreshCw" class="import-button" title="刷新" @click="refreshAll" />
      </div>
    </div>

    <!-- 第二行:筛选工具栏(签到 / 排序 / 搜索 同一行) -->
    <div class="flex flex-wrap items-center gap-x-4 gap-y-2 pb-4">
      <!-- 全部 / 未签到 过滤 -->
      <div class="flex items-center gap-1.5">
        <span class="hidden text-[11px] text-neutral-500 md:inline">签到</span>
        <el-segmented
          :model-value="filterUncheckInOnly ? 'uncheck' : 'all'"
          :options="[
            { label: '全部', value: 'all' },
            { label: '未签到', value: 'uncheck' }
          ]"
          size="small"
          class="amber-segmented"
          @change="handleFilterMode"
        />
      </div>

      <!-- 排序:默认顺序 / 按签到拿到的号码升序 -->
      <div class="flex items-center gap-1.5">
        <span class="hidden text-[11px] text-neutral-500 md:inline">排序</span>
        <el-segmented
          v-model="sortMode"
          :options="[
            { label: '默认顺序', value: 'DEFAULT' },
            { label: '按号码', value: 'NUMBER' }
          ]"
          size="small"
          class="amber-segmented"
          title="按签到拿到的号码升序排列(没号码的排在最后)"
        />
      </div>

      <!-- 宽度放外层 div:el-input 自带 width 规则会盖掉工具类宽度;sm 起靠右对齐 -->
      <div class="w-full sm:ml-auto sm:w-56">
        <el-input v-model="searchKeyword" placeholder="搜索选手姓名" size="small" :prefix-icon="Users" clearable class="search-input rounded-lg" />
      </div>
    </div>

    <div class="space-y-3 relative">
      <!-- 加载指示:覆盖在列表上方,不占布局空间,避免刷新时顶部出现空白跳动 -->
      <div v-show="loading" class="absolute top-0 left-0 right-0 z-10 flex items-center justify-center py-1 pointer-events-none">
        <div class="animate-spin rounded-full h-5 w-5 border-b-2 border-amber-500"></div>
      </div>
      <!-- 显示选手列表 -->
      <TransitionGroup
        v-if="displayMode === 'player'"
        tag="div"
        name="card"
        class="grid grid-cols-1 md:grid-cols-[repeat(auto-fill,minmax(340px,1fr))] gap-3"
      >
        <div
          v-for="player in filteredPlayers"
          :key="player.id"
          class="flex flex-col gap-3 rounded-2xl border bg-neutral-900 p-4 transition-colors"
          :class="player.competitorId ? 'border-neutral-800 hover:border-amber-500/40' : 'border-neutral-800/80 hover:border-neutral-700'"
        >
          <!-- 主行:头像 + 姓名/备注 + 操作 -->
          <div class="flex items-start gap-3">
            <div class="relative flex-none">
              <div class="h-12 w-12 overflow-hidden rounded-full border border-neutral-700 bg-neutral-800">
                <img v-if="player.avatar" :src="player.avatar" crossorigin="anonymous" class="h-full w-full object-cover" />
                <div v-else class="flex h-full w-full items-center justify-center text-neutral-600">
                  <User class="h-5 w-5" />
                </div>
              </div>
              <!-- 签到状态角标(唯一状态标记,不再另加装饰性绿点) -->
              <span
                class="absolute -bottom-1 -right-1 flex h-5 w-5 items-center justify-center rounded-full border-2 border-neutral-900 shadow"
                :class="player.competitorId ? 'bg-green-500 text-neutral-900' : 'bg-neutral-700 text-neutral-300'"
                :title="player.competitorId ? '已签到' : '未签到'"
              >
                <Check v-if="player.competitorId" class="h-3 w-3" />
                <Clock v-else class="h-3 w-3" />
              </span>
            </div>

            <div class="min-w-0 flex-1">
              <div class="flex items-center gap-2">
                <span class="truncate text-sm font-bold text-white">{{ player.name || '未命名' }}</span>
                <span
                  class="flex-none rounded-full px-1.5 py-0.5 text-[10px] font-medium"
                  :class="player.competitorId ? 'bg-green-500/10 text-green-400' : 'bg-neutral-700/40 text-neutral-400'"
                >
                  {{ player.competitorId ? '已签到' : '未签到' }}
                </span>
              </div>
              <p class="mt-0.5 truncate text-xs text-neutral-500">{{ player.remark || '—' }}</p>
            </div>

            <!-- 操作按钮:常显 -->
            <div class="flex flex-none items-center gap-1.5">
              <button
                v-if="!player.competitorId"
                @click.stop="handleCheckIn(player)"
                class="flex h-8 w-8 items-center justify-center rounded-lg border border-green-500/25 bg-green-500/10 text-green-400 transition-colors hover:!border-green-500 hover:!bg-green-500 hover:!text-neutral-900"
                title="签到"
              >
                <UserRoundCheck class="h-4 w-4" />
              </button>
              <button
                v-else-if="!checkInLocked"
                @click.stop="handleEditCheckIn(player)"
                class="flex h-8 w-8 items-center justify-center rounded-lg border border-neutral-700 bg-neutral-800/60 text-neutral-300 transition-colors hover:!border-amber-500/40 hover:!bg-amber-500/15 hover:!text-amber-400"
                title="编辑签到结果"
              >
                <UserRoundCheck class="h-4 w-4" />
              </button>
              <button
                @click.stop="handleEdit(player)"
                class="flex h-8 w-8 items-center justify-center rounded-lg border border-neutral-700 bg-neutral-800/60 text-neutral-300 transition-colors hover:!border-amber-500/40 hover:!bg-amber-500/15 hover:!text-amber-400"
                title="编辑"
              >
                <svg
                  xmlns="http://www.w3.org/2000/svg"
                  class="h-4 w-4"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                >
                  <path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z" />
                </svg>
              </button>
            </div>
          </div>

          <!-- 信息行:号码 / 所在圈 / 标签 / 证件号 -->
          <div
            v-if="player.competitorVo?.number || player.competitorId || player.tags || player.idCard"
            class="flex flex-wrap items-center gap-1.5 border-t border-neutral-800/70 pt-2.5"
          >
            <span v-if="player.competitorVo?.number" class="rounded-md bg-amber-500/15 px-1.5 py-0.5 font-mono text-[11px] font-bold text-amber-400">
              NO.{{ player.competitorVo.number }}
            </span>
            <span
              v-if="player.competitorId && circleLabelOf(player.competitorId)"
              class="rounded-md bg-sky-500/10 px-1.5 py-0.5 text-[11px] text-sky-400"
              title="所在圈(以裁判命名)"
            >
              {{ circleLabelOf(player.competitorId) }}
            </span>
            <span v-for="tag in parseTags(player.tags)" :key="tag" class="rounded-md bg-neutral-800 px-1.5 py-0.5 text-[11px] text-neutral-400">
              {{ tag }}
            </span>
            <span v-if="player.idCard" class="ml-auto hidden font-mono text-[10px] text-neutral-600 sm:block">ID {{ player.idCard }}</span>
          </div>
        </div>
      </TransitionGroup>

      <!-- 显示参赛选手列表 -->
      <TransitionGroup v-else tag="div" name="card" class="grid grid-cols-1 md:grid-cols-[repeat(auto-fill,minmax(340px,1fr))] gap-3">
        <div
          v-for="competitor in filteredCompetitors"
          :key="competitor.id"
          class="flex flex-col gap-3 rounded-2xl border border-neutral-800/80 bg-neutral-900 p-4 transition-colors hover:border-neutral-700"
        >
          <div class="flex items-start gap-3">
            <!-- 号码徽章 -->
            <div class="flex h-12 w-12 flex-none items-center justify-center rounded-xl border border-amber-500/30 bg-amber-500/10">
              <span class="text-lg font-bold text-amber-400">{{ competitor.number || '-' }}</span>
            </div>

            <div class="min-w-0 flex-1">
              <!-- 成员姓名 -->
              <div v-if="competitor.playerList && competitor.playerList.length > 0" class="flex flex-wrap items-center gap-x-1.5 gap-y-0.5">
                <span v-for="player in competitor.playerList" :key="player.id" class="truncate text-sm font-bold text-white">
                  {{ player.name }}
                </span>
              </div>
              <div v-else class="truncate text-sm font-bold text-white">{{ competitor.name || '未命名' }}</div>
              <p class="mt-0.5 truncate text-xs text-neutral-500">{{ competitor.remark || '—' }}</p>
            </div>
          </div>

          <!-- 信息行:种子位 / 所在圈 -->
          <div
            v-if="competitor.seedRank !== null || circleLabelOf(competitor.id)"
            class="flex flex-wrap items-center gap-1.5 border-t border-neutral-800/70 pt-2.5"
          >
            <span v-if="competitor.seedRank !== null" class="rounded-md bg-neutral-800 px-1.5 py-0.5 font-mono text-[11px] text-neutral-400">
              SEED {{ competitor.seedRank }}
            </span>
            <span
              v-if="circleLabelOf(competitor.id)"
              class="rounded-md bg-sky-500/10 px-1.5 py-0.5 text-[11px] text-sky-400"
              title="所在圈(以裁判命名)"
            >
              {{ circleLabelOf(competitor.id) }}
            </span>
          </div>
        </div>
      </TransitionGroup>
    </div>

    <!-- PlayerForm Dialog -->
    <PlayerForm v-model="formVisible" :player="currentPlayer" :tournament-id="tournamentId" @submit="handleSubmit" />

    <!-- CheckIn Dialog -->
    <CheckInDialog
      ref="checkInDialogRef"
      :player="currentCheckInPlayer"
      :tournament-id="tournamentId!"
      :stage-id="firstStageId!"
      @success="refreshAll"
    />

    <!-- 批量导入弹窗 -->
    <Teleport to="body">
      <div
        v-if="showImportDialog"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
        @click.self="showImportDialog = false"
      >
        <div class="bg-neutral-900 border border-neutral-700 rounded-xl w-full max-w-md mx-4 max-h-[90vh] flex flex-col shadow-2xl" @click.stop>
          <div class="flex-none px-5 py-4 border-b border-neutral-800 flex items-center justify-between">
            <h3 class="text-sm font-bold text-neutral-100">批量导入选手</h3>
            <button @click="showImportDialog = false" class="dialog-close-btn">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>

          <div class="flex-1 min-h-0 overflow-y-auto p-5 space-y-4">
            <div class="p-3 rounded-lg bg-neutral-800 border border-neutral-700">
              <p class="text-[10px] text-neutral-400 mb-2">下载模板，按格式填写后上传</p>
              <button @click="downloadTemplate" class="text-xs text-amber-500 hover:text-amber-400 underline">下载导入模板</button>
            </div>

            <label class="block cursor-pointer">
              <input type="file" accept=".xlsx,.xls" class="hidden" @change="handleFileSelect" ref="fileInputRef" />
              <div
                class="border-2 border-dashed rounded-lg p-6 text-center transition-colors"
                :class="dragOver ? 'border-amber-500 bg-amber-500/10' : 'border-neutral-700 hover:border-amber-500/50'"
                @dragenter.prevent="handleDragEnter"
                @dragover.prevent="handleDragOver"
                @dragleave.prevent="handleDragLeave"
                @drop.prevent.stop="handleFileDrop"
              >
                <div v-if="!importFile" class="space-y-2">
                  <svg class="w-8 h-8 text-neutral-600 mx-auto" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"
                    />
                  </svg>
                  <p class="text-xs" :class="dragOver ? 'text-amber-400' : 'text-neutral-500'">
                    {{ dragOver ? '松开鼠标导入文件' : '点击选择或拖入 Excel 文件' }}
                  </p>
                  <p class="text-[10px] text-neutral-600">支持 .xlsx / .xls 格式</p>
                </div>
                <div v-else class="space-y-1">
                  <p class="text-xs text-amber-400 font-bold">{{ importFile.name }}</p>
                  <p class="text-[10px] text-neutral-500">{{ formatFileSize(importFile.size) }}</p>
                </div>
              </div>
            </label>

            <div
              v-if="importResult"
              class="p-3 rounded-lg"
              :class="importResult.success ? 'bg-green-500/10 border border-green-500/20' : 'bg-red-500/10 border border-red-500/20'"
            >
              <p class="text-xs" :class="importResult.success ? 'text-green-400' : 'text-red-400'">{{ importResult.msg }}</p>
            </div>
          </div>

          <div class="flex-none px-5 py-4 border-t border-neutral-800 flex justify-end gap-3">
            <button
              @click="showImportDialog = false"
              class="px-4 py-2 text-xs text-neutral-400 hover:text-neutral-200 bg-neutral-800 hover:bg-neutral-700 rounded-lg border border-neutral-700 transition-colors"
            >
              取消
            </button>
            <button
              @click="doImport"
              :disabled="!importFile || importing"
              class="px-4 py-2 text-xs font-bold text-neutral-900 bg-amber-500 hover:bg-amber-400 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {{ importing ? '导入中...' : '开始导入' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, computed, nextTick } from 'vue';
import { Users, Plus, User, Check, Clock, UserRoundCheck, File, RefreshCw } from 'lucide-vue-next';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listPlayer, addPlayer, updatePlayer as updatePlayerApi, importPlayers, checkInPlayer } from '@/api/game/player';
import { download } from '@/utils/request';
import { PlayerVO, PlayerForm as PlayerFormType } from '@/api/game/player/types';
import { listCompetitor } from '@/api/game/competitor';
import { CompetitorVO } from '@/api/game/competitor/types';
import { getFirstStage, getStage } from '@/api/game/stage';
import { ensureAuditionCircles } from '@/api/game/stage/lifecycle';
import { listMatch } from '@/api/game/match';
import { listMatchParticipant, listParticipantsByStage } from '@/api/game/matchParticipant';
import { listMatchReferee } from '@/api/game/matchReferee';
import { isTiebreakerMatch } from '@/utils/tiebreaker';
import PlayerForm from './PlayerForm.vue';
import CheckInDialog from './CheckInDialog.vue';

const props = defineProps<{
  tournamentId?: string | number;
}>();

// 本地状态
const displayMode = ref<'player' | 'competitor'>('player');
const searchKeyword = ref('');
const filterUncheckInOnly = ref(false);
/** 排序方式:DEFAULT=后端顺序 / NUMBER=按签到拿到的号码升序 */
const sortMode = ref<'DEFAULT' | 'NUMBER'>('NUMBER');
const players = ref<PlayerVO[]>([]);
const competitors = ref<CompetitorVO[]>([]);
const loading = ref(false);
const formVisible = ref(false);
const currentPlayer = ref<PlayerVO | null>(null);
const firstStageId = ref<string | number | null>(null);
const checkInDialogRef = ref<InstanceType<typeof CheckInDialog> | null>(null);
const currentCheckInPlayer = ref<PlayerVO | null>(null);

// 首个赛段信息(判断海选进行中提示)
const firstStageInfo = ref<{ stageMode?: string; status?: string } | null>(null);

// 参赛方 -> 所在圈标签(以圈裁判命名;无裁判回退「第N圈」)
const circleLabels = ref<Record<string, string>>({});
const circleLabelOf = (competitorId?: string | number | null) => (competitorId == null ? '' : circleLabels.value[String(competitorId)] || '');

// 海选赛段已开始:提示签到后会自动加入当前场次继续参赛
const auditionGaming = computed(() => {
  const info = firstStageInfo.value;
  return !!info && info.stageMode === 'AUDITION' && info.status === 'GAMING';
});

// 赛段已开始/结束后,签到结果锁定:只能新增签到,不能编辑/解除
const checkInLocked = computed(() => {
  const status = firstStageInfo.value?.status;
  return status === 'GAMING' || status === 'SETTLED' || status === 'DISCARD';
});

// 选手数量与已签到数量
const playerStats = computed(() => {
  const total = players.value.length;
  const checkedIn = players.value.filter((p) => p.competitorId).length;
  return { total, checkedIn };
});

// 批量导入状态
const showImportDialog = ref(false);
const importFile = ref<File | null>(null);
const importing = ref(false);

/**
 * 测试工具开关:一键全签到只在开发/测试构建里出现。
 * 生产打包(.env.production)默认关闭,避免现场误点把所有选手一次性签到。
 * 本地要用测试构建:`VITE_ENABLE_DEV_TOOLS=true npm run build:prod`。
 */
const devToolsEnabled = computed(() =>
  import.meta.env.DEV || import.meta.env.VITE_ENABLE_DEV_TOOLS === 'true'
);
const bulkCheckingIn = ref(false);
const importResult = ref<{ success: boolean; msg: string } | null>(null);
const fileInputRef = ref<HTMLInputElement | null>(null);
const dragOver = ref(false);
let dragDepth = 0;

// 过滤后的选手列表
/**
 * 号码排序键:签到拿到的号码按数值升序(9 在 10 前面);没签到/号码非数字的排最后。
 */
const numberKeyOf = (number?: string | number | null): number => {
  const n = parseInt(String(number ?? '').trim(), 10);
  return Number.isFinite(n) ? n : Number.MAX_SAFE_INTEGER;
};

const filteredPlayers = computed(() => {
  let result = players.value;

  // 过滤掉已签到的选手
  if (filterUncheckInOnly.value) {
    result = result.filter((player) => !player.competitorId);
  }

  // 搜索过滤
  if (searchKeyword.value) {
    const keyword = searchKeyword.value.toLowerCase().trim();
    result = result.filter((player) => player.name?.toLowerCase().includes(keyword));
  }

  // 按号码排序(号码来自签到结果 competitorVo.number);不改动源数组
  if (sortMode.value === 'NUMBER') {
    result = [...result].sort(
      (a, b) => numberKeyOf(a.competitorVo?.number) - numberKeyOf(b.competitorVo?.number)
    );
  }

  return result;
});

// 过滤后的参赛选手列表（按号码排序）
const filteredCompetitors = computed(() => {
  let result = competitors.value;

  if (searchKeyword.value) {
    const keyword = searchKeyword.value.toLowerCase().trim();
    result = result.filter(
      (competitor) =>
        competitor.name?.toLowerCase().includes(keyword) || competitor.playerList?.some((p: any) => p.name?.toLowerCase().includes(keyword))
    );
  }

  // 按号码排序(签到拿到的号码,数值升序);默认顺序时保持后端返回顺序
  const list = [...result];
  if (sortMode.value === 'NUMBER') {
    list.sort((a, b) => numberKeyOf(a.number) - numberKeyOf(b.number));
  }
  return list;
});

// 加载选手列表
const loadPlayers = async () => {
  if (!props.tournamentId) {
    players.value = [];
    return;
  }

  try {
    const response = await listPlayer({
      tournamentId: props.tournamentId,
      pageNum: 1,
      pageSize: 1000
    });
    players.value = response.data || [];
  } catch (error) {
    console.error('加载选手列表失败:', error);
    ElMessage.error('加载选手列表失败');
    players.value = [];
  }
};

// 加载参赛选手列表
const loadCompetitors = async () => {
  if (!props.tournamentId || !firstStageId.value) {
    competitors.value = [];
    return;
  }

  try {
    const response = await listCompetitor({
      tournamentId: props.tournamentId,
      stageId: firstStageId.value,
      pageNum: 1,
      pageSize: 1000
    });
    competitors.value = response.data || [];
  } catch (error) {
    console.error('加载参赛选手失败:', error);
    ElMessage.error('加载参赛选手失败');
    competitors.value = [];
  }
};

// 加载「参赛方 -> 所在圈」映射:圈名以该圈裁判命名(无裁判回退第N圈)
const loadCircleLabels = async () => {
  circleLabels.value = {};
  if (!props.tournamentId || !firstStageId.value) return;
  try {
    const stageRes = await getStage(firstStageId.value);
    const stage: any = stageRes.data || (stageRes as any).data;
    if (!stage || stage.stageMode !== 'AUDITION') return;
    // 圈 = 真实 match:确保已按配置建齐,再统计各圈人数归属
    try {
      await ensureAuditionCircles(firstStageId.value);
    } catch {
      // 幂等接口,失败时按现有场次继续(可能尚未配置分圈)
    }
    const matchRes: any = await listMatch({ stageId: firstStageId.value, pageNum: 1, pageSize: 99 } as any);
    const matches: any[] = Array.isArray(matchRes?.data) ? matchRes.data : matchRes?.data?.data || [];
    const zones = matches.filter((m) => m.displayZone && String(m.displayZone).startsWith('ZONE-'));
    if (zones.length === 0) {
      return;
    }
    const refRes: any = await listMatchReferee(firstStageId.value);
    const refRows: any[] = refRes?.data || [];
    const namesByMatch: Record<string, string[]> = {};
    refRows.forEach((r) => {
      if (r.matchId != null && r.refereeName) {
        const key = String(r.matchId);
        (namesByMatch[key] = namesByMatch[key] || []).push(r.refereeName);
      }
    });
    const map: Record<string, string> = {};
    // 参赛方按赛段一次取回再分组(此前逐圈一次请求)
    let partsByMatch: Record<string, any[]> = {};
    try {
      partsByMatch = await listParticipantsByStage(firstStageId.value);
    } catch {
      partsByMatch = {};
    }
    zones.forEach((m) => {
      const zoneNo = String(m.displayZone).replace('ZONE-', '');
      const refNames = namesByMatch[String(m.id)];
      const label = refNames && refNames.length > 0 ? refNames.join(' / ') : `第${zoneNo}圈`;
      (partsByMatch[String(m.id)] || []).forEach((p) => {
        if (p.competitorId != null) {
          map[String(p.competitorId)] = label;
        }
      });
    });
    circleLabels.value = map;
  } catch (error) {
    console.warn('加载圈标签失败:', error);
    circleLabels.value = {};
  }
};

// 记录/恢复页面滚动位置:刷新列表时 DOM 重建会把滚动拉回顶部,刷新后还原
const captureScroll = () =>
  (
    [
      document.documentElement,
      document.body,
      document.querySelector('.main-container'),
      document.querySelector('.app-main')
    ] as (HTMLElement | null)[]
  )
    .filter((el): el is HTMLElement => !!el)
    .map((el) => ({ el, top: el === document.documentElement || el === document.body ? window.scrollY : el.scrollTop }));

const restoreScroll = (positions: { el: HTMLElement; top: number }[]) => {
  positions.forEach(({ el, top }) => {
    if (el === document.documentElement || el === document.body) {
      window.scrollTo(0, top);
    } else {
      el.scrollTop = top;
    }
  });
};

// 同时刷新选手和参赛选手列表
const refreshAll = async () => {
  const positions = captureScroll();
  if (!props.tournamentId) {
    players.value = [];
    competitors.value = [];
    firstStageId.value = null;
    return;
  }

  loading.value = true;
  try {
    // 如果没有赛段ID，先获取赛段ID（失败不影响选手列表加载）
    if (!firstStageId.value) {
      try {
        const stageResponse = await getFirstStage(props.tournamentId);
        firstStageId.value = stageResponse.data.id;
      } catch {
        firstStageId.value = null;
      }
    }
    await loadFirstStageInfo();

    // 同时加载选手、参赛选手与圈归属
    await Promise.all([loadPlayers(), loadCompetitors(), loadCircleLabels()]);
  } catch (error) {
    console.error('加载数据失败:', error);
  } finally {
    loading.value = false;
    await nextTick();
    restoreScroll(positions);
  }
};

/** 全部 / 未签到 过滤切换 */
const handleFilterMode = (v: string | number | boolean) => {
  filterUncheckInOnly.value = String(v) === 'uncheck';
};

// 打开添加表单
const handleAdd = () => {
  if (!props.tournamentId) {
    ElMessage.warning('请先选择赛事');
    return;
  }
  currentPlayer.value = null;
  formVisible.value = true;
};

// 打开编辑表单
const handleEdit = (player: PlayerVO) => {
  currentPlayer.value = player;
  formVisible.value = true;
};

// 签到
const handleCheckIn = (player: PlayerVO) => {
  if (!props.tournamentId || !firstStageId.value) {
    ElMessage.warning('无法签到：缺少赛事或赛段信息');
    return;
  }
  currentCheckInPlayer.value = player;
  checkInDialogRef.value?.open(player);
};

// 编辑签到结果(改号码/换圈)
const handleEditCheckIn = (player: PlayerVO) => {
  if (!props.tournamentId || !firstStageId.value) {
    ElMessage.warning('无法编辑：缺少赛事或赛段信息');
    return;
  }
  if (checkInLocked.value) {
    ElMessage.warning('赛段已开始，签到结果已锁定：开赛后仅支持新增签到，不能编辑');
    return;
  }
  currentCheckInPlayer.value = player;
  checkInDialogRef.value?.open(player);
};

/**
 * 测试用:把所有未签到选手一次性签到。
 *
 * <p>与逐个签到走同一套接口,差别只在批量与自动选圈:海选/排名赛必须指定目标圈,
 * 这里按圈轮转分配(均衡落圈),号码接在现有最大号之后避免重号。</p>
 */
const handleBulkCheckIn = async () => {
  if (!props.tournamentId || !firstStageId.value) {
    ElMessage.warning('无法签到：缺少赛事或赛段信息');
    return;
  }
  const status = firstStageInfo.value?.status;
  if (status === 'SETTLED' || status === 'DISCARD') {
    ElMessage.warning('首个赛段已结束，无法签到');
    return;
  }
  const targets = players.value.filter((p) => !p.competitorId);
  if (targets.length === 0) {
    ElMessage.info('没有待签到的选手');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `将把 ${targets.length} 名未签到选手一次性签到（海选/排名赛会按圈轮转分配）。该功能仅用于测试，请勿在正式赛事中使用。`,
      '一键全签到',
      { type: 'warning', confirmButtonText: '确定签到', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }

  bulkCheckingIn.value = true;
  try {
    const stage = ((await getStage(firstStageId.value)).data ?? {}) as any;
    const needCircle = stage.stageMode === 'AUDITION' || stage.stageMode === 'RANK';
    let circles: any[] = [];
    if (needCircle) {
      // 圈必须先存在:没有圈时后端会拒绝落圈(圈只由配置侧产生),这里先按配置补齐
      await ensureAuditionCircles(firstStageId.value);
      const matches = ((await listMatch({ stageId: firstStageId.value } as any)).data ?? []) as any[];
      circles = matches
        .filter((m) => m.status !== 'SETTLED' && !isTiebreakerMatch(m))
        .sort(
          (a, b) =>
            Number(a.displayRow ?? 0) - Number(b.displayRow ?? 0) || String(a.id).localeCompare(String(b.id))
        );
      if (circles.length === 0) {
        ElMessage.warning('该赛段没有可落圈的圈场次，无法签到');
        return;
      }
    }

    // 号码接在现有最大数字之后:重号会让海选结算的并列名次失去区分度
    let nextNumber = maxCompetitorNumber() + 1;
    let ok = 0;
    const failures: string[] = [];
    for (let i = 0; i < targets.length; i++) {
      const player = targets[i];
      const body: any = {
        playerId: player.id,
        checkInType: 'CREATE',
        competitorNumber: String(nextNumber++)
      };
      if (needCircle) {
        body.matchId = circles[i % circles.length].id;
      }
      try {
        await checkInPlayer(body);
        ok++;
      } catch (e: any) {
        failures.push(`${player.name || player.id}: ${e?.msg || e?.message || '失败'}`);
      }
    }

    await refreshAll();
    if (failures.length === 0) {
      ElMessage.success(`已签到 ${ok} 名选手`);
    } else {
      const detail = failures.slice(0, 3).join('；');
      ElMessage.warning(
        `成功 ${ok} 名，失败 ${failures.length} 名：${detail}${failures.length > 3 ? ' …' : ''}`
      );
    }
  } catch (e: any) {
    ElMessage.error(e?.msg || e?.message || '一键全签到失败');
  } finally {
    bulkCheckingIn.value = false;
  }
};

/** 现有参赛号码中的最大数字,用于批量签到续号 */
const maxCompetitorNumber = () => {
  let max = 0;
  for (const p of players.value) {
    const n = parseInt(String(p.competitorVo?.number ?? ''), 10);
    if (!Number.isNaN(n) && n > max) {
      max = n;
    }
  }
  return max;
};

// 加载首个赛段信息(海选进行中提示用)
const loadFirstStageInfo = async () => {
  firstStageInfo.value = null;
  if (!firstStageId.value) return;
  try {
    const resp = await getStage(firstStageId.value);
    const data = resp.data as any;
    firstStageInfo.value = {
      stageMode: data.stageMode,
      status: data.status
    };
  } catch (e) {
    console.error('加载赛段信息失败:', e);
  }
};

// 解析tags
const parseTags = (tags: string | string[]): string[] => {
  if (Array.isArray(tags)) return tags;
  try {
    return JSON.parse(tags);
  } catch {
    return [];
  }
};

// 提交表单
const handleSubmit = async (data: any) => {
  try {
    // 将tags数组转换为JSON字符串
    const submitData: PlayerFormType = {
      ...data,
      tags: Array.isArray(data.tags) ? JSON.stringify(data.tags) : data.tags
    };

    let createdPlayer: PlayerVO | null = null;
    if (data.id) {
      // 编辑
      await updatePlayerApi(submitData);
      ElMessage.success('更新成功');
    } else {
      // 添加
      const resp: any = await addPlayer(submitData);
      // 接口返回创建后的选手(含 id),用于新增后直接进入签到弹窗
      createdPlayer = resp?.data?.data ?? resp?.data ?? null;
      ElMessage.success('添加成功');
    }
    formVisible.value = false;
    currentPlayer.value = null;
    await refreshAll();

    // 新增选手后直接弹出签到弹窗,免去在列表中查找刚添加的选手
    if (createdPlayer?.id && props.tournamentId && firstStageId.value) {
      currentCheckInPlayer.value = createdPlayer;
      checkInDialogRef.value?.open(createdPlayer);
    }
  } catch (error) {
    console.error('操作失败:', error);
    ElMessage.error(data.id ? '更新失败' : '添加失败');
  }
};

// 监听tournamentId变化
watch(
  () => props.tournamentId,
  async () => {
    await refreshAll();
  },
  { immediate: true }
);

// 批量导入
const downloadTemplate = () => {
  download('/game/player/import-template', {}, '选手导入模板.xlsx');
};

const handleFileSelect = (e: Event) => {
  const input = e.target as HTMLInputElement;
  if (input.files && input.files.length > 0) {
    importFile.value = input.files[0];
    importResult.value = null;
  }
};

// 拖入文件：用计数器避免子元素触发 dragenter/dragleave 导致高亮闪烁
const handleDragEnter = () => {
  dragDepth++;
  dragOver.value = true;
};

const handleDragOver = (e: DragEvent) => {
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
};

const handleDragLeave = () => {
  dragDepth = Math.max(0, dragDepth - 1);
  if (dragDepth === 0) dragOver.value = false;
};

const handleFileDrop = (e: DragEvent) => {
  dragDepth = 0;
  dragOver.value = false;
  const file = e.dataTransfer?.files?.[0];
  if (!file) return;
  if (!/\.(xlsx|xls)$/i.test(file.name)) {
    ElMessage.warning('仅支持 .xlsx / .xls 格式的 Excel 文件');
    return;
  }
  importFile.value = file;
  importResult.value = null;
};

const formatFileSize = (bytes: number) => {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
};

// 关闭弹窗时复位拖拽高亮状态
watch(showImportDialog, (visible) => {
  if (!visible) {
    dragOver.value = false;
    dragDepth = 0;
  }
});

const doImport = async () => {
  if (!importFile.value || !props.tournamentId) return;

  importing.value = true;
  importResult.value = null;

  try {
    const formData = new FormData();
    formData.append('file', importFile.value);
    formData.append('tournamentId', String(props.tournamentId));

    const res = await importPlayers(formData);
    importResult.value = { success: true, msg: res.msg || res.data || '导入成功' };
    ElMessage.success(importResult.value.msg);
    importFile.value = null;
    if (fileInputRef.value) {
      fileInputRef.value.value = '';
    }
    // 导入成功后关闭弹窗
    showImportDialog.value = false;
    await refreshAll();
  } catch (e: any) {
    importResult.value = { success: false, msg: e?.msg || e?.message || '导入失败' };
  } finally {
    importing.value = false;
  }
};

// 暴露刷新方法供父组件调用
defineExpose({
  refresh: refreshAll
});
</script>

<style scoped>
.card-enter-active,
.card-leave-active {
  transition: all 0.3s ease;
}
.card-enter-from,
.card-leave-to {
  opacity: 0;
  transform: scale(0.95);
}

/* 覆盖 switch 颜色 */
/* 活跃状态：蓝色背景 */
.amber-switch.is-checked :deep(.el-switch__core) {
  background-color: #dcdfe6 !important;
  border-color: #dcdfe6 !important;
}

/* 非活跃状态：灰色背景 */
.amber-switch :deep(.el-switch__core) {
  background-color: #dcdfe6 !important;
  border-color: #dcdfe6 !important;
}

.amber-switch :deep(.el-switch__action) {
  background-color: #fff !important;
}

/* 文字颜色：非活跃状态为灰色 */
.amber-switch :deep(.el-switch__label) {
  color: #606266 !important;
}

/* 活跃状态文字颜色为 amber */
.amber-switch :deep(.el-switch__label.is-active) {
  color: #f59e0b !important;
}

/* 搜索框深灰色背景 */
.search-input :deep(.el-input__wrapper) {
  background-color: #27272a !important;
  box-shadow: 0 0 0 1px #3f3f46 inset !important;
  border-radius: 0.5rem !important;
}

.search-input :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #52525b inset !important;
}

.search-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #f59e0b inset !important;
}

.search-input :deep(.el-input__inner) {
  color: #a1a1aa !important;
}

.search-input :deep(.el-input__inner::placeholder) {
  color: #71717a !important;
}

/* Amber 主题按钮 */
.amber-button {
  background-color: #f59e0b !important;
  border-color: #f59e0b !important;
  color: #fff !important;
}

.amber-button:hover {
  background-color: #d97706 !important;
  border-color: #d97706 !important;
}

.amber-button:focus {
  background-color: #f59e0b !important;
  border-color: #f59e0b !important;
}

.import-button {
  background-color: #27272a !important;
  border-color: #3f3f46 !important;
  color: #a1a1aa !important;
}

.import-button:hover {
  background-color: #3f3f46 !important;
  border-color: #52525b !important;
  color: #fff !important;
}

/* Amber 主题 segmented */
.amber-segmented :deep(.el-segmented__group) {
  background-color: transparent !important;
}

.amber-segmented :deep(.el-segmented__item) {
  color: #a1a1aa !important;
  background-color: transparent !important;
  border-radius: 4px !important;
}

.amber-segmented :deep(.el-segmented__item-selected) {
  background-color: #f59e0b !important;
  color: #fff !important;
}

.amber-segmented :deep(.el-segmented__item-selected .el-segmented__item-label) {
  color: #fff !important;
}

.amber-segmented :deep(.el-segmented__item:hover) {
  color: #fff !important;
}

/* 工具栏内相邻 el-button 去掉默认 12px 左边距,统一用 flex gap 控制间距 */
.toolbar-buttons .el-button + .el-button {
  margin-left: 0 !important;
}

/* 过滤开关样式 */
.filter-switch :deep(.el-switch__core) {
  background-color: #3f3f46 !important;
  border-color: #3f3f46 !important;
}

.filter-switch.is-checked :deep(.el-switch__core) {
  background-color: #f59e0b !important;
  border-color: #f59e0b !important;
}

.filter-switch :deep(.el-switch__action) {
  background-color: #fff !important;
}

.filter-switch :deep(.el-switch__label) {
  color: #a1a1aa !important;
}

.filter-switch.is-checked :deep(.el-switch__label) {
  color: #f59e0b !important;
}
</style>

<style>
/* 全局样式 - el-segmented amber 主题 */
.amber-segmented.el-segmented {
  background-color: #27272a !important;
  padding: 2px !important;
  border-radius: 0.5rem !important;
}

.amber-segmented .el-segmented__group {
  background-color: transparent !important;
}

.amber-segmented .el-segmented__item {
  color: #a1a1aa !important;
  background-color: transparent !important;
  border-radius: 4px !important;
}

.amber-segmented .el-segmented__item .el-segmented__item-label {
  color: #ffffff !important;
}

/* 选中项样式 - 使用最高优先级 */
.amber-segmented.el-segmented .el-segmented__item-selected {
  background-color: #f59e0b !important;
  color: #fff !important;
  border-radius: 6px !important;
}

.amber-segmented.el-segmented .el-segmented__item-selected .el-segmented__item-label,
.amber-segmented.el-segmented > .el-segmented__group > .el-segmented__item-selected > .el-segmented__item-label,
.amber-segmented .el-segmented__group .el-segmented__item-selected .el-segmented__item-label,
.amber-segmented .el-segmented__item-selected > span {
  color: #fff !important;
}

.amber-segmented .el-segmented__item:hover {
  color: #fff !important;
}

.amber-segmented .el-segmented__item:hover .el-segmented__item-label {
  color: #fff !important;
}
</style>
