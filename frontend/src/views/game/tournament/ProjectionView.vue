<template>
  <div class="w-screen h-screen bg-black text-white overflow-hidden relative group">
    <!-- 全屏提示按钮 -->
    <div
      @click="enterFullscreen"
      v-if="!isFullscreen && !isConnectionLost && !isScreenClosed"
      class="absolute inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-md opacity-0 group-hover:opacity-100 transition-opacity duration-300"
    >
      <button
        class="flex flex-col items-center gap-3 px-8 py-6 bg-gray-900/80 hover:bg-black border border-gray-600 hover:border-amber-500 rounded-xl shadow-2xl transition-all transform hover:scale-105 group/btn"
      >
        <Maximize class="w-10 h-10 text-amber-500 group-hover/btn:text-amber-400 transition-colors" />
        <span class="text-lg font-bold tracking-widest text-white">点击进入全屏模式</span>
        <span class="text-xs text-gray-400">最佳投射体验</span>
      </button>
    </div>

    <!-- 信号丢失提示 -->
    <div v-if="isConnectionLost" class="absolute inset-0 z-40 flex items-center justify-center bg-neutral-950 backdrop-blur-sm">
      <div class="flex flex-col items-center gap-6 px-12 py-10 bg-neutral-900 border border-neutral-800 rounded-2xl shadow-2xl max-w-md">
        <!-- 信号丢失图标 -->
        <div class="relative">
          <div class="absolute inset-0 bg-amber-500/20 blur-3xl rounded-full"></div>
          <svg class="relative w-24 h-24 text-amber-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="1.5"
              d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636"
            />
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M12 9v2m0 4h.01" />
          </svg>
        </div>

        <!-- 文字提示 -->
        <div class="text-center space-y-3">
          <h2 class="text-2xl font-bold text-white tracking-wide">信号丢失</h2>
        </div>

        <!-- 连接状态指示器 -->
        <div class="flex items-center gap-3 px-4 py-2 bg-neutral-800/50 rounded-lg">
          <div class="relative">
            <div class="w-2 h-2 bg-amber-500 rounded-full animate-pulse"></div>
            <div class="absolute inset-0 w-2 h-2 bg-amber-500 rounded-full animate-ping opacity-75"></div>
          </div>
          <span class="text-xs text-neutral-500 font-mono">正在尝试重新连接...</span>
        </div>
      </div>
    </div>

    <!-- 加载指示器 -->
    <div v-if="loading && !currentSceneId && !isConnectionLost" class="flex items-center justify-center h-full">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-amber-500"></div>
    </div>

    <!-- 等待场景投射提示 -->
    <div
      v-if="!loading && !currentSceneId && !isConnectionLost && !isScreenClosed"
      class="absolute inset-0 z-30 flex items-center justify-center bg-neutral-950 backdrop-blur-sm"
    >
      <div class="flex flex-col items-center gap-6 px-12 py-10 bg-neutral-900 border border-neutral-800 rounded-2xl shadow-2xl max-w-md">
        <!-- 等待投射图标 -->
        <div class="relative">
          <div class="absolute inset-0 bg-blue-500/20 blur-3xl rounded-full"></div>
          <svg class="relative w-24 h-24 text-blue-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="1.5"
              d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z"
            />
          </svg>
        </div>

        <!-- 文字提示 -->
        <div class="text-center space-y-3">
          <h2 class="text-2xl font-bold text-white tracking-wide">等待场景投射</h2>
        </div>
      </div>
    </div>

    <!-- 屏幕已关闭提示 -->
    <div
      v-if="isScreenClosed && !isConnectionLost"
      class="absolute inset-0 z-30 flex items-center justify-center bg-neutral-950 backdrop-blur-sm cursor-pointer"
      @click="closeWindow"
    >
      <div class="flex flex-col items-center gap-6 px-12 py-10 bg-neutral-900 border border-neutral-800 rounded-2xl shadow-2xl max-w-md" @click.stop>
        <!-- 屏幕已关闭图标 -->
        <div class="relative">
          <div class="absolute inset-0 bg-gray-500/20 blur-3xl rounded-full"></div>
          <svg class="relative w-24 h-24 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="1.5"
              d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
            />
          </svg>
        </div>

        <!-- 文字提示 -->
        <div class="text-center space-y-3">
          <h2 class="text-2xl font-bold text-white tracking-wide">屏幕已关闭</h2>
          <p class="text-sm text-gray-500">点击任意位置关闭窗口</p>
        </div>
      </div>
    </div>

    <!-- 渲染所有已缓存的场景，使用绝对定位叠加 -->
    <div class="relative w-full h-full">
      <div
        v-for="(scene, id) in renderedScenes"
        :key="id"
        class="absolute inset-0 w-full h-full"
        :style="{
          transition: 'opacity 0.5s ease-in-out',
          opacity: id === currentSceneId ? 1 : 0,
          pointerEvents: id === currentSceneId ? 'auto' : 'none'
        }"
      >
        <ScreenRenderer
          :ref="(el: any) => setRendererRef(el, id)"
          :scene-config="scene.config"
          :tournament-id="scene.config.tournamentId"
          mode="render"
          @rendered="() => handleRendered(id)"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Maximize } from 'lucide-vue-next';
