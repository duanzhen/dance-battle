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

      <!-- 分圈横排(海选分圈时,号码列表上方:抽号即同步看到圈位) -->
      <div v-if="showCircleBar" class="bg-neutral-800/50 rounded-lg p-3 border border-neutral-700">
        <div class="flex items-center justify-between mb-2">
          <label class="block text-sm font-medium text-neutral-400">分圈</label>
          <!-- <span v-if="splitByNumber && hasRealCircles" class="text-[10px] text-neutral-500">号码决定圈位，抽号即落圈</span> -->
          <!-- <span v-else-if="splitByNumber" class="text-[10px] text-neutral-500">按号均分预估，实际以生成对阵为准</span> -->
          <!-- <span v-else-if="hasRealCircles" class="text-[10px] text-neutral-500">线下抽签可手动选圈</span> -->
          <!-- <span v-else class="text-[10px] text-neutral-500">签到后按所选圈落位</span> -->
        </div>
        <div class="flex gap-2 overflow-x-auto scrollbar-hide pb-0.5">
          <!-- 随机分圈:自动分配入口 -->
          <button
            v-if="!splitByNumber && circleBar.length > 0"
            @click="handleAutoCircle"
            class="flex-none rounded-lg border px-3 py-2 text-left transition-all min-w-[88px]"
            :class="
              selectedMatchId === null && selectedZoneIndex === null
                ? 'border-amber-500 bg-amber-500/10'
                : 'border-neutral-700 bg-neutral-900/40 hover:border-neutral-500'
            "
          >
            <div class="text-xs font-bold" :class="selectedMatchId === null && selectedZoneIndex === null ? 'text-amber-500' : 'text-neutral-300'">
              自动分配
            </div>
            <div class="text-[10px] text-neutral-500 mt-0.5">按剩余名额择优</div>
          </button>
          <button
            v-for="(c, ci) in circleBar"
            :key="c.key"
            @click="handleCircleChipClick(c, ci)"
            class="flex-none rounded-lg border px-3 py-2 text-left transition-all min-w-[88px]"
            :class="[
              splitByNumber
                ? ci === activeCircleIndex
                  ? 'border-amber-500 bg-amber-500/10'
                  : 'border-neutral-700 bg-neutral-900/40'
                : isCircleChipActive(c, ci)
                  ? 'border-amber-500 bg-amber-500/10'
                  : 'border-neutral-700 bg-neutral-900/40 hover:border-neutral-500'
            ]"
            :title="splitByNumber ? '' : c.matchId == null ? '选择此圈抽号(签到后生效)' : '选择此圈抽号'"
          >
            <div class="text-xs font-bold flex items-center gap-1">
              <span
                :class="
                  splitByNumber
                    ? ci === activeCircleIndex
                      ? 'text-amber-500'
                      : 'text-neutral-200'
                    : isCircleChipActive(c, ci)
                      ? 'text-amber-500'
                      : 'text-neutral-200'
                "
              >
                {{ c.name }}
              </span>
              <span v-if="splitByNumber && ci === activeCircleIndex" class="text-[9px] px-1 py-px rounded bg-amber-500/20 text-amber-500">
                {{ hasRealCircles ? '落此圈' : '预估此圈' }}
              </span>
            </div>
            <div class="text-[10px] mt-0.5" :class="isCircleChipActive(c, ci) ? 'text-amber-400' : 'text-neutral-500'">
              {{ c.count != null ? c.count + ' 人' : '—' }}<template v-if="c.quota !== null"> · 晋 {{ c.quota }}</template>
            </div>
          </button>
        </div>
        <!-- 按号分圈:当前抽中号码的目标圈提示 -->
        <p v-if="splitByNumber && selectedSlot && targetCircleName" class="mt-2 text-xs text-neutral-400">
          <template v-if="editing && selectedSlot.competitor">
            当前号码 <span class="text-white font-bold">{{ selectedSlot.number }}</span> 位于
          </template>
          <template v-else>
            抽到号码 <span class="text-white font-bold">{{ selectedSlot.number }}</span> 将进入
          </template>
          <span class="text-amber-500 font-bold">{{ targetCircleName }}</span>
          <template v-if="editing && selectedSlot.competitor">，换号后将按新号码自动落圈</template>
        </p>
        <p v-else-if="splitByNumber" class="mt-2 text-[10px] text-neutral-600">
          {{
            hasRealCircles
              ? '按号码顺序均分，余数从前圈补；选中号码后自动显示对应圈'
              : '按号码稳定轮转落圈（第N号 → 第 ((N-1) mod 圈数)+1 圈），不受签到顺序影响'
          }}
        </p>
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

      <!-- 选中信息:常显,避免弹窗高度变化闪烁 -->
      <div class="bg-neutral-800/50 rounded-lg p-3 border border-neutral-700">
        <div class="text-sm">
          <template v-if="selectedSlot">
            <span class="text-neutral-400">已选择：</span>
            <span class="text-white font-bold">号码 {{ selectedSlot.number }}</span>
            <span v-if="selectedCircleLabel" class="text-amber-500 ml-2">· {{ selectedCircleLabel }}</span>
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
import { ensureAuditionCircles } from '@/api/game/stage/lifecycle';
import { listMatch } from '@/api/game/match';
import { listMatchParticipant } from '@/api/game/matchParticipant';
import { listMatchReferee } from '@/api/game/matchReferee';
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
// 计划圈(尚未生成真圈)时选中的圈序号 1..n
const selectedZoneIndex = ref<number | null>(null);
// 编辑模式:当前选手实际所在圈场次ID(用于编辑时高亮真实圈,而非"自动")
const currentCircleMatchId = ref<string | number | null>(null);
// 参赛方 -> 所在圈场次ID(编辑回显用)
const competitorMatchMap = ref<Record<string, string | number>>({});
// 海选圈是否按号码顺序均分(生成对阵时后端会把 randomSplit 写入 ruleConfig)
const splitByNumber = ref(false);
const circleConfigCount = ref(1);
const stageMode = ref('');
const circleQuotaList = ref<(number | null)[]>([]);

