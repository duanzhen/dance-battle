<template>
  <div class="bg-black border border-neutral-700 rounded-lg p-4 space-y-3">
    <div class="flex items-center justify-between">
      <label class="text-xs text-neutral-500">
        出口去向
        <span class="text-[11px] text-neutral-600 ml-1">
          {{ knockoutMode ? '(胜者/败者去向;不配置时默认胜者进入下一赛段)' : isAudition ? '(可按圈分别配置)' : '(本赛段结果送入下游名单)' }}
        </span>
      </label>
      <span class="text-[11px] text-neutral-600">{{ entries.length }} 条</span>
    </div>

    <div v-if="!created" class="text-[11px] text-neutral-600">赛段尚未创建，出口配置在赛段创建后可用。</div>
    <div v-else-if="stageStarted" class="text-[11px] text-neutral-600">赛段已开始或已结束，出口配置只读。</div>
    <div v-else-if="targetOptions.length === 0" class="text-[11px] text-neutral-600">
      暂无可承接的赛段（下游赛段需处于规划中，且名单尚未确认/跳过）
    </div>

    <!-- 出口列表 -->
    <div v-if="entries.length > 0" class="space-y-1.5">
      <div
        v-for="e in entries"
        :key="String(e.groupId)"
        class="flex items-center gap-2 rounded bg-neutral-900/70 border border-neutral-800 px-2.5 py-1.5"
      >
        <span class="text-[11px] text-amber-500/90 flex-none">{{ stageNameOf(e.targetStageId) }}</span>
        <span class="text-[11px] text-neutral-300 flex-1 min-w-0 truncate">{{ ruleText(e.g) }}</span>
        <span
          v-if="e.g.generated === 1"
          class="text-[10px] px-1 py-0.5 rounded bg-neutral-800 text-neutral-500 flex-none"
          title="建段时系统自动补的链式衔接"
        >
          默认衔接
        </span>
        <!-- 取人顺序:只影响同一个目标赛段内部(先取哪条出口的人) -->
        <button
          v-if="editable && exitEditable(e)"
          :disabled="!canMoveExit(e, -1)"
          class="px-1 py-1 text-[11px] rounded border border-neutral-700 text-neutral-400 hover:text-amber-400 hover:border-amber-500/40 transition-colors flex-none disabled:opacity-30 disabled:hover:text-neutral-400 disabled:hover:border-neutral-700"
          title="上移(更先取人)"
          @click="moveExit(e, -1)"
        >
          ↑
        </button>
        <button
          v-if="editable && exitEditable(e)"
          :disabled="!canMoveExit(e, 1)"
          class="px-1 py-1 text-[11px] rounded border border-neutral-700 text-neutral-400 hover:text-amber-400 hover:border-amber-500/40 transition-colors flex-none disabled:opacity-30 disabled:hover:text-neutral-400 disabled:hover:border-neutral-700"
          title="下移(更后取人)"
          @click="moveExit(e, 1)"
        >
          ↓
        </button>
        <button
          v-if="editable && exitEditable(e)"
          class="px-1.5 py-1 text-[11px] rounded border border-neutral-700 text-neutral-400 hover:text-amber-400 hover:border-amber-500/40 transition-colors flex-none"
          @click="openEdit(e)"
        >
          编辑
        </button>
        <button
          v-if="editable && exitEditable(e)"
          class="px-1.5 py-1 text-[11px] rounded border border-neutral-700 text-neutral-500 hover:text-red-400 hover:border-red-900/40 transition-colors flex-none"
          @click="removeExit(e)"
        >
          移除
        </button>
        <span
          v-if="editable && !exitEditable(e)"
          class="text-[10px] px-1 py-0.5 rounded bg-neutral-800 text-neutral-500 flex-none"
          title="目标赛段已开赛或名单已确认/跳过,这条出口已锁定"
        >
          已锁定
        </span>
      </div>
    </div>
    <div v-else-if="editable" class="text-[12px] text-neutral-600">
      {{ knockoutMode ? '未配置出口；默认胜者进入下一赛段' : '暂无出口；默认由「下一赛段」自动衔接' }}
    </div>

    <!-- 编辑/新增表单 -->
    <div v-if="editable && formVisible" class="rounded bg-neutral-900/60 border border-neutral-800 p-3 space-y-2">
      <div class="grid grid-cols-2 gap-2">
        <div>
          <label class="text-[11px] text-neutral-600 block mb-1">去向赛段</label>
          <el-select v-model="form.targetStageId" :disabled="editing" class="w-full" placeholder="请选择去向赛段">
            <el-option v-for="t in targetOptions" :key="String(t.id)" :label="t.name" :value="t.id" />
          </el-select>
        </div>
        <div>
          <label class="text-[11px] text-neutral-600 block mb-1">结果</label>
          <el-select v-model="form.resultFilter" class="w-full">
            <el-option value="ADVANCE" :label="knockoutMode ? '胜者' : '晋级'" />
            <el-option value="ELIMINATED" :label="knockoutMode ? '败者' : '落选'" />
            <el-option v-if="!knockoutMode" value="ANY" label="不限" />
          </el-select>
        </div>
      </div>
      <div v-if="isAudition && !editing">
        <label class="text-[11px] text-neutral-600 block mb-1">圈</label>
        <el-select v-model="form.zone" class="w-full">
          <el-option value="__all__" :label="`全部 ${circleCount} 圈（同样规则）`" />
          <el-option v-for="z in zoneOptions" :key="z" :value="z" :label="circleLabel(z.replace('ZONE-', ''))" />
        </el-select>
      </div>
      <!-- 淘汰赛(晋级赛)只分胜负,不涉及名次匹配 -->
      <div v-if="!knockoutMode">
        <label class="text-[11px] text-neutral-600 block mb-1">名次</label>
        <el-select v-model="form.rankMode" class="w-full">
          <el-option value="none" label="不限名次（全部）" />
          <el-option value="range" label="指定名次段" />
        </el-select>
      </div>
      <div v-if="form.rankMode === 'range'" class="grid grid-cols-2 gap-2">
        <input v-model.number="form.rankStart" type="number" min="1" class="cfg-input w-full" placeholder="起，如 9" />
        <input v-model.number="form.rankEnd" type="number" min="1" class="cfg-input w-full" placeholder="止，如 24（空=末名）" />
      </div>
      <p v-if="isAudition && form.rankMode === 'range'" class="text-[11px] text-amber-500/80">
        海选按「{{ form.zone === '__all__' ? '各圈' : circleLabel(String(form.zone).replace('ZONE-', '')) }}内名次」取人
      </p>
      <div class="flex justify-end gap-2">
        <button
          class="px-3 py-1.5 text-[11px] rounded border border-neutral-700 text-neutral-400 hover:bg-neutral-800 transition-colors"
          @click="closeForm"
        >
          取消
        </button>
        <button
          class="px-3.5 py-1.5 text-[11px] rounded bg-amber-500 text-neutral-900 font-medium hover:bg-amber-400 disabled:opacity-50 transition-colors"
          :disabled="saving || !form.targetStageId"
          @click="save"
        >
          {{ saving ? '保存中...' : editing ? '保存' : '添加出口' }}
        </button>
      </div>
    </div>

    <button
      v-if="editable && !formVisible"
      class="w-full py-2 text-[11px] rounded border border-amber-500/30 text-amber-400 bg-amber-500/10 hover:bg-amber-500/20 transition-colors"
      @click="openAdd"
    >
      ＋ 添加出口
    </button>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { StageData } from './types';
