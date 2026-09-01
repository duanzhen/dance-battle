<template>
  <div class="w-full h-full">
    <!-- 查看模式 -->
    <div v-if="mode !== 'edit'" ref="viewRef" class="w-full h-full relative overflow-hidden">
      <div v-if="loading" class="w-full h-full flex items-center justify-center text-white/50" :style="{ fontSize: sz(14) + 'px' }">加载中...</div>
      <div
        v-else-if="error"
        class="w-full h-full flex items-center justify-center text-white/40 px-4 text-center"
        :style="{ fontSize: sz(12) + 'px' }"
      >
        {{ error }}
      </div>
      <div
        v-else-if="participants.length === 0"
        class="w-full h-full flex items-center justify-center text-white/40"
        :style="{ fontSize: sz(13) + 'px' }"
      >
        当前场次暂无选手
      </div>

      <!-- 显示当前上场:顶部一排圆形头像(自动滚动居中) + 中间大圆形当前选手 -->
      <div v-else-if="showCurrent" class="w-full h-full flex flex-col">
        <div
          ref="rowRef"
          class="flex-none overflow-x-auto no-scrollbar flex items-stretch"
          :class="participants.length <= 9 ? 'justify-center' : ''"
          :style="{ gap: sz(10) + 'px', padding: sz(6) + 'px' }"
        >
          <div
            v-for="p in participants"
            :key="p.competitorId"
            :ref="setChipRef(p.competitorId)"
            class="flex-none min-w-0 flex flex-col items-center justify-center gap-1 rounded-xl border-2 px-3"
            :style="{ width: `calc((100% - ${sz(10) * 8}px) / 9)` }"
            :class="isCurrent(p.competitorId) ? 'border-amber-400/70 bg-amber-500/10' : 'border-white/10'"
          >
            <div
              class="rounded-full border-2 overflow-hidden flex items-center justify-center flex-none bg-white/5"
              :style="{ width: sz(52) + 'px', height: sz(52) + 'px' }"
              :class="isCurrent(p.competitorId) ? 'border-amber-400/80' : 'border-white/15'"
            >
              <img v-if="p.avatar" :src="p.avatar" class="w-full h-full object-cover" @error="onImgError" />
            </div>
            <span
              class="font-bold truncate max-w-full"
              :style="{ fontSize: sz(13) + 'px' }"
              :class="isCurrent(p.competitorId) ? 'text-white' : 'text-white/80'"
            >
              {{ p.name || '待定' }}
            </span>
            <span
              class="font-mono leading-none"
              :style="{ fontSize: sz(14) + 'px' }"
              :class="isCurrent(p.competitorId) ? 'text-amber-400' : 'text-white/40'"
            >
              No.{{ p.number || '-' }}
            </span>
          </div>
        </div>

        <!-- 中间大圆形当前选手 -->
        <div class="flex-1 min-h-0 flex items-center justify-center">
          <div v-if="currentPlayer" class="flex flex-col items-center gap-3">
            <div
              class="rounded-full border-4 overflow-hidden flex items-center justify-center flex-none bg-white/5"
              :style="{ width: sz(180) + 'px', height: sz(180) + 'px' }"
              :class="currentPlayer.avatar ? 'border-amber-400/80' : 'border-white/15'"
            >
              <img v-if="currentPlayer.avatar" :src="currentPlayer.avatar" class="w-full h-full object-cover" @error="onImgError" />
            </div>
            <span class="text-white font-black truncate max-w-full" :style="{ fontSize: sz(28) + 'px' }">{{ currentPlayer.name || '待定' }}</span>
            <span class="text-amber-400 font-mono" :style="{ fontSize: sz(22) + 'px' }">No.{{ currentPlayer.number || '-' }}</span>
          </div>
          <div v-else class="text-white/40" :style="{ fontSize: sz(14) + 'px' }">等待标记当前上场选手</div>
        </div>
      </div>

      <!-- 不显示当前:每行 9 个,满 9 个铺满;不足 9 个时整体居中 -->
      <div v-else class="w-full h-full flex flex-wrap items-center content-center justify-center" :style="{ gap: sz(8) + 'px' }">
        <div
          v-for="p in participants"
          :key="p.competitorId"
          class="min-w-0 flex flex-col items-center justify-center gap-1"
          :style="{ width: `calc((100% - ${sz(8) * 8}px) / 9)` }"
          :class="isCurrent(p.competitorId) ? 'rounded-xl bg-amber-500/10' : ''"
        >
          <div
            class="rounded-full border-2 overflow-hidden flex items-center justify-center flex-none bg-white/5"
            :style="{ width: sz(58) + 'px', height: sz(58) + 'px' }"
            :class="isCurrent(p.competitorId) ? 'border-amber-400/80' : 'border-white/15'"
          >
            <img v-if="p.avatar" :src="p.avatar" class="w-full h-full object-cover" @error="onImgError" />
          </div>
          <span
            class="font-bold truncate w-full text-center"
            :style="{ fontSize: sz(13) + 'px' }"
            :class="isCurrent(p.competitorId) ? 'text-white' : 'text-white/80'"
          >
            {{ p.name || '待定' }}
          </span>
          <span
            class="font-mono leading-none"
            :style="{ fontSize: sz(14) + 'px' }"
            :class="isCurrent(p.competitorId) ? 'text-amber-400' : 'text-white/40'"
          >
            No.{{ p.number || '-' }}
          </span>
        </div>
      </div>
    </div>

    <!-- 编辑模式:绑定海选赛段 + 选择展示场次 + 显示当前开关 -->
    <div v-else class="space-y-4 px-2 py-4">
      <section>
        <span class="section-title">海选名单属性</span>
        <StageSelector
          label="绑定海选赛段"
          :model-value="(stageId as any) ?? null"
          only-mode="AUDITION"
          @update:model-value="$emit('update:stageId', $event)"
        />
      </section>

      <section>
        <label class="text-xs text-neutral-500 mb-2 block">展示场次(二海场景选择加赛场)</label>
        <select
          :value="matchId ?? ''"
          class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 outline-none"
          @change="$emit('update:matchId', ($event.target as HTMLSelectElement).value || null)"
        >
          <option value="">请选择场次</option>
          <option v-for="m in matches" :key="m.id" :value="String(m.id)">{{ m.name || '场次 ' + m.id }}</option>
        </select>
      </section>

      <section>
        <label class="flex items-center justify-between cursor-pointer">
          <span class="text-xs text-neutral-500">显示当前上场选手</span>
          <input
            type="checkbox"
            :checked="!!showCurrent"
            class="accent-amber-500 w-4 h-4"
            @change="$emit('update:showCurrent', ($event.target as HTMLInputElement).checked)"
          />
        </label>
        <p class="text-[10px] text-neutral-600 mt-2 leading-relaxed">
          关闭:9 列 grid 占满整个屏幕展示选手名单;开启:只显示顶部一排,横向自动滚动,当前选手居中高亮。分数不展示。
        </p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue';
