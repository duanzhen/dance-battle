package org.dromara.common.file.utils;

import java.util.Locale;
import java.util.Set;

/**
 * 文件类型探测器:通过文件头魔数识别真实类型,防止伪造扩展名 / 图片里夹带可执行载荷。
 *
 * <p>只识别允许上传的图片与视频格式,其余一律返回 null。</p>
 */
public final class FileTypeDetector {

    private FileTypeDetector() {
    }

    /** 识别出的真实文件格式 */
    public enum DetectedType {
        JPEG("jpg", "jpeg"),
        PNG("png"),
        GIF("gif"),
        WEBP("webp"),
        BMP("bmp"),
        ICO("ico"),
        MP4("mp4", "m4v", "mov"),
        WEBM("webm"),
        AVI("avi");

        private final Set<String> extensions;

        DetectedType(String... extensions) {
            this.extensions = Set.of(extensions);
        }

        public boolean supportsExtension(String ext) {
            return extensions.contains(ext);
        }

        public static boolean isAllowedExtension(String ext) {
            for (DetectedType t : values()) {
                if (t.extensions.contains(ext)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 读取文件头(建议 64 字节)并识别类型。
     *
     * @param head 文件头字节
     * @return 识别出的类型,无法识别返回 null
     */
    public static DetectedType detect(byte[] head) {
        if (head == null || head.length < 4) {
            return null;
        }
        // JPEG: FF D8 FF
        if (startsWith(head, new int[]{0xFF, 0xD8, 0xFF})) {
            return DetectedType.JPEG;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (startsWith(head, new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
            return DetectedType.PNG;
        }
        // GIF: GIF8
        if (ascii(head, 0, "GIF8")) {
            return DetectedType.GIF;
        }
        // WEBP: RIFF....WEBP
        if (ascii(head, 0, "RIFF") && head.length >= 12 && ascii(head, 8, "WEBP")) {
            return DetectedType.WEBP;
        }
        // BMP: BM
        if (ascii(head, 0, "BM")) {
            return DetectedType.BMP;
        }
        // ICO: 00 00 01 00
        if (head[0] == 0 && head[1] == 0 && head[2] == 1 && head[3] == 0) {
            return DetectedType.ICO;
        }
        // MP4 / M4V / MOV: 前 4 字节长度 + "ftyp"
        if (head.length >= 8 && ascii(head, 4, "ftyp")) {
            return DetectedType.MP4;
        }
        // WEBM / MKV: EBML 头 1A 45 DF A3
        if (startsWith(head, new int[]{0x1A, 0x45, 0xDF, 0xA3})) {
            return DetectedType.WEBM;
        }
        // AVI: RIFF....AVI
        if (ascii(head, 0, "RIFF") && head.length >= 12 && ascii(head, 8, "AVI ")) {
            return DetectedType.AVI;
        }
        return null;
    }

    private static boolean startsWith(byte[] head, int[] magic) {
        if (head.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((head[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean ascii(byte[] head, int offset, String text) {
        if (offset + text.length() > head.length) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if ((head[offset + i] & 0xFF) != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** 从原始文件名提取小写扩展名(只取最后一个点之后的部分),无扩展名返回 null */
    public static String extractExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return null;
        }
        // 兼容 Windows/Unix 路径分隔符,只取文件名部分,防止路径穿越
        String name = originalFilename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,10}") ? ext : null;
    }
}