import { subscribeChannel } from '@/utils/sseChannel';
import ScreenRenderer from './SceneRenderer.vue';
import type { SceneConfig } from './SceneRenderer.vue';
import { getVisScene } from '@/api/game/visScene';
import { listVisWidget } from '@/api/game/visWidget';

const route = useRoute();
const screenId = ref('');
const loading = ref(true);
const isFullscreen = ref(false);
const currentSceneId = ref<string | null>(null);

// 连接丢失状态
const isConnectionLost = ref(false);

// 屏幕已关闭状态
const isScreenClosed = ref(false);

// 已渲染的场景缓存：sceneId -> { config: SceneConfig, isReady: boolean }
const renderedScenes = reactive<Record<string, { config: SceneConfig; isReady: boolean }>>({});

// 渲染器引用：sceneId -> ScreenRenderer 实例
const rendererRefs = new Map<string, any>();

// 是否正在加载新场景
const isLoadingScene = ref(false);

// 屏幕视图 SSE 订阅
let unsubScreenSse: (() => void) | null = null;
let connectionLostTimer: ReturnType<typeof setTimeout> | null = null;

onMounted(() => {
  const id = route.query.screenId as string;
  if (id) {
    screenId.value = id;
    subscribeToScreenView(id);
    document.addEventListener('fullscreenchange', checkFullscreenState);
    checkFullscreenState();
  } else {
    console.error('未检测到屏幕 UUID');
  }
});

onUnmounted(() => {
  document.removeEventListener('fullscreenchange', checkFullscreenState);
  unsubScreenSse?.();
  if (connectionLostTimer) {
    clearTimeout(connectionLostTimer);
  }
});

// 设置渲染器引用
const setRendererRef = (el: any, sceneId: string) => {
  if (el) {
    rendererRefs.set(sceneId, el);
  }
};

// 检查全屏状态
const checkFullscreenState = () => {
  const fullscreenElement = document.fullscreenElement;
  isFullscreen.value = !!fullscreenElement;
};

// 进入全屏
const enterFullscreen = async () => {
  try {
    if (!document.fullscreenElement) {
      await document.documentElement.requestFullscreen();
    }
  } catch (err) {
    console.error(`Error attempting to enable full-screen mode: ${err}`);
  }
};

// 订阅屏幕视图 SSE:统一客户端负责指数退避重连,连接状态驱动"信号丢失"提示
const subscribeToScreenView = (screenId: string) => {
  const clientId = import.meta.env.VITE_APP_CLIENT_ID;
  const baseUrl = import.meta.env.VITE_APP_BASE_API;
  const terminalId = `terminal_${Date.now()}_${Math.random().toString(36).substring(2, 15)}`;

  const url = `${baseUrl}/tournament/screen/view?clientid=${clientId}&screenId=${screenId}&terminalId=${terminalId}`;

  console.log(`[ProjectionView] 正在订阅屏幕 ${screenId} 的视图通道 (terminalId: ${terminalId})`);

  unsubScreenSse = subscribeChannel({
    key: `screen-view:${screenId}`,
    buildUrl: () => url,
    onMessage: (message: any) => {
      // 收到消息说明连接正常
      isConnectionLost.value = false;
      if (message && typeof message === 'object' && message.type) {
        console.log('[ProjectionView] 收到消息:', message);
        handleScreenMessage(message);
      }
    },
    onStatus: (status) => {
      if (status === 'open') {
        console.log('[ProjectionView] SSE 连接已建立');
        if (connectionLostTimer) {
          clearTimeout(connectionLostTimer);
          connectionLostTimer = null;
        }
        // 连接建立后，停止加载状态，显示等待提示
        loading.value = false;
        isConnectionLost.value = false;
      } else if (status === 'error') {
        console.error('[ProjectionView] SSE 连接错误,等待自动重连');
        // 延迟显示"信号丢失":短暂断线会自动重连,避免黑屏闪烁
        if (!connectionLostTimer) {
          connectionLostTimer = setTimeout(() => {
            isConnectionLost.value = true;
            connectionLostTimer = null;
          }, 8000);
        }
      }
    }
  });
};

