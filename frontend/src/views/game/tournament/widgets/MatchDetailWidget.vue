<template>
  <div class="w-full h-full">
    <!-- 查看模式:自动匹配当前进行中(GAMING)场次,否则透明 -->
    <div v-if="mode !== 'edit'" class="w-full h-full">
      <Transition name="fade" @after-leave="onCelebrationFadeDone">
        <div
          v-if="match"
          :key="match?.id"
          class="w-full h-full relative bg-cover bg-center flex items-center justify-between px-[5%]"
          :style="bgImage ? { backgroundImage: `url(${bgImage})` } : { backgroundColor: '#0a0a0a' }"
        >
          <!-- 顶部信息:赛段 · 场次 · 轮次 -->
          <div class="absolute top-[4%] left-0 right-0 flex items-center justify-center gap-2 text-white/90">
            <span class="text-[clamp(9px,1.2vw,18px)] font-bold tracking-wider text-amber-400/90">{{ stageName || '当前场次' }}</span>
            <span class="opacity-50">·</span>
            <span class="text-[clamp(9px,1.2vw,18px)] font-bold">{{ match.name }}</span>
            <span v-if="roundSeq" class="text-[clamp(8px,1vw,14px)] text-white/60 font-mono">R{{ roundSeq }}</span>
          </div>

          <!-- 左方 -->
          <div
            class="player-col flex flex-col items-center gap-2 flex-1 min-w-0 h-full justify-center"
            :class="{ celebrating: celebration.active }"
            :style="playerClass('LEFT')"
          >
            <img v-if="leftAvatar" :src="leftAvatar" class="h-[46%] max-w-full w-auto object-contain" @error="onImgError" />
            <div v-else class="h-[46%] w-full"></div>
            <span
              class="font-bold text-[clamp(12px,2vw,30px)] drop-shadow text-center truncate w-full"
              :class="leftLead ? 'text-amber-400' : 'text-white'"
              :title="leftName"
              >{{ leftName }}</span
            >
            <span v-if="leftOutcome" class="text-[clamp(8px,1vw,14px)] font-bold" :class="leftLead ? 'text-green-400' : 'text-white/60'">
              {{ outcomeText(leftOutcome) }}
            </span>
          </div>

          <!-- 中间:比分 -->
          <div class="flex flex-col items-center gap-2 px-4">
            <div class="flex items-center gap-4 text-white font-mono font-black drop-shadow">
              <span class="text-[clamp(20px,3.5vw,56px)]" :class="leftLead ? 'text-amber-400' : ''">{{ leftScore }}</span>
              <span class="opacity-50 text-[clamp(16px,2.5vw,40px)]">:</span>
              <span class="text-[clamp(20px,3.5vw,56px)]" :class="rightLead ? 'text-amber-400' : ''">{{ rightScore }}</span>
            </div>
            <!-- 最终结果 -->
            <div v-if="finalVerdict" class="px-4 py-1 rounded-full border font-bold text-[clamp(10px,1.3vw,18px)]" :class="finalVerdictClass">
              {{ finalVerdict }}
            </div>
          </div>

          <!-- 右方 -->
          <div
            class="player-col flex flex-col items-center gap-2 flex-1 min-w-0 h-full justify-center"
            :class="{ celebrating: celebration.active }"
            :style="playerClass('RIGHT')"
          >
            <img v-if="rightAvatar" :src="rightAvatar" class="h-[46%] max-w-full w-auto object-contain" @error="onImgError" />
            <div v-else class="h-[46%] w-full"></div>
            <span
              class="font-bold text-[clamp(12px,2vw,30px)] drop-shadow text-center truncate w-full"
              :class="rightLead ? 'text-amber-400' : 'text-white'"
              :title="rightName"
              >{{ rightName }}</span
            >
            <span v-if="rightOutcome" class="text-[clamp(8px,1vw,14px)] font-bold" :class="rightLead ? 'text-green-400' : 'text-white/60'">
              {{ outcomeText(rightOutcome) }}
            </span>
          </div>

          <!-- 底部:裁判判罚(每个裁判红/蓝/平/未判)+ 各轮判罚明细 -->
          <div
            v-if="showVotePanel"
            class="absolute bottom-[2.5%] left-1/2 -translate-x-1/2 w-[94%] max-w-[1700px] flex flex-col items-center gap-1.5"
          >
            <!-- 胜场汇总:谁赢的轮次多谁获胜(平局轮不计) -->
            <div
              v-if="roundWins.left + roundWins.right > 0"
              class="px-4 py-1 rounded-full bg-black/60 border border-white/10 backdrop-blur text-[clamp(8px,0.9vw,13px)] font-bold"
            >
              <span :class="sideColorClass('LEFT')">{{ sideLabel('LEFT') }} {{ roundWins.left }} 胜</span>
              <span class="text-white/40 mx-2">·</span>
              <span :class="sideColorClass('RIGHT')">{{ sideLabel('RIGHT') }} {{ roundWins.right }} 胜</span>
              <template v-if="roundWins.draw > 0">
                <span class="text-white/40 mx-2">·</span>
                <span class="text-neutral-400">平 {{ roundWins.draw }} 轮</span>
              </template>
            </div>
            <div
              v-for="(r, ri) in voteRounds"
              :key="r.roundId ?? ri"
              class="w-full rounded-xl bg-black/60 border border-white/10 backdrop-blur px-4 py-1.5"
            >
              <div class="flex items-center justify-center gap-1.5 flex-wrap">
                <span class="text-[clamp(8px,0.9vw,13px)] font-bold flex-none" :class="r.status === 'SETTLED' ? 'text-white/60' : 'text-amber-400'">
                  R{{ r.roundSequence }} · {{ roundStatusText(r) }}
                </span>
                <span v-if="r.winnerSide" class="text-[clamp(8px,0.9vw,13px)] font-bold flex-none" :class="roundWinnerClass(r.winnerSide)">
                  {{ roundWinnerText(r.winnerSide) }}
                </span>
                <span class="w-px h-3 bg-white/15 flex-none"></span>
                <span class="text-[clamp(8px,0.9vw,13px)] text-white/70 font-semibold flex-none">{{ r.leftName }}</span>
                <span
                  v-for="ref in r.refereeVotes || []"
                  :key="ref.refereeId"
                  class="flex items-center gap-1 px-2 py-0.5 rounded-md text-[clamp(8px,0.9vw,13px)] font-bold border"
                  :class="voteChipClass(ref.vote)"
                >
                  <span class="max-w-[7vw] truncate">{{ ref.refereeName }}</span>
                  <span>{{ voteText(ref.vote) }}</span>
                </span>
                <span class="text-[clamp(8px,0.9vw,13px)] text-white/70 font-semibold flex-none">{{ r.rightName }}</span>
                <span v-if="!(r.refereeVotes || []).length" class="text-[clamp(8px,0.9vw,13px)] text-white/40">无裁判分配</span>
              </div>
              <!-- 本轮票数与判定 -->
              <div v-if="roundVoteCounts(r).total > 0" class="flex items-center justify-center gap-3 mt-0.5 text-[clamp(8px,0.9vw,13px)] font-mono">
                <span :class="sideColorClass('LEFT')">{{ sideLabel('LEFT') }} {{ roundVoteCounts(r).left }}</span>
                <span :class="sideColorClass('RIGHT')">{{ sideLabel('RIGHT') }} {{ roundVoteCounts(r).right }}</span>
                <span class="text-neutral-400">平 {{ roundVoteCounts(r).draw }}</span>
                <span class="text-white/50">已判 {{ roundVoteCounts(r).voted }}/{{ roundVoteCounts(r).total }}</span>
              </div>
            </div>
          </div>
        </div>
      </Transition>
    </div>

    <!-- 编辑模式:仅配置背景图 -->
    <div v-else class="space-y-4 px-2 py-4">
      <section>
        <span class="section-title">当前场次属性</span>
        <!-- <div class="p-3 rounded-lg bg-amber-500/10 border border-amber-500/20 mb-3">
          <p class="text-[10px] text-amber-400">仅淘汰赛赛段显示，自动关联当前进行(GAMING)的场次，无需手动绑定；SSE 实时刷新。</p>
        </div> -->
        <div>
          <AssetUpload
            label="背景图片"
            :model-value="bgImage ?? ''"
            :is-image="true"
            accept="image/*"
            :show-url-input="true"
            @update:model-value="$emit('update:bgImage', $event as string)"
          />
        </div>
        <p class="text-[10px] text-neutral-600 mt-2">
          <!-- 显示当前淘汰赛赛段的进行中场次：赛段名、场次、轮次、双方姓名/头像/实时比分，领先方高亮。非淘汰赛赛段或暂无进行中场次时控件保持透明。 -->
        </p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, watch, onMounted, onUnmounted, computed } from 'vue';
