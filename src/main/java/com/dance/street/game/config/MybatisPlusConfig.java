package com.dance.street.game.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：分页插件(按 JDBC URL 自动选择 MySQL/SQLite 方言) + Mapper 扫描
 */
@Configuration
@MapperScan("com.dance.street.game.mapper")
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(
        @Value("${spring.datasource.url:}") String jdbcUrl) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        DbType dbType = jdbcUrl != null && jdbcUrl.startsWith("jdbc:sqlite:")
            ? DbType.SQLITE
            : DbType.MYSQL;
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(dbType));
        return interceptor;
    }
}
