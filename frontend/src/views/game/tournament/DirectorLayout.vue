<template>
  <div class="flex flex-col h-full">
    <section class="h-32 sm:h-40 flex-none bg-neutral-925 border-b border-neutral-800 flex items-center px-2 sm:px-4 overflow-y-hidden bg-[#0a0a0a]">
      <!-- 左侧：屏幕列表区域 -->
      <div class="flex items-center justify-center gap-4 overflow-x-auto custom-scrollbar-x flex-1">
        <div
          v-for="screen in store.screens"
          :key="screen.id"
          class="flex-none w-48 sm:w-64 h-28 sm:h-32 bg-black rounded-lg border relative group flex flex-col transition-colors"
          :class="[
            dragOverScreenId === screen.id
              ? 'border-amber-500 bg-neutral-800 scale-[1.02] shadow-[0_0_20px_rgba(245,158,11,0.3)]'
              : 'border-neutral-800'
          ]"
          @dragover="handleSceneDragOver($event, screen.id)"
          @dragleave="handleSceneDragLeave"
          @drop="handleSceneDropOnScreen($event, screen.id)"
        >
          <button
            @click.stop="deleteScreen(screen.id)"
            class="absolute top-1.5 right-1.5 w-6 h-6 bg-neutral-700 hover:bg-red-500 text-white rounded-full flex items-center justify-center opacity-0 group-hover:opacity-100 transition-all hover:scale-110 z-[60] shadow-lg"
            title="删除屏幕"
          >
            <X class="w-4 h-4" />
          </button>

          <div class="h-8 bg-neutral-900 border-b border-neutral-800 flex items-center justify-between px-3 relative rounded-t-lg shrink-0">
            <div class="flex items-center gap-2">
              <span class="w-2 h-2 rounded-full" :class="screen.status === 'ONLINE' ? 'bg-green-500 shadow-[0_0_5px_lime]' : 'bg-neutral-600'"></span>
              <span class="text-xs font-medium text-neutral-300">{{ screen.name }}</span>
            </div>
            <svg v-if="screen.currentSceneId" class="w-3 h-3 text-amber-500 animate-pulse" fill="currentColor" viewBox="0 0 20 20">
              <path
                d="M2 6a2 2 0 012-2h6a2 2 0 012 2v8a2 2 0 01-2 2H4a2 2 0 01-2-2V6zM14.553 7.106A1 1 0 0014 8v4a1 1 0 00.553.894l2 1A1 1 0 0018 13V7a1 1 0 00-1.447-.894l-2 1z"
              />
            </svg>
          </div>

          <!-- 内部容器处理场景拖拽到屏幕 -->
          <div class="flex-1 flex flex-col items-center justify-center bg-neutral-900/30 relative z-10 overflow-hidden rounded-b-lg">
            <div v-if="screen.status === 'OFFLINE' && !screen.currentSceneId" class="flex flex-col items-center opacity-50 pointer-events-none">
              <svg class="w-8 h-8 text-neutral-600 mb-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636"
                />
              </svg>
              <span class="text-[10px] text-neutral-500">OFFLINE</span>
            </div>

            <!-- 有场景投射时显示缩略图 -->
            <div
              v-else-if="screen.currentSceneId"
              class="absolute inset-2 rounded overflow-hidden bg-black flex items-center justify-center"
              :class="screen.status === 'OFFLINE' ? 'opacity-60' : ''"
            >
              <SceneThumbnail
                :thumbnailData="sceneThumbnails[screen.currentSceneId]"
                :width="getSceneById(screen.currentSceneId)?.width"
                :height="getSceneById(screen.currentSceneId)?.height"
                class="max-w-full max-h-full"
              />

              <!-- 场景名称遮罩 -->
              <div class="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/80 to-transparent p-2">
                <div class="flex items-center justify-between">
                  <div class="flex-1 min-w-0">
                    <div v-if="screen.status === 'OFFLINE'" class="flex items-center gap-1 mb-0.5">
                      <svg class="w-2.5 h-2.5 text-neutral-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                      </svg>
                      <span class="text-[8px] text-neutral-500">等待上线</span>
                    </div>
                    <div class="text-amber-500 text-[10px] font-bold truncate">{{ store.getSceneName(screen.currentSceneId) }}</div>
                    <div class="text-[8px] font-mono text-neutral-500">{{ screen.status === 'OFFLINE' ? 'PENDING' : 'LIVE' }}</div>
                  </div>
                </div>
              </div>
            </div>

            <span v-else class="text-xs text-neutral-600 pointer-events-none">无场景投射</span>
          </div>

          <div
            class="absolute inset-0 bg-neutral-900/90 opacity-0 group-hover:opacity-100 transition-opacity flex flex-col items-center justify-center gap-2 backdrop-blur-sm z-30 pointer-events-none rounded-lg"
          >
            <button
              @click.stop="openProjectionWindow(screen.id)"
              class="px-3 py-1.5 bg-neutral-700 hover:bg-neutral-600 text-white text-xs font-bold rounded shadow border border-neutral-600 flex items-center gap-2 transform hover:scale-105 transition-all pointer-events-auto"
              title="在新窗口打开此屏幕页面"
            >
              <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14"
                />
              </svg>
              打开监视器
            </button>

            <button
              @click="projectCurrentScene(screen.id)"
              class="px-3 py-1.5 bg-amber-600 hover:bg-amber-500 text-white text-xs font-bold rounded shadow-lg shadow-amber-900/50 flex items-center gap-2 transform hover:scale-105 transition-all pointer-events-auto"
            >
              <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z"
                />
              </svg>
              投射当前场景
            </button>

            <span class="text-[10px] text-neutral-400 mt-1">或拖拽下方场景至此</span>
          </div>

          <!-- 拖拽提示层 -->
          <div
            v-if="dragOverScreenId === screen.id"
            v-show="dragHintVisibleScreenId === screen.id"
            class="drag-hint-overlay absolute inset-0 bg-amber-500/10 flex items-center justify-center border-2 border-amber-500/50 border-dashed rounded-lg z-50 pointer-events-none"
            style="filter: drop-shadow(0 0 8px rgba(245, 158, 11, 0.6)) drop-shadow(0 0 16px rgba(245, 158, 11, 0.4))"
          >
            <div class="flex flex-col items-center gap-2">
              <svg class="w-10 h-10 text-amber-500/70" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z"
                />
              </svg>
              <span class="text-amber-500/80 font-medium text-sm">释放以投射</span>
              <span class="text-amber-400/70 text-[10px]">{{
                store.getSceneName(draggedSceneIndex !== null ? store.scenes[draggedSceneIndex]?.id : null) || '拖拽场景至此'
              }}</span>
            </div>
          </div>
        </div>

        <button
          @click="store.addScreen"
          class="flex-none w-20 sm:w-24 h-28 sm:h-32 rounded-lg border-2 border-dashed border-neutral-800 bg-[#0a0a0a] flex flex-col items-center justify-center gap-2 text-neutral-600 hover:text-amber-500 hover:border-amber-500/50 hover:bg-amber-500/5 transition-all cursor-pointer active:scale-95 group"
          title="添加新屏幕"
        >
          <span class="w-10 h-10 rounded-full border-2 border-current flex items-center justify-center group-hover:scale-110 transition-transform">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
            </svg>
          </span>
          <span class="text-[11px] font-medium">添加屏幕</span>
        </button>
      </div>
    </section>

    <main class="flex-1 flex overflow-hidden min-h-0 relative">
      <!-- 移动端浮层遮罩 -->
      <div v-if="mobilePanel" class="absolute inset-0 bg-black/50 z-30 lg:hidden" @click="mobilePanel = null"></div>

      <!-- 左侧:组件库(移动端浮层) -->
      <aside
        class="w-64 flex-none border-r border-neutral-800 z-40 bg-neutral-900 shadow-2xl lg:shadow-none transition-transform duration-200"
        :class="mobilePanel === 'toolbox' ? 'absolute inset-y-0 left-0' : 'hidden lg:block'"
      >
        <WidgetToolbox />
      </aside>

      <div ref="canvasContainer" class="flex-1 min-w-0 bg-black relative overflow-hidden group">
        <!-- 移动端:组件库/配置开关 -->
        <div class="absolute top-2 left-2 z-30 flex gap-1.5 lg:hidden">
          <button
            @click="toggleMobilePanel('toolbox')"
            class="px-2.5 py-1.5 rounded-lg text-[11px] font-bold flex items-center gap-1 bg-neutral-800/90 backdrop-blur border transition-colors"
            :class="mobilePanel === 'toolbox' ? 'border-amber-500 text-amber-400' : 'border-neutral-700 text-neutral-300'"
          >
            <Package class="w-3.5 h-3.5" /> 组件
          </button>
          <button
            @click="toggleMobilePanel('property')"
            class="px-2.5 py-1.5 rounded-lg text-[11px] font-bold flex items-center gap-1 bg-neutral-800/90 backdrop-blur border transition-colors"
            :class="mobilePanel === 'property' ? 'border-amber-500 text-amber-400' : 'border-neutral-700 text-neutral-300'"
          >
            <Settings2 class="w-3.5 h-3.5" /> 配置
          </button>
        </div>

        <div
          class="absolute top-12 sm:top-4 left-1/2 -translate-x-1/2 bg-neutral-800/90 backdrop-blur border border-neutral-700 rounded-full px-2.5 sm:px-4 py-1 sm:py-1.5 flex items-center gap-1.5 sm:gap-4 z-20 shadow-xl opacity-0 group-hover:opacity-100 transition-opacity duration-300 delay-100"
        >
          <span class="text-[10px] sm:text-xs text-neutral-400 font-mono">{{ store.currentScene?.width || 1920 }} x {{ store.currentScene?.height || 1080 }}</span>
          <div class="w-px h-3 bg-neutral-600"></div>
          <div class="flex items-center gap-2">
            <button @click="adjustScale(-0.05)" class="hover:text-amber-500 active:scale-90 transition-transform">
              <svg class="w-3 h-3 sm:w-4 sm:h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20 12H4" />
              </svg>
            </button>
            <span class="text-[10px] sm:text-xs font-bold w-9 sm:w-12 text-center tabular-nums">{{ Math.round(currentScaleValue * 100) }}%</span>
            <button @click="adjustScale(0.05)" class="hover:text-amber-500 active:scale-90 transition-transform">
              <svg class="w-3 h-3 sm:w-4 sm:h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
              </svg>
            </button>
          </div>
          <div class="w-px h-3 bg-neutral-600"></div>
          <button @click="autoFitCanvas" class="text-[10px] sm:text-xs hover:text-amber-500" title="自适应窗口">Fit</button>
        </div>

        <div class="absolute inset-0">
          <SceneRenderer ref="mainEditorRef" :sceneConfig="sceneConfig" mode="edit" :sceneId="store.currentSceneId" />
        </div>
      </div>

      <!-- 右侧:组件配置(移动端浮层) -->
      <aside
        class="w-72 flex-none border-l border-neutral-800 z-40 bg-neutral-900 shadow-2xl lg:shadow-none transition-transform duration-200"
        :class="mobilePanel === 'property' ? 'absolute inset-y-0 right-0' : 'hidden lg:block'"
      >
        <PropertyPanel @widget-updated="handleWidgetUpdated" />
      </aside>
    </main>

    <!-- 隐藏的缩略图生成容器 -->
    <div v-if="thumbnailSceneConfig" style="position: fixed; left: 0; top: 0; overflow: hidden; opacity: 0; pointer-events: none; z-index: -9999">
      <div
        :style="{
          width: `${thumbnailContainerSize.width}px`,
          height: `${thumbnailContainerSize.height}px`,
          position: 'absolute',
          left: '0',
          top: '0'
        }"
      >
        <SceneRenderer ref="thumbnailGeneratorRef" :sceneConfig="thumbnailSceneConfig" mode="edit" :sceneId="thumbnailTargetSceneId" />
      </div>
    </div>

    <footer class="h-32 sm:h-36 flex-none bg-neutral-900 border-t border-neutral-800 flex flex-col z-20 shadow-[0_-5px_20px_rgba(0,0,0,0.3)]">
      <div class="h-8 bg-neutral-900 border-b border-neutral-800 flex items-center justify-between px-4">
        <span class="text-[10px] font-bold text-neutral-500 tracking-wider uppercase">场景</span>
        <div class="flex items-center gap-2">
          <div class="flex items-center gap-1">
            <button
              @click="handleUndo"
              :disabled="!store.canUndo"
              :title="store.canUndo ? `撤销 ${store.undoLabel} (Ctrl+Z)` : '没有可撤销的操作'"
              class="w-6 h-6 rounded flex items-center justify-center transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
              :class="store.canUndo ? 'text-neutral-400 hover:text-amber-400 hover:bg-neutral-800' : ''"
            >
              <Undo2 class="w-3.5 h-3.5" />
            </button>
            <button
              @click="handleRedo"
              :disabled="!store.canRedo"
              :title="store.canRedo ? `重做 ${store.redoLabel} (Ctrl+Shift+Z)` : '没有可重做的操作'"
              class="w-6 h-6 rounded flex items-center justify-center transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
              :class="store.canRedo ? 'text-neutral-400 hover:text-amber-400 hover:bg-neutral-800' : ''"
            >
              <Redo2 class="w-3.5 h-3.5" />
            </button>
          </div>
          <span class="text-[10px] text-neutral-600">Total: {{ store.scenes.length }}</span>
        </div>
      </div>

      <div class="flex-1 overflow-x-auto flex items-center gap-3 px-4 custom-scrollbar-x bg-[#0a0a0a]">
        <div
          v-for="(scene, index) in store.scenes"
          :key="scene.id"
          draggable="true"
          @click="store.switchScene(scene.id)"
          @dragstart="handleSceneReorderDragStart($event, index)"
          @dragover.prevent="handleSceneReorderDragOver($event, index)"
          @drop="handleSceneReorderDrop($event, index)"
          @dragend="handleSceneReorderDragEnd"
          :class="[
            'flex-none w-36 sm:w-48 h-16 sm:h-20 rounded-lg border-2 relative cursor-pointer group transition-all duration-200 overflow-hidden',
            scene.id === store.currentSceneId
              ? 'border-amber-500 bg-neutral-800 shadow-lg shadow-amber-900/20 scale-[1.02]'
              : 'border-neutral-800 bg-neutral-900 hover:border-neutral-600 hover:bg-neutral-850',
            draggedSceneIndex === index ? 'opacity-50 scale-95' : '',
            dragOverSceneIndex === index && draggedSceneIndex !== index ? 'border-amber-500 border-dashed scale-105' : ''
          ]"
        >
          <!-- 场景缩略图 -->
          <div class="absolute inset-0 m-1 rounded overflow-hidden bg-black flex items-center justify-center">
            <SceneThumbnail :thumbnailData="sceneThumbnails[scene.id]" :width="scene.width" :height="scene.height" class="max-w-full max-h-full" />
          </div>

          <!-- 投射至屏幕按钮 - 仅在单屏幕时hover显示 -->
          <button
            v-if="store.screens.length === 1"
            @click.stop="projectSceneToFirstScreen(scene.id)"
            class="absolute bottom-2 right-2 w-7 h-7 bg-amber-600 hover:bg-amber-500 text-white rounded-full flex items-center justify-center opacity-0 group-hover:opacity-100 transition-all hover:scale-110 shadow-lg z-20"
            title="投射至屏幕"
          >
            <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z"
              />
            </svg>
          </button>

          <div
            class="absolute bottom-0 left-0 right-0 h-6 bg-neutral-900/95 backdrop-blur border-t border-neutral-700/50 flex items-center px-2 rounded-b-md"
            :class="{ 'pr-10': store.screens.length === 1 }"
          >
            <span
              class="text-[10px] font-bold truncate transition-colors"
              :class="scene.id === store.currentSceneId ? 'text-amber-500' : 'text-neutral-400 group-hover:text-neutral-200'"
            >
              {{ scene.name }}
            </span>
          </div>
        </div>

        <div
          @click="createNewScene"
          class="flex-none w-16 sm:w-20 h-16 sm:h-20 rounded-lg border-2 border-dashed border-neutral-800 flex flex-col items-center justify-center text-neutral-600 hover:text-amber-500 hover:border-amber-500/50 hover:bg-amber-500/5 transition-all cursor-pointer active:scale-95"
        >
          <svg class="w-6 h-6 mb-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
          </svg>
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useDirectorStore } from '@/store/modules/directorStore'; // 确保路径正确
import { useRouter } from 'vue-router';
import { X, Package, Settings2, Undo2, Redo2 } from 'lucide-vue-next';

