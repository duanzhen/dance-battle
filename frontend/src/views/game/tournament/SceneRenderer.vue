<template>
  <div
    ref="containerRef"
    class="overflow-hidden select-none"
    :class="{
      'fixed inset-0 w-screen h-screen bg-black': !editable && props.mode !== 'thumbnail',
      'w-full h-full flex items-center justify-center': editable || props.mode === 'thumbnail'
    }"
    @mousedown="handleStageClick"
  >
    <!-- 正常渲染模式 -->
    <div ref="stageRef" class="will-change-transform relative" :class="{ 'shadow-2xl ring-1 ring-white/10': editable }" :style="stageStyle">
      <!-- 调试信息 -->
      <!-- <div v-if="editable" class="absolute top-2 left-2 bg-red-500 text-white text-xs px-2 py-1 z-[9999]">
        Widgets: {{ sceneConfig.widgets?.length || 0 }}
      </div> -->

      <div
        v-for="widgets in sceneConfig.widgets" v-show="widgets.visible !== 0 && widgets.visible !== false"
        :key="widgets.id"
        class="absolute group"
        :style="{
          left: `${widgets.x}px`,
          top: `${widgets.y}px`,
          width: `${widgets.w}px`,
          height: `${widgets.h}px`,
          zIndex: widgets.z || 1,
          cursor: editable ? 'move' : 'default',
          border: editable ? '1px dashed rgba(255,0,0,0.3)' : 'none'
        }"
        @mousedown.stop="(e) => handleElementMouseDown(e, widgets)"
      >
        <component
          :is="componentMap[widgets.type]"
          :ref="(el: any) => setWidgetRef(el, widgets.id)"
          v-bind="widgetProps(widgets)"
          class="w-full h-full"
        />

        <span
          v-if="editable && widgets.locked"
          class="absolute top-1.5 right-1.5 z-40 w-5 h-5 rounded bg-neutral-900/80 border border-amber-600/40 flex items-center justify-center"
          title="已锁定"
        >
          <svg class="w-3 h-3 text-amber-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
            />
          </svg>
        </span>

        <template v-if="editable && !widgets.locked && selectedId === widgets.id">
          <div class="absolute inset-0 border-2 border-blue-500 pointer-events-none z-50"></div>

          <div
            class="absolute -right-1.5 -bottom-1.5 w-4 h-4 bg-white border-2 border-blue-500 rounded-full cursor-nwse-resize z-50 hover:scale-125 transition-transform"
            @mousedown.stop="(e) => handleResizeMouseDown(e, widgets, 'se')"
          ></div>
          <div
            class="absolute -right-1.5 -top-1.5 w-4 h-4 bg-white border-2 border-blue-500 rounded-full cursor-nesw-resize z-50 hover:scale-125 transition-transform"
            @mousedown.stop="(e) => handleResizeMouseDown(e, widgets, 'ne')"
          ></div>
          <div
            class="absolute -left-1.5 -bottom-1.5 w-4 h-4 bg-white border-2 border-blue-500 rounded-full cursor-nesw-resize z-50 hover:scale-125 transition-transform"
            @mousedown.stop="(e) => handleResizeMouseDown(e, widgets, 'sw')"
          ></div>
          <div
            class="absolute -left-1.5 -top-1.5 w-4 h-4 bg-white border-2 border-blue-500 rounded-full cursor-nwse-resize z-50 hover:scale-125 transition-transform"
            @mousedown.stop="(e) => handleResizeMouseDown(e, widgets, 'nw')"
          ></div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed, watch, type CSSProperties } from 'vue';
import { useRoute } from 'vue-router';
import { useDirectorStore } from '@/store/modules/directorStore';
import BracketWidget from './widgets/BracketWidget.vue';
import MatchDetailWidget from './widgets/MatchDetailWidget.vue';
import ImageWidget from './widgets/ImageWidget.vue';
import VideoWidget from './widgets/VideoWidget.vue';
import TextWidget from './widgets/TextWidget.vue';
import TimerWidget from './widgets/TimerWidget.vue';
import ScoreboardWidget from './widgets/ScoreboardWidget.vue';
import ArenaWidget from './widgets/ArenaWidget.vue';
import html2canvas from 'html2canvas';