import { useRoute } from 'vue-router';
import AssetUpload from './common/AssetUpload.vue';
import { getStageFlow } from '@/api/game/stage';
import { getMatch } from '@/api/game/match';
import { listCompetitor } from '@/api/game/competitor';
import { getTournament } from '@/api/game/tournament';
import { parseTournamentColorConfig, DEFAULT_TOURNAMENT_COLOR_CONFIG, TournamentColorConfig } from '@/utils/tournamentColorConfig';
import { subscribeTournamentEvents, unsubscribeTournamentEvents } from '@/utils/tournamentEventSse';
import { StageMode } from '../stages/types';

const route = useRoute();

const props = defineProps<{ bgImage?: string; mode?: 'view' | 'edit'; tournamentId?: string | number | null }>();
defineEmits<{
  'update:bgImage': [v: string];
}>();

const match = ref<any | null>(null);
const matchDetail = ref<any | null>(null);
const stageName = ref('');
const roundSeq = ref<number | null>(null);
const participants = ref<any[]>([]);
const compMap = ref<Record<string, any>>({});
let lastStageId: string | null = null;
let lastMatchId: string | null = null;

/** 获胜动画状态:结算后保留场次播 2 秒动画,停 1 秒后淡出(由 Transition 完成)再切换 */
const celebration = reactive({ active: false, winner: null as 'LEFT' | 'RIGHT' | null });
let celebrationTimer: ReturnType<typeof setTimeout> | null = null;
/** 动画+停顿结束后允许切换场次(淡出期间保持获胜姿态,淡出完成后再复位) */
let celebrationSwitchReady = false;
/** 已播过获胜动画的场次ID:防止动画结束后重新检测到同一场次导致死循环 */
let celebratedMatchId: string | null = null;