const router = useRouter();

// 引入拆分好的子组件
import WidgetToolbox from './WidgetToolbox.vue';
import SceneRenderer from './SceneRenderer.vue';
import SceneThumbnail from './SceneThumbnail.vue';
import PropertyPanel from './PropertyPanel.vue';
import { ElMessage, ElMessageBox } from 'element-plus';

const store = useDirectorStore();
/** 撤销/重做(Ctrl+Z 键盘监听放在父页面,避免 KeepAlive 下切到其他页签仍响应) */
const handleUndo = async () => {
  try {
    const ok = await store.undo();
    if (ok) ElMessage.success(`已撤销：${store.redoLabel || '上一步'}`);
  } catch (e) {
    ElMessage.error(e?.message || '撤销失败');
  }
};

const handleRedo = async () => {
  try {
    const ok = await store.redo();
    if (ok) ElMessage.success(`已重做：${store.undoLabel || '下一步'}`);
  } catch (e) {
    ElMessage.error(e?.message || '重做失败');
  }
};

/** 移动端:左右面板浮层开关('toolbox' | 'property' | null) */
const mobilePanel = ref(null);
const toggleMobilePanel = (panel) => {
  mobilePanel.value = mobilePanel.value === panel ? null : panel;
};
const dragOverScreenId = ref(null);
const canvasContainer = ref(null);
const mainEditorRef = ref(null);
const thumbnailGeneratorRef = ref(null);

