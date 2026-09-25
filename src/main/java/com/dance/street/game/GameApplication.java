package com.dance.street.game;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 街舞赛事对战系统启动类。
 *
 * @author duane
 */
@SpringBootApplication(scanBasePackages = {"com.dance.street.game", "org.dromara.common"})
public class GameApplication {

	public static void main(String[] args) {
		SpringApplication.run(GameApplication.class, args);
	}

}
