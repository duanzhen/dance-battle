package org.dromara.common.sse.config;

import org.dromara.common.sse.controller.SseController;
import org.dromara.common.sse.core.SseEmitterManager;
import org.dromara.common.sse.core.TournamentEventSseEmitterManager;
import org.dromara.common.sse.core.TournamentSseEmitterManager;
import org.dromara.common.sse.listener.TournamentEventSseTopicListener;
import org.dromara.common.sse.listener.SseTopicListener;
import org.dromara.common.sse.listener.TournamentSseTopicListener;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * SSE 自动装配
 *
 * @author Lion Li
 */
@AutoConfiguration
@ConditionalOnProperty(value = "sse.enabled", havingValue = "true")
@EnableConfigurationProperties(SseProperties.class)
public class SseAutoConfiguration {

    @Bean
    public SseEmitterManager sseEmitterManager() {
        return new SseEmitterManager();
    }

    @Bean
    public TournamentSseEmitterManager tournamentSseEmitterManager() {
        return new TournamentSseEmitterManager();
    }

    @Bean
    public TournamentEventSseEmitterManager tournamentEventSseEmitterManager() {
        return new TournamentEventSseEmitterManager();
    }

    @Bean
    public SseTopicListener sseTopicListener() {
        return new SseTopicListener();
    }

    @Bean
    public TournamentSseTopicListener tournamentSseTopicListener() {
        return new TournamentSseTopicListener();
    }

    @Bean
    public TournamentEventSseTopicListener tournamentEventSseTopicListener() {
        return new TournamentEventSseTopicListener();
    }

    @Bean
    public SseController sseController(SseEmitterManager sseEmitterManager, TournamentSseEmitterManager tournamentSseEmitterManager) {
        return new SseController(sseEmitterManager, tournamentSseEmitterManager);
    }

}
