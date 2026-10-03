<template>
  <div class="w-full h-full">
    <!-- 查看模式:赛段参赛选手 -->
    <div v-if="mode !== 'edit'" ref="viewRef" class="w-full h-full rounded-lg overflow-hidden relative">
      <div v-if="loading" class="relative w-full h-full flex items-center justify-center text-white/50" :style="fz(15)">加载中...</div>
      <div v-else-if="error" class="relative w-full h-full flex items-center justify-center text-white/40 px-4 text-center" :style="fz(13)">
        {{ error }}
      </div>
      <div v-else-if="!stageId" class="relative w-full h-full flex items-center justify-center text-white/40" :style="fz(13)">未绑定赛段</div>
      <div v-else class="relative w-full h-full flex flex-col">
        <!-- 标题栏:赛段名 + 人数 + 名单来源(未确认/已确认) -->
        <div class="px-3 py-2 border-b border-white/10 flex items-center justify-between gap-2 flex-none">
          <span class="text-white font-bold truncate" :style="fz(15)">{{ stageName || '参赛选手' }}</span>
          <div class="flex items-center gap-2 flex-none">
            <span class="text-white/50 font-mono" :style="fz(11)">{{ items.length }} 人</span>
          </div>
        </div>

        <!-- 选手列表:一行多人,按座位号排列 -->
        <div class="flex-1 min-h-0 overflow-y-auto p-2 scrollbar-hide">
          <!-- 显示头像:卡片模式(中间圆形头像、下方人名) -->
          <div
            v-if="showAvatar"
            class="grid gap-3"
            :style="{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }"
          >
            <div v-for="(p, idx) in items" :key="itemKey(p, idx)" class="participant-card" :style="cardStyle">
              <img
                v-if="p.avatar"
                :src="p.avatar"
                class="participant-avatar"
                :style="{ width: avatarPx, height: avatarPx }"
                alt=""
                @error="onAvatarError"
              />
              <span v-else class="participant-avatar participant-avatar-empty" :style="{ width: avatarPx, height: avatarPx }"></span>
              <span class="participant-card-name" :style="fz(13)" :title="p.name || '待定'">{{ p.name || '待定' }}</span>
            </div>
          </div>
          <!-- 默认:一行多人(座位号 + 名字 + 号码) -->
          <div v-else class="grid gap-1.5" :style="{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }">
            <div
              v-for="(p, idx) in items"
              :key="itemKey(p, idx)"
              class="flex items-center gap-2 px-2 py-1.5 rounded bg-white/5 border border-white/10"
              :style="cardStyle"
            >
              <span
                v-if="showSeat"
                class="font-mono flex-none w-7 text-right text-white/45"
                :style="fz(11)"
              >
                {{ p.seedRank ?? '' }}
              </span>
              <span class="flex-1 min-w-0 truncate text-white" :style="fz(13)">{{ p.name || '待定' }}</span>
              <span v-if="showNumber && p.number" class="flex-none font-mono text-white/40" :style="fz(10)">#{{ p.number }}</span>
            </div>
          </div>
          <p v-if="!items.length" class="text-center text-white/30 py-4" :style="fz(12)">该赛段暂无参赛选手</p>
        </div>
      </div>
    </div>

    <!-- 编辑模式:绑定任意赛段 + 显示选项 -->
    <div v-else class="space-y-4 px-2 py-4">
      <section>
        <span class="section-title">参赛选手属性</span>
        <StageSelector label="绑定赛段(任意赛制)" :model-value="(stageId as any) ?? null" @update:model-value="$emit('update:stageId', $event)" />
        <div class="mt-3 space-y-2">
          <label class="flex items-center justify-between text-xs text-neutral-400 cursor-pointer">
            <span>显示座位号</span>
            <input
              type="checkbox"
              class="accent-amber-500"
              :checked="showSeat"
              @change="$emit('update:showSeat', ($event.target as HTMLInputElement).checked)"
            />
          </label>
          <label class="flex items-center justify-between text-xs text-neutral-400 cursor-pointer">
            <span>显示号码牌</span>
            <input
              type="checkbox"
              class="accent-amber-500"
              :checked="showNumber"
              @change="$emit('update:showNumber', ($event.target as HTMLInputElement).checked)"
            />
          </label>
          <label class="flex items-center justify-between text-xs text-neutral-400 cursor-pointer">
            <span>显示头像</span>
            <input
              type="checkbox"
              class="accent-amber-500"
              :checked="showAvatar"
              @change="$emit('update:showAvatar', ($event.target as HTMLInputElement).checked)"
            />
          </label>
        </div>
        <div class="mt-3">
          <label class="block text-[10px] font-bold text-neutral-500 uppercase tracking-wider mb-1">每行人数</label>
          <div class="flex items-center gap-3">
            <input
              type="range"
              min="1"
              max="8"
              step="1"
              :value="columns"
              class="flex-1 accent-amber-500"
              @change="$emit('update:columns', Number(($event.target as HTMLInputElement).value))"
            />
            <span class="text-xs text-neutral-400 font-mono w-8 text-right flex-none">{{ columns }}</span>
          </div>
        </div>
        <div class="mt-3">
          <span class="block text-[10px] font-bold text-neutral-500 uppercase tracking-wider mb-1">卡片背景颜色</span>
          <div class="flex items-center gap-1.5">
            <div class="flex-1 min-w-0">
              <ColorInput :model-value="bgColor ?? ''" @update:model-value="$emit('update:bgColor', $event)" />
            </div>
            <button
              class="shrink-0 w-7 h-7 rounded flex items-center justify-center transition-colors"
              :class="bgColor === '' ? 'text-amber-400 bg-amber-500/10' : 'text-neutral-500 hover:text-amber-400 hover:bg-neutral-800'"
              title="设为透明"
              @click="$emit('update:bgColor', '')"
            >
              <Ban class="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
        <p class="text-[10px] text-neutral-600 mt-2">
          绑定任意赛段展示参赛选手:名单还没确认时读中间态名单(刚晋级/还没落位的人都在),
          确认名单后自动切换成该赛段的真实参赛选手。背景颜色作用于卡片(列表/头像卡),可设为透明。
        </p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { useRoute } from 'vue-router';
