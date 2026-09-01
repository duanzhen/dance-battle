<template>
  <div class="flex flex-col h-full bg-neutral-900 border-l border-neutral-800">
    <!-- 选项卡按钮:整体长条圆角矩形,内部接缝直角(全局 button 默认 8px 圆角需 rounded-none 覆盖) -->
    <div class="border-b border-neutral-800 p-1.5">
      <div class="flex rounded-lg overflow-hidden bg-neutral-800/60">
        <!-- 组件按钮 - 仅在选中组件时显示 -->
        <button
          v-if="widget"
          @click="activeTab = 'widget'"
          :class="[
            'flex-1 py-2.5 text-xs font-bold rounded-none transition-colors bg-transparent',
            activeTab === 'widget' ? 'text-amber-500' : 'text-neutral-500 hover:text-neutral-300'
          ]"
        >
          组件
        </button>

        <!-- 场景按钮 -->
        <button
          @click="activeTab = 'scene'"
          :class="[
            'flex-1 py-2.5 text-xs font-bold rounded-none transition-colors bg-transparent',
            activeTab === 'scene' ? 'text-amber-500' : 'text-neutral-500 hover:text-neutral-300'
          ]"
        >
          场景
        </button>

        <!-- 图层按钮 -->
        <button
          @click="activeTab = 'layers'"
          :class="[
            'flex-1 py-2.5 text-xs font-bold rounded-none transition-colors bg-transparent',
            activeTab === 'layers' ? 'text-amber-500' : 'text-neutral-500 hover:text-neutral-300'
          ]"
        >
          图层
        </button>
      </div>
    </div>

    <div class="flex-1 overflow-y-auto custom-scrollbar-y">
      <!-- 组件属性面板 -->
      <div v-show="activeTab === 'widget'">
        <template v-if="widget">
          <div class="px-4 space-y-6">
            <div class="flex items-center gap-2 mb-2 pb-4 border-b border-neutral-800">
              <div class="w-8 h-8 rounded bg-amber-500/10 flex items-center justify-center text-amber-500 font-bold text-xs">
                {{ widget.type.substring(0, 2) }}
              </div>
              <div class="flex-1">
                <input
                  v-model="widget.name"
                  :disabled="isLocked"
                  class="w-full bg-transparent text-sm font-bold text-white focus:outline-none border-b border-transparent focus:border-amber-500 disabled:opacity-40 disabled:cursor-not-allowed"
                />
                <!-- <div class="text-[10px] text-neutral-500">ID: {{ widget.id }}</div> -->
              </div>
              <!-- 显示/隐藏组件 -->
              <button
                @click="toggleVisible(widget)"
                :disabled="isLocked"
                :title="widget.visible === 0 || widget.visible === false ? '显示组件' : '隐藏组件'"
                class="flex-none w-8 h-8 rounded flex items-center justify-center transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                :class="
                  widget.visible === 0 || widget.visible === false
                    ? 'bg-neutral-800 text-neutral-500 hover:text-neutral-300'
                    : 'bg-amber-500/10 text-amber-500 hover:bg-amber-500/20'
                "
              >
                <svg v-if="widget.visible === 0 || widget.visible === false" class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M3.98 8.223A10.477 10.477 0 001.934 12C3.226 16.338 7.244 19.5 12 19.5c.993 0 1.953-.138 2.863-.395M6.228 6.228A10.45 10.45 0 0112 4.5c4.756 0 8.773 3.162 10.065 7.498a10.523 10.523 0 01-4.293 5.774M6.228 6.228L3 3m3.228 3.228l3.65 3.65m7.894 7.894L21 21m-3.228-3.228l-3.65-3.65m0 0a3 3 0 10-4.243-4.243m4.242 4.242L9.88 9.88"
                  />
                </svg>
                <svg v-else class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                  />
                  <circle cx="12" cy="12" r="3" />
                </svg>
              </button>
            </div>

            <div
              v-if="isLocked"
              class="flex items-center gap-2 px-3 py-2 rounded-lg bg-amber-500/10 border border-amber-500/20 text-amber-400 text-[11px] font-medium"
            >
              <svg class="w-3.5 h-3.5 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
                />
              </svg>
              已锁定，解锁后才能编辑位置、大小与内容
            </div>

            <section>
              <div class="flex items-center justify-between mb-2"><span class="section-title">布局 (Layout)</span></div>
              <div class="grid grid-cols-2 gap-3">
                <NumberInput label="X" v-model="widget.x" :disabled="isLocked" />
                <NumberInput label="Y" v-model="widget.y" :disabled="isLocked" />
                <NumberInput label="W" v-model="widget.w" :disabled="isLocked" />
                <NumberInput label="H" v-model="widget.h" :disabled="isLocked" />
                <NumberInput label="R" :modelValue="0" suffix="°" disabled />
                <NumberInput label="Z" v-model="widget.z" :disabled="isLocked" />
              </div>
              <button
                @click="openWidgetFullscreen"
                class="w-full mt-3 py-2 border border-amber-900/50 text-amber-500 bg-amber-500/5 hover:bg-amber-500/10 rounded text-xs font-bold transition-colors"
              >
                全屏展示组件
              </button>
            </section>

            <!-- Widget 特有属性编辑器 -->
            <section class="border-t border-neutral-800 pt-4">
              <div class="flex items-center justify-between mb-3">
                <span class="section-title">组件属性</span>
              </div>

              <!-- 动态渲染 widget 的编辑模式 -->
              <div
                class="widget-editor-container bg-neutral-950 rounded-lg p-4 border border-neutral-800 min-h-[200px]"
                :class="isLocked ? 'pointer-events-none opacity-50' : ''"
              >
                <component
                  :is="widgetComponentMap[widget.type]"
                  v-bind="widgetProps"
                  :mode="'edit'"
                  @update:src="handleUpdateProp('src', $event)"
                  @update:text="handleUpdateProp('text', $event)"
                  @update:fontSize="handleUpdateProp('fontSize', $event)"
                  @update:color="handleUpdateProp('color', $event)"
                  @update:fontWeight="handleUpdateProp('fontWeight', $event)"
                  @update:textAlign="handleUpdateProp('textAlign', $event)"
                  @update:loop="handleUpdateProp('loop', $event)"
                  @update:muted="handleUpdateProp('muted', $event)"
                  @update:autoplay="handleUpdateProp('autoplay', $event)"
                  @update:volume="handleUpdateProp('volume', $event)"
                  @update:title="handleUpdateProp('title', $event)"
                  @update:hours="handleUpdateProp('hours', $event)"
                  @update:minutes="handleUpdateProp('minutes', $event)"
                  @update:seconds="handleUpdateProp('seconds', $event)"
                  @update:milliseconds="handleUpdateProp('milliseconds', $event)"
                  @update:showTitle="handleUpdateProp('showTitle', $event)"
                  @update:showMilliseconds="handleUpdateProp('showMilliseconds', $event)"
                  @update:stageId="handleUpdateProp('stageId', $event)"
                  @update:matchId="handleUpdateProp('matchId', $event)"
                  @update:showCurrent="handleUpdateProp('showCurrent', $event)"
                  @update:bgImage="handleUpdateProp('bgImage', $event)"
                  @update:showScore="handleUpdateProp('showScore', $event)"
                  @update:opacity="handleUpdateProp('opacity', $event)"
                  @update:textColor="handleUpdateProp('textColor', $event)"
                  @update:borderColor="handleUpdateProp('borderColor', $event)"
                  @update:bgColor="handleUpdateProp('bgColor', $event)"
                />
              </div>
            </section>

            <div class="pt-4 mt-4 border-t border-neutral-800">
              <button
                @click="deleteSelected"
                :disabled="isLocked"
                class="w-full py-2 border border-red-900/50 text-red-500 bg-red-500/5 hover:bg-red-500/10 rounded text-xs font-bold transition-colors disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-red-500/5"
              >
                删除组件
              </button>
            </div>
          </div>
        </template>
      </div>

      <!-- 场景属性面板 -->
      <div v-show="activeTab === 'scene'">
        <div class="px-6 h-full flex flex-col">
          <div class="space-y-5" v-if="scene">
            <div class="space-y-1">
              <label class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider">名称</label>
              <input
                v-model="scene.name"
                class="w-full bg-neutral-950 border border-neutral-800 rounded px-2 py-2 text-xs text-white focus:border-amber-500 focus:outline-none transition-colors"
              />
            </div>

            <div class="space-y-1">
              <label class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider">分辨率</label>
              <div class="grid grid-cols-2 gap-3">
                <NumberInput label="宽" v-model="scene.width" suffix="px" />
                <NumberInput label="高" v-model="scene.height" suffix="px" />
              </div>

              <!-- 常用预设 -->
              <div class="text-[10px] text-neutral-600 mt-2 mb-1">常用预设</div>
              <div class="grid grid-cols-3 gap-2">
                <button
                  @click="setRes(1920, 1080)"
                  class="py-1.5 text-[10px] bg-neutral-800 hover:bg-neutral-700 text-neutral-400 rounded border border-neutral-700 transition-colors"
                  title="1920x1080 - Full HD"
                >
                  1080p
                </button>
                <button
                  @click="setRes(1080, 1920)"
                  class="py-1.5 text-[10px] bg-neutral-800 hover:bg-neutral-700 text-neutral-400 rounded border border-neutral-700 transition-colors"
                  title="1080x1920 - 竖屏"
                >
                  竖屏
                </button>
                <button
                  @click="setRes(3840, 2160)"
                  class="py-1.5 text-[10px] bg-neutral-800 hover:bg-neutral-700 text-neutral-400 rounded border border-neutral-700 transition-colors"
                  title="3840x2160 - 4K UHD"
                >
                  4K
                </button>
                <button
                  @click="setRes(2560, 1440)"
                  class="py-1.5 text-[10px] bg-neutral-800 hover:bg-neutral-700 text-neutral-400 rounded border border-neutral-700 transition-colors"
                  title="2560x1440 - 2K QHD"
                >
                  2K
                </button>
                <button
                  @click="setRes(1280, 720)"
                  class="py-1.5 text-[10px] bg-neutral-800 hover:bg-neutral-700 text-neutral-400 rounded border border-neutral-700 transition-colors"
                  title="1280x720 - HD"
                >
                  720p
                </button>
                <button
                  @click="setRes(7680, 4320)"
                  class="py-1.5 text-[10px] bg-neutral-800 hover:bg-neutral-700 text-neutral-400 rounded border border-neutral-700 transition-colors"
                  title="7680x4320 - 8K"
                >
                  8K
                </button>
              </div>

              <!-- 自定义宽高比 -->
              <div class="text-[10px] text-neutral-600 mt-3 mb-1">按比例设置</div>
              <div class="grid grid-cols-4 gap-2">
                <button
                  @click="setAspectRatio(16, 9)"
                  class="py-1.5 text-[10px] bg-neutral-800 hover:bg-neutral-700 text-neutral-400 rounded border border-neutral-700 transition-colors"
                  title="16:9 横屏"
                >
                  16:9
                </button>
                <button
                  @click="setAspectRatio(9, 16)"
                  class="py-1.5 text-[10px] bg-neutral-800 hover:bg-neutral-700 text-neutral-400 rounded border border-neutral-700 transition-colors"
                  title="9:16 竖屏"
                >
                  9:16
                </button>
                <button
                  @click="setAspectRatio(4, 3)"
                  class="py-1.5 text-[10px] bg-neutral-800 hover:bg-neutral-700 text-neutral-400 rounded border border-neutral-700 transition-colors"
                  title="4:3 传统"
                >
                  4:3
                </button>
                <button
                  @click="setAspectRatio(21, 9)"
                  class="py-1.5 text-[10px] bg-neutral-800 hover:bg-neutral-700 text-neutral-400 rounded border border-neutral-700 transition-colors"
                  title="21:9 超宽屏"
                >
                  21:9
                </button>
              </div>
            </div>

            <div class="space-y-1.5">
              <label class="text-[10px] font-bold text-neutral-500 uppercase tracking-wider">背景颜色</label>
              <div class="flex items-center gap-2 bg-neutral-950 border border-neutral-800 rounded p-2">
                <input type="color" v-model="scene.bgColor" class="w-6 h-6 rounded bg-transparent border-none cursor-pointer p-0" />
                <input
                  type="text"
                  v-model="scene.bgColor"
                  class="flex-1 bg-transparent text-xs font-mono text-neutral-400 focus:outline-none uppercase"
                />
              </div>
            </div>

            <!-- 删除场景按钮 -->
            <div class="pt-4 mt-4 border-t border-neutral-800">
              <button
                @click="handleDeleteScene"
                class="w-full py-2 border border-red-900/50 text-red-500 bg-red-500/5 hover:bg-red-500/10 rounded text-xs font-bold transition-colors flex items-center justify-center gap-2"
              >
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                </svg>
                删除场景
              </button>
            </div>
          </div>

          <div v-else class="flex flex-col items-center justify-center text-neutral-500 mb-8 mt-4">
            <div class="w-16 h-16 rounded-2xl bg-neutral-800 border border-neutral-700 flex items-center justify-center mb-4 shadow-inner">
              <svg class="w-8 h-8 opacity-50" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"
                />
              </svg>
            </div>
            <p class="text-sm font-bold text-neutral-300">场景设置</p>
            <p class="text-xs text-neutral-600 mt-1">Global Scene Settings</p>
          </div>
        </div>
      </div>

      <!-- 图层面板 -->
      <div v-show="activeTab === 'layers'" class="p-2">
        <div
          ref="layerListRef"
          class="relative"
          @dragover.prevent="onListDragOver"
          @drop.stop.prevent="onDrop"
        >
          <!-- 图层行:独立容器承载 space-y,指示线作为绝对定位兄弟,不会挤动行布局 -->
          <div class="space-y-1">
            <template v-for="layer in reversedWidgets" :key="layer.id">
              <div
                data-layer-row
                :draggable="dragHandleId === layer.id && !layer.locked"
                @click="store.selectWidget(layer.id)"
                @dblclick="openLayerWidget(layer)"
                @dragstart="onDragStart(layer.id)"
                @dragend="onDragEnd"
                class="flex items-center gap-2 p-2 rounded group select-none transition-colors border"
                :class="[
                  dragId === layer.id ? 'opacity-40 border-amber-500/50' : store.selectedWidgetId === layer.id ? 'bg-amber-500/10 border-amber-500/30' : 'border-transparent hover:bg-neutral-800'
                ]"
              >
                <button @click.stop="toggleVisible(layer)" class="bg-transparent text-neutral-600 hover:text-neutral-300 p-1">
                  <svg v-if="layer.visible !== 0 && layer.visible !== false" class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 5 8.268 7.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                    />
                  </svg>
                  <svg v-else class="w-3.5 h-3.5 text-neutral-700" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"
                    />
                  </svg>
                </button>

                <div class="w-5 h-5 rounded flex items-center justify-center text-[10px] font-bold text-neutral-500 bg-transparent border border-neutral-700">
                  {{ layer.type.charAt(0) }}
                </div>

                <span class="text-xs text-neutral-300 flex-1 truncate font-medium" :class="{ 'text-amber-500': store.selectedWidgetId === layer.id }">
                  {{ layer.name || layer.type }}
                </span>

                <button @click.stop="toggleLock(layer)" class="bg-transparent text-neutral-600 hover:text-neutral-300 p-1">
                  <svg v-if="layer.locked" class="w-3 h-3 text-amber-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
                    />
                  </svg>
                  <svg v-else class="w-3 h-3 opacity-0 group-hover:opacity-50" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M8 11V7a4 4 0 118 0m-4 8v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2z"
                    />
                  </svg>
                </button>
                <div class="flex flex-col opacity-0 group-hover:opacity-100 cursor-move" @mousedown.stop="dragHandleId = layer.id" @mouseup="dragHandleId = null">
                  <button @click.stop="moveLayer(layer, 'up')" class="bg-transparent text-neutral-600 hover:text-amber-500 text-[10px] leading-none" title="上移图层">▲</button>
                  <button @click.stop="moveLayer(layer, 'down')" class="bg-transparent text-neutral-600 hover:text-amber-500 text-[10px] leading-none" title="下移图层">▼</button>
                </div>
              </div>
            </template>

            <div v-if="reversedWidgets.length === 0" class="text-center py-8 text-neutral-600 text-xs">暂无图层</div>
          </div>

          <!-- 拖拽放置指示线:绝对定位覆盖,出现/消失不影响其他图层位置 -->
          <div
            v-if="dragId && dropLineTop != null"
            class="absolute left-0 right-0 z-10 pointer-events-none"
            :style="{ top: dropLineTop + 'px' }"
          >
            <div class="h-0.5 bg-amber-500 shadow-[0_0_6px_rgba(245,158,11,0.8)]"></div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useDirectorStore } from '@/store/modules/directorStore';