import { useRoute } from 'vue-router';
import StageSelector from '../stages/StageSelector.vue';
import { listMatch } from '@/api/game/match';
import { listMatchParticipant } from '@/api/game/matchParticipant';
import { listCompetitor } from '@/api/game/competitor';
import { listPlayer } from '@/api/game/player';
import { getMatchCurrentCompetitor } from '@/api/game/match';
import { subscribeTournamentEvents, unsubscribeTournamentEvents } from '@/utils/tournamentEventSse';

const props = defineProps<{
  stageId?: string | number | null;
  matchId?: string | number | null;
  mode?: 'view' | 'edit';
  showCurrent?: boolean;
  tournamentId?: string | number | null;
}>();
const emit = defineEmits<{
  'update:stageId': [v: string | number | null];
  'update:matchId': [v: string | number | null];
  'update:showCurrent': [v: boolean];
}>();

const route = useRoute();
const qid = (v: unknown) => (typeof v === 'string' || typeof v === 'number' ? v : null);
const tournamentId = computed(() => props.tournamentId ?? qid(route.query.id) ?? qid(route.query.tournamentId) ?? null);

const loading = ref(false);
const loadedOnce = ref(false);
const error = ref('');
const matches = ref<any[]>([]);
const participants = ref<{ competitorId: string | number; number?: string; name?: string; avatar?: string; slot: number }[]>([]);
const currentCompetitorId = ref<string | number | null>(null);

// 查看模式容器 + 尺寸缩放(与擂台 widget 一致)
const viewRef = ref<HTMLElement | null>(null);
const rowRef = ref<HTMLElement | null>(null);
const chipEls = new Map<string, HTMLElement>();
let resizeObserver: ResizeObserver | null = null;
const scale = ref(1);
const sz = (base: number) => Math.round(base * scale.value);

const setChipRef = (cid: string | number) => (el: unknown) => {
  const key = String(cid);
  if (el) {
    chipEls.set(key, el as HTMLElement);
  } else {
    chipEls.delete(key);
  }
};

const isCurrent = (cid: unknown) => cid != null && String(cid) === String(currentCompetitorId.value);
/** 当前选手:优先标记的选手,未标记时取第一位 */
const currentPlayer = computed(() => {
  if (currentCompetitorId.value != null) {
    const hit = participants.value.find((p) => String(p.competitorId) === String(currentCompetitorId.value));
    if (hit) return hit;
  }
  return participants.value[0] || null;
});

