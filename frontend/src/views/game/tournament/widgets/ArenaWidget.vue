<template>
  <div class="w-full h-full">
    <!-- 查看模式:顶部顺序列表 + 中间当前对决 -->
    <div v-if="mode !== 'edit'" class="w-full h-full relative">
      <div v-if="loading" class="w-full h-full flex items-center justify-center text-white/50 text-sm">加载中...</div>
      <div v-else-if="error" class="w-full h-full flex items-center justify-center text-white/40 text-xs px-4 text-center">{{ error }}</div>
      <div v-else-if="!stageId" class="w-full h-full flex items-center justify-center text-white/40 text-xs">未绑定擂台赛段</div>
      <div v-else class="w-full h-full flex flex-col">
        <!-- 顶部顺序列表:按队列顺序一行展示 头像/名字/积分,队首为擂主 -->
        <div class="flex-none px-3 pt-3 overflow-x-auto scrollbar-hide">
          <div class="flex gap-2.5 w-max mx-auto">
            <div
              v-for="c in queue"
              :key="c.competitorId"
              class="relative flex-none w-[104px] rounded-lg border p-2 pt-3 flex flex-col items-center gap-1"
              :class="
                c.queueIndex === 1
                  ? 'border-amber-400/70 bg-amber-500/10'
                  : isInBattle(c.competitorId)
                    ? 'border-green-500/40'
                    : 'border-white/10'
              "
            >
              <div class="relative">
                <img
                  v-if="c.avatar"
                  :src="c.avatar"
                  class="w-[52px] h-[52px] rounded-full object-cover border-2"
                  :class="c.queueIndex === 1 ? 'border-amber-400/80' : 'border-white/15'"
                  @error="onImgError"
                />
                <div
                  v-else
                  class="w-[52px] h-[52px] rounded-full bg-white/5 border-2"
                  :class="c.queueIndex === 1 ? 'border-amber-400/80' : 'border-white/15'"
                ></div>
                <span
                  v-if="c.queueIndex === 1"
                  class="absolute -right-1 -top-1 w-5 h-5 rounded-full bg-amber-500 flex items-center justify-center"
                >
                  <Crown class="w-3 h-3 text-neutral-900" />
                </span>
              </div>
              <div class="w-full text-center min-w-0">
                <div class="text-white text-xs font-bold truncate" :title="c.name">{{ c.name || '待定' }}</div>
                <div class="text-amber-400 font-mono text-sm leading-tight">{{ c.points ?? 0 }}</div>
              </div>
            </div>
            <div v-if="!queue.length" class="text-white/30 text-xs py-6 px-2 flex-none">暂无参赛者</div>
          </div>
        </div>

        <!-- 中间当前对决:正在进行对战的两人;未开赛时显示下一场配对(位置大小一致) -->
        <div class="flex-1 min-h-0 flex items-center justify-center px-6 py-3">
          <div v-if="currentMatch || queue.length >= 2" class="w-full h-full flex items-center justify-center gap-[6%]">
            <div class="flex flex-col items-center gap-2 flex-1 min-w-0 h-full justify-center">
              <img v-if="defender.avatar" :src="defender.avatar" class="h-[58%] max-w-full object-contain" @error="onImgError" />
              <div v-else class="h-[58%] w-full"></div>
              <span class="text-white font-bold text-[clamp(14px,2vw,30px)] truncate max-w-full" :title="defender.name">{{ defender.name || '待定' }}</span>
              <span class="text-amber-400 font-mono text-[clamp(12px,1.5vw,22px)]">
                <Crown class="w-[clamp(12px,1.3vw,18px)] h-[clamp(12px,1.3vw,18px)] inline-block text-amber-400 align-[-2px] mr-1" />
                积分 {{ defender.points ?? 0 }}
              </span>
            </div>
            <div class="flex-none text-white/40 font-black text-[clamp(18px,2.5vw,40px)]">VS</div>
            <div class="flex flex-col items-center gap-2 flex-1 min-w-0 h-full justify-center">
              <img v-if="challenger.avatar" :src="challenger.avatar" class="h-[58%] max-w-full object-contain" @error="onImgError" />
              <div v-else class="h-[58%] w-full"></div>
              <span class="text-white font-bold text-[clamp(14px,2vw,30px)] truncate max-w-full" :title="challenger.name">{{ challenger.name || '待定' }}</span>
              <span class="text-amber-400 font-mono text-[clamp(12px,1.5vw,22px)]">积分 {{ challenger.points ?? 0 }}</span>
            </div>
          </div>
          <div v-else class="text-white/40 text-sm text-center">暂无参赛者</div>
        </div>
      </div>
    </div>

    <!-- 编辑模式:绑定擂台赛段 -->
    <div v-else class="space-y-4 px-2 py-4">
      <section>
        <span class="section-title">擂台积分属性</span>
        <StageSelector
          label="绑定擂台赛段"
          :model-value="(stageId as any) ?? null"
          only-mode="ARENA"
          @update:model-value="$emit('update:stageId', $event)"
        />
        <p class="text-[10px] text-neutral-600 mt-2">顶部一行按轮转顺序展示参赛者（头像/名字/积分，队首为擂主），中间展示当前对决；结算由现场决定，系统只负责累计积分。</p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { useRoute } from 'vue-router';
