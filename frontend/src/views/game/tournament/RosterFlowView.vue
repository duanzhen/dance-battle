<template>
  <div ref="wrapRef" class="relative min-w-max px-2 pt-3 pb-12">
    <!-- SVG 连线:来源池卡 → 目标入边池卡(无赛段标题行) -->
    <svg ref="svgRef" class="absolute top-0 left-0 pointer-events-none" :width="svgW" :height="svgH">
      <path v-for="(l, i) in lines" :key="i" :d="l" fill="none" stroke="#f59e0b" stroke-opacity="0.6" stroke-width="1.5" />
    </svg>

    <div class="flex items-start">
      <template v-for="(stage, index) in stages" :key="String(stage.id)">
        <!-- 每列只放该赛段的入边池卡(来源赛段同款卡即为其"出口") -->
        <div class="flex flex-col gap-1 w-40 sm:w-48">
          <button
            v-for="p in rostersOf(stage)"
            :key="String(p.id)"
            :data-flow-roster="String(p.id)"
            :data-source="p.sourceStageId != null ? String(p.sourceStageId) : ''"
            :data-target="String(stage.id)"
            @click="openTarget(stage)"
            class="flow-roster-card w-full text-left rounded border border-neutral-800 bg-black/60 hover:border-amber-500/50 px-2 py-1 text-[10px] text-neutral-400 transition-colors"
            :title="'来源:' + rosterText(p)"
          >
            <!-- 多组来源:逐组显示来源(源赛段·圈·结果·名次·填充方式) -->
            <span v-for="(g, gi) in groupsOf(p)" :key="gi" class="block truncate">
              <span class="text-amber-500/90">{{ g.sourceStageId == null ? '签到/报名' : stageNameOf(g.sourceStageId) }}</span>
              <span v-if="g.zone" class="text-amber-500/60">·{{ zoneText(g.zone) }}</span>
              ·{{ resultLabel(g.resultFilter) }}<span v-if="g.quota && g.quota > 0"> 前{{ g.quota }}</span>
              <span v-if="g.rankStart != null || g.rankEnd != null" class="text-neutral-500">
                {{ g.rankByZone ? '圈内' : '全场' }}{{ g.rankStart ?? '' }}~{{ g.rankEnd ?? '末' }}名
              </span>
              <span class="text-neutral-600"> · {{ fillLabel(g.fillMode) }}</span>
            </span>
            <span class="block text-[9px] text-neutral-600 mt-0.5">{{ rosterStateLabel(p.state) }}</span>
          </button>
          <div v-if="rostersOf(stage).length === 0" class="text-[9px] text-neutral-700 text-center pt-1">无名单来源</div>
        </div>
        <div v-if="index < stages.length - 1" class="w-24 flex-none"></div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue';

const props = defineProps<{
  stages: any[];
}>();

const emit = defineEmits<{
  'open-stage': [stageId: string | number];
}>();

const wrapRef = ref<HTMLElement | null>(null);
const svgW = ref(0);
const svgH = ref(0);
const lines = ref<string[]>([]);

const stageNameOf = (id: string | number) => props.stages.find((s) => String(s.id) === String(id))?.name || '赛段 #' + id;

// 名单以后端 stage 的 incoming(roster) 为准
const rostersOf = (stage: any): any[] => stage?.incoming || [];

/** 名单行的来源组(为空时退回行上的首组冗余列,保证旧数据也有显示) */
const groupsOf = (p: any): any[] => {
  const groups = p?.groups;
  if (Array.isArray(groups) && groups.length > 0) {
    return groups;
  }
  return [p];
};

const resultLabel = (filter?: string): string => {
  if (filter === 'ANY') return '不限';
  if (filter === 'ADVANCE') return '晋级';
  if (filter === 'ELIMINATED') return '落选(复活)';
  return filter || '不限';
};

const fillLabel = (fill?: string): string => {
  if (fill === 'MANUAL') return '手动';
  if (fill === 'STREAM') return '签到';
  return '自动';
};