const onImgError = (e: Event) => {
  (e.target as HTMLImageElement).style.visibility = 'hidden';
};

const startObserve = () => {
  resizeObserver?.disconnect();
  if (!viewRef.value) return;
  resizeObserver = new ResizeObserver((entries) => {
    const r = entries[0]?.contentRect;
    if (!r) return;
    scale.value = Math.max(0.5, Math.min(2.5, Math.min(r.width / 800, r.height / 600)));
  });
  resizeObserver.observe(viewRef.value);
};

/** 显示当前模式:把当前选手滚动到横向中间 */
const centerCurrent = async () => {
  if (!props.showCurrent || currentCompetitorId.value == null) return;
  await nextTick();
  const el = chipEls.get(String(currentCompetitorId.value));
  el?.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' });
};

const loadData = async () => {
  if (!props.stageId) {
    matches.value = [];
    participants.value = [];
    currentCompetitorId.value = null;
    error.value = '';
    return;
  }
  if (!loadedOnce.value) {
    loading.value = true;
  }
  error.value = '';
  try {
    // 场次列表(二海场景可选择加赛场)
    const mr: any = await listMatch({ stageId: props.stageId, pageNum: 1, pageSize: 100 } as any);
    matches.value = mr?.data?.data || mr?.data || [];
    const mid = props.matchId != null ? String(props.matchId) : String(matches.value[0]?.id ?? '');
    if (!mid) {
      participants.value = [];
      currentCompetitorId.value = null;
      loadedOnce.value = true;
      return;
    }
    // 参赛方 + 选手档案(名称/号码/头像)
    const [pr, cr, plr]: any[] = await Promise.all([
      listMatchParticipant({ matchId: mid, pageNum: 1, pageSize: 1000 } as any),
      listCompetitor({ stageId: props.stageId, pageNum: 1, pageSize: 1000 } as any),
      tournamentId.value != null ? listPlayer({ tournamentId: tournamentId.value, pageNum: 1, pageSize: 1000 } as any) : Promise.resolve(null)
    ]);
    const parts = pr?.data?.data || pr?.data || [];
    const comps = cr?.data?.data || cr?.data || [];
    const players = plr?.data?.data || plr?.data || [];
    const compMap: Record<string, any> = {};
    comps.forEach((c: any) => {
      compMap[String(c.id)] = c;
    });
    const avatarByCompetitor: Record<string, string | undefined> = {};
    players.forEach((p: any) => {
      if (p.competitorId != null && p.avatar) {
        avatarByCompetitor[String(p.competitorId)] = p.avatar;
      }
    });
    participants.value = parts
      .filter((p: any) => p.competitorId != null)
      .map((p: any) => {
        const c = compMap[String(p.competitorId)] || {};
        return {
          competitorId: p.competitorId,
          number: c.number != null ? String(c.number) : undefined,
          name: c.name || p.competitorName,
          avatar: avatarByCompetitor[String(p.competitorId)],
          slot: Number(p.displaySlotIndex) || 0
        };
      })
      .sort((a: any, b: any) => a.slot - b.slot);
    // 当前标记
    try {
      const ccr: any = await getMatchCurrentCompetitor(mid);
      currentCompetitorId.value = ccr?.data ?? null;
    } catch {
      currentCompetitorId.value = null;
    }
    loadedOnce.value = true;
    centerCurrent();
  } catch (e: any) {
    console.error('AuditionWidget 加载失败', e);
    error.value = e?.response?.data?.msg || '加载失败';
  } finally {
    loading.value = false;
  }
};

/** 事件回调:重连补偿(null)或事件涉及本赛段时刷新 */
const handleTournamentEvent = (data: any) => {
  if (!data) {
    loadData();
    return;
  }
  if (data.stageId != null && String(data.stageId) === String(props.stageId)) {
    loadData();
  }
};

onMounted(() => {
  loadData();
  subscribeTournamentEvents(tournamentId.value, handleTournamentEvent);
  nextTick(startObserve);
});
onUnmounted(() => {
  unsubscribeTournamentEvents(tournamentId.value, handleTournamentEvent);
  resizeObserver?.disconnect();
  resizeObserver = null;
});

watch(tournamentId, (newTid, oldTid) => {
  if (oldTid !== newTid) {
    unsubscribeTournamentEvents(oldTid, handleTournamentEvent);
    subscribeTournamentEvents(newTid, handleTournamentEvent);
  }
  loadData();
});
watch(
  () => [props.stageId, props.matchId] as const,
  () => loadData()
);
watch(
  () => props.showCurrent,
  () => centerCurrent()
);
</script>

<style scoped>
.no-scrollbar {
  -ms-overflow-style: none;
  scrollbar-width: none;
}
.no-scrollbar::-webkit-scrollbar {
  display: none;
}
</style>