import { moveWidgetLayer, reorderWidgets } from '@/api/game/visWidget';
import { ElMessage } from 'element-plus';
import NumberInput from './NumberInput.vue';
import ImageWidget from './widgets/ImageWidget.vue';
import VideoWidget from './widgets/VideoWidget.vue';
import TextWidget from './widgets/TextWidget.vue';
import TimerWidget from './widgets/TimerWidget.vue';
import BracketWidget from './widgets/BracketWidget.vue';
import MatchDetailWidget from './widgets/MatchDetailWidget.vue';
import ScoreboardWidget from './widgets/ScoreboardWidget.vue';
import ArenaWidget from './widgets/ArenaWidget.vue';
import RankingWidget from './widgets/RankingWidget.vue';
import AuditionWidget from './widgets/AuditionWidget.vue';

const emit = defineEmits(['widgetUpdated']);

const store = useDirectorStore();
const activeTab = ref('scene'); // 默认显示场景选项卡

// Widget 组件映射 (用于编辑模式)
const widgetComponentMap = {
  'IMAGE': ImageWidget,
  'VIDEO': VideoWidget,
  'TEXT': TextWidget,
  'TIMER': TimerWidget,
  'BRACKET': BracketWidget,
  'MATCH_DETAIL': MatchDetailWidget,
  'SCOREBOARD': ScoreboardWidget,
  'ARENA_SCORE': ArenaWidget,
  'AUDITION': AuditionWidget,
  'RANKING': RankingWidget
};