/** 获胜动画:胜者放大并向中间靠,败者缩小向边缘退 */
const playerClass = (side: 'LEFT' | 'RIGHT') => {
  if (!celebration.active || !celebration.winner) return {};
  if (celebration.winner === side) {
    const dir = side === 'LEFT' ? 1 : -1;
    return { transform: `translateX(${dir * 16}vw) scale(1.18)`, zIndex: 2 };
  }
  const dir = side === 'LEFT' ? -1 : 1;
  return { transform: `translateX(${dir * 12}vw) scale(0.75)`, opacity: 0.65 };
};

/** 播放获胜动画:2 秒动画 → 停 1 秒 → 淡出(1 秒)→ 切换到新场次 */
const startCelebration = (winner: 'LEFT' | 'RIGHT') => {
  celebration.active = true;
  celebration.winner = winner;
  celebrationSwitchReady = false;
  celebratedMatchId = match.value?.id != null ? String(match.value.id) : null;
  if (celebrationTimer) clearTimeout(celebrationTimer);
  // 2 秒动画 → 停 1 秒 → 允许切场,由 Transition 淡出(约 1 秒)
  celebrationTimer = setTimeout(() => {
    celebrationSwitchReady = true;
    celebrationTimer = null;
    loadData();
    // 兜底:无论 Transition 是否触发,1.5 秒后强制复位动画状态
    celebrationTimer = setTimeout(() => {
      celebration.active = false;
      celebration.winner = null;
      celebrationSwitchReady = false;
      celebrationTimer = null;
    }, 1500);
  }, 3000);
};