// 处理组件更新事件，更新缩略图
const handleWidgetUpdated = () => {
  if (mainEditorRef.value?.debouncedCreateThumbnail) {
    mainEditorRef.value.debouncedCreateThumbnail();
  }
};

// 缓存场景查找结果,避免重复计算
const getSceneById = (sceneId) => {
  return store.scenes.find((s) => s.id === sceneId);
};

// 用于防止同一屏幕内重复触发动画
const dragHintVisibleScreenId = ref(null);

// 屏幕拖拽排序状态
const draggedScreenIndex = ref(null);
const dragOverScreenIndex = ref(null);

// 场景拖拽排序状态
const draggedSceneIndex = ref(null);
const dragOverSceneIndex = ref(null);

// SceneRenderer 的缩放控制 (手动覆盖自动计算的缩放)
const manualScale = ref(null);
const autoScale = ref(0.9); // 存储自动计算的缩放值

// 获取当前显示的缩放比例
const currentScaleValue = computed(() => {
  if (manualScale.value !== null) return manualScale.value;
  return autoScale.value;
});

// 更新自动缩放值
const updateAutoScale = () => {
  if (!canvasContainer.value) {
    autoScale.value = 0.9;
    return;
  }

  const containerWidth = canvasContainer.value.clientWidth;
  const containerHeight = canvasContainer.value.clientHeight;
  const sceneWidth = store.currentScene?.width || 1920;
  const sceneHeight = store.currentScene?.height || 1080;

  // 与 SceneRenderer 中的计算公式一致
  autoScale.value = Math.min(containerWidth / sceneWidth, containerHeight / sceneHeight) * 0.9;
};