// 检查 sceneId 是否为无效值
const isInvalidSceneId = (sceneId: any): boolean => {
  return sceneId === null || sceneId === 'null' || sceneId === '' || sceneId === undefined;
};

// 处理屏幕消息
const handleScreenMessage = async (message: any) => {
  switch (message.type) {
    case 'sceneSwitch':
      if (isInvalidSceneId(message.sceneId)) {
        console.log('[ProjectionView] 收到无效 sceneId，屏幕已关闭');
        clearScene(true);
      } else if (message.sceneId) {
        isScreenClosed.value = false;
        await loadScene(String(message.sceneId));
      }
      break;
    case 'sceneUpdate':
      // 更新当前场景配置
      if (isInvalidSceneId(message.sceneId)) {
        console.log('[ProjectionView] 收到无效 sceneId，屏幕已关闭');
        clearScene(true);
      } else if (message.sceneId) {
        isScreenClosed.value = false;
        await updateScene(String(message.sceneId));
      }
      break;
    case 'SCREEN_CLEARED':
      clearScene(true);
      break;
    default:
      console.warn('[ProjectionView] 未知消息类型:', message.type);
  }
};

// 预加载场景中的所有图片
const preloadImages = (sceneConfig: SceneConfig): Promise<void> => {
  return new Promise((resolve) => {
    const imageUrls: string[] = [];

    // 从 widget 的 dataConfig 中提取图片 URL
    sceneConfig.widgets.forEach((widget) => {
      if (widget.type === 'IMAGE') {
        try {
          const props = JSON.parse(widget.dataConfig || '{}');
          if (props.src) {
            imageUrls.push(props.src);
          }
        } catch (e) {
          console.warn('解析 widget dataConfig 失败:', e);
        }
      }
    });

    if (imageUrls.length === 0) {
      resolve();
      return;
    }

    console.log(`[ProjectionView] 预加载 ${imageUrls.length} 张图片...`);

    let loadedCount = 0;
    const totalImages = imageUrls.length;

    imageUrls.forEach((url) => {
      const img = new Image();
      img.onload = () => {
        loadedCount++;
        if (loadedCount === totalImages) {
          console.log('[ProjectionView] 所有图片预加载完成');
          resolve();
        }
      };
      img.onerror = () => {
        loadedCount++;
        if (loadedCount === totalImages) {
          console.warn('[ProjectionView] 部分图片预加载失败，但继续渲染');
          resolve();
        }
      };
      img.src = url;
    });
  });
};

// 加载场景
const loadScene = async (sceneId: string) => {
  if (currentSceneId.value === sceneId) {
    console.log(`[ProjectionView] 场景 ${sceneId} 已是当前场景，跳过加载`);
    return;
  }

  if (isLoadingScene.value) {
    console.log(`[ProjectionView] 正在加载其他场景，跳过场景 ${sceneId}`);
    return;
  }

  isLoadingScene.value = true;

  try {
    console.log(`[ProjectionView] 正在加载场景 ${sceneId}...`);

    // 如果场景已渲染，直接切换
    if (renderedScenes[sceneId]) {
      console.log(`[ProjectionView] 场景 ${sceneId} 已缓存，直接切换`);
      currentSceneId.value = sceneId;
      loading.value = false;
      isLoadingScene.value = false;
      return;
    }

    // 从 API 加载场景
    const sceneResponse = await getVisScene(sceneId);
    if (!sceneResponse.data) {
      throw new Error('场景不存在');
    }

    const scene = sceneResponse.data;

    // 获取场景下的所有组件
    const widgetsResponse = await listVisWidget({
      tournamentId: scene.tournamentId,
      sceneId: sceneId,
      pageNum: 1,
      pageSize: 1000
    });

    // 准备场景配置
    const sceneConfig: SceneConfig = {
      tournamentId: scene.tournamentId,
      width: Number(scene.designWidth) || 1920,
      height: Number(scene.designHeight) || 1080,
      widgets: (widgetsResponse.data || []).map((widget: any) => ({
        id: widget.id,
        name: widget.name,
        type: widget.type,
        x: widget.x || 0,
        y: widget.y || 0,
        w: widget.w || 100,
        h: widget.h || 100,
        z: Number(widget.zIndex) || 1,
        visible: widget.visible === 1,
        locked: widget.locked === 1,
        dataConfig: widget.dataConfig,
        renderConfig: widget.renderConfig
      }))
    };

    console.log(`[ProjectionView] 场景 ${sceneId} 配置加载完成，包含 ${sceneConfig.widgets.length} 个组件`);

    // 先添加到缓存（但标记为未就绪）
    renderedScenes[sceneId] = {
      config: sceneConfig,
      isReady: false
    };

    console.log(`[ProjectionView] 场景 ${sceneId} 已添加到缓存，开始预加载图片...`);

    // 预加载图片
    await preloadImages(sceneConfig);

    // 标记为就绪
    renderedScenes[sceneId].isReady = true;

    // 等待一帧确保 DOM 更新
    await new Promise((resolve) => requestAnimationFrame(resolve));

    // 切换到新场景
    console.log(`[ProjectionView] 场景 ${sceneId} 准备就绪，开始切换，设置 currentSceneId = ${sceneId}`);
    currentSceneId.value = sceneId;
    loading.value = false;

    console.log(`[ProjectionView] 当前渲染的场景列表:`, Object.keys(renderedScenes));
    console.log(`[ProjectionView] 当前显示的场景 ID:`, currentSceneId.value);
  } catch (error) {
    console.error('[ProjectionView] 加载场景失败:', error);
    // 清理失败的场景
    delete renderedScenes[sceneId];
  } finally {
    isLoadingScene.value = false;
  }
};

