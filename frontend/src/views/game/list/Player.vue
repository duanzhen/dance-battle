<template>
  <div class="space-y-1">
    <!-- 第一行：搜索框和标题 -->
    <div class="flex justify-between items-center gap-4">
      <h2 class="text-xl font-bold text-neutral-100 flex items-center flex-shrink-0"><Users class="w-5 h-5 text-amber-500" /> 参赛阵容</h2>

      <div class="flex items-center gap-2">
        <!-- 仅显示未签到选手开关 -->
        <div v-if="displayMode === 'player'" class="flex items-center gap-2">
          <span class="text-xs text-neutral-400">仅未签到</span>
          <el-switch
            v-model="filterUncheckInOnly"
            size="small"
            inline-prompt
            active-text="开"
            inactive-text="关"
            class="filter-switch"
          />
        </div>

        <el-input
          v-model="searchKeyword"
          :placeholder="displayMode === 'player' ? '搜索选手姓名' : '搜索参赛队伍'"
          size="small"
          :prefix-icon="Users"
          clearable
          class="search-input"
          style="width: 200px"
        />
      </div>
    </div>

    <!-- 第二行：按钮和segmented -->
    <div class="flex justify-between items-center pb-4">
      <div class="flex items-center gap-2 min-w-0">
        <span class="text-xs text-neutral-500">管理参赛选手名单及战队归属</span>
        <span
          v-if="auditionGaming"
          class="px-1.5 py-0.5 bg-amber-500/10 border border-amber-500/30 rounded text-[10px] text-amber-500 flex items-center gap-1 flex-shrink-0"
        >
          <span class="w-1.5 h-1.5 rounded-full bg-amber-500 animate-pulse"></span>
          海选进行中，签到后自动加入场次
        </span>
      </div>
      <div class="flex items-center gap-1">
        <el-button type="warning" size="small" :icon="Plus" @click="handleAdd" class="amber-button">添加选手</el-button>
        <el-button size="small" @click="showImportDialog = true" class="import-button">批量导入</el-button>
        <el-button
          v-if="canRandomCircles"
          size="small"
          :icon="Shuffle"
          @click="handleRandomCircles"
          class="import-button"
        >
          随机抽取圈
        </el-button>
        <el-segmented
          v-model="displayMode"
          :options="displayModeOptions"
          size="small"
          class="amber-segmented"
          @change="handleModeChange"
        />
      </div>
    </div>

    <div class="space-y-3">
      <!-- 加载指示:仅顶部小提示,不卸载列表 DOM,避免滚动位置被拉回顶部 -->
      <div v-show="loading" class="flex items-center justify-center py-2">
        <div class="animate-spin rounded-full h-5 w-5 border-b-2 border-amber-500"></div>
      </div>
      <!-- 显示选手列表 -->
      <TransitionGroup v-if="displayMode === 'player'" name="card">
        <div
          v-for="player in filteredPlayers"
          :key="player.id"
          class="bg-neutral-900 border border-neutral-800 rounded-xl p-4 flex items-center gap-4 relative group hover:border-neutral-600 hover:shadow-lg transition-all duration-300"
        >
          <div
            class="w-12 h-12 rounded-lg bg-white border border-neutral-800 flex-none flex items-center justify-center text-neutral-700 overflow-hidden relative group-hover:border-amber-500/30 transition-colors"
          >
            <img
              v-if="player.avatar"
              :src="player.avatar"
              crossorigin="anonymous"
              class="w-full h-full object-cover"
            />
            <User v-else class="w-6 h-6" />
            <div class="absolute bottom-0.5 right-0.5 w-1.5 h-1.5 bg-green-500 rounded-full border border-neutral-950 shadow-[0_0_5px_lime]"></div>
            <!-- 签到状态标记 -->
            <div class="absolute top-0.5 right-0.5 flex items-center justify-center w-4 h-4 rounded-full shadow-md bg-neutral-900">
              <Check v-if="player.competitorId" class="w-2.5 h-2.5 text-green-500" />
              <Clock v-else class="w-2.5 h-2.5 text-neutral-400" />
            </div>
          </div>

          <div class="flex-1 min-w-0 flex items-center gap-4">
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2">

                <div class="text-sm font-bold text-white truncate">{{ player.name || '未命名' }}</div>
                <!-- 签到状态标签 -->
                <span
                  v-if="player.competitorId"
                  class="px-1.5 py-0.5 bg-green-500/10 border border-green-500/20 rounded text-[10px] text-green-500 flex-shrink-0"
                >
                  已签到
                </span>
                <span
                  v-else
                  class="px-1.5 py-0.5 bg-neutral-500/10 border border-neutral-500/20 rounded text-[10px] text-neutral-500 flex-shrink-0"
                >
                  未签到
                </span>
                <!-- 选手号码 -->
                <span
                  v-if="player.competitorVo?.number"
                  class="px-1.5 py-0.5 bg-amber-500/20 border border-amber-500/30 rounded text-[10px] text-amber-500 font-bold flex-shrink-0"
                >
                  NO.{{ player.competitorVo.number }}
                </span>
              </div>
              <div v-if="player.remark" class="text-xs text-neutral-400 truncate">{{ player.remark }}</div>
            </div>

            <div v-if="player.tags" class="flex items-center gap-1 flex-wrap">
              <span
                v-for="tag in parseTags(player.tags)"
                :key="tag"
                class="px-1.5 py-0.5 bg-amber-500/10 border border-amber-500/20 rounded text-[10px] text-amber-500"
              >
                {{ tag }}
              </span>
            </div>

            <div v-if="player.idCard" class="text-[10px] text-neutral-500 font-mono flex-shrink-0">
              ID: {{ player.idCard }}
            </div>
          </div>

          <!-- 编辑按钮 -->
          <button
            @click.stop="handleEdit(player)"
            class="bg-amber-500/20 text-amber-500 hover:bg-amber-500 hover:text-white p-1.5 rounded-full opacity-0 group-hover:opacity-100 transition-all shadow-md flex items-center justify-center"
            title="编辑"
          >
            <svg xmlns="http://www.w3.org/2000/svg" class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"/>
            </svg>
          </button>

          <!-- 签到按钮 -->
          <button
            v-if="!player.competitorId"
            @click.stop="handleCheckIn(player)"
            class="bg-green-500/20 text-green-500 hover:bg-green-500 hover:text-white p-1.5 rounded-full transition-all shadow-md flex items-center justify-center"
            title="签到"
          >
            <UserRoundCheck class="w-3.5 h-3.5" />
          </button>
        </div>
      </TransitionGroup>

      <!-- 显示参赛队伍列表 -->
      <TransitionGroup v-else name="card">
        <div
          v-for="competitor in filteredCompetitors"
          :key="competitor.id"
          class="bg-neutral-900 border border-neutral-800 rounded-xl p-4 flex items-center gap-4 relative group hover:border-neutral-600 hover:shadow-lg transition-all duration-300"
        >
          <!-- 选手号码徽章 -->
          <div
            class="w-12 h-12 rounded-lg bg-amber-500/20 border border-amber-500/30 flex-none flex items-center justify-center"
          >
            <span class="text-amber-500 font-bold text-lg">{{ competitor.number || '-' }}</span>
          </div>

          <div class="flex-1 min-w-0 flex items-center gap-4">
            <div class="flex-1 min-w-0">
              <!-- 选手姓名列表 -->
              <div v-if="competitor.playerList && competitor.playerList.length > 0" class="flex items-center gap-1 flex-wrap">
                <span
                  v-for="player in competitor.playerList"
                  :key="player.id"
                  class="text-sm font-medium text-neutral-200"
                >
                  {{ player.name }}
                </span>
              </div>
              <div v-else class="text-sm font-bold text-white truncate">{{ competitor.name || '未命名' }}</div>
              <div v-if="competitor.remark" class="text-xs text-neutral-400 truncate">{{ competitor.remark }}</div>
            </div>

            <div class="flex items-center gap-2 text-[10px] text-neutral-500 font-mono flex-shrink-0">
              <span v-if="competitor.seedRank !== null">SEED: {{ competitor.seedRank }}</span>
            </div>
          </div>
        </div>
      </TransitionGroup>
    </div>

    <!-- PlayerForm Dialog -->
    <PlayerForm
      v-model="formVisible"
      :player="currentPlayer"
      :tournament-id="tournamentId"
      @submit="handleSubmit"
    />

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
        <div class="bg-neutral-900 border border-neutral-700 rounded-xl w-full max-w-md mx-4 shadow-2xl" @click.stop>
          <div class="px-5 py-4 border-b border-neutral-800 flex items-center justify-between">
            <h3 class="text-sm font-bold text-neutral-100">批量导入选手</h3>
            <button @click="showImportDialog = false" class="text-neutral-500 hover:text-neutral-300">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>

          <div class="p-5 space-y-4">
            <div class="p-3 rounded-lg bg-neutral-800 border border-neutral-700">
              <p class="text-[10px] text-neutral-400 mb-2">下载模板，按格式填写后上传</p>
              <button
                @click="downloadTemplate"
                class="text-xs text-amber-500 hover:text-amber-400 underline"
              >
                下载导入模板
              </button>
            </div>

            <label class="block cursor-pointer">
              <input
                type="file"
                accept=".xlsx,.xls"
                class="hidden"
                @change="handleFileSelect"
                ref="fileInputRef"
              />
              <div class="border-2 border-dashed border-neutral-700 rounded-lg p-6 text-center hover:border-amber-500/50 transition-colors">
                <div v-if="!importFile" class="space-y-2">
                  <svg class="w-8 h-8 text-neutral-600 mx-auto" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                  </svg>
                  <p class="text-xs text-neutral-500">点击选择 Excel 文件</p>
                  <p class="text-[10px] text-neutral-600">支持 .xlsx / .xls 格式</p>
                </div>
                <div v-else class="space-y-1">
                  <p class="text-xs text-amber-400 font-bold">{{ importFile.name }}</p>
                  <p class="text-[10px] text-neutral-500">{{ formatFileSize(importFile.size) }}</p>
                </div>
              </div>
            </label>

            <div v-if="importResult" class="p-3 rounded-lg" :class="importResult.success ? 'bg-green-500/10 border border-green-500/20' : 'bg-red-500/10 border border-red-500/20'">
              <p class="text-xs" :class="importResult.success ? 'text-green-400' : 'text-red-400'">{{ importResult.msg }}</p>
            </div>
          </div>

          <div class="px-5 py-4 border-t border-neutral-800 flex justify-end gap-3">
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

    <!-- 随机抽取圈结果弹窗 -->
    <Teleport to="body">
      <div
        v-if="circleResult && circleResult.length > 0"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
        @click.self="circleResult = null"
      >
        <div class="bg-neutral-900 border border-neutral-700 rounded-xl w-full max-w-2xl mx-4 shadow-2xl max-h-[80vh] flex flex-col" @click.stop>
          <div class="px-5 py-4 border-b border-neutral-800 flex items-center justify-between">
            <h3 class="text-sm font-bold text-neutral-100 flex items-center gap-2">
              <Shuffle class="w-4 h-4 text-amber-500" /> 随机抽取圈结果
            </h3>
            <button @click="circleResult = null" class="text-neutral-500 hover:text-neutral-300">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
          <div class="p-5 overflow-y-auto space-y-4">
            <div
              v-for="circle in circleResult"
              :key="circle.matchId"
              class="rounded-lg border border-neutral-800 bg-black/30 overflow-hidden"
            >
              <div class="px-4 py-2.5 bg-neutral-800/60 border-b border-neutral-800 flex items-center justify-between">
                <span class="text-xs font-bold text-amber-400">{{ circle.matchName }}</span>
                <span class="text-[10px] text-neutral-500">{{ circle.competitors.length }} 人</span>
              </div>
              <div class="p-3 flex flex-wrap gap-1.5">
                <span
                  v-for="comp in circle.competitors"
                  :key="comp.competitorId"
                  class="px-2 py-1 rounded bg-neutral-800 border border-neutral-700 text-[11px] text-neutral-300"
                >
                  {{ comp.number ? 'NO.' + comp.number : '' }} {{ comp.name }}
                </span>
              </div>
            </div>
          </div>
          <div class="px-5 py-4 border-t border-neutral-800 flex justify-end">
            <button
              @click="circleResult = null"
              class="px-4 py-2 text-xs font-bold text-neutral-900 bg-amber-500 hover:bg-amber-400 rounded-lg transition-colors"
            >
              知道了
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, computed, nextTick } from 'vue';
import { Users, Plus, User, Check, Clock, UserRoundCheck, Shuffle, File } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import { listPlayer, addPlayer, updatePlayer as updatePlayerApi, importPlayers } from '@/api/game/player';
import { download } from '@/utils/request';
import { PlayerVO, PlayerForm as PlayerFormType } from '@/api/game/player/types';
import { listCompetitor } from '@/api/game/competitor';
import { CompetitorVO } from '@/api/game/competitor/types';
import { getFirstStage, getStage } from '@/api/game/stage';
import { randomCircles, CircleAssignVo } from '@/api/game/stage/lifecycle';
import PlayerForm from './PlayerForm.vue';
import CheckInDialog from './CheckInDialog.vue';