const widget = computed(() => store.selectedWidget);
const scene = computed(() => store.currentScene);
const isLocked = computed(() => widget.value?.locked === true);

// 解析 widget 的 dataConfig 为 props
const widgetProps = computed(() => {
  if (!widget.value) return {};
  try {
    return JSON.parse(widget.value.dataConfig || '{}');
  } catch {
    return {};
  }
});

// 监听选中状态变化，自动切换选项卡
watch(
  () => store.selectedWidgetId,
  (newVal, oldVal) => {
    if (newVal === null && oldVal) {
      // 从选中变为未选中，切换到场景选项卡
      activeTab.value = 'scene';
    }
  }
);

// 监听组件布局属性变化 (x, y, w, h, z) - 仅在 PropertyPanel 中手动修改时触发
// 移除 watch,让 SceneRenderer 专门处理位置大小的更新

// 处理属性更新 - 使用防抖避免频繁请求
let updateTimer = null;
let isUpdatingProp = false;
const updatePropQueue = new Map(); // 使用队列来合并更新

const handleUpdateProp = async (key, value) => {
  if (!widget.value || isLocked.value) return;

  // 将更新加入队列
  updatePropQueue.set(key, value);

  // 清除之前的定时器
  if (updateTimer) {
    clearTimeout(updateTimer);
  }

  // 延迟执行,等待用户停止编辑
  updateTimer = setTimeout(async () => {
    if (!widget.value || isUpdatingProp) return;

    // 获取所有待更新的属性
    const updates = Object.fromEntries(updatePropQueue);
    updatePropQueue.clear();

    isUpdatingProp = true;

    let dataConfig = {};
    try {
      dataConfig = widget.value.dataConfig ? JSON.parse(widget.value.dataConfig) : {};
    } catch (e) {
      dataConfig = {};
    }

    // 合并所有更新
    Object.assign(dataConfig, updates);

    console.log('[PropertyPanel] 属性更新,准备提交:', { id: widget.value.id, updates });

    try {
      // 调用 API 更新（直接传递对象，store 会处理 stringify）
      await store.updateWidget(widget.value.id, {
        dataConfig: dataConfig
      });

      // 通知父组件更新缩略图
      emit('widgetUpdated');
    } finally {
      // 延迟重置标志
      setTimeout(() => {
        isUpdatingProp = false;
      }, 100);
    }
  }, 500); // 500ms 防抖延迟
};

