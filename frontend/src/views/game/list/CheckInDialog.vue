<template>
  <GameDialog
    v-model="visible"
    width="600px"
    :title="editing ? '编辑签到' : '选手签到'"
    :subtitle="editing ? '修改参赛号码与落圈，确认选手信息' : '选择参赛号码并确认选手信息'"
    :icon="UserRoundCheck"
    :before-close="handleClose"
    :close-on-click-modal="false"
    dialog-class="checkin-dialog"
  >
    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-amber-500"></div>
    </div>

    <div v-else class="space-y-4">
      <!-- 选手名称：展示 / 编辑 -->
      <div>
        <label class="block text-sm font-medium text-neutral-400 mb-1.5">选手名称</label>
        <!-- 展示模式 -->
        <div v-if="!isEditing" class="flex items-center justify-between bg-neutral-800/50 rounded-lg px-3 py-2.5 border border-neutral-700">
          <span class="text-white font-bold text-lg">{{ playerName || '未命名' }}</span>
          <button
            @click="startEdit"
            class="w-8 h-8 rounded-lg flex items-center justify-center text-neutral-400 hover:text-amber-500 hover:bg-amber-500/10 transition-colors shrink-0"
            title="编辑名称"
          >
            <Pencil class="w-4 h-4" />
          </button>
        </div>
        <!-- 编辑模式 -->
        <input
          v-else
          v-model="editPlayerName"
          type="text"
          maxlength="50"
          class="w-full bg-neutral-950 border border-amber-500 rounded-lg px-3 py-2.5 text-white placeholder-neutral-600 focus:outline-none transition-all text-sm"
        />
      </div>

      <!-- 选手头像：展示 / 编辑 -->
      <div>
        <label class="block text-sm font-medium text-neutral-400 mb-1.5">选手头像</label>
        <!-- 展示模式 -->
        <div v-if="!isEditing" class="flex items-center justify-between bg-neutral-800/50 rounded-lg px-3 py-2.5 border border-neutral-700">
          <img v-if="playerAvatar" :src="playerAvatar" class="w-16 h-16 rounded-lg object-cover" />
          <div v-else class="w-16 h-16 rounded-lg bg-neutral-700 flex items-center justify-center text-neutral-500">
            <User class="w-6 h-6" />
          </div>
          <button
            @click="startEdit"
            class="w-8 h-8 rounded-lg flex items-center justify-center text-neutral-400 hover:text-amber-500 hover:bg-amber-500/10 transition-colors shrink-0"
            title="编辑头像"
          >
            <Pencil class="w-4 h-4" />
          </button>
        </div>
        <!-- 编辑模式 -->
        <div v-else>
          <PortraitMatting ref="mattingRef" v-model="editPlayerAvatar" />
        </div>
      </div>

      <!-- 编辑模式操作按钮 -->
      <div v-if="isEditing" class="flex gap-2 pt-1">
        <button
          @click="cancelEdit"
          class="flex-1 py-2 text-sm rounded-lg border border-neutral-700 text-neutral-400 hover:text-neutral-200 hover:bg-neutral-800 transition-colors"
        >
          取消编辑
        </button>
        <button @click="confirmEdit" class="flex-1 py-2 text-sm font-bold rounded-lg bg-amber-600 hover:bg-amber-500 text-white transition-colors">
          确认
        </button>
      </div>

      <!-- 号码选择 -->
      <div>
        <label class="block text-sm font-medium text-neutral-400 mb-1.5">参赛号码</label>
        <div ref="slotListRef" class="space-y-1.5 max-h-64 overflow-y-auto pr-2 scrollbar-hide">
          <div
            v-for="slot in numberSlots"
            :key="slot.number"
            :data-slot-number="slot.number"
            @click="handleSelectSlot(slot)"
            class="relative rounded flex items-center gap-2 cursor-pointer transition-all border-2 py-2 px-2"
            :class="[
              selectedSlot?.number === slot.number ? 'border-amber-500 bg-amber-500/10' : '',
              selectedSlot?.number === slot.number
                ? ''
                : slot.competitor
                  ? 'bg-green-500/10 border-green-500/30'
                  : 'bg-neutral-800/50 border-neutral-700',
              selectedSlot?.number === slot.number
                ? ''
                : slot.competitor
                  ? 'hover:border-green-500/60'
                  : 'hover:border-neutral-500 hover:bg-neutral-800'
            ]"
          >
            <div class="w-10 h-10 flex items-center justify-center flex-shrink-0">
              <div class="text-lg font-bold" :class="slot.competitor ? 'text-green-500' : 'text-neutral-400'">
                {{ slot.number }}
              </div>
            </div>
            <div class="flex-1 min-w-0">
              <div v-if="slot.competitor" class="text-sm text-white truncate">
                {{ slot.competitor.name }}
              </div>
              <div v-else class="text-sm text-neutral-500">空闲</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 分圈落位(海选/排名赛分圈且已生成对阵时) -->
      <div v-if="circleList.length > 1" class="bg-neutral-800/50 rounded-lg p-3 border border-neutral-700">
        <label class="block text-sm font-medium text-neutral-400 mb-1.5">分圈落位</label>
        <!-- 按号码顺序均分:号码决定圈位,只读提示 -->
        <template v-if="splitByNumber">
          <div class="rounded-lg border border-neutral-700 bg-neutral-950/60 px-3 py-2.5">
            <div class="flex items-center gap-2 text-xs text-neutral-300">
              <svg class="w-3.5 h-3.5 text-amber-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M7 7h.01M7 3h5a1.99 1.99 0 011.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A1.994 1.994 0 013 12V7a4 4 0 014-4z"
                />
              </svg>
              <span class="font-bold">按号码自动分圈</span>
            </div>
            <p v-if="selectedSlot && !selectedSlot.competitor && selectedCircleName" class="mt-1.5 text-xs text-neutral-400">
              号码 <span class="text-white font-bold">{{ selectedSlot.number }}</span> 将进入
              <span class="text-amber-500 font-bold">{{ selectedCircleName }}</span>
            </p>
            <p v-else class="mt-1.5 text-xs text-neutral-500">选择空闲号码后自动进入对应圈，无需手动指定</p>
          </div>
        </template>
        <!-- 随机抽取分圈:线下抽签可手动选圈 -->
        <template v-else>
          <div class="grid grid-cols-2 gap-2">
            <button
              @click="selectedMatchId = null"
              class="rounded-lg border px-3 py-2 text-left transition-all"
              :class="
                selectedMatchId === null
                  ? 'border-amber-500 bg-amber-500/10 text-amber-500'
                  : 'border-neutral-700 text-neutral-400 hover:border-neutral-500'
              "
            >
              <div class="text-xs font-bold">自动分配</div>
              <div class="text-[10px] text-neutral-500 mt-0.5">按各圈剩余名额择优</div>
            </button>
            <button
              v-for="circle in circleList"
              :key="circle.matchId"
              @click="selectedMatchId = circle.matchId"
              class="rounded-lg border px-3 py-2 text-left transition-all"
              :class="
                selectedMatchId === circle.matchId
                  ? 'border-amber-500 bg-amber-500/10 text-amber-500'
                  : 'border-neutral-700 text-neutral-400 hover:border-neutral-500'
              "
            >
              <div class="text-xs font-bold">{{ circle.name }}</div>
              <div class="text-[10px] text-neutral-500 mt-0.5">
                当前 {{ circle.count }} 人<template v-if="circle.quota !== null"> / 名额 {{ circle.quota }}</template>
              </div>
            </button>
          </div>
          <p class="text-[10px] text-neutral-600 mt-1.5">随机分圈模式下可手动指定目标圈，号码与圈位无关</p>
        </template>
      </div>

      <!-- 选中信息:常显,避免弹窗高度变化闪烁 -->
      <div class="bg-neutral-800/50 rounded-lg p-3 border border-neutral-700">
        <div class="text-sm">
          <template v-if="selectedSlot">
            <span class="text-neutral-400">已选择：</span>
            <span class="text-white font-bold">号码 {{ selectedSlot.number }}</span>
            <span v-if="selectedSlot.competitor" class="text-green-500 ml-2"> ({{ selectedSlot.competitor.name }}) </span>
            <span v-else class="text-neutral-500 ml-2"> (空白号码 - 新增参赛) </span>
          </template>
          <span v-else class="text-neutral-500">尚未选择参赛号码</span>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex items-center justify-between gap-2.5">
        <!-- 编辑模式:左侧提供解除签到,普通签到保留随机抽取 -->
        <button
          v-if="editing"
          @click="handleCancelCheckIn"
          :disabled="submitting"
          class="px-4 py-2 rounded-lg text-sm font-medium text-red-400 bg-red-500/10 border border-red-900/50 hover:bg-red-500 hover:text-white transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1.5"
        >
          <Ban class="w-4 h-4" />
          解除签到
        </button>
        <button
          v-if="!editing"
          @click="handleRandomSelect"
          :disabled="loading || numberSlots.filter((s) => !s.competitor).length === 0"
          class="px-4 py-2 bg-green-600 hover:bg-green-500 text-white text-sm rounded-lg font-bold shadow-lg shadow-green-900/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
        >
          <svg xmlns="http://www.w3.org/2000/svg" class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
            />
          </svg>
          随机抽取
        </button>
        <div class="flex gap-2.5">
          <button @click="handleClose" class="px-4 py-2 rounded-lg text-sm text-neutral-400 hover:text-white hover:bg-neutral-800 transition-all">
            取消
          </button>
          <button
            @click="handleSubmit"
            :disabled="!selectedSlot || submitting"
            class="px-5 py-2 bg-amber-600 hover:bg-amber-500 text-white text-sm rounded-lg font-bold shadow-lg shadow-amber-900/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center"
          >
            <svg v-if="submitting" class="animate-spin -ml-1 mr-2 h-4 w-4 text-white" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
              <path
                class="opacity-75"
                fill="currentColor"
                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
              ></path>
            </svg>
            {{ submitting ? '提交中...' : editing ? '保存修改' : '确认签到' }}
          </button>
        </div>
      </div>
    </template>
  </GameDialog>