const props = defineProps<{
  tournamentId?: string | number;
}>();

// 本地状态
const displayMode = ref<'player' | 'competitor'>('player');
const displayModeOptions = [
  { label: '选手', value: 'player' },
  { label: '参赛队伍', value: 'competitor' }
];
const searchKeyword = ref('');
const filterUncheckInOnly = ref(false);
const players = ref<PlayerVO[]>([]);
const competitors = ref<CompetitorVO[]>([]);
const loading = ref(false);
const formVisible = ref(false);
const currentPlayer = ref<PlayerVO | null>(null);
const firstStageId = ref<string | number | null>(null);
const checkInDialogRef = ref<InstanceType<typeof CheckInDialog> | null>(null);
const currentCheckInPlayer = ref<PlayerVO | null>(null);

// 海选分圈随机抽取状态
const firstStageInfo = ref<{ stageMode?: string; status?: string; circles?: number } | null>(null);
const circleResult = ref<CircleAssignVo[] | null>(null);
const drawingCircles = ref(false);

// 海选赛段已开始:提示签到后会自动加入当前场次继续参赛
const auditionGaming = computed(() => {
  const info = firstStageInfo.value;
  return !!info && info.stageMode === 'AUDITION' && info.status === 'GAMING';
});

// 仅分圈海选、赛段未开始时显示"随机抽取圈"
const canRandomCircles = computed(() => {
  const info = firstStageInfo.value;
  return !!info
    && info.stageMode === 'AUDITION'
    && (info.circles ?? 1) > 1
    && (info.status === 'DRAFT' || info.status === 'PENDING');
});

