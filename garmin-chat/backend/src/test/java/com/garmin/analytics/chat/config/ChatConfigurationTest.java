package com.garmin.analytics.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.garmin.analytics.chat.GarminChatApplication;
import com.garmin.analytics.chat.ai.ChatModelAvailability;
import com.garmin.analytics.chat.ai.PromptBudget;
import com.garmin.analytics.chat.ai.QuestionDateResolver;
import com.garmin.analytics.chat.controller.ChatExceptionHandler;
import com.garmin.analytics.chat.controller.GarminChatController;
import com.garmin.analytics.chat.repository.DailySummaryRepository;
import com.garmin.analytics.chat.service.GarminChatService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.http.client.HttpClientProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ChatConfigurationTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withUserConfiguration(GarminChatApplication.class)
      .withPropertyValues("GARMIN_CHAT_DB_PASSWORD=synthetic-only", "GARMIN_CHAT_PROFILE_ID=123");

  @Test
  void wiresTheActualApplicationAndConfigurationWithoutDatabaseOrOllamaConnections() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasSingleBean(GarminChatController.class);
      assertThat(context).hasSingleBean(ChatExceptionHandler.class);
      assertThat(context).hasSingleBean(GarminChatService.class);
      assertThat(context).hasSingleBean(ChatModel.class);
      assertThat(context).hasSingleBean(ChatModelAvailability.class);
      assertThat(context).hasSingleBean(DailySummaryRepository.class);
      assertThat(context).hasSingleBean(QuestionDateResolver.class);
      assertThat(context).hasSingleBean(PromptBudget.class);
      assertThat(context.getBean(HttpClientProperties.class).getReadTimeout())
          .isEqualTo(Duration.ofSeconds(300));
      assertThat(context.getBean(GarminChatProperties.class).maxQueryDays()).isEqualTo(31);
    });
  }

  @Test
  void bindsTheConfiguredModelTimeoutToTheHttpClient() {
    runner.withPropertyValues("OLLAMA_REQUEST_TIMEOUT=420s").run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBean(HttpClientProperties.class).getReadTimeout())
          .isEqualTo(Duration.ofSeconds(420));
    });
  }

  @Test
  void bindsTheMaximumQueryRangeFromServerConfiguration() {
    runner.withPropertyValues("GARMIN_CHAT_MAX_QUERY_DAYS=14").run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBean(GarminChatProperties.class).maxQueryDays()).isEqualTo(14);
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "GARMIN_CHAT_PROFILE_ID=0", "GARMIN_CHAT_TIMEZONE=Not/A_Timezone",
      "GARMIN_CHAT_RESPONSE_TOKENS=0", "GARMIN_CHAT_RESPONSE_TOKENS=8192",
      "GARMIN_CHAT_MAX_QUERY_DAYS=0", "GARMIN_CHAT_MAX_QUERY_DAYS=-1"
  })
  void rejectsInvalidProfileTimezoneOrResponseBudgetAtStartup(String override) {
    runner.withPropertyValues(override).run(context -> assertThat(context).hasFailed());
  }
}