</template>

<script setup lang="ts">
import { ref, computed, nextTick } from 'vue';
import { Pencil, User, UserRoundCheck, Ban } from 'lucide-vue-next';
import { ElMessage, ElMessageBox } from 'element-plus';
import { PlayerVO } from '@/api/game/player/types';
import { CompetitorVO } from '@/api/game/competitor/types';
import { listCompetitor } from '@/api/game/competitor';
import { checkInPlayer, editCheckIn, cancelCheckIn, listPlayer } from '@/api/game/player';
import { getStage } from '@/api/game/stage';
import { listMatch } from '@/api/game/match';
import { listMatchParticipant } from '@/api/game/matchParticipant';
import PortraitMatting from './PortraitMatting.vue';
import GameDialog from '@/components/GameDialog/index.vue';

const props = defineProps<{
  player: PlayerVO | null;
  tournamentId: string | number;
  stageId: string | number;
}>();

const emit = defineEmits<{
  success: [];
}>();

const visible = ref(false);
const loading = ref(false);
const submitting = ref(false);
// 编辑模式:选手已签到,可改号码/换圈
const editing = ref(false);
const competitors = ref<CompetitorVO[]>([]);
const playerCount = ref(0);
const selectedSlot = ref<{ number: number; competitor: CompetitorVO | null } | null>(null);
const playerName = ref('');
const playerAvatar = ref('');
const currentPlayer = ref<PlayerVO | null>(null);
const isEditing = ref(false);
const editPlayerName = ref('');
const editPlayerAvatar = ref('');
const mattingRef = ref<InstanceType<typeof PortraitMatting> | null>(null);

