package com.garmin.analytics.chat;

import static com.garmin.analytics.chat.ChatTestSupport.LAST_SEVEN;
import static com.garmin.analytics.chat.ChatTestSupport.WEEK;
import static com.garmin.analytics.chat.ChatTestSupport.isSelection;
import static com.garmin.analytics.chat.ChatTestSupport.model;
import static com.garmin.analytics.chat.ChatTestSupport.response;
import static com.garmin.analytics.chat.ChatTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.dao.DataAccessResourceFailureException;

class GarminChatServiceTest {

  private final LocalDate start = LocalDate.of(2026, 11, 9);
  private final LocalDate end = LocalDate.of(2026, 11, 15);

  @Test
  void queriesBoundDatesForTheServerProfileAndReturnsTheSelectedPeriodIncludingGaps()
      throws Exception {
    DailySummaryRepository repository = (profile, from, to) -> {
      assertThat(profile).isEqualTo(123L);
      assertThat(from).isEqualTo(start);
      assertThat(to).isEqualTo(end);
      return List.of(new DailySummary(profile, start, "{\"totalSteps\":0}"));
    };
    var service = service(repository, model(WEEK, prompt -> response("0 recorded steps.")));

    assertThat(service.answer("Steps between 2026-11-09 and 2026-11-15?"))
        .isEqualTo(new ChatAnswer("0 recorded steps.", start, end));
  }

  @Test
  void resolvesLastSevenDaysBeforeQueryingAndNeverIncludesToday() throws Exception {
    var loads = new AtomicInteger();
    var service = service((profile, from, to) -> {
      loads.incrementAndGet();
      assertThat(from).isEqualTo(start);
      assertThat(to).isEqualTo(end);
      return List.of(new DailySummary(profile, from, "{\"restingHeartRate\":55}"));
    }, model(LAST_SEVEN, prompt -> response("Recorded resting heart rate was 55.")));

    service.answer("How did I sleep over the last seven days?");
    assertThat(loads.get()).isEqualTo(1);
  }