// 批量导入状态
const showImportDialog = ref(false);
const importFile = ref<File | null>(null);
const importing = ref(false);
const importResult = ref<{ success: boolean; msg: string } | null>(null);
const fileInputRef = ref<HTMLInputElement | null>(null);

// 过滤后的选手列表
const filteredPlayers = computed(() => {
  let result = players.value;

  // 过滤掉已签到的选手
  if (filterUncheckInOnly.value) {
    result = result.filter(player => !player.competitorId);
  }

  // 搜索过滤
  if (searchKeyword.value) {
    const keyword = searchKeyword.value.toLowerCase().trim();
    result = result.filter(player =>
      player.name?.toLowerCase().includes(keyword)
    );
  }

  return result;
});

// 过滤后的参赛队伍列表（按号码排序）
const filteredCompetitors = computed(() => {
  let result = competitors.value;

  if (searchKeyword.value) {
    const keyword = searchKeyword.value.toLowerCase().trim();
    result = result.filter(competitor =>
      competitor.name?.toLowerCase().includes(keyword) ||
      competitor.playerList?.some((p: any) => p.name?.toLowerCase().includes(keyword))
    );
  }

  // 按号码排序（转为数字）
  return result.sort((a, b) => {
    const numA = parseInt(a.number) || 0;
    const numB = parseInt(b.number) || 0;
    return numA - numB;
  });
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

// 加载参赛队伍列表
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
    console.error('加载参赛队伍失败:', error);
    ElMessage.error('加载参赛队伍失败');
    competitors.value = [];
  }
};