import { Ban } from 'lucide-vue-next';
import StageSelector from '../stages/StageSelector.vue';
import ColorInput from './common/ColorInput.vue';
import { getStageParticipants } from '@/api/game/screen';
import { subscribeTournamentEvents, unsubscribeTournamentEvents } from '@/utils/tournamentEventSse';

const props = defineProps<{
  stageId?: string | number | null;
  mode?: 'view' | 'edit';
  tournamentId?: string | number | null;
  /** 背景颜色;空字符串 = 透明,未配置 = 默认深色底 */
  bgColor?: string;
  showSeat?: boolean;
  showNumber?: boolean;
  showAvatar?: boolean;
  columns?: number;
}>();
const emit = defineEmits<{
  'update:stageId': [v: string | number | null];
  'update:bgColor': [v: string];
  'update:showSeat': [v: boolean];
  'update:showNumber': [v: boolean];
  'update:showAvatar': [v: boolean];
  'update:columns': [v: number];
}>();

const route = useRoute();
const qid = (v: unknown) => (typeof v === 'string' || typeof v === 'number' ? v : null);
const tournamentId = () => props.tournamentId ?? qid(route.query.id) ?? qid(route.query.tournamentId) ?? null;

/** 查看模式容器:用于测量实际渲染尺寸 */
const viewRef = ref<HTMLElement | null>(null);
let resizeObserver: ResizeObserver | null = null;

/** 字号缩放:以默认组件尺寸 800x600 为基准,大组件放大、小组件缩小,限制在 0.6~2.5 */
const scale = ref(1);
const fz = (base: number) => ({
  fontSize: `${Math.round(base * scale.value)}px`,
  lineHeight: `${Math.round(base * scale.value * 1.4)}px`
});

/** 卡片模式头像尺寸:随组件缩放联动 */
const avatarPx = computed(() => fz(48).fontSize);