// 图层列表:按 zIndex 降序(z 大在上层,显示在列表顶部)
const reversedWidgets = computed(() => {
  if (!store.currentScene) return [];
  return [...store.currentScene.widgets].sort((a, b) => (b.z || 1) - (a.z || 1));
});

const deleteSelected = async () => {
  if (!store.currentScene || !store.selectedWidgetId) return;
  if (isLocked.value) {
    ElMessage.warning('已锁定的控件不能删除，请先解锁');
    return;
  }
  await store.deleteWidget(store.selectedWidgetId);
};

// 快捷设置分辨率
const setRes = (w, h) => {
  if (scene.value) {
    scene.value.width = w;
    scene.value.height = h;
  }
};

// 按比例设置分辨率 (保持当前宽度,调整高度以匹配比例)
const setAspectRatio = (ratioW, ratioH) => {
  if (scene.value) {
    const currentWidth = scene.value.width || 1920;
    scene.value.height = Math.round(currentWidth * (ratioH / ratioW));
  }
};

const toggleVisible = async (w) => {
  // visible 为 number(1/0)或 boolean;当前隐藏(0/false)则显示,否则隐藏
  const isHidden = w.visible === 0 || w.visible === false;
  await store.updateWidget(w.id, {
    visible: isHidden ? 1 : 0
  });
  // 触发缩略图更新
  emit('widgetUpdated');
};