  @Test
  void sendsOriginalQuestionAndCompleteJsonTogetherWithAllFieldsNullsZerosAndMetadata()
      throws Exception {
    String payload = """
        {"totalSteps":0,"sleep":{"duration":25200,"score":null},
        "bodyBattery":{"events":[0,null,75]},"futureField":{"values":[1,2.5],"text":"æ 漢字"}}
        """;
    String question = "  How was walking related to sleep from 2026-11-09 to 2026-11-15? {keep}  ";
    var service = service((profile, from, to) -> List.of(new DailySummary(profile, from, payload)),
        model(WEEK, prompt -> {
          assertThat(prompt.getInstructions()).hasSize(2);
          assertThat(prompt.getInstructions().getFirst()).isInstanceOf(SystemMessage.class);
          assertThat(prompt.getInstructions().getFirst().getText()).contains(
              "missing fields and null readings as unknown", "recorded zeros as valid",
              "If the data cannot answer the question, say what is missing");
          assertThat(prompt.getInstructions().getLast().getText())
              .contains(payload, "Profile ID: 123", "2026-11-09 through 2026-11-15",
                  "Dates absent for a profile are unknown").endsWith(question);
          assertThat(((OllamaChatOptions) prompt.getOptions()).getNumPredict()).isEqualTo(1024);
          return response("Sleep scores are missing, so that association cannot be assessed.");
        }));

    assertThat(service.answer(question).answer()).contains("Sleep scores are missing");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"type\":\"CLARIFICATION\",\"startDate\":\"\",\"endDate\":\"\",\"days\":0}",
      "{\"type\":\"EXPLICIT\",\"startDate\":\"2026-02-29\",\"endDate\":\"2026-03-01\",\"days\":0}",
      "{\"type\":\"EXPLICIT\",\"startDate\":\"2026-11-15\",\"endDate\":\"2026-11-09\",\"days\":0}",
      "{\"type\":\"LAST_N_DAYS\",\"startDate\":\"\",\"endDate\":\"\",\"days\":-7}",
      "{\"type\":\"EXPLICIT\",\"startDate\":\"2027-01-01\",\"endDate\":\"2027-01-02\",\"days\":0}",
      "{}", "not JSON"
  })
  void invalidMissingOrAmbiguousSelectionsAskForClarificationWithoutQueryOrAnswer(String selection)
      throws Exception {
    var calls = new AtomicInteger();
    var service = service((profile, from, to) -> {
      throw new AssertionError("Unvalidated dates must not reach PostgreSQL");
    }, prompt -> {
      calls.incrementAndGet();
      assertThat(isSelection(prompt)).isTrue();
      return response(selection);
    });

    var result = service.answer("How did I sleep?");
    assertThat(result.answer()).contains("Which dates should I use?");
    assertThat(result.startDate()).isNull();
    assertThat(result.endDate()).isNull();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void noSummariesReturnsADirectAnswerWithoutCallingTheAnswerModel() throws Exception {
    var calls = new AtomicInteger();
    var service = service((profile, from, to) -> List.of(), prompt -> {
      assertThat(isSelection(prompt)).isTrue();
      calls.incrementAndGet();
      return response(WEEK);
    });

    var result = service.answer("How did I sleep from 2026-11-09 to 2026-11-15?");
    assertThat(result.answer()).contains("No Garmin daily summaries", "missing");
    assertThat(result.startDate()).isEqualTo(start);
    assertThat(result.endDate()).isEqualTo(end);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void oversizedJsonSkipsTheAnswerCallAndAsksForAShorterPeriod() throws Exception {
    var calls = new AtomicInteger();
    var service = service((profile, from, to) -> List.of(
        new DailySummary(profile, from, "{\"largeField\":\"" + "x".repeat(20_000) + "\"}")),
        prompt -> {
          calls.incrementAndGet();
          assertThat(isSelection(prompt)).isTrue();
          return response(WEEK);
        });

    var result = service.answer("What do you see from 2026-11-09 to 2026-11-15?");
    assertThat(result.answer()).contains("choose a shorter period", "No data was truncated");
    assertThat(result.startDate()).isEqualTo(start);
    assertThat(result.endDate()).isEqualTo(end);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void oversizedQuestionSkipsBothModelCallsAndTheDatabase() throws Exception {
    var service = service((profile, from, to) -> {
      throw new AssertionError("No query should happen");
    }, prompt -> { throw new AssertionError("No model call should happen"); });

    assertThat(service.answer("x".repeat(20_000)).answer()).contains("question is too long");
  }

  @Test
  void summariesWithoutStepsAndEmptyObjectsStillReachTheAnswerModel() throws Exception {
    var service = service((profile, from, to) -> List.of(
        new DailySummary(profile, from, "{}"),
        new DailySummary(profile, to, "{\"restingHeartRate\":55,\"totalSteps\":null}")),
        model(WEEK, prompt -> {
          assertThat(prompt.getInstructions().getLast().getText()).contains(
              "{}", "{\"restingHeartRate\":55,\"totalSteps\":null}");
          return response("There are no sleep measurements in these summaries.");
        }));

    assertThat(service.answer("How did I sleep last week?").answer())
        .contains("no sleep measurements");
  }

  @Test
  void unavailableModelSkipsDateExtractionAndDatabase() throws Exception {
    var service = service((profile, from, to) -> {
      throw new AssertionError("No query should happen");
    }, prompt -> { throw new AssertionError("No model call should happen"); }, () -> false);

    assertThatThrownBy(() -> service.answer("How did I sleep last week?"))
        .isInstanceOf(ChatModelUnavailableException.class).hasMessageContaining("retry shortly");
  }

  @Test
  void dateModelFailureSkipsQueryAndRemainsRetryable() throws Exception {
    var failure = new IllegalStateException("synthetic date model failure");
    var service = service((profile, from, to) -> {
      throw new AssertionError("No query should happen");
    }, prompt -> { throw failure; });

    assertThatThrownBy(() -> service.answer("Last week?"))
        .isInstanceOf(ChatModelUnavailableException.class).hasCause(failure);
  }

  @Test
  void answerModelFailureRemainsRetryable() throws Exception {
    var failure = new IllegalStateException("synthetic answer failure");
    var service = service(withData(), model(WEEK, prompt -> { throw failure; }));

    assertThatThrownBy(() -> service.answer("Last week?"))
        .isInstanceOf(ChatModelUnavailableException.class).hasCause(failure);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void missingAnswerTextIsAModelFailure(String text) throws Exception {
    var service = service(withData(), model(WEEK, prompt -> text == null ? null : response(text)));

    assertThatThrownBy(() -> service.answer("Last week?"))
        .isInstanceOf(ChatModelUnavailableException.class);
  }

  @Test
  void databaseFailureSkipsAnswerCall() throws Exception {
    var failure = new DataAccessResourceFailureException("synthetic database failure");
    var service = service((profile, from, to) -> { throw failure; }, model(WEEK, prompt -> {
      throw new AssertionError("No answer should be requested");
    }));

    assertThatThrownBy(() -> service.answer("Last week?")).isSameAs(failure);
  }

  @Test
  void questionsAreIndependentAndBothStagesRunAgain() throws Exception {
    var calls = new ArrayList<Prompt>();
    var loads = new AtomicInteger();
    var service = service((profile, from, to) -> {
      loads.incrementAndGet();
      return List.of(new DailySummary(profile, from, "{\"totalSteps\":0}"));
    }, prompt -> {
      calls.add(prompt);
      return response(isSelection(prompt) ? WEEK : "Synthetic answer");
    });

    service.answer("First question last week?");
    service.answer("Second question last week?");
    assertThat(loads.get()).isEqualTo(2);
    assertThat(calls).hasSize(4);
    assertThat(calls.get(2).getContents()).doesNotContain("First question", "Synthetic answer");
    assertThat(calls.get(3).getContents()).doesNotContain("First question", "Synthetic answer");
  }

  private DailySummaryRepository withData() {
    return (profile, from, to) -> List.of(new DailySummary(profile, from, "{\"totalSteps\":0}"));
  }
}