/** 头像加载失败时隐藏,避免裂图 */
const onAvatarError = (e: Event) => {
  (e.target as HTMLImageElement).style.display = 'none';
};

const startObserve = () => {
  resizeObserver?.disconnect();
  if (!viewRef.value) return;
  resizeObserver = new ResizeObserver((entries) => {
    const r = entries[0]?.contentRect;
    if (!r) return;
    const s = Math.min(r.width / 800, r.height / 600);
    scale.value = Math.max(0.6, Math.min(2.5, s));
  });
  resizeObserver.observe(viewRef.value);
};

// 默认不显示座位号:座位号是导播用的排位信息,大屏一般只关心"这个赛段有哪些人"
const showSeat = computed(() => props.showSeat === true);
const showNumber = computed(() => props.showNumber !== false);
const showAvatar = computed(() => props.showAvatar === true);
const columns = computed(() => Math.max(1, Math.min(8, Number(props.columns) || 2)));

/**
 * 卡片背景颜色(作用于列表行 / 头像卡,不是整个组件底):
 * 空字符串=透明,已配置=该颜色,未配置=沿用卡片默认浅色。
 */
const cardStyle = computed(() => {
  if (props.bgColor === '') return { backgroundColor: 'transparent' };
  if (props.bgColor) return { backgroundColor: props.bgColor };
  return {};
});

const loading = ref(false);
const loadedOnce = ref(false);
const error = ref('');
const stageName = ref('');
const items = ref<any[]>([]);

const itemKey = (p: any, idx: number) => `${p.refType}-${p.seedRank ?? 'h'}-${p.name ?? ''}-${idx}`;

const loadData = async () => {
  if (!props.stageId) {
    items.value = [];
    error.value = '';
    return;
  }
  if (!loadedOnce.value) {
    loading.value = true;
  }
  error.value = '';
  try {
    const resp: any = await getStageParticipants(props.stageId);
    const data = resp?.data;
    if (!data) {
      throw new Error('赛段不存在');
    }
    stageName.value = data.stageName || '';
    items.value = data.items || [];
  } catch (e) {
    console.error('ParticipantWidget 加载失败', e);
    error.value = '加载失败,请检查赛段绑定';
    items.value = [];
  } finally {
    loading.value = false;
    loadedOnce.value = true;
  }
};

/**
 * 事件回调:重连补偿(null)或非打分事件都刷新。
 * "加人/名单确认"这类事件的 stageId 口径可能与绑定赛段不完全一致(如签到广播带的是首个赛段),
 * 只按 stageId 过滤会漏刷新;打分事件不改变名单构成,直接跳过。
 */
const handleTournamentEvent = (data: any) => {
  if (data && data.type === 'scores') {
    return;
  }
  loadData();
};

onMounted(() => {
  loadData();
  startObserve();
  subscribeTournamentEvents(tournamentId(), handleTournamentEvent);
});
onUnmounted(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
  unsubscribeTournamentEvents(tournamentId(), handleTournamentEvent);
});

watch(
  () => props.mode,
  () => startObserve()
);
watch(
  () => props.tournamentId,
  (newTid, oldTid) => {
    if (oldTid !== newTid) {
      unsubscribeTournamentEvents(oldTid, handleTournamentEvent);
      subscribeTournamentEvents(newTid, handleTournamentEvent);
    }
    loadData();
  }
);
watch(
  () => props.stageId,
  () => loadData()
);
</script>

<style scoped>
.scrollbar-hide {
  -ms-overflow-style: none;
  scrollbar-width: none;
}
.scrollbar-hide::-webkit-scrollbar {
  display: none;
}
/* 头像卡模式:中间圆形头像,下面人名 */
.participant-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 10px 6px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.05);
}
.participant-avatar {
  border-radius: 9999px;
  object-fit: cover;
  flex: none;
  background: rgba(255, 255, 255, 0.1);
}
.participant-avatar-empty {
  display: block;
}
.participant-card-name {
  color: #fff;
  text-align: center;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