const toggleLock = async (w) => {
  const newLocked = !w.locked;
  await store.updateWidget(w.id, {
    locked: newLocked ? 1 : 0
  });
  // 触发缩略图更新
  emit('widgetUpdated');
};

// 双击图层:选中该组件并跳转到组件配置选项卡
const openLayerWidget = (layer) => {
  store.selectWidget(layer.id);
  activeTab.value = 'widget';
};

// 图层排序:调后端原子交换(按场景加锁),成功后前端同步交换并强制刷新
const moveLayer = async (w, dir) => {
  if (w.locked) {
    ElMessage.warning('已锁定的控件不能调整图层顺序，请先解锁');
    return;
  }
  const ordered = [...store.currentScene.widgets].sort((a, b) => (b.z || 1) - (a.z || 1));
  const curIdx = ordered.findIndex((x) => String(x.id) === String(w.id));
  const neighbor = dir === 'up' ? ordered[curIdx - 1] : ordered[curIdx + 1];
  if (neighbor?.locked) {
    ElMessage.warning('相邻控件已锁定，请先解锁后再调整图层顺序');
    return;
  }
  try {
    await moveWidgetLayer(w.id, dir);
  } catch (e: any) {
    console.error('图层排序失败', e);
    ElMessage.error(e?.msg || e?.message || '图层排序失败');
    return;
  }
  // 后端已原子交换;前端同步交换 z 并用新数组引用强制触发响应式重排
  const idx = ordered.findIndex((x) => String(x.id) === String(w.id));
  const sw = dir === 'up' ? ordered[idx - 1] : ordered[idx + 1];
  if (sw) {
    const tz = w.z;
    w.z = sw.z;
    sw.z = tz;
    store.currentScene.widgets = [...store.currentScene.widgets];
  }
  emit('widgetUpdated');
};