/** 淡出动画结束后复位获胜姿态(此时元素已不可见,不会产生回弹) */
const onCelebrationFadeDone = () => {
  if (!celebration.active) return;
  if (celebrationTimer) clearTimeout(celebrationTimer);
  celebrationTimer = null;
  celebration.active = false;
  celebration.winner = null;
  celebrationSwitchReady = false;
};

const qid = (v: unknown) => (typeof v === 'string' || typeof v === 'number' ? v : null);
const tournamentId = computed(() => props.tournamentId ?? qid(route.query.id) ?? qid(route.query.tournamentId) ?? null);

// ---- 赛事级红蓝配色(当前场次/裁判列表 左红右蓝 或 右红左蓝)----
const colorConfig = ref<TournamentColorConfig>({ ...DEFAULT_TOURNAMENT_COLOR_CONFIG });
let colorLoaded = false;
const loadColorConfig = async () => {
  if (colorLoaded || !tournamentId.value) return;
  colorLoaded = true;
  try {
    const tr: any = await getTournament(tournamentId.value);
    colorConfig.value = parseTournamentColorConfig(tr?.data?.themeConfig);
  } catch (e) {
    colorConfig.value = { ...DEFAULT_TOURNAMENT_COLOR_CONFIG };
  }
};
const isLeftRed = computed(() => colorConfig.value.matchColorOrder !== 'BLUE_LEFT');
/** LEFT/RIGHT 对应的红蓝文字色 */
const sideColorClass = (side: 'LEFT' | 'RIGHT') => {
  const red = side === 'LEFT' ? isLeftRed.value : !isLeftRed.value;
  return red ? 'text-red-400' : 'text-blue-400';
};
/** LEFT/RIGHT 对应的红蓝文案 */
const sideLabel = (side: 'LEFT' | 'RIGHT') => {
  const red = side === 'LEFT' ? isLeftRed.value : !isLeftRed.value;
  return red ? '红' : '蓝';
};

const loadData = async () => {
  if (celebration.active && !celebrationSwitchReady) return;
  if (!tournamentId.value) {
    match.value = null;
    matchDetail.value = null;
    return;
  }
  await loadColorConfig();
  try {
    // 用赛程流转接口定位当前进行中赛段及其场次(大屏投射窗口没有路由 id,依赖组件注入)
    const flow: any = await getStageFlow(tournamentId.value);
    const data = flow.data;
    const currentStage = (data?.stages || []).find((s: any) => s.id === data?.currentStageId);
    lastStageId = currentStage?.id != null ? String(currentStage.id) : null;
    stageName.value = currentStage?.name || '';
    // 场次控件仅服务淘汰赛:非淘汰赛赛段(小组/海选/擂台等)保持透明
    if (currentStage?.stageMode !== StageMode.KNOCKOUT) {
      match.value = null;
      matchDetail.value = null;
      participants.value = [];
      lastMatchId = null;
      return;
    }
    const current = data?.currentMatch;
    // 获胜动画:之前展示的场次已不在进行中(被结算/切走),且结算明细带胜者时,
    // 先保留该场数据播放动画,动画结束后再切换,避免结果一出就消失
    if (current?.id !== match.value?.id && match.value && celebratedMatchId !== String(match.value.id)) {
      try {
        const md: any = await getMatch(match.value.id);
        const detail = md.data || null;
        const winnerSide = detail?.leftWin === true ? 'LEFT' : detail?.rightWin === true ? 'RIGHT' : null;
        if (winnerSide) {
          matchDetail.value = detail;
          startCelebration(winnerSide);
          return;
        }
      } catch (e) {
        // 结算明细拉取失败时走正常切换,不阻塞刷新
      }
    }
    if (!current) {
      match.value = null;
      matchDetail.value = null;
      participants.value = [];
      lastMatchId = null;
      return;
    }
    match.value = current;
    lastMatchId = current?.id != null ? String(current.id) : null;
    participants.value = ((data?.currentMatchParticipants || []) as any[])
      .slice()
      .sort((a: any, b: any) => (a.displaySlotIndex ?? 0) - (b.displaySlotIndex ?? 0));

    // 场次详情:裁判判罚(refereeVotes)与各轮判罚明细(roundVotes)
    try {
      const md: any = await getMatch(current.id);
      matchDetail.value = md.data || null;
      const rounds: any[] = matchDetail.value?.roundVotes || [];
      roundSeq.value = rounds.length > 0 ? rounds[rounds.length - 1].roundSequence : 1;
    } catch (e) {
      console.warn('MatchDetailWidget 加载场次详情失败', e);
      matchDetail.value = null;
      roundSeq.value = 1;
    }

    // 头像:参赛方关联选手的 avatar
    try {
      const cr: any = await listCompetitor({ tournamentId: tournamentId.value, pageNum: 1, pageSize: 999 } as any);
      (cr?.data?.data || cr?.data || []).forEach((c: any) => {
        compMap.value[c.id] = c;
      });
    } catch (e) {
      console.warn('加载参赛方信息失败', e);
    }
  } catch (e) {
    console.error('MatchDetailWidget 加载失败', e);
  }
};