// 调整缩放
const adjustScale = (delta) => {
  const newScale = Math.max(0.1, Math.min(3.0, currentScaleValue.value + delta));
  manualScale.value = newScale;
};

// 自适应窗口
const autoFitCanvas = () => {
  manualScale.value = null; // 清除手动缩放,让 SceneRenderer 自动计算
};

// 计算场景配置,添加自定义缩放
const sceneConfig = computed(() => {
  if (!store.currentScene) {
    return {
      width: 1920,
      height: 1080,
      widgets: []
    };
  }
  return {
    ...store.currentScene,
    manualScale: manualScale.value // 传递手动缩放值
  };
});

// 创建稳定的缩略图映射，避免所有 SceneThumbnail 组件重新渲染
const sceneThumbnails = computed(() => {
  const thumbnails = {};
  for (const scene of store.scenes) {
    thumbnails[scene.id] = store.getSceneThumbnail(scene.id);
  }
  return thumbnails;
});

// 场景拖拽排序 - 开始拖拽
const handleSceneReorderDragStart = (e, index) => {
  draggedSceneIndex.value = index;
  e.dataTransfer.effectAllowed = 'move';
  // 同时设置两种数据：索引用于排序，场景ID用于投射到屏幕
  e.dataTransfer.setData('text/plain', index.toString());
  e.dataTransfer.setData('scene-id', store.scenes[index].id.toString());
};