// ===== 拖动排序(HTML5 draggable)=====
const dragId = ref(null);
const dropIndex = ref(null); // 悬停间隙:0..len(0=列表顶部, len=末尾)
const dropLineTop = ref(null); // 指示线相对列表容器顶部的 Y(px),绝对定位不影响布局
const dragHandleId = ref(null);
const layerListRef = ref(null);

/** 被拖拽控件当前所在下标(用于排除原位间隙) */
const dragFromIdx = computed(() => {
  if (!dragId.value) return -1;
  return reversedWidgets.value.findIndex((w) => String(w.id) === String(dragId.value));
});

const onDragStart = (id) => {
  const w = store.currentScene?.widgets.find((x) => String(x.id) === String(id));
  if (w?.locked) {
    ElMessage.warning('已锁定的控件不能拖动排序，请先解锁');
    return;
  }
  dragId.value = id;
};

/** 列表内拖动悬停:按鼠标所在行的上下半区实时计算插入间隙,并贴边自动滚动 */
const onListDragOver = (e) => {
  if (!dragId.value) return;
  e.preventDefault();
  const list = layerListRef.value;
  const rows = list ? Array.from(list.querySelectorAll('[data-layer-row]')) : [];
  const listRect = list.getBoundingClientRect();
  let gap = rows.length; // 默认末尾
  for (let i = 0; i < rows.length; i++) {
    const rect = rows[i].getBoundingClientRect();
    if (e.clientY < rect.top + rect.height / 2) {
      gap = i;
      break;
    }
  }
  dropIndex.value = gap;
  // 拖到控件自身所在间隙(落回原位)时不显示指示线
  const from = dragFromIdx.value;
  if (gap === from || gap === from + 1) {
    dropLineTop.value = null;
  } else {
    dropLineTop.value = gapLineTop(rows, listRect, gap);
  }
  maybeAutoScroll(e, list);
};