onMounted(() => {
  loadData();
  // 赛事事件 SSE 实时推送为主,30 秒心跳兜底
  subscribeTournamentEvents(tournamentId.value, handleTournamentEvent);
});
onUnmounted(() => {
  if (celebrationTimer) clearTimeout(celebrationTimer);
  celebrationTimer = null;
  unsubscribeTournamentEvents(tournamentId.value, handleTournamentEvent);
});

watch(tournamentId, (newTid, oldTid) => {
  if (oldTid !== newTid) {
    unsubscribeTournamentEvents(oldTid, handleTournamentEvent);
    subscribeTournamentEvents(newTid, handleTournamentEvent);
  }
  loadData();
});

/** 事件回调:重连补偿(null)或事件涉及当前进行中的赛段/场次时才刷新 */
const handleTournamentEvent = (data: any) => {
  if (!data) {
    loadData();
    return;
  }
  const sid = data.stageId != null ? String(data.stageId) : null;
  const mid = data.matchId != null ? String(data.matchId) : null;
  // 尚未定位当前赛段/场次(如首屏加载时比赛未开始):任何赛事事件都尝试刷新,避免首屏未就绪后永远不显示
  if (!lastStageId && !lastMatchId) {
    loadData();
    return;
  }
  // 赛段级事件且赛段已切换(如从海选进入淘汰赛):刷新以切换显示/隐藏
  if (!mid && sid && sid !== lastStageId) {
    loadData();
    return;
  }
  if ((sid && sid === lastStageId) || (mid && mid === lastMatchId)) {
    loadData();
  }
};

const leftP = computed(() => participants.value[0]);
const rightP = computed(() => participants.value[1]);

const leftName = computed(() => leftP.value?.competitorName || 'TBD');
const rightName = computed(() => rightP.value?.competitorName || 'TBD');
const avatarOf = (p: any) => p?.avatar || compMap.value[p?.competitorId]?.playerList?.[0]?.avatar || '';
const onImgError = (e: Event) => {
  (e.target as HTMLImageElement).style.display = 'none';
};
const leftAvatar = computed(() => avatarOf(leftP.value));
const rightAvatar = computed(() => avatarOf(rightP.value));
const leftScore = computed(() => (leftP.value?.scoreValue == null ? '–' : String(leftP.value.scoreValue)));
const rightScore = computed(() => (rightP.value?.scoreValue == null ? '–' : String(rightP.value.scoreValue)));
const leftLead = computed(() => {
  if (matchDetail.value?.leftWin === true) return true;
  if (matchDetail.value?.rightWin === true) return false;
  return leftP.value?.rankInMatch === 1 && leftP.value?.scoreValue != null;
});
const rightLead = computed(() => {
  if (matchDetail.value?.rightWin === true) return true;
  if (matchDetail.value?.leftWin === true) return false;
  return rightP.value?.rankInMatch === 1 && rightP.value?.scoreValue != null;
});
const leftOutcome = computed(() => leftP.value?.outcomeStatus || '');
const rightOutcome = computed(() => rightP.value?.outcomeStatus || '');