// 场景拖拽排序 - 拖拽经过
const handleSceneReorderDragOver = (_e, index) => {
  if (draggedSceneIndex.value === null) return;
  dragOverSceneIndex.value = index;
};

// 场景拖拽排序 - 放下
const handleSceneReorderDrop = (e, targetIndex) => {
  e.preventDefault();
  const fromIndex = draggedSceneIndex.value;

  if (fromIndex !== null && fromIndex !== targetIndex) {
    // 重新排列场景数组
    const scenes = [...store.scenes];
    const [removedScene] = scenes.splice(fromIndex, 1);
    scenes.splice(targetIndex, 0, removedScene);

    // 更新 store 中的场景顺序
    store.scenes = scenes;
  }

  handleSceneReorderDragEnd();
};

// 场景拖拽排序 - 结束拖拽
const handleSceneReorderDragEnd = () => {
  draggedSceneIndex.value = null;
  dragOverSceneIndex.value = null;
};

// 屏幕拖拽排序 - 开始拖拽
const handleScreenReorderDragStart = (e, index) => {
  draggedScreenIndex.value = index;
  e.dataTransfer.effectAllowed = 'move';
  e.dataTransfer.setData('screen-reorder', index.toString());
};

// 屏幕拖拽排序 - 拖拽经过
const handleScreenReorderDragOver = (_e, index) => {
  if (draggedScreenIndex.value === null) return;
  dragOverScreenIndex.value = index;
};