import { listStage } from '@/api/game/stage';
import { listRostersBySource, addRosterGroups, removeRosterGroup, updateRosterGroup, reorderRosterGroups } from '@/api/game/stage/roster';
import { circleLabel } from '@/utils/circleLabel';

const props = defineProps<{
  stage: StageData;
}>();

interface ExitEntry {
  targetStageId: string | number;
  /** 来源组行 ID(表化后按 ID 定位,不再用数组下标) */
  groupId: string | number;
  g: any;
}

const entries = ref<ExitEntry[]>([]);
const stageOptions = ref<StageData[]>([]);
const loading = ref(false);
const saving = ref(false);
const formVisible = ref(false);
const editing = ref(false);
const editEntry = ref<ExitEntry | null>(null);

const form = reactive({
  targetStageId: null as string | number | null,
  resultFilter: 'ADVANCE',
  rankMode: 'none',
  rankStart: null as number | null,
  rankEnd: null as number | null,
  zone: '__all__'
});

const isAudition = computed(() => props.stage?.stageMode === 'AUDITION');
/** 淘汰赛/晋级赛:出口只分胜负(胜者/败者),不涉及名次 */
const knockoutMode = computed(() => props.stage?.stageMode === 'KNOCKOUT');

const circleCount = computed(() => {
  try {
    const cfg = JSON.parse(props.stage?.ruleConfig || '{}');
    return Math.max(1, Number(cfg.circles) || 1);
  } catch {
    return 1;
  }
});
/**
 * 圈选项:恒为 ZONE-1..n(单圈就是第 1 圈)。
 *
 * 圈的编号与单/多圈无关,后端圈场次的分区名也恒为 ZONE-k——出口规则按圈取人时
 * 单圈与多圈走同一套过滤,不再有"单圈没有圈可选"的特例。
 */
const zoneOptions = computed(() => Array.from({ length: Math.max(1, circleCount.value) }, (_, i) => 'ZONE-' + (i + 1)));