/** 计算间隙在列表容器内的 Y 坐标:首行上方/行间中点/末尾下方 */
const gapLineTop = (rows, listRect, gap) => {
  let top;
  if (rows.length === 0) {
    top = 0;
  } else if (gap === 0) {
    const r0 = rows[0].getBoundingClientRect();
    top = r0.top - listRect.top - 2;
  } else if (gap === rows.length) {
    const rn = rows[rows.length - 1].getBoundingClientRect();
    top = rn.bottom - listRect.top + 2;
  } else {
    const prev = rows[gap - 1].getBoundingClientRect();
    const next = rows[gap].getBoundingClientRect();
    top = (prev.bottom + next.top) / 2 - listRect.top;
  }
  // 限制在容器可见范围内
  return Math.max(0, Math.min(top, listRect.height - 2));
};

/** 拖到列表上下边缘时自动滚动,便于拖到长列表两端 */
const maybeAutoScroll = (e, list) => {
  const scroller = list?.closest('.custom-scrollbar-y');
  if (!scroller) return;
  const rect = scroller.getBoundingClientRect();
  const threshold = 48;
  if (e.clientY < rect.top + threshold) {
    scroller.scrollTop -= 14;
  } else if (e.clientY > rect.bottom - threshold) {
    scroller.scrollTop += 14;
  }
};

const onDragEnd = () => {
  dragId.value = null;
  dropIndex.value = null;
  dropLineTop.value = null;
  dragHandleId.value = null;
};

const onDrop = async () => {
  const srcId = dragId.value;
  const gap = dropIndex.value;
  onDragEnd();
  if (!srcId || gap == null) return;

  const arr = [...reversedWidgets.value];
  const fromIdx = arr.findIndex((w) => String(w.id) === String(srcId));
  if (fromIdx < 0) return;

  // 间隙换算为"移除后"的插入下标
  let targetIdx = gap;
  if (targetIdx > fromIdx) targetIdx -= 1;
  if (targetIdx === fromIdx) return; // 落回原位,无需变更

  const [moved] = arr.splice(fromIdx, 1);
  arr.splice(targetIdx, 0, moved);
  if (arr.some((w) => w.locked)) {
    ElMessage.warning('存在已锁定的控件，请先解锁后再拖动排序');
    return;
  }
  const widgetIds = arr.map((w) => w.id);
  const sceneId = store.currentScene?.id;
  try {
    await reorderWidgets(sceneId, widgetIds);
  } catch (e) {
    ElMessage.error('拖动排序失败');
    return;
  }
  // 前端按新顺序重排 z(arr 上 = z 大)
  arr.forEach((w, i) => {
    w.z = arr.length - i;
  });
  store.currentScene.widgets = [...store.currentScene.widgets];
};

// 全屏展示组件
const openWidgetFullscreen = async () => {
  if (!widget.value || !scene.value) return;

  // 调用 API 更新组件位置和尺寸（使用专门的 updateWidgetPosition 方法）
  await store.updateWidgetPosition(widget.value.id, 0, 0, scene.value.width, scene.value.height, undefined);

  // 通知父组件更新缩略图
  emit('widgetUpdated');
};

// 删除场景
const handleDeleteScene = async () => {
  if (!scene.value) return;

  // 确认对话框
  if (store.scenes.length <= 1) {
    alert('至少需要保留一个场景');
    return;
  }

  const confirmed = confirm(`确定要删除场景 "${scene.value.name}" 吗？此操作不可恢复。`);
  if (!confirmed) return;

  try {
    await store.deleteScene(scene.value.id);
  } catch (error) {
    console.error('删除场景失败:', error);
    alert('删除场景失败,请稍后重试');
  }
};
</script>

<style scoped>
.section-title {
  @apply text-xs font-bold text-neutral-400;
}
</style>