const componentMap: Record<string, any> = {
  'IMAGE': ImageWidget,
  'VIDEO': VideoWidget,
  'TEXT': TextWidget,
  'TIMER': TimerWidget,
  'BRACKET': BracketWidget,
  'MATCH_DETAIL': MatchDetailWidget,
  'SCOREBOARD': ScoreboardWidget,
  'ARENA_SCORE': ArenaWidget
};

export interface SceneElement {
  id: string;
  name: string;
  type: string;
  x: number;
  y: number;
  w: number;
  h: number;
  z?: number;
  visible?: boolean;
  locked?: boolean;
  dataConfig: string; // JSON 字符串
  renderConfig?: string; // JSON 字符串
}

export interface SceneConfig {
  width: number;
  height: number;
  widgets: SceneElement[];
  thumbnailData?: string | null; // 缩略图数据
  tournamentId?: string | number; // 赛事ID(大屏投射时使用)
}

// 渲染模式枚举
export type RenderMode = 'edit' | 'render';

const props = defineProps<{
  sceneConfig: SceneConfig & { manualScale?: number | null }; // 支持手动缩放
  mode?: RenderMode; // 渲染模式：edit=编辑模式, render=正常渲染, thumbnail=缩略图模式
  sceneId?: number | string; // 场景ID，用于更新缩略图到 store
  tournamentId?: string | number; // 赛事ID(大屏投射时由场景提供;编辑态回退路由参数)
}>();

const store = useDirectorStore(); // 获取 store
const route = useRoute();

// 计算是否为编辑模式（向后兼容）
const editable = computed(() => props.mode === 'edit');

// 从 sceneConfig 中读取缩略图数据（缩略图模式使用）
const thumbnailData = computed(() => props.sceneConfig.thumbnailData);

// 解析 widget 的 dataConfig 为 props
const widgetProps = (widget: SceneElement) => {
  try {
    const parsed = JSON.parse(widget.dataConfig || '{}');
    // 统一注入 tournamentId:优先组件配置 → 场景/路由,保证大屏投射窗口也能拿到赛事上下文
    return {
      ...parsed,
      tournamentId: parsed.tournamentId ?? props.tournamentId ?? route.query.id ?? route.query.tournamentId ?? undefined
    };
  } catch {
    return { tournamentId: props.tournamentId ?? route.query.id ?? route.query.tournamentId ?? undefined };
  }
};

// --- 基础逻辑 ---
const widgetRefs = ref<Record<string, any>>({});
const setWidgetRef = (el: any, id: string) => {
  if (el) widgetRefs.value[id] = el;
};

// --- 缩放与布局核心逻辑 ---
const containerRef = ref<HTMLElement | null>(null);
const stageRef = ref<HTMLElement | null>(null);
const scaleX = ref(1);
const scaleY = ref(1);
const translateX = ref(0); // 编辑模式下的居中偏移
const translateY = ref(0);

const updateScale = () => {
  if (!containerRef.value) {
    console.warn('[updateScale] containerRef 未就绪');
    return;
  }

  // 检查场景配置是否有效
  if (!props.sceneConfig || props.sceneConfig.width <= 0 || props.sceneConfig.height <= 0) {
    console.warn('[updateScale] 场景配置无效:', props.sceneConfig);
    scaleX.value = 1;
    scaleY.value = 1;
    return;
  }

  // 直接使用 containerRef 的尺寸,因为它是 w-full h-full
  const cw = containerRef.value.clientWidth;
  const ch = containerRef.value.clientHeight;
  const dw = props.sceneConfig.width;
  const dh = props.sceneConfig.height;

  if (editable.value) {
    // [编辑模式]：使用手动缩放或自动计算
    if (props.sceneConfig.manualScale !== null && props.sceneConfig.manualScale !== undefined) {
      scaleX.value = props.sceneConfig.manualScale;
      scaleY.value = props.sceneConfig.manualScale;
    } else {
      // 自动计算缩放
      const scale = Math.min(cw / dw, ch / dh) * 0.9; // *0.9 为了留一点边距好看
      scaleX.value = scale;
      scaleY.value = scale;
    }
  } else {
    // [预览模式]：暴力拉伸 (Fill)
    scaleX.value = cw / dw;
    scaleY.value = ch / dh;
  }

  console.log('[updateScale] 缩放更新:', {
    containerSize: `${cw}x${ch}`,
    sceneSize: `${dw}x${dh}`,
    scale: `${scaleX.value.toFixed(3)}x${scaleY.value.toFixed(3)}`
  });
};

