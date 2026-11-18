package com.garmin.analytics.chat;

import com.garmin.analytics.chat.config.ChatExecutionProperties;
import com.garmin.analytics.chat.config.GarminChatProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties({GarminChatProperties.class, ChatExecutionProperties.class})
public class GarminChatApplication {

  public static void main(String[] args) {
    SpringApplication.run(GarminChatApplication.class, args);
  }

  @Bean
  public Clock chatClock() {
    return Clock.systemUTC();
  }
}