/** 赛段是否已真正落库(创建向导里的临时赛段还没有 ID) */
const created = computed(() => {
  const s = props.stage;
  return !!s?.id && String(s.id) !== 'temp';
});

/** 本赛段是否还在规划中:开赛后(进行中/已结束)出口只能看,不能改 */
const stageDraft = computed(() => created.value && (!props.stage.status || props.stage.status === 'DRAFT'));

/** 赛段已开赛/已结束:整块出口配置只读(单条出口是否可改还要再看目标赛段,见 exitEditable) */
const stageStarted = computed(() => created.value && !stageDraft.value);

/** 出口写在"目标赛段"上:来源侧可配置,目标侧限制在 targetOptions(规划中未初始化) */
const editable = stageDraft;

const stageNameOf = (id: string | number | null | undefined): string =>
  stageOptions.value.find((s) => String(s.id) === String(id))?.name || '赛段 #' + id;

/**
 * 名单是否已被物化锁定(已确认带入或已跳过)。
 * 只有这种情况下游赛段才不能再承接新的出口来源;单看 is_initialized 会误杀
 * 历史数据里被创建流程提前置 1 的空赛段(它们既无对阵也无参赛方)。
 */
const rosterLocked = (s: StageData): boolean =>
  Array.isArray((s as any).incoming) && (s as any).incoming.some((r: any) => r?.state === 'CONFIRMED' || r?.state === 'SKIPPED');

/**
 * 某条出口现在还能不能改。
 *
 * <p>表化后出口就是目标赛段的一条入边,能不能动只取决于<b>目标赛段</b>:
 * 已开赛(非 DRAFT)或名单已确认/已跳过 → 目标锁定,这条出口只能看。</p>
 */
const exitEditable = (e: ExitEntry): boolean => {
  const target = stageOptions.value.find((x) => String(x.id) === String(e.targetStageId));
  return !!target && target.status === 'DRAFT' && !rosterLocked(target);
};

/** 可选去向:沿 next 链位于本赛段之后,且仍为规划中、名单未锁定的赛段 */
const targetOptions = computed(() => {
  const list = stageOptions.value;
  if (!list.length) return [];
  const byId = new Map(list.map((s) => [String(s.id), s]));
  const out: StageData[] = [];
  let cur = byId.get(String(props.stage.id));
  const visited = new Set<string>();
  while (cur && cur.nextStageId != null && !visited.has(String(cur.nextStageId))) {
    visited.add(String(cur.nextStageId));
    const next = byId.get(String(cur.nextStageId));
    if (!next) break;
    if (next.status === 'DRAFT' && !rosterLocked(next)) {
      out.push(next);
    }
    cur = next;
  }
  return out;
});

const rankText = (g: any): string => {
  if (g.rankStart == null && g.rankEnd == null) return '全部名次';
  return `第${g.rankStart ?? ''}~${g.rankEnd ?? '末'}名`;
};

const ruleText = (g: any): string => {
  const result = knockoutMode.value
    ? g.resultFilter === 'ELIMINATED'
      ? '败者'
      : g.resultFilter === 'ADVANCE'
        ? '胜者'
        : '不限'
    : g.resultFilter === 'ELIMINATED'
      ? '落选'
      : g.resultFilter === 'ADVANCE'
        ? '晋级'
        : '不限';
  // 淘汰赛/晋级赛只分胜负,不展示名次
  if (knockoutMode.value) {
    return result;
  }
  const zone = isAudition.value && g.zone ? `${circleLabel(String(g.zone).replace('ZONE-', ''))} · ` : '';
  return `${zone}${result} · ${rankText(g)}`;
};

const load = async () => {
  if (!props.stage?.tournamentId) return;
  loading.value = true;
  try {
    const resp: any = await listStage({ tournamentId: props.stage.tournamentId, pageNum: 1, pageSize: 200 });
    stageOptions.value = (resp?.data || resp || []) as StageData[];
    if (props.stage?.id && String(props.stage.id) !== 'temp') {
      const ex: any = await listRostersBySource(props.stage.id);
      const rosters: any[] = ex?.data || [];
      const list: ExitEntry[] = [];
      for (const r of rosters) {
        (r.groups || []).forEach((g: any) => {
          if (String(g.sourceStageId) === String(props.stage.id)) {
            list.push({
              targetStageId: r.targetStageId ?? r.id,
              groupId: g.id,
              g
            });
          }
        });
      }
      entries.value = list;
    } else {
      entries.value = [];
    }
  } catch (e: any) {
    notifyError(e, '出口加载失败');
  } finally {
    loading.value = false;
  }
};

const resetForm = () => {
  form.targetStageId = targetOptions.value[0]?.id ?? null;
  form.resultFilter = 'ADVANCE';
  form.rankMode = 'none';
  form.rankStart = null;
  form.rankEnd = null;
  // 海选默认按第 1 圈(单圈也是第 1 圈);多圈时用户可切「全部圈」
  form.zone = isAudition.value ? 'ZONE-1' : '__all__';
};