// 计算舞台的动态样式
const stageStyle = computed<CSSProperties>(() => {
  const base = {
    width: `${props.sceneConfig.width}px`,
    height: `${props.sceneConfig.height}px`,
    transform: `scale(${scaleX.value}, ${scaleY.value})`,
    backgroundColor: editable.value ? '#171717' : 'transparent', // 编辑模式给个背景色
    transformOrigin: 'center center', // 从中心缩放,配合 flex 居中
    flexShrink: '0' as any // 防止 flex 子元素收缩
  };

  if (editable.value) {
    // 编辑模式：Flex 居中，无需 absolute 定位
    return { ...base };
  } else {
    // 预览模式：绝对定位铺满
    return { ...base, position: 'absolute', left: '0px', top: '0px', transformOrigin: 'top left' };
  }
});

// --- 编辑交互逻辑 (Drag & Resize) ---
const selectedId = computed({
  get: () => {
    console.log('[SceneRenderer] getter - store.selectedWidgetId:', store.selectedWidgetId, 'type:', typeof store.selectedWidgetId);
    return store.selectedWidgetId;
  },
  set: (value) => {
    console.log('[SceneRenderer] setter - value:', value, 'type:', typeof value);
    store.selectWidget(value);
  }
});

const handleStageClick = (e: MouseEvent) => {
  // 点击空白处取消选中
  if (editable.value) {
    // 检查点击的是否是 widget 或其子元素
    const target = e.target as HTMLElement;
    const widgetElement = target.closest('.group');

    console.log('[SceneRenderer] handleStageClick - isWidget:', !!widgetElement);

    // 如果点击的不是 widget，才取消选中
    if (!widgetElement) {
      console.log('[SceneRenderer] 点击空白区域，取消选中');
      selectedId.value = null;
    } else {
      console.log('[SceneRenderer] 点击了 widget，不取消选中');
    }
  }
};

// 1. 拖拽移动 (Move)
const handleElementMouseDown = (e: MouseEvent, element: SceneElement) => {
  if (!editable.value || element.locked) return;

  console.log('[SceneRenderer] handleElementMouseDown - element.id:', element.id, 'type:', typeof element.id);
  console.log('[SceneRenderer] handleElementMouseDown - element.id === store.selectedWidgetId:', element.id === store.selectedWidgetId);

  // 设置选中的 widget ID 到 store
  selectedId.value = element.id;

  const startX = e.clientX;
  const startY = e.clientY;
  const startLeft = element.x;
  const startTop = element.y;

  const onMove = (me: MouseEvent) => {
    // 核心：移动距离 / 缩放比例 = 逻辑距离
    const dx = (me.clientX - startX) / scaleX.value;
    const dy = (me.clientY - startY) / scaleY.value;

    element.x = startLeft + dx;
    element.y = startTop + dy;
  };

  const onUp = async () => {
    window.removeEventListener('mousemove', onMove);
    window.removeEventListener('mouseup', onUp);
    // 拖动结束后调用 API 更新位置
    await store.updateWidgetPosition(element.id, Math.round(element.x), Math.round(element.y), undefined, undefined, undefined);
    // 生成缩略图
    debouncedCreateThumbnail();
  };

  window.addEventListener('mousemove', onMove);
  window.addEventListener('mouseup', onUp);
};