const rosterStateLabel = (state?: string): string => {
  const map: Record<string, string> = {
    WAIT_SOURCE: '等来源结算',
    READY: '就绪',
    CONFIRMED: '已带入',
    SKIPPED: '跳过'
  };
  return (state && map[state]) || state || '—';
};

const rosterText = (p: any): string =>
  groupsOf(p)
    .map((g) => `${g.sourceStageId == null ? '签到/报名' : stageNameOf(g.sourceStageId)}${
      g.zone ? '·' + zoneText(g.zone) : ''
    } · ${resultLabel(g.resultFilter)}${g.quota && g.quota > 0 ? ` 前${g.quota}` : ''}${
      g.rankStart != null || g.rankEnd != null
        ? ` · ${g.rankByZone ? '圈内' : '全场'}${g.rankStart ?? ''}~${g.rankEnd ?? '末'}名` : ''
    } · ${fillLabel(g.fillMode)}`)
    .join('\n');

/** ZONE-2 → 第2圈 */
const zoneText = (zone?: string | null): string => {
  const m = /^ZONE-(\d+)$/.exec(zone || '');
  return m ? `第${m[1]}圈` : (zone || '');
};

const openTarget = (stage: any) => {
  emit('open-stage', stage.id);
};

const recomputeLines = async () => {
  await nextTick();
  const wrap = wrapRef.value;
  if (!wrap) return;
  svgW.value = wrap.scrollWidth;
  svgH.value = wrap.scrollHeight;
  const rosterEls = Array.from(wrap.querySelectorAll<HTMLElement>('[data-flow-roster]'));
  const rostersByTarget = new Map<string, HTMLElement[]>();
  rosterEls.forEach((el) => {
    const target = el.getAttribute('data-target') || '';
    if (!target) return;
    const list = rostersByTarget.get(target) || [];
    list.push(el);
    rostersByTarget.set(target, list);
  });
  const rectOf = (el: HTMLElement) => ({
    left: el.offsetLeft,
    right: el.offsetLeft + el.offsetWidth,
    top: el.offsetTop,
    bottom: el.offsetTop + el.offsetHeight
  });
  // 底部空通道:所有池卡底边下方 +10px,保证 svg 内一定可见
  const bottomY = Math.max(...rosterEls.map((el) => rectOf(el).bottom)) + 10;
  const next: string[] = [];
  rosterEls.forEach((rosterEl) => {
    const sourceId = rosterEl.getAttribute('data-source') || '';
    if (!sourceId) return;
    const px = rosterEl.offsetLeft;
    const py = rosterEl.offsetTop + rosterEl.offsetHeight / 2;
    // 起点 = 来源赛段自己的全部池卡(不二选一):中间每个池都要有出口
    const sourceRosters = rostersByTarget.get(sourceId) || [];
    if (sourceRosters.length === 0) return;
    sourceRosters.forEach((src) => {
      const sx = src.offsetLeft + src.offsetWidth;
      const sy = src.offsetTop + src.offsetHeight / 2;
      const gutterX = sx + 10;
      const minX = Math.min(gutterX, px);
      const maxX = Math.max(gutterX, px);
      const blocked = rosterEls.some((el) => {
        if (el === src || el === rosterEl) return false;
        const r = rectOf(el);
        const verticalHit = py >= r.top - 1 && py <= r.bottom + 1;
        const horizontalHit = r.right >= minX - 1 && r.left <= maxX + 1;
        return verticalHit && horizontalHit;
      });
      let d: string;
      if (!blocked) {
        d = `M ${sx} ${sy} L ${gutterX} ${sy} L ${gutterX} ${py} L ${px} ${py}`;
      } else {
        const gutterB = Math.max(12, px - 14);
        d = `M ${sx} ${sy} L ${gutterX} ${sy} ` + `L ${gutterX} ${bottomY} L ${gutterB} ${bottomY} ` + `L ${gutterB} ${py} L ${px} ${py}`;
      }
      next.push(d);
    });
  });
  lines.value = next;
};

onMounted(() => recomputeLines());
watch(
  () => props.stages,
  () => recomputeLines(),
  { deep: true }
);
</script>