// 渲染完成回调
const handleRendered = (sceneId: string) => {
  console.log(`[ProjectionView] 场景 ${sceneId} 渲染完成`);
  if (renderedScenes[sceneId]) {
    renderedScenes[sceneId].isReady = true;
  }
};

// 更新场景配置（不切换场景，只更新配置）
const updateScene = async (sceneId: string) => {
  console.log(`[ProjectionView] 正在更新场景 ${sceneId} 的配置...`);

  try {
    // 从 API 加载场景
    const sceneResponse = await getVisScene(sceneId);
    if (!sceneResponse.data) {
      throw new Error('场景不存在');
    }

    const scene = sceneResponse.data;

    // 获取场景下的所有组件
    const widgetsResponse = await listVisWidget({
      tournamentId: scene.tournamentId,
      sceneId: sceneId,
      pageNum: 1,
      pageSize: 1000
    });

    // 准备场景配置
    const sceneConfig: SceneConfig = {
      tournamentId: scene.tournamentId,
      width: Number(scene.designWidth) || 1920,
      height: Number(scene.designHeight) || 1080,
      widgets: (widgetsResponse.data || []).map((widget: any) => ({
        id: widget.id,
        name: widget.name,
        type: widget.type,
        x: widget.x || 0,
        y: widget.y || 0,
        w: widget.w || 100,
        h: widget.h || 100,
        z: Number(widget.zIndex) || 1,
        visible: widget.visible === 1,
        locked: widget.locked === 1,
        dataConfig: widget.dataConfig,
        renderConfig: widget.renderConfig
      }))
    };

    console.log(`[ProjectionView] 场景 ${sceneId} 配置更新完成，包含 ${sceneConfig.widgets.length} 个组件`);

    // 如果场景已缓存，更新配置
    if (renderedScenes[sceneId]) {
      // 直接更新配置，Vue 的响应式会自动触发重新渲染
      renderedScenes[sceneId].config = sceneConfig;
      console.log(`[ProjectionView] 场景 ${sceneId} 配置已更新，触发重新渲染`);
    } else {
      // 如果场景未缓存，添加到缓存
      renderedScenes[sceneId] = {
        config: sceneConfig,
        isReady: false
      };
      console.log(`[ProjectionView] 场景 ${sceneId} 未缓存，已添加到缓存`);
    }
  } catch (error) {
    console.error('[ProjectionView] 更新场景配置失败:', error);
  }
};

// 清除场景
const clearScene = (setClosed = false) => {
  console.log('[ProjectionView] 清除投射');
  currentSceneId.value = null;
  loading.value = false;

  // 设置屏幕已关闭状态
  isScreenClosed.value = setClosed;

  // 清空所有缓存的场景
  Object.keys(renderedScenes).forEach((key) => {
    delete renderedScenes[key];
  });

  // 清空渲染器引用
  rendererRefs.clear();
};

// 关闭窗口
const closeWindow = () => {
  console.log('[ProjectionView] 关闭窗口');
  window.close();
};
</script>

<style>
body {
  overflow: hidden;
  margin: 0;
}
</style>