// 2. 调整大小 (Resize)
const handleResizeMouseDown = (e: MouseEvent, element: SceneElement, handle: 'se' | 'ne' | 'sw' | 'nw') => {
  if (!editable.value || element.locked) return;

  const startX = e.clientX;
  const startY = e.clientY;
  const startW = element.w;
  const startH = element.h;
  const startXPos = element.x;
  const startYPos = element.y;

  const onMove = (me: MouseEvent) => {
    const dx = (me.clientX - startX) / scaleX.value;
    const dy = (me.clientY - startY) / scaleY.value;

    if (handle === 'se') {
      // 右下：加宽 加高
      element.w = Math.max(10, startW + dx);
      element.h = Math.max(10, startH + dy);
    } else if (handle === 'ne') {
      // 右上：加宽，减Y，加高
      element.w = Math.max(10, startW + dx);
      const newH = Math.max(10, startH - dy);
      element.h = newH;
      element.y = startYPos + (startH - newH);
    } else if (handle === 'sw') {
      // 左下：减X，加宽，加高
      const newW = Math.max(10, startW - dx);
      element.x = startXPos + (startW - newW);
      element.w = newW;
      element.h = Math.max(10, startH + dy);
    } else if (handle === 'nw') {
      // 左上：减X，加宽，减Y，加高
      const newW = Math.max(10, startW - dx);
      element.x = startXPos + (startW - newW);
      element.w = newW;
      const newH = Math.max(10, startH - dy);
      element.y = startYPos + (startH - newH);
      element.h = newH;
    }
  };

  const onUp = async () => {
    window.removeEventListener('mousemove', onMove);
    window.removeEventListener('mouseup', onUp);
    // 调整大小结束后调用 API 更新位置和尺寸
    await store.updateWidgetPosition(
      element.id,
      Math.round(element.x),
      Math.round(element.y),
      Math.round(element.w),
      Math.round(element.h),
      undefined
    );
    // 生成缩略图
    debouncedCreateThumbnail();
  };

  window.addEventListener('mousemove', onMove);
  window.addEventListener('mouseup', onUp);
};

// --- 生命周期 ---
let resizeObserver: ResizeObserver | null = null;

// 监听场景尺寸变化和手动缩放变化,重新计算缩放
watch(
  () => [props.sceneConfig.width, props.sceneConfig.height, props.sceneConfig.manualScale],
  () => {
    updateScale();
  }
);

onMounted(() => {
  updateScale();
  if (containerRef.value) {
    resizeObserver = new ResizeObserver(() => requestAnimationFrame(updateScale));
    resizeObserver.observe(containerRef.value);
  }
  window.addEventListener('resize', updateScale);
});
onUnmounted(() => {
  if (resizeObserver) resizeObserver.disconnect();
  window.removeEventListener('resize', updateScale);
});

// --- 暴露方法 ---
const controlWidget = (id: string, action: 'play' | 'pause' | 'seek', payload?: any) => {
  const widget = widgetRefs.value[id];
  if (widget && widget[action]) widget[action](payload);
};

// 等待所有图片加载完成
const waitForImages = (): Promise<void> => {
  return new Promise((resolve) => {
    if (!stageRef.value) {
      resolve();
      return;
    }

    const images = stageRef.value.querySelectorAll('img');
    if (images.length === 0) {
      resolve();
      return;
    }

    const promises = Array.from(images).map((img) => {
      return new Promise<void>((imgResolve) => {
        if (img.complete) {
          imgResolve();
        } else {
          img.onload = () => imgResolve();
          img.onerror = () => imgResolve(); // 即使失败也继续
        }
      });
    });

    Promise.all(promises).then(() => resolve());
  });
};