const numberSlots = ref<{ number: number; competitor: CompetitorVO | null }[]>([]);
const slotListRef = ref<HTMLElement | null>(null);
const circleList = ref<{ matchId: string | number; name: string; count: number; quota: number | null }[]>([]);
const selectedMatchId = ref<string | number | null>(null);
// 海选圈是否按号码顺序均分(生成对阵时后端会把 randomSplit 写入 ruleConfig)
const splitByNumber = ref(false);
const circleConfigCount = ref(1);

// 加载分圈信息:赛段配置(circles/circleAdvanceCounts)+ 各圈当前人数(仅海选/排名赛分圈且已生成对阵时)
const loadCircleInfo = async () => {
  circleList.value = [];
  splitByNumber.value = false;
  selectedMatchId.value = null;
  try {
    const stageRes = await getStage(props.stageId);
    const stage = stageRes.data || (stageRes as any).data;
    const mode = stage?.stageMode;
    if (!mode || (mode !== 'AUDITION' && mode !== 'RANK')) {
      return;
    }
    let circles = 0;
    let quotas: number[] = [];
    let randomSplit = false;
    try {
      const rc = JSON.parse(stage?.ruleConfig || '{}');
      circles = Math.max(1, Number(rc.circles) || 1);
      quotas = Array.isArray(rc.circleAdvanceCounts) ? rc.circleAdvanceCounts.map(Number) : [];
      randomSplit = rc.randomSplit === true;
    } catch {
      // 忽略解析失败,不展示分圈信息
    }
    if (circles <= 1) {
      return;
    }
    circleConfigCount.value = circles;
    // randomSplit=false / 旧数据未记录:按号分圈;randomSplit=true:随机分圈
    splitByNumber.value = mode === 'AUDITION' && !randomSplit;
    const matchRes = await listMatch({ stageId: props.stageId } as any);
    const matches = (matchRes.data || (matchRes as any).data || []) as any[];
    const ordered = [...matches].sort((a, b) => (Number(a.displayRow) || 0) - (Number(b.displayRow) || 0));
    const list: typeof circleList.value = [];
    for (let i = 0; i < ordered.length; i++) {
      const m = ordered[i];
      const parts = await listMatchParticipant({ matchId: m.id } as any);
      const participants = (parts.data || (parts as any).data || []) as any[];
      list.push({
        matchId: m.id,
        name: m.displayZone ? `第${String(m.displayZone).replace('ZONE-', '')}圈` : m.name || `圈${i + 1}`,
        count: participants.length,
        quota: quotas.length > i && quotas[i] >= 0 ? quotas[i] : null
      });
    }
    circleList.value = list;
  } catch (error) {
    console.warn('加载分圈信息失败:', error);
    circleList.value = [];
  }
};

