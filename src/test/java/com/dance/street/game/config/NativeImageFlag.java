package com.dance.street.game.config;

/**
 * 测试用:临时把当前 JVM 伪装成 GraalVM Native Image 运行时
 * (设置 native-image 自身的 {@code org.graalvm.nativeimage.imagecode} 标记),
 * 用于验证"native 默认 standalone"这类按运行形态分叉的默认值逻辑。
 */
final class NativeImageFlag {

    private static final String KEY = "org.graalvm.nativeimage.imagecode";

    private NativeImageFlag() {
    }

    static void runAsNativeImage(Runnable body) {
        String previous = System.getProperty(KEY);
        System.setProperty(KEY, "runtime");
        try {
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty(KEY);
            } else {
                System.setProperty(KEY, previous);
            }
        }
    }
}