import { Crown } from 'lucide-vue-next';
import StageSelector from '../stages/StageSelector.vue';
import { getArenaOverview } from '@/api/game/stage';
import { subscribeTournamentEvents, unsubscribeTournamentEvents } from '@/utils/tournamentEventSse';

const props = defineProps<{
  stageId?: string | number | null;
  mode?: 'view' | 'edit';
  tournamentId?: string | number | null;
}>();
const emit = defineEmits<{
  'update:stageId': [v: string | number | null];
}>();

const route = useRoute();
const qid = (v: unknown) => (typeof v === 'string' || typeof v === 'number' ? v : null);
const tournamentId = computed(() => props.tournamentId ?? qid(route.query.id) ?? qid(route.query.tournamentId) ?? null);

const loading = ref(false);
const loadedOnce = ref(false);
const error = ref('');
const stageName = ref('');
const status = ref('');
const battleCount = ref<number | null>(null);
const queue = ref<any[]>([]);
const currentMatch = ref<any | null>(null);

const defender = computed(() => currentMatch.value?.defender || queue.value[0] || {});
const challenger = computed(() => currentMatch.value?.challenger || queue.value[1] || {});
const inBattleIds = computed(() => {
  const ids = new Set<string>();
  if (currentMatch.value?.defender?.competitorId != null) {
    ids.add(String(currentMatch.value.defender.competitorId));
  }
  if (currentMatch.value?.challenger?.competitorId != null) {
    ids.add(String(currentMatch.value.challenger.competitorId));
  }
  return ids;
});
const isInBattle = (cid: unknown) => cid != null && inBattleIds.value.has(String(cid));

const loadData = async () => {
  if (!props.stageId) {
    queue.value = [];
    currentMatch.value = null;
    error.value = '';
    return;
  }
  if (!loadedOnce.value) {
    loading.value = true;
  }
  error.value = '';
  try {
    const res: any = await getArenaOverview(props.stageId);
    const data = res?.data?.data ?? res?.data ?? res;
    if (!data || data.stageId == null) {
      throw new Error('擂台赛数据不存在');
    }
    stageName.value = data.stageName || '';
    status.value = data.status || '';
    battleCount.value = data.battleCount ?? 0;
    queue.value = data.queue || [];
    currentMatch.value = data.currentMatch || null;
    loadedOnce.value = true;
  } catch (e: any) {
    console.error('ArenaWidget 加载失败', e);
    error.value = e?.response?.data?.msg || '加载失败';
  } finally {
    loading.value = false;
  }
};

const onImgError = (e: Event) => {
  (e.target as HTMLImageElement).style.visibility = 'hidden';
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
watch(() => props.stageId, loadData);
</script>