// 滚动到第一个空闲号码(只滚动号码列表容器,不影响页面)
const scrollToFirstFreeSlot = async () => {
  await nextTick();
  const container = slotListRef.value;
  const firstFree = numberSlots.value.find((slot) => !slot.competitor);
  if (!container || !firstFree) return;
  const el = container.querySelector(`[data-slot-number="${firstFree.number}"]`) as HTMLElement | null;
  if (!el) return;
  const targetTop =
    el.getBoundingClientRect().top - container.getBoundingClientRect().top + container.scrollTop - container.clientHeight / 2 + el.clientHeight / 2;
  container.scrollTop = Math.max(0, targetTop);
};

const loadCompetitors = async () => {
  loading.value = true;
  try {
    // 已添加的选手总数:随机抽号时最多只会抽到这个数字以内的号码
    try {
      const pResp: any = await listPlayer({
        tournamentId: props.tournamentId,
        pageNum: 1,
        pageSize: 10000
      } as any);
      const players = pResp?.data ?? [];
      playerCount.value = Array.isArray(players) ? players.length : 0;
    } catch (e) {
      console.warn('加载选手总数失败:', e);
      playerCount.value = 0;
    }

    const response = await listCompetitor({
      tournamentId: props.tournamentId,
      stageId: props.stageId,
      pageNum: 1,
      pageSize: 1000
    });
    competitors.value = response.data || [];

    let maxNumber = 10;
    if (competitors.value.length > 0) {
      const numbers = competitors.value.map((c) => parseInt(c.number || '0')).filter((n) => !isNaN(n) && n > 0);
      if (numbers.length > 0) {
        maxNumber = Math.max(...numbers) + 10;
      }
    }

    const competitorMap = new Map<number, CompetitorVO>();
    competitors.value.forEach((c) => {
      const num = parseInt(c.number || '0');
      if (!isNaN(num) && num > 0) {
        competitorMap.set(num, c);
      }
    });

    numberSlots.value = [];
    for (let i = 1; i <= maxNumber; i++) {
      numberSlots.value.push({
        number: i,
        competitor: competitorMap.get(i) || null
      });
    }
    // 编辑模式:预选当前选手已占用的号码
    if (editing.value && currentPlayer.value?.competitorId) {
      const ownNumber = competitors.value.find((c) => String(c.id) === String(currentPlayer.value?.competitorId))?.number;
      const ownSlot = numberSlots.value.find((s) => String(s.number) === String(ownNumber));
      if (ownSlot) {
        selectedSlot.value = ownSlot;
      }
    }
  } catch (error) {
    console.error('加载参赛选手失败:', error);
    ElMessage.error('加载参赛选手失败');
  } finally {
    loading.value = false;
    scrollToFirstFreeSlot();
  }
};