// 屏幕拖拽排序 - 放下
const handleScreenReorderDrop = (e, targetIndex) => {
  e.preventDefault();
  const fromIndex = draggedScreenIndex.value;

  if (fromIndex !== null && fromIndex !== targetIndex) {
    // 重新排列屏幕数组
    const screens = [...store.screens];
    const [removedScreen] = screens.splice(fromIndex, 1);
    screens.splice(targetIndex, 0, removedScreen);

    // 更新 store 中的屏幕顺序
    store.screens = screens;
  }

  handleScreenReorderDragEnd();
};

// 屏幕拖拽排序 - 结束拖拽
const handleScreenReorderDragEnd = () => {
  draggedScreenIndex.value = null;
  dragOverScreenIndex.value = null;
};

// 删除屏幕
const deleteScreen = async (screenId) => {
  try {
    await ElMessageBox.confirm('确定要删除这个屏幕吗？', '删除屏幕', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  try {
    await store.deleteScreen(screenId);
  } catch (error) {
    console.error('删除屏幕失败:', error);
    ElMessage.error(error.message || '删除屏幕失败，请稍后重试');
  }
};

// 场景拖拽到屏幕 - 拖拽经过
const handleSceneDragOver = (e, screenId) => {
  e.preventDefault();

  // 只在切换到不同屏幕时才更新，避免重复触发动画
  if (dragOverScreenId.value !== screenId) {
    dragOverScreenId.value = screenId;
    dragHintVisibleScreenId.value = screenId;
  }
};

// 场景拖拽到屏幕 - 放下
const handleSceneDropOnScreen = async (e, screenId) => {
  e.preventDefault();
  e.stopPropagation();

  // 获取场景ID（保持原始类型，不转换）
  const sceneId = e.dataTransfer.getData('scene-id');

  if (sceneId) {
    dragOverScreenId.value = null;
    dragHintVisibleScreenId.value = null;
    store.projectScene(screenId, sceneId);

    // 如果该场景还没有缩略图，立即生成
    if (!store.getSceneThumbnail(sceneId)) {
      console.log(`[handleSceneDropOnScreen] 场景 ${sceneId} 无缩略图，开始生成...`);

      // 设置要生成缩略图的场景
      thumbnailTargetSceneId.value = sceneId;

      // 等待 Vue 响应式更新
      await new Promise((resolve) => setTimeout(resolve, 100));

      // 等待组件挂载
      let attempts = 0;
      while (!thumbnailGeneratorRef.value && attempts < 10) {
        await new Promise((resolve) => setTimeout(resolve, 100));
        attempts++;
      }

      if (thumbnailGeneratorRef.value) {
        // 等待渲染完成
        await new Promise((resolve) => setTimeout(resolve, 500));
        await thumbnailGeneratorRef.value.createThumbnail();
        console.log(`[handleSceneDropOnScreen] 场景 ${sceneId} 缩略图生成完成`);
      }

      // 清空
      thumbnailTargetSceneId.value = null;
    }
  }
};

// 场景拖拽到屏幕 - 离开屏幕
const handleSceneDragLeave = () => {
  // 延迟清空，避免在屏幕内部移动时闪烁
  setTimeout(() => {
    if (!dragOverScreenId.value) {
      dragHintVisibleScreenId.value = null;
    }
  }, 100);
};

// --- 业务逻辑 ---

const createNewScene = async () => {
  const newScene = {
    name: `新场景 ${store.scenes.length + 1}`,
    width: 1920,
    height: 1080,
    bgColor: '#000000',
    widgets: []
  };

  try {
    // 调用 API 添加场景
    const createdScene = await store.addScene(newScene);
    if (createdScene) {
      // 自动切换到新场景
      store.switchScene(createdScene.id);
    }
  } catch (error) {
    console.error('创建场景失败:', error);
    // 显示错误提示
    ElMessage.error(error.message || '创建场景失败，请稍后重试');
  }
};

const openProjectionWindow = (screenId) => {
  // 解析路由，假设你的路由名称为 'ProjectionScreen'
  const routeData = router.resolve({
    name: 'ProjectionView', // 需确保 router/index.ts 里有配置这个 name
    query: {
      screenId: screenId // 传入 UUID
    }
  });

  // 打开新窗口，设置为全屏模式的参数 (无工具栏、无菜单栏)
  window.open(routeData.href, '_blank', 'width=1920,height=1080,menubar=no,toolbar=no,location=no,status=no,resizable=yes,scrollbars=no');
};

const projectCurrentScene = (screenId) => {
  store.projectScene(screenId, store.currentSceneId);
};

// 投射场景到第一个屏幕（用于单屏快捷投射）
const projectSceneToFirstScreen = (sceneId) => {
  if (store.screens.length > 0) {
    store.projectScene(store.screens[0].id, sceneId);
  }
};

// 监听容器尺寸变化
let resizeObserver = null;

onMounted(async () => {
  updateAutoScale();

  if (canvasContainer.value) {
    resizeObserver = new ResizeObserver(() => {
      requestAnimationFrame(updateAutoScale);
    });
    resizeObserver.observe(canvasContainer.value);
  }

  window.addEventListener('resize', updateAutoScale);

  // 等待场景和组件加载完成后，为所有场景生成缩略图
  await generateAllThumbnails();
});

// 缩略图生成器的场景配置
const thumbnailTargetSceneId = ref(null);
const thumbnailSceneConfig = computed(() => {
  const scene = store.scenes.find((s) => s.id === thumbnailTargetSceneId.value);
  if (!scene) {
    return null; // 返回 null 而不是空对象,防止渲染无效的 Canvas
  }
  return {
    ...scene,
    manualScale: 0.25 // 使用 1/4 的缩放比例
  };
});

// 缩略图容器尺寸(场景尺寸的 1/4)
const thumbnailContainerSize = computed(() => {
  const scene = store.scenes.find((s) => s.id === thumbnailTargetSceneId.value);
  if (!scene) {
    return { width: 480, height: 270 }; // 默认值
  }
  return {
    width: scene.width / 4,
    height: scene.height / 4
  };
});

// 为所有场景生成缩略图（在隐藏的容器中进行）
const generateAllThumbnails = async () => {
  // 等待 DOM 完全渲染
  await new Promise((resolve) => setTimeout(resolve, 500));

  // 保存当前场景ID
  const originalSceneId = store.currentSceneId;

  try {
    // 为每个场景生成缩略图
    for (const scene of store.scenes) {
      try {
        // 确保有组件才生成
        if (scene.widgets && scene.widgets.length > 0 && scene.width > 0 && scene.height > 0) {
          console.log(`[generateAllThumbnails] 正在为场景 "${scene.name}" (${scene.width}x${scene.height}) 生成缩略图...`);

          // 设置要生成缩略图的场景ID
          thumbnailTargetSceneId.value = scene.id;

          // 等待 Vue 响应式更新（使用 nextTick 确保 DOM 更新）
          await new Promise((resolve) => setTimeout(resolve, 100));

          // 多次检查引用是否存在，等待组件挂载
          let attempts = 0;
          while (!thumbnailGeneratorRef.value && attempts < 10) {
            await new Promise((resolve) => setTimeout(resolve, 100));
            attempts++;
          }

          if (!thumbnailGeneratorRef.value) {
            console.warn('[generateAllThumbnails] thumbnailGeneratorRef 未就绪，跳过场景:', scene.name);
            continue;
          }

          // 等待隐藏的 SceneRenderer 完全渲染
          await new Promise((resolve) => setTimeout(resolve, 500));

          // 生成缩略图（使用非防抖版本，立即执行）
          await thumbnailGeneratorRef.value.createThumbnail();

          // 等待缩略图生成完成并保存
          await new Promise((resolve) => setTimeout(resolve, 300));

          console.log(`[generateAllThumbnails] 场景 "${scene.name}" 缩略图生成完成`);
        } else {
          console.log(`[generateAllThumbnails] 跳过场景 "${scene.name}": 无组件或尺寸无效`);
        }
      } catch (error) {
        console.error(`[generateAllThumbnails] 为场景 "${scene.name}" 生成缩略图失败:`, error);
        // 继续处理下一个场景
      }
    }
  } finally {
    // 清空缩略图生成器的场景
    thumbnailTargetSceneId.value = null;

    // 恢复到原来的场景
    if (originalSceneId) {
      store.switchScene(originalSceneId);
    }

    console.log('[generateAllThumbnails] 所有场景缩略图生成完成');
  }
};

onUnmounted(() => {
  if (resizeObserver) {
    resizeObserver.disconnect();
  }
  window.removeEventListener('resize', updateAutoScale);
});
</script>

<style>
/* 注意：这里去掉了 scoped，目的是为了让 body 的滚动条也生效。
  如果你只想让当前组件内的某个容器生效，可以加 scoped 并指定类名。
*/

/* --- WebKit 浏览器 (Chrome, Safari, Edge) --- */

/* 1. 滚动条整体宽度 */
::-webkit-scrollbar {
  width: 8px; /* 纵向滚动条宽度 */
  height: 8px; /* 横向滚动条高度 */
}

/* 2. 滚动条轨道 (背景) */
::-webkit-scrollbar-track {
  background: #0a0a0a; /* 对应 Tailwind neutral-950 */
  border-radius: 4px;
}

/* 3. 滚动条滑块 (句柄) */
::-webkit-scrollbar-thumb {
  background: #0a0a0a; /* 对应 Tailwind neutral-700 */
  border-radius: 4px;
}

/* 4. 滑块悬停状态 - 变成点缀色 Amber */
::-webkit-scrollbar-thumb:hover {
  background: #d97706; /* 对应 Tailwind amber-600 */
}

/* --- Firefox 浏览器 --- */
html {
  /* 颜色: 滑块 轨道 */
  scrollbar-color: #404040 #0a0a0a;
  scrollbar-width: thin; /* 变细 */
}

/* 拖拽提示淡入动画 */
.drag-hint-overlay {
  animation: fadeIn 0.2s ease-out;
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}
</style>
