<template>
  <div class="bg-black border border-neutral-700 rounded-lg p-4 space-y-3">
    <div class="flex items-center justify-between">
      <label class="text-xs text-neutral-500">
        出口去向
        <span class="text-[11px] text-neutral-600 ml-1">
          {{ knockoutMode ? '(胜者/败者去向;不配置时默认胜者进入下一赛段)'
            : isAudition ? '(可按圈分别配置)' : '(本赛段结果送入下游名单)' }}
        </span>
      </label>
      <span class="text-[11px] text-neutral-600">{{ entries.length }} 条</span>
    </div>

    <div v-if="!editable" class="text-[11px] text-neutral-600">
      赛段已开始或已初始化，出口配置已锁定；如需调整请先重置该赛段。
    </div>
    <div v-else-if="targetOptions.length === 0" class="text-[11px] text-neutral-600">
      暂无可承接的赛段（下游赛段需处于规划中且未初始化）
    </div>

    <!-- 出口列表 -->
    <div v-if="entries.length > 0" class="space-y-1.5">
      <div
        v-for="e in entries"
        :key="String(e.targetStageId) + '-' + e.groupIndex"
        class="flex items-center gap-2 rounded bg-neutral-900/70 border border-neutral-800 px-2.5 py-1.5"
      >
        <span class="text-[11px] text-amber-500/90 flex-none">{{ stageNameOf(e.targetStageId) }}</span>
        <span class="text-[11px] text-neutral-300 flex-1 min-w-0 truncate">{{ ruleText(e.g) }}</span>
        <button
          v-if="editable"
          class="px-1.5 py-1 text-[11px] rounded border border-neutral-700 text-neutral-400 hover:text-amber-400 hover:border-amber-500/40 transition-colors flex-none"
          @click="openEdit(e)"
        >
          编辑
        </button>
        <button
          v-if="editable"
          class="px-1.5 py-1 text-[11px] rounded border border-neutral-700 text-neutral-500 hover:text-red-400 hover:border-red-900/40 transition-colors flex-none"
          @click="removeExit(e)"
        >
          移除
        </button>
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
          <select v-model="form.targetStageId" :disabled="editing" class="cfg-select w-full">
            <option v-for="t in targetOptions" :key="String(t.id)" :value="t.id">{{ t.name }}</option>
          </select>
        </div>
        <div>
          <label class="text-[11px] text-neutral-600 block mb-1">结果</label>
          <select v-model="form.resultFilter" class="cfg-select w-full">
            <option value="ADVANCE">{{ knockoutMode ? '胜者' : '晋级' }}</option>
            <option value="ELIMINATED">{{ knockoutMode ? '败者' : '落选' }}</option>
            <option v-if="!knockoutMode" value="ANY">不限</option>
          </select>
        </div>
      </div>
      <div v-if="isAudition && !editing">
        <label class="text-[11px] text-neutral-600 block mb-1">圈</label>
        <select v-model="form.zone" class="cfg-select w-full">
          <option value="__all__">全部 {{ circleCount }} 圈（同样规则）</option>
          <option v-for="z in zoneOptions" :key="z" :value="z">第 {{ z.replace('ZONE-', '') }} 圈</option>
        </select>
      </div>
      <!-- 淘汰赛(晋级赛)只分胜负,不涉及名次匹配 -->
      <div v-if="!knockoutMode">
        <label class="text-[11px] text-neutral-600 block mb-1">名次</label>
        <select v-model="form.rankMode" class="cfg-select w-full">
          <option value="none">不限名次（全部）</option>
          <option value="range">指定名次段</option>
        </select>
      </div>
      <div v-if="form.rankMode === 'range'" class="grid grid-cols-2 gap-2">
        <input v-model.number="form.rankStart" type="number" min="1" class="cfg-input w-full" placeholder="起，如 9" />
        <input v-model.number="form.rankEnd" type="number" min="1" class="cfg-input w-full" placeholder="止，如 24（空=末名）" />
      </div>
      <p v-if="isAudition && form.rankMode === 'range'" class="text-[11px] text-amber-500/80">
        海选按「{{ form.zone === '__all__' ? '各圈' : '第' + String(form.zone).replace('ZONE-', '') + '圈' }}内名次」取人
      </p>
      <div class="flex justify-end gap-2">
        <button class="px-3 py-1.5 text-[11px] rounded border border-neutral-700 text-neutral-400 hover:bg-neutral-800 transition-colors" @click="closeForm">
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
import { listRostersBySource, addRosterGroups, removeRosterGroup, updateRosterGroup } from '@/api/game/stage/roster';

const props = defineProps<{
  stage: StageData;
}>();

interface ExitEntry {
  targetStageId: string | number;
  groupIndex: number;
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
const zoneOptions = computed(() => Array.from({ length: circleCount.value }, (_, i) => 'ZONE-' + (i + 1)));

/** 出口写在"目标赛段"上:来源侧始终可配置,目标侧限制在 targetOptions(规划中未初始化) */
const editable = computed(() => {
  const s = props.stage;
  return !!s?.id && String(s.id) !== 'temp';
});

const stageNameOf = (id: string | number | null | undefined): string =>
  stageOptions.value.find((s) => String(s.id) === String(id))?.name || ('赛段 #' + id);

/** 可选去向:沿 next 链位于本赛段之后,且仍为规划中未初始化 */
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
    if ((next.status === 'DRAFT' || next.status === 'PENDING') && Number(next.isInitialized) !== 1) {
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
    ? (g.resultFilter === 'ELIMINATED' ? '败者' : g.resultFilter === 'ADVANCE' ? '胜者' : '不限')
    : (g.resultFilter === 'ELIMINATED' ? '落选' : g.resultFilter === 'ADVANCE' ? '晋级' : '不限');
  // 淘汰赛/晋级赛只分胜负,不展示名次
  if (knockoutMode.value) {
    return result;
  }
  const zone = isAudition.value && g.zone ? `第${String(g.zone).replace('ZONE-', '')}圈 · ` : '';
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
        (r.groups || []).forEach((g: any, gi: number) => {
          if (String(g.sourceStageId) === String(props.stage.id)) {
            list.push({
              targetStageId: r.targetStageId ?? r.id,
              groupIndex: gi,
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
    ElMessage.error(e?.response?.data?.msg || e?.message || '出口加载失败');
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
  form.zone = isAudition.value && circleCount.value > 1 ? 'ZONE-1' : '__all__';
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
      await updateRosterGroup(editEntry.value.targetStageId, editEntry.value.groupIndex, group);
      ElMessage.success('出口已更新');
    } else {
      const zones = isAudition.value
        ? (form.zone === '__all__' ? zoneOptions.value : [String(form.zone)])
        : [null];
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
    ElMessage.error(e?.response?.data?.msg || e?.message || '出口保存失败');
  } finally {
    saving.value = false;
  }
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
    await removeRosterGroup(e.targetStageId, e.groupIndex);
    ElMessage.success('出口已移除');
    await load();
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.msg || err?.message || '移除失败');
  }
};

onMounted(load);
watch(() => [props.stage?.id, props.stage?.ruleConfig, props.stage?.status], load);
</script>