const handleSelectSlot = (slot: { number: number; competitor: CompetitorVO | null }) => {
  if (
    editing.value &&
    slot.competitor &&
    currentPlayer.value?.competitorId &&
    String(slot.competitor.id) !== String(currentPlayer.value.competitorId)
  ) {
    ElMessage.warning('该号码已被其他参赛占用，请选择空闲号码');
    return;
  }
  selectedSlot.value = slot;
};

// 按号分圈:把号码插入当前已有号码序列,按「均分、余数从前圈补」计算应落圈位(与后端生成口径一致)
const circleIndexOfNumber = (num: number) => {
  // 与后端一致:退赛选手不参与按号分圈的人数计算
  const numbers = competitors.value
    .filter((c) => c.outcomeStatus !== 'WITHDRAWN')
    .map((c) => parseInt(c.number || '0'))
    .filter((n) => !Number.isNaN(n) && n > 0);
  const sorted = [...numbers, num].sort((a, b) => a - b);
  const idx = sorted.indexOf(num);
  const total = sorted.length;
  const circles = circleList.value.length;
  if (idx < 0 || circles <= 1) return idx < 0 ? -1 : 0;
  const effective = Math.min(circleConfigCount.value, circles, Math.max(1, total));
  if (effective <= 1) return 0;
  const base = Math.floor(total / effective);
  const remainder = total % effective;
  if (idx < remainder * (base + 1)) {
    return Math.floor(idx / (base + 1));
  }
  return remainder + Math.floor((idx - remainder * (base + 1)) / base);
};

const selectedCircleName = computed(() => {
  const slot = selectedSlot.value;
  if (!slot || slot.competitor || !splitByNumber.value) return '';
  const idx = circleIndexOfNumber(Number(slot.number));
  return idx >= 0 && idx < circleList.value.length ? circleList.value[idx].name : '';
});

const handleRandomSelect = () => {
  const availableSlots = numberSlots.value.filter((slot) => !slot.competitor);
  if (availableSlots.length === 0) {
    ElMessage.warning('没有可用的空闲号码');
    return;
  }
  // 只在"已添加选手数量以内"的空闲号码里随机(最多随机到该数字);保留最小的 10 个号机制
  let candidates = availableSlots.filter((slot) => slot.number <= playerCount.value);
  if (candidates.length === 0) {
    candidates = availableSlots;
  }
  candidates = candidates.slice(0, 10);
  const randomIndex = Math.floor(Math.random() * candidates.length);
  selectedSlot.value = candidates[randomIndex];
  ElMessage.success(`已随机抽取号码 ${candidates[randomIndex].number}`);
};