// 创建缩略图
const createThumbnail = async (): Promise<string | null> => {
  if (!stageRef.value) {
    console.log('[createThumbnail] 跳过生成:', {
      mode: props.mode,
      hasStageRef: !!stageRef.value
    });
    return null;
  }

  // 检查场景配置是否有效
  if (!props.sceneConfig || props.sceneConfig.width <= 0 || props.sceneConfig.height <= 0) {
    console.warn('[createThumbnail] 场景配置无效:', props.sceneConfig);
    return null;
  }

  // 捕获当前的 sceneId，避免异步操作完成后 sceneId 已变化
  const targetSceneId = props.sceneId;

  try {
    // 等待所有图片加载完成
    await waitForImages();

    // 获取 stageRef 的实际渲染尺寸（考虑了 CSS transform scale）
    const actualWidth = props.sceneConfig.width * scaleX.value;
    const actualHeight = props.sceneConfig.height * scaleY.value;

    // 使用 html2canvas 捕获（会自动捕获 CSS transform 的效果）
    const canvas = await html2canvas(stageRef.value, {
      backgroundColor: '#171717',
      scale: 1, // 不额外缩放，保持原始渲染尺寸
      logging: false,
      useCORS: true, // 允许跨域图片
      allowTaint: true,
      imageTimeout: 0 // 图片加载超时（0 表示无限，但已加载的图片会立即返回）
      // 不指定 width/height，让 html2canvas 自动捕获元素的视觉尺寸
    });

    // 检查 html2canvas 返回的 canvas 是否有效
    if (!canvas || canvas.width <= 0 || canvas.height <= 0) {
      console.error('[createThumbnail] html2canvas 返回无效的 canvas:', {
        canvas: !!canvas,
        width: canvas?.width,
        height: canvas?.height
      });
      return null;
    }

    // 如果 canvas 尺寸不等于场景实际尺寸，需要缩放到正确尺寸
    let finalCanvas = canvas;
    if (canvas.width !== props.sceneConfig.width || canvas.height !== props.sceneConfig.height) {
      finalCanvas = document.createElement('canvas');
      finalCanvas.width = props.sceneConfig.width;
      finalCanvas.height = props.sceneConfig.height;
      const ctx = finalCanvas.getContext('2d');
      if (ctx) {
        ctx.drawImage(canvas, 0, 0, props.sceneConfig.width, props.sceneConfig.height);
      }
    }

    const result = finalCanvas.toDataURL('image/png');

    // 保存到 store（使用捕获的 sceneId）
    // 检查缩略图是否真的改变了,避免不必要的响应式更新
    if (result && targetSceneId) {
      const currentScene = store.scenes.find((s) => s.id === targetSceneId);
      // 只在缩略图数据不同时才更新
      if (!currentScene || currentScene.thumbnailData !== result) {
        store.updateSceneThumbnail(targetSceneId, result);
      }
    } else {
      console.warn('[createThumbnail] 无法保存到 store:', { hasDataUrl: !!result, sceneId: targetSceneId });
    }

    return result;
  } catch (error) {
    console.error('[createThumbnail] 创建缩略图失败:', error);
    // 降级到简单绘制方案
    return null;
  }
};

// 防抖函数
const debounce = <T extends (...args: any[]) => any>(fn: T, delay: number): ((...args: Parameters<T>) => void) => {
  let timeoutId: ReturnType<typeof setTimeout> | null = null;
  return (...args: Parameters<T>) => {
    if (timeoutId) clearTimeout(timeoutId);
    timeoutId = setTimeout(() => fn(...args), delay);
  };
};

// 防抖后的缩略图生成函数
const debouncedCreateThumbnail = debounce(async () => {
  if (props.mode === 'edit' && props.sceneId) {
    console.log(`[SceneRenderer] 准备为场景 ${props.sceneId} 生成缩略图...`);
    // 延迟生成，确保 DOM 已更新
    await new Promise((resolve) => setTimeout(resolve, 100));
    const result = await createThumbnail();
    console.log(`[SceneRenderer] 场景 ${props.sceneId} 缩略图生成完成:`, result ? '成功' : '失败');
  }
}, 500); // 500ms 防抖延迟

// 只在 widgets 数组长度变化时（添加/删除组件）触发缩略图生成
watch(
  () => props.sceneConfig.widgets?.length,
  () => {
    if (props.mode === 'edit' && props.sceneId) {
      debouncedCreateThumbnail();
    }
  }
);

// 监听 widget dataConfig 的变化（特别是图片 src 地址变化）
watch(
  () =>
    props.sceneConfig.widgets?.map((w) => {
      const props = widgetProps(w);
      return { id: w.id, src: props?.src };
    }),
  (newProps, oldProps) => {
    if (props.mode === 'edit' && props.sceneId) {
      // 检查是否有任何 widget 的 src 发生变化 fixme 让widget通知外部 不要从外部做检测
      const hasSrcChanged = newProps?.some((newProp, index) => {
        const oldProp = oldProps?.[index];
        return oldProp && newProp.src !== oldProp.src;
      });

      if (hasSrcChanged) {
        debouncedCreateThumbnail();
      }
    }
  },
  { deep: true }
);

defineExpose({ controlWidget, createThumbnail, debouncedCreateThumbnail });
</script>

<style scoped>
div {
  -webkit-font-smoothing: antialiased;
}
</style>