// ---- 裁判判罚展示 ----
const voteRounds = computed<any[]>(() => (matchDetail.value?.roundVotes || []).slice());
/** 有裁判分配(任一 轮次含判罚明细)时才展示判罚面板 */
const showVotePanel = computed(() => {
  const d = matchDetail.value;
  if (!d) return false;
  return (d.roundVotes || []).some((r: any) => (r.refereeVotes || []).length > 0) || (d.refereeVotes || []).length > 0;
});

const voteText = (v: string | null | undefined) =>
  v === 'LEFT' ? sideLabel('LEFT') : v === 'RIGHT' ? sideLabel('RIGHT') : v === 'DRAW' ? '平' : '未判';
const voteChipClass = (v: string | null | undefined) => {
  if (v === 'LEFT' || v === 'RIGHT') {
    const red = v === 'LEFT' ? isLeftRed.value : !isLeftRed.value;
    return red ? 'bg-red-500/15 text-red-400 border-red-500/40' : 'bg-blue-500/15 text-blue-400 border-blue-500/40';
  }
  if (v === 'DRAW') return 'bg-neutral-500/15 text-neutral-300 border-neutral-500/40';
  return 'bg-neutral-800/60 text-neutral-500 border-neutral-800';
};

const roundStatusText = (r: any) => {
  if (r.outcome === 'DRAW') return '平局加赛';
  return r.status === 'SETTLED' ? '已结算' : '判罚中';
};

/** 单轮各裁判票数统计 */
const roundVoteCounts = (r: any) => {
  const votes: any[] = r.refereeVotes || [];
  let left = 0;
  let right = 0;
  let draw = 0;
  let voted = 0;
  for (const v of votes) {
    if (v.vote === 'LEFT') {
      left++;
      voted++;
    } else if (v.vote === 'RIGHT') {
      right++;
      voted++;
    } else if (v.vote === 'DRAW') {
      draw++;
      voted++;
    }
  }
  return { left, right, draw, voted, total: votes.length };
};

/** 本轮胜方文案与样式(后端 winnerSide:LEFT/RIGHT/DRAW) */
const roundWinnerText = (s: string) => (s === 'LEFT' ? sideLabel('LEFT') + '胜' : s === 'RIGHT' ? sideLabel('RIGHT') + '胜' : '平局');
const roundWinnerClass = (s: string) => (s === 'LEFT' ? sideColorClass('LEFT') : s === 'RIGHT' ? sideColorClass('RIGHT') : 'text-neutral-400');

/** 胜场汇总:统计各轮胜方(平局轮不计入任何一方) */
const roundWins = computed(() => {
  let left = 0;
  let right = 0;
  let draw = 0;
  for (const r of voteRounds.value) {
    if (r.winnerSide === 'LEFT') left++;
    else if (r.winnerSide === 'RIGHT') right++;
    else if (r.winnerSide === 'DRAW') draw++;
  }
  return { left, right, draw };
});

/** 最终结果(已结算/已判定):显示胜者 */
const finalVerdict = computed(() => {
  const d = matchDetail.value;
  if (!d) return '';
  if (d.winnerName) return `${d.winnerName} 胜`;
  if (d.leftWin === true && leftName.value) return `${leftName.value} 胜`;
  if (d.rightWin === true && rightName.value) return `${rightName.value} 胜`;
  return '';
});
const finalVerdictClass = computed(() =>
  matchDetail.value?.status === 'SETTLED'
    ? 'bg-green-500/15 text-green-400 border-green-500/40'
    : 'bg-amber-500/15 text-amber-400 border-amber-500/40'
);

const outcomeText = (o: string) =>
  ({
    WIN: '胜',
    LOSS: '负',
    DRAW: '平',
    ADVANCE: '晋级',
    ELIMINATED: '淘汰'
  })[o] || '';
</script>

<style scoped>
/* 场次出现/消失:淡入淡出,避免透明度瞬间跳变 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 1s ease-in-out;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* 获胜动画:胜者/败者位移与缩放过渡(2 秒),缓启缓停(ease-in-out) */
.player-col.celebrating {
  transition:
    transform 2s cubic-bezier(0.45, 0, 0.55, 1),
    opacity 1.5s ease-in-out;
  will-change: transform, opacity;
}
</style>