const startEdit = () => {
  editPlayerName.value = playerName.value;
  editPlayerAvatar.value = playerAvatar.value;
  isEditing.value = true;
};

const cancelEdit = () => {
  isEditing.value = false;
};

const confirmEdit = async () => {
  if (mattingRef.value?.hasProcessedImg) {
    const uploadedUrl = await mattingRef.value.exportImage();
    if (uploadedUrl) {
      editPlayerAvatar.value = uploadedUrl;
    }
  }
  playerName.value = editPlayerName.value.trim() || playerName.value;
  playerAvatar.value = editPlayerAvatar.value;
  isEditing.value = false;
};

const handleSubmit = async () => {
  if (!selectedSlot.value) {
    ElMessage.warning('请选择参赛号码');
    return;
  }

  if (!currentPlayer.value) {
    ElMessage.warning('选手信息不存在');
    return;
  }

  if (!playerName.value.trim()) {
    ElMessage.warning('请输入选手名称');
    return;
  }

  submitting.value = true;
  try {
    if (editing.value) {
      // 按号分圈时号码决定圈位,不传 matchId 由后端按号换圈
      await editCheckIn({
        playerId: currentPlayer.value.id,
        competitorNumber: String(selectedSlot.value.number),
        matchId: splitByNumber.value ? undefined : (selectedMatchId.value ?? undefined),
        name: playerName.value.trim(),
        avatar: playerAvatar.value
      });
      ElMessage.success('签到结果已更新');
    } else {
      await checkInPlayer({
        playerId: currentPlayer.value.id,
        checkInType: selectedSlot.value.competitor ? 'JOIN' : 'CREATE',
        competitorNumber: String(selectedSlot.value.number),
        competitorId: selectedSlot.value.competitor?.id,
        name: playerName.value.trim(),
        avatar: playerAvatar.value,
        // 按号分圈时号码决定圈位,不传 matchId 由后端按号落圈
        matchId: splitByNumber.value ? undefined : (selectedMatchId.value ?? undefined)
      });
      ElMessage.success('签到成功');
    }
    emit('success');
    handleClose();
  } catch (error) {
    console.error('签到失败:', error);
    ElMessage.error((error as any)?.message || '签到失败');
  } finally {
    submitting.value = false;
  }
};

// 解除签到:回到未签到状态(已有打分记录时后端会拦截)
const handleCancelCheckIn = async () => {
  if (!currentPlayer.value) return;
  try {
    await ElMessageBox.confirm(
      `确认解除「${currentPlayer.value.name || '该选手'}」的签到？解除后选手回到未签到状态，可重新选择号码签到。`,
      '解除签到',
      {
        type: 'warning',
        confirmButtonText: '确认解除',
        cancelButtonText: '取消'
      }
    );
  } catch {
    return;
  }
  submitting.value = true;
  try {
    await cancelCheckIn(currentPlayer.value.id);
    ElMessage.success('已解除签到');
    emit('success');
    handleClose();
  } catch (error) {
    console.error('解除签到失败:', error);
    ElMessage.error((error as any)?.message || '解除签到失败');
  } finally {
    submitting.value = false;
  }
};

const open = (player?: PlayerVO | null) => {
  currentPlayer.value = player ?? props.player ?? null;
  editing.value = !!currentPlayer.value?.competitorId;
  selectedSlot.value = null;
  playerName.value = currentPlayer.value?.name || '';
  playerAvatar.value = currentPlayer.value?.avatar || '';
  isEditing.value = false;
  visible.value = true;
  loadCompetitors();
  loadCircleInfo();
};

const handleClose = () => {
  selectedSlot.value = null;
  isEditing.value = false;
  visible.value = false;
};

defineExpose({
  open
});
</script>

<style scoped>
.scrollbar-hide {
  -ms-overflow-style: none;
  scrollbar-width: none;
}

.scrollbar-hide::-webkit-scrollbar {
  display: none;
}
</style>
