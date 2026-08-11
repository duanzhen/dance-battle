<template>
  <div class="w-full h-full">
    <!-- 查看模式:自动匹配当前进行中(GAMING)场次,否则透明 -->
    <div v-if="mode !== 'edit'" class="w-full h-full">
      <div
        v-if="match"
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
        <div class="flex flex-col items-center gap-2 flex-1 min-w-0 h-full justify-center">
          <img
            v-if="leftAvatar"
            :src="leftAvatar"
            class="h-[46%] max-w-full w-auto object-contain"
            @error="onImgError"
          />
          <div v-else class="h-[46%] w-full"></div>
          <span
            class="font-bold text-[clamp(12px,2vw,30px)] drop-shadow text-center truncate w-full"
            :class="leftLead ? 'text-amber-400' : 'text-white'"
            :title="leftName"
          >{{ leftName }}</span>
          <span v-if="leftOutcome" class="text-[clamp(8px,1vw,14px)] font-bold" :class="leftLead ? 'text-green-400' : 'text-white/60'">
            {{ outcomeText(leftOutcome) }}
          </span>
        </div>

        <!-- 中间:比分 -->
        <div class="flex items-center gap-4 text-white font-mono font-black drop-shadow px-4">
          <span class="text-[clamp(20px,3.5vw,56px)]" :class="leftLead ? 'text-amber-400' : ''">{{ leftScore }}</span>
          <span class="opacity-50 text-[clamp(16px,2.5vw,40px)]">:</span>
          <span class="text-[clamp(20px,3.5vw,56px)]" :class="rightLead ? 'text-amber-400' : ''">{{ rightScore }}</span>
        </div>

        <!-- 右方 -->
        <div class="flex flex-col items-center gap-2 flex-1 min-w-0 h-full justify-center">
          <img
            v-if="rightAvatar"
            :src="rightAvatar"
            class="h-[46%] max-w-full w-auto object-contain"
            @error="onImgError"
          />
          <div v-else class="h-[46%] w-full"></div>
          <span
            class="font-bold text-[clamp(12px,2vw,30px)] drop-shadow text-center truncate w-full"
            :class="rightLead ? 'text-amber-400' : 'text-white'"
            :title="rightName"
          >{{ rightName }}</span>
          <span v-if="rightOutcome" class="text-[clamp(8px,1vw,14px)] font-bold" :class="rightLead ? 'text-green-400' : 'text-white/60'">
            {{ outcomeText(rightOutcome) }}
          </span>
        </div>
      </div>
    </div>

    <!-- 编辑模式:仅配置背景图 -->
    <div v-else class="space-y-4 px-2 py-4">
      <section>
        <span class="section-title">当前场次属性</span>
        <div class="p-3 rounded-lg bg-amber-500/10 border border-amber-500/20 mb-3">
          <p class="text-[10px] text-amber-400">仅淘汰赛赛段显示，自动关联当前进行(GAMING)的场次，无需手动绑定；SSE 实时刷新。</p>
        </div>
        <div>
          <AssetUpload
            label="背景图片"
            :model-value="bgImage ?? ''"
            :is-image="true"
            accept="image/*"
            :show-url-input="true"
            @update:model-value="$emit('update:bgImage', ($event as string))"
          />
        </div>
        <p class="text-[10px] text-neutral-600 mt-2">
          显示当前淘汰赛赛段的进行中场次：赛段名、场次、轮次、双方姓名/头像/实时比分，领先方高亮。非淘汰赛赛段或暂无进行中场次时控件保持透明。
        </p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, computed } from 'vue';
import { useRoute } from 'vue-router';
import AssetUpload from './common/AssetUpload.vue';
import { getStageFlow } from '@/api/game/stage';
import { listCompetitor } from '@/api/game/competitor';
import { subscribeTournamentEvents, unsubscribeTournamentEvents } from '@/utils/tournamentEventSse';
import { StageMode } from '../stages/types';

const route = useRoute();

const props = defineProps<{ bgImage?: string; mode?: 'view' | 'edit'; tournamentId?: string | number | null }>();
defineEmits<{
  'update:bgImage': [v: string];
}>();

const match = ref<any | null>(null);
const stageName = ref('');
const roundSeq = ref<number | null>(null);
const participants = ref<any[]>([]);
const compMap = ref<Record<string, any>>({});
let lastStageId: string | null = null;
let lastMatchId: string | null = null;

const qid = (v: unknown) => (typeof v === 'string' || typeof v === 'number' ? v : null);
const tournamentId = computed(() => props.tournamentId ?? qid(route.query.id) ?? qid(route.query.tournamentId) ?? null);

const loadData = async () => {
  if (!tournamentId.value) {
    match.value = null;
    return;
  }
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
      participants.value = [];
      lastMatchId = null;
      return;
    }
    const current = data?.currentMatch;
    if (!current) {
      match.value = null;
      participants.value = [];
      lastMatchId = null;
      return;
    }
    match.value = current;
    lastMatchId = current?.id != null ? String(current.id) : null;
    roundSeq.value = 1;
    participants.value = ((data?.currentMatchParticipants || []) as any[])
      .slice()
      .sort((a: any, b: any) => (a.displaySlotIndex ?? 0) - (b.displaySlotIndex ?? 0));

    // 头像:参赛方关联选手的 avatar
    try {
      const cr: any = await listCompetitor({ tournamentId: tournamentId.value, pageNum: 1, pageSize: 999 } as any);
      ((cr?.data?.data || cr?.data) || []).forEach((c: any) => {
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
const avatarOf = (p: any) =>
  p?.avatar ||
  compMap.value[p?.competitorId]?.playerList?.[0]?.avatar ||
  '';
const onImgError = (e: Event) => {
  (e.target as HTMLImageElement).style.display = 'none';
};
const leftAvatar = computed(() => avatarOf(leftP.value));
const rightAvatar = computed(() => avatarOf(rightP.value));
const leftScore = computed(() => leftP.value?.scoreValue == null ? '–' : String(leftP.value.scoreValue));
const rightScore = computed(() => rightP.value?.scoreValue == null ? '–' : String(rightP.value.scoreValue));
const leftLead = computed(() => leftP.value?.rankInMatch === 1 && leftP.value?.scoreValue != null);
const rightLead = computed(() => rightP.value?.rankInMatch === 1 && rightP.value?.scoreValue != null);
const leftOutcome = computed(() => leftP.value?.outcomeStatus || '');
const rightOutcome = computed(() => rightP.value?.outcomeStatus || '');

const outcomeText = (o: string) => ({
  WIN: '胜',
  LOSS: '负',
  DRAW: '平',
  ADVANCE: '晋级',
  ELIMINATED: '淘汰'
}[o] || '');
</script>