// 加载分圈信息:赛段配置(circles/circleAdvanceCounts)+ 各圈当前人数(海选分圈且已生成对阵时)
const loadCircleInfo = async () => {
  circleList.value = [];
  splitByNumber.value = false;
  selectedMatchId.value = null;
  selectedZoneIndex.value = null;
  currentCircleMatchId.value = null;
  competitorMatchMap.value = {};
  stageMode.value = '';
  circleQuotaList.value = [];
  circleConfigCount.value = 1;
  try {
    const stageRes = await getStage(props.stageId);
    const stage = stageRes.data || (stageRes as any).data;
    const mode = stage?.stageMode;
    if (!mode || (mode !== 'AUDITION' && mode !== 'RANK')) {
      return;
    }
    stageMode.value = mode;
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
    circleQuotaList.value = Array.from({ length: circles }, (_, i) => (i < quotas.length && quotas[i] >= 0 ? quotas[i] : null));
    // randomSplit=false / 旧数据未记录:按号分圈;randomSplit=true:随机分圈
    splitByNumber.value = mode === 'AUDITION' && !randomSplit;
    // 非海选分圈(排名赛等)不按圈展示
    if (mode !== 'AUDITION') {
      return;
    }
    // 圈 = 真实 match:抽号页打开时先确保按配置建齐 ZONE 圈(空场也可),再拉取圈场次
    try {
      await ensureAuditionCircles(props.stageId);
    } catch (error) {
      console.warn('确保海选圈场次失败,按配置展示占位圈:', error);
    }
    // 圈名优先用该圈裁判名(如「张三」),无裁判时回退「第N圈」
    const refereeNamesByMatch: Record<string, string[]> = {};
    try {
      const refRes: any = await listMatchReferee(props.stageId);
      const refRows: any[] = refRes?.data || [];
      refRows.forEach((r) => {
        if (r.matchId != null && r.refereeName) {
          const key = String(r.matchId);
          (refereeNamesByMatch[key] = refereeNamesByMatch[key] || []).push(r.refereeName);
        }
      });
    } catch {
      // 裁判绑定读取失败时圈名回退第N圈
    }
    // 未生成对阵时 matches 为空,顶部按配置圈数展示实时预估
    const matchRes = await listMatch({ stageId: props.stageId } as any);
    const matches = (matchRes.data || (matchRes as any).data || []) as any[];
    const ordered = [...matches].sort((a, b) => (Number(a.displayRow) || 0) - (Number(b.displayRow) || 0));
    const list: typeof circleList.value = [];
    const compMatchMap: Record<string, string | number> = {};
    let zoneCursor = 0;
    for (let i = 0; i < ordered.length; i++) {
      const m = ordered[i];
      // 只展示海选分圈场次(单场 CENTER 非分圈)
      if (!m.displayZone || String(m.displayZone).startsWith('CENTER')) {
        continue;
      }
      const zoneNo = String(m.displayZone).replace('ZONE-', '');
      const refNames = refereeNamesByMatch[String(m.id)];
      const parts = await listMatchParticipant({ matchId: m.id } as any);
      const participants = (parts.data || (parts as any).data || []) as any[];
      participants.forEach((p: any) => {
        if (p.competitorId != null) {
          compMatchMap[String(p.competitorId)] = m.id;
        }
      });
      list.push({
        matchId: m.id,
        name: refNames && refNames.length > 0 ? refNames.join(' / ') : `第${zoneNo}圈`,
        count: participants.length,
        quota: quotas.length > zoneCursor && quotas[zoneCursor] >= 0 ? quotas[zoneCursor] : null
      });
      zoneCursor++;
    }
    circleList.value = list;
    competitorMatchMap.value = compMatchMap;
    // 编辑已签到选手:预选并高亮其实际所在圈
    if (editing.value && currentPlayer.value?.competitorId) {
      const actual = compMatchMap[String(currentPlayer.value.competitorId)];
      currentCircleMatchId.value = actual ?? null;
      if (!splitByNumber.value && actual != null && list.some((c) => String(c.matchId) === String(actual))) {
        selectedMatchId.value = actual;
      }
    }
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

// 随机分圈:顶部圈栏点击指定目标圈(按号分圈时圈由号码决定,点击无操作)。
// 已生成真圈时记录场次ID;尚未生成时记录计划圈序号,提交签到后由后端补建圈并落圈。
const handleCircleChipClick = (c: { matchId: string | number | null }, ci: number) => {
  if (splitByNumber.value) return;
  if (c.matchId != null) {
    selectedMatchId.value = c.matchId;
    selectedZoneIndex.value = null;
  } else {
    selectedMatchId.value = null;
    selectedZoneIndex.value = ci + 1;
  }
};

// 自动分配:清空手动选圈(含计划圈序号)
const handleAutoCircle = () => {
  selectedMatchId.value = null;
  selectedZoneIndex.value = null;
};

// 圈卡片选中态:按号分圈高亮号码对应圈;随机分圈高亮手动选中的真圈/计划圈
const isCircleChipActive = (c: { matchId: string | number | null }, ci: number) => {
  if (splitByNumber.value) {
    return ci === activeCircleIndex.value;
  }
  if (c.matchId != null) {
    return String(selectedMatchId.value) === String(c.matchId);
  }
  return ci + 1 === selectedZoneIndex.value;
};

// 与后端一致:退赛选手不参与按号分圈的人数计算
const activeCompetitorNumbers = () =>
  competitors.value
    .filter((c) => c.outcomeStatus !== 'WITHDRAWN')
    .map((c) => parseInt(c.number || '0'))
    .filter((n) => !Number.isNaN(n) && n > 0)
    .sort((a, b) => a - b);

// 按号分圈:圈位由号码本身决定(第N号 → 第 ((N-1) mod 圈数)+1 圈),与后端一致,
// 不依赖已签到人数/签到顺序,保证"先空圈、边签到边抽号"时各圈均分
const circleIndexOfNumber = (num: number, circleCount: number) => {
  if (circleCount <= 1 || !Number.isFinite(num) || num <= 0) return -1;
  return (num - 1) % circleCount;
};

// 当前生效的圈数:已生成对阵以实际场次数为准,否则按配置圈数预估
const effectiveCircleCount = computed(() => (circleList.value.length > 1 ? circleList.value.length : Math.max(1, circleConfigCount.value)));

// 是否已生成实际分圈场次
const hasRealCircles = computed(() => circleList.value.length > 1);

// 顶部圈栏:已生成对阵时展示实际圈(含人数/名额),否则按配置圈数实时预估
const circleBar = computed(() => {
  const total = hasRealCircles.value ? circleList.value.length : Math.max(1, circleConfigCount.value);
  const items: { key: string; matchId: string | number | null; name: string; count: number | null; quota: number | null }[] = [];
  for (let i = 0; i < total; i++) {
    const actual = hasRealCircles.value ? circleList.value[i] : null;
    items.push({
      key: actual ? String(actual.matchId) : `cfg-${i}`,
      matchId: actual ? actual.matchId : null,
      name: actual ? actual.name : `第${i + 1}圈`,
      // 未生成真圈时:按号分圈用当前号码实时预估,随机分圈(计划圈)暂无落位人数
      count: actual ? actual.count : splitByNumber.value ? (liveCircleCounts.value[i] ?? 0) : 0,
      quota: actual ? actual.quota : (circleQuotaList.value[i] ?? null)
    });
  }
  return items;
});

// 未生成对阵时按当前已签到号码预估各圈人数(与后端按号落圈口径一致)
const liveCircleCounts = computed(() => {
  const out = Array.from({ length: Math.max(1, circleConfigCount.value) }, () => 0);
  const numbers = activeCompetitorNumbers();
  if (numbers.length === 0) {
    return out;
  }
  numbers.forEach((n) => {
    out[(n - 1) % out.length]++;
  });
  return out;
});

// 海选分圈(按号或随机)时展示顶部圈栏:
// 已生成真圈按实际展示;未生成时按配置圈数展示计划圈(随机分圈下第一位签到后按圈落位)
const showCircleBar = computed(() => stageMode.value === 'AUDITION' && circleConfigCount.value > 1);

// 按号分圈:当前选中号码对应的圈下标(抽号即落圈)
const activeCircleIndex = computed(() => {
  if (!splitByNumber.value || !selectedSlot.value) return -1;
  // 编辑模式且仍停留在本人当前号码:直接高亮其实际所在圈
  if (editing.value && selectedSlot.value.competitor && currentCircleMatchId.value != null) {
    return circleBar.value.findIndex((c) => c.matchId != null && String(c.matchId) === String(currentCircleMatchId.value));
  }
  const count = effectiveCircleCount.value;
  return count > 1 ? circleIndexOfNumber(Number(selectedSlot.value.number), count) : -1;
});

// 按号分圈:选中号码将进入的圈名
const targetCircleName = computed(() => {
  if (!splitByNumber.value) return '';
  const idx = activeCircleIndex.value;
  return idx >= 0 && idx < circleBar.value.length ? circleBar.value[idx].name : '';
});

// 已选择摘要:补充目标圈/手动圈信息
const selectedCircleLabel = computed(() => {
  if (!selectedSlot.value) return '';
  if (splitByNumber.value) {
    return targetCircleName.value;
  }
  if (circleBar.value.length === 0) {
    return '';
  }
  const selected = circleBar.value.find((c) => c.matchId != null && String(c.matchId) === String(selectedMatchId.value ?? ''));
  if (selected) {
    return selected.name;
  }
  if (selectedMatchId.value === null && selectedZoneIndex.value != null) {
    const planned = circleBar.value[selectedZoneIndex.value - 1];
    if (planned) {
      return planned.name;
    }
  }
  return selectedMatchId.value === null && selectedZoneIndex.value === null ? '自动分配' : '';
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
      // 号码/圈均未变化时不再传 matchId,避免无意义地把本人从原圈移除再挂回
      const ownNumber = competitors.value.find((c) => String(c.id) === String(currentPlayer.value?.competitorId))?.number;
      const numberChanged = ownNumber != null && String(selectedSlot.value.number) !== String(ownNumber);
      const circleUnchanged =
        !splitByNumber.value &&
        selectedMatchId.value != null &&
        currentCircleMatchId.value != null &&
        String(selectedMatchId.value) === String(currentCircleMatchId.value);
      // 按号分圈时号码决定圈位,不传 matchId 由后端按号换圈
      await editCheckIn({
        playerId: currentPlayer.value.id,
        competitorNumber: String(selectedSlot.value.number),
        matchId: splitByNumber.value ? undefined : !numberChanged && circleUnchanged ? undefined : (selectedMatchId.value ?? undefined),
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
        matchId: splitByNumber.value ? undefined : (selectedMatchId.value ?? undefined),
        // 尚未生成圈场次时,把抽号页选中的计划圈序号带给后端,由后端补建圈并落圈
        zoneIndex: !splitByNumber.value && selectedMatchId.value === null ? (selectedZoneIndex.value ?? undefined) : undefined
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