// 记录/恢复页面滚动位置:刷新列表时 DOM 重建会把滚动拉回顶部,刷新后还原
const captureScroll = () =>
  ([document.documentElement, document.body, document.querySelector('.main-container'), document.querySelector('.app-main')] as (HTMLElement | null)[])
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

// 同时刷新选手和参赛队伍列表
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

    // 同时加载选手和参赛队伍
    await Promise.all([
      loadPlayers(),
      loadCompetitors()
    ]);
  } catch (error) {
    console.error('加载数据失败:', error);
  } finally {
    loading.value = false;
    await nextTick();
    restoreScroll(positions);
  }
};

// 切换显示模式（不再加载数据，只用于切换视图）
const handleModeChange = () => {
  // 数据已经在 refreshAll 中统一加载
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

// 加载首个赛段信息(判断是否分圈海选,控制"随机抽取圈"按钮)
const loadFirstStageInfo = async () => {
  firstStageInfo.value = null;
  if (!firstStageId.value) return;
  try {
    const resp = await getStage(firstStageId.value);
    const data = resp.data as any;
    let circles = 1;
    try {
      if (data.ruleConfig) {
        const rc = JSON.parse(data.ruleConfig);
        if (rc && typeof rc.circles === 'number') circles = rc.circles;
      }
    } catch {
      // 忽略解析失败
    }
    firstStageInfo.value = {
      stageMode: data.stageMode,
      status: data.status,
      circles
    };
  } catch (e) {
    console.error('加载赛段信息失败:', e);
  }
};

// 随机抽取圈:把已签到选手随机均衡分到各圈场次(可重抽)
const handleRandomCircles = async () => {
  if (!firstStageId.value) return;
  if (!confirm('随机抽取圈会把已签到选手随机均衡分到各圈场次，可重复抽取直到满意，确定？')) return;
  drawingCircles.value = true;
  try {
    const resp = await randomCircles(firstStageId.value);
    circleResult.value = (resp.data || []) as CircleAssignVo[];
    ElMessage.success(`已随机分到 ${circleResult.value.length} 圈`);
    await refreshAll();
  } catch (e: any) {
    ElMessage.error(e?.msg || e?.message || '随机抽取圈失败');
  } finally {
    drawingCircles.value = false;
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

    if (data.id) {
      // 编辑
      await updatePlayerApi(submitData);
      ElMessage.success('更新成功');
    } else {
      // 添加
      await addPlayer(submitData);
      ElMessage.success('添加成功');
    }
    formVisible.value = false;
    currentPlayer.value = null;
    await refreshAll();
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

const formatFileSize = (bytes: number) => {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
};

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
    importFile.value = null;
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
.amber-segmented :deep(.el-segmented) {
  background-color: #27272a !important;
  padding: 2px !important;
}

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