const openAdd = () => {
  editing.value = false;
  editEntry.value = null;
  resetForm();
  formVisible.value = true;
};

const openEdit = (e: ExitEntry) => {
  editing.value = true;
  editEntry.value = e;
  form.targetStageId = e.targetStageId;
  form.resultFilter = e.g.resultFilter || 'ADVANCE';
  form.rankMode = !knockoutMode.value && (e.g.rankStart != null || e.g.rankEnd != null) ? 'range' : 'none';
  form.rankStart = e.g.rankStart ?? null;
  form.rankEnd = e.g.rankEnd ?? null;
  form.zone = e.g.zone || '__all__';
  formVisible.value = true;
};

const closeForm = () => {
  formVisible.value = false;
};

const buildGroup = (zone: string | null) => {
  const group: any = {
    sourceStageId: props.stage.id,
    resultFilter: form.resultFilter,
    fillMode: 'AUTO',
    quota: 0
  };
  if (isAudition.value && zone) {
    group.zone = zone;
    group.rankByZone = true;
  } else {
    group.rankByZone = false;
  }
  // 淘汰赛/晋级赛不写名次段
  if (!knockoutMode.value && form.rankMode === 'range') {
    group.rankStart = form.rankStart ?? null;
    group.rankEnd = form.rankEnd ?? null;
  }
  return group;
};

const save = async () => {
  if (!form.targetStageId) return;
  saving.value = true;
  try {
    if (editing.value && editEntry.value) {
      const group = buildGroup(isAudition.value && form.zone !== '__all__' ? String(form.zone) : null);
      await updateRosterGroup(editEntry.value.targetStageId, editEntry.value.groupId, group);
      ElMessage.success('出口已更新');
    } else {
      const zones = isAudition.value ? (form.zone === '__all__' ? zoneOptions.value : [String(form.zone)]) : [null];
      const groups = zones.map((z) => buildGroup(z as string | null));
      await addRosterGroups(form.targetStageId, {
        sourceStageId: props.stage.id,
        resultFilter: form.resultFilter,
        fillMode: 'AUTO',
        quota: 0,
        groups
      });
      ElMessage.success(groups.length > 1 ? `已添加 ${groups.length} 条出口` : '出口已添加');
    }
    formVisible.value = false;
    await load();
  } catch (e: any) {
    notifyError(e, '出口保存失败');
  } finally {
    saving.value = false;
  }
};

/** 同一目标赛段内、与某条出口相邻的位置(顺序只在该目标内部有意义) */
const siblingExits = (e: ExitEntry) => entries.value.filter((x) => String(x.targetStageId) === String(e.targetStageId));

const canMoveExit = (e: ExitEntry, delta: number): boolean => {
  const list = siblingExits(e);
  const idx = list.findIndex((x) => String(x.groupId) === String(e.groupId));
  const to = idx + delta;
  return idx >= 0 && to >= 0 && to < list.length;
};

/**
 * 调整取人顺序:交换同目标内的相邻两条出口。
 * 顺序决定"先取哪条出口的人",多出口时直接影响谁先落座。
 */
const moveExit = async (e: ExitEntry, delta: number) => {
  if (!canMoveExit(e, delta) || rosterLockedFor(e.targetStageId)) return;
  const list = siblingExits(e);
  const idx = list.findIndex((x) => String(x.groupId) === String(e.groupId));
  const ids = list.map((x) => x.groupId);
  const to = idx + delta;
  [ids[idx], ids[to]] = [ids[to], ids[idx]];
  try {
    await reorderRosterGroups(e.targetStageId, ids);
    ElMessage.success('取人顺序已调整');
    await load();
  } catch (err: any) {
    notifyError(err, '顺序调整失败');
  }
};

/** 目标赛段名单已锁定(已确认/已跳过/已开赛)时不允许调顺序 */
const rosterLockedFor = (targetStageId: string | number): boolean => {
  const s = stageOptions.value.find((x) => String(x.id) === String(targetStageId));
  return !!s && s.status !== 'DRAFT';
};

const removeExit = async (e: ExitEntry) => {
  try {
    await ElMessageBox.confirm(`确认移除「${stageNameOf(e.targetStageId)} · ${ruleText(e.g)}」这条出口？`, '移除出口', {
      type: 'warning',
      confirmButtonText: '移除',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  try {
    await removeRosterGroup(e.targetStageId, e.groupId);
    ElMessage.success('出口已移除');
    await load();
  } catch (err: any) {
    notifyError(err, '移除失败');
  }
};

onMounted(load);
watch(() => [props.stage?.id, props.stage?.ruleConfig, props.stage?.status], load);
</script>
