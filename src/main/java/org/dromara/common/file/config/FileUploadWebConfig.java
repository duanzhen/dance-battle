package org.dromara.common.file.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * 上传文件静态访问映射:/uploads/** → 本地 upload 目录。
 * 与 SPA 回退转发冲突的带扩展名资源不受影响。
 */
@Configuration
public class FileUploadWebConfig implements WebMvcConfigurer {

    @Value("${file.upload.path:./upload}")
    private String uploadPath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path root = Path.of(uploadPath).toAbsolutePath().normalize();
        String location = "file:" + root.toString() + "/";
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
