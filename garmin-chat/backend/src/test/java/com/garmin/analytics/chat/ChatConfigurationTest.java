package com.garmin.analytics.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
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
      assertThat(context).hasSingleBean(GarminChatService.class);
      assertThat(context).hasSingleBean(ChatModel.class);
      assertThat(context).hasSingleBean(ChatModelAvailability.class);
      assertThat(context).hasSingleBean(DailySummaryRepository.class);
      assertThat(context).hasSingleBean(QuestionDateResolver.class);
      assertThat(context).hasSingleBean(PromptBudget.class);
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "GARMIN_CHAT_PROFILE_ID=0", "GARMIN_CHAT_TIMEZONE=Not/A_Timezone",
      "GARMIN_CHAT_RESPONSE_TOKENS=0", "GARMIN_CHAT_RESPONSE_TOKENS=8192"
  })
  void rejectsInvalidProfileTimezoneOrResponseBudgetAtStartup(String override) {
    runner.withPropertyValues(override).run(context -> assertThat(context).hasFailed());
  }
}
