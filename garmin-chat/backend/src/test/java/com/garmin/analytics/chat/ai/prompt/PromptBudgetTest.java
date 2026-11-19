package com.garmin.analytics.chat.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.garmin.analytics.chat.config.GarminChatProperties;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class PromptBudgetTest {

  @Test
  void includesTheSystemPromptQuestionJsonResponseReserveAndTemplateAllowance() {
    var budget = budget(4096, 1024);
    var fits = new Prompt(List.of(new SystemMessage("s".repeat(200)),
        new UserMessage("q".repeat(100) + "j".repeat(2200))));
    var over = new Prompt(List.of(new SystemMessage("s".repeat(300)),
        new UserMessage("q".repeat(100) + "j".repeat(2200))));
    assertThat(budget.fits(fits)).isTrue();
    assertThat(budget.fits(over)).isFalse();
  }

  @Test
  void reservesResponseSpaceEvenWhenInputAloneFitsTheContext() {
    assertThat(budget(4096, 1024).fits(new Prompt("x".repeat(3000)))).isFalse();
  }

  @Test
  void handlesTheExactBudgetBoundaryConservatively() {
    var budget = budget(4096, 1024);
    assertThat(budget.fits(new Prompt("x".repeat(2560)))).isTrue();
    assertThat(budget.fits(new Prompt("x".repeat(2561)))).isFalse();
  }

  @Test
  void countsUtf8BytesSoNonLatinTextIsNotUnderestimated() {
    var budget = budget(2048, 512);
    assertThat(budget.fits(new Prompt("a".repeat(600)))).isTrue();
    assertThat(budget.fits(new Prompt("漢".repeat(600)))).isFalse();
  }

  @Test
  void rejectsAContextThatCannotHoldTheResponseAndTemplateReserve() {
    assertThatThrownBy(() -> budget(1024, 1024)).isInstanceOf(IllegalArgumentException.class);
  }

  private PromptBudget budget(int context, int response) {
    return new PromptBudget(new GarminChatProperties(123L, ZoneId.of("Europe/Oslo"),
        context, response, 31));
  }
}
