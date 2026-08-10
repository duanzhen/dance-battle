package org.dromara.common.file.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.hutool.core.util.StrUtil;
import org.dromara.common.file.utils.FileTypeDetector;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 本地文件上传(替代对象存储):
 * 仅允许图片/视频,做扩展名白名单 + 魔数校验 + 大小限制 + 防路径穿越,
 * 文件落到本地 upload 目录,通过 /uploads/** 静态访问。
 */
@RestController
public class ResourceController {

    @Value("${file.upload.path:./upload}")
    private String uploadPath;

    @Value("${file.upload.max-size:104857600}")
    private long maxSize;

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * 上传文件(兼容前端 /resource/oss/upload 调用,返回 {url})
     */
    @SaCheckLogin
    @PostMapping("/resource/oss/upload")
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        // 1. 基础校验:非空、大小限制
        if (file == null || file.isEmpty()) {
            throw new ServiceException("上传文件不能为空");
        }
        if (file.getSize() > maxSize) {
            throw new ServiceException("文件大小超过限制(最大 {} MB)", maxSize / 1024 / 1024);
        }

        // 2. 扩展名白名单(仅图片/视频,排除 html/svg/jsp 等可执行或脚本类型)
        String ext = FileTypeDetector.extractExtension(file.getOriginalFilename());
        if (ext == null || !FileTypeDetector.DetectedType.isAllowedExtension(ext)) {
            throw new ServiceException("仅支持上传图片或视频文件");
        }

        // 3. 魔数校验:扩展名与真实内容必须一致,防伪造扩展名夹带载荷
        byte[] head = readHead(file);
        FileTypeDetector.DetectedType detected = FileTypeDetector.detect(head);
        if (detected == null) {
            throw new ServiceException("文件内容不是有效的图片或视频");
        }
        if (!detected.supportsExtension(ext)) {
            throw new ServiceException("文件扩展名与内容类型不匹配");
        }

        // 4. 保存:目录按日期分层,文件名使用 UUID,完全不信任客户端文件名
        Path root = Path.of(uploadPath).toAbsolutePath().normalize();
        String dateDir = LocalDate.now().format(DATE_DIR);
        Path dir = root.resolve(dateDir).normalize();
        if (!dir.startsWith(root)) {
            throw new ServiceException("非法上传路径");
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new ServiceException("创建上传目录失败: {}", e.getMessage());
        }
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = dir.resolve(filename).normalize();
        if (!target.startsWith(dir)) {
            throw new ServiceException("非法上传路径");
        }
        try {
            file.transferTo(target.toFile());
        } catch (IOException e) {
            throw new ServiceException("文件保存失败: {}", e.getMessage());
        }

        // 5. 返回可访问 URL(相对路径,前后端同源;开发环境由 Vite 代理 /uploads)
        String url = "/uploads/" + dateDir + "/" + filename;
        Map<String, Object> data = new HashMap<>();
        data.put("url", url);
        data.put("name", StrUtil.nullToDefault(file.getOriginalFilename(), filename));
        data.put("size", file.getSize());
        return R.ok(data);
    }

    private byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(64);
        } catch (IOException e) {
            throw new ServiceException("读取文件失败: {}", e.getMessage());
        }
    }
}
