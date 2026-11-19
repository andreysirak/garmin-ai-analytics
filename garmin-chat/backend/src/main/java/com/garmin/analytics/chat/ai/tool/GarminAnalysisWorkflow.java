package com.garmin.analytics.chat.ai.tool;

import com.garmin.analytics.chat.ai.ChatCompletion;
import com.garmin.analytics.chat.ai.ChatModelTurnAdvisor;
import com.garmin.analytics.chat.ai.prompt.GarminChatPromptFactory;
import com.garmin.analytics.chat.ai.prompt.PromptBudget;
import com.garmin.analytics.chat.analytics.GarminAnalyticsService;
import com.garmin.analytics.chat.analytics.exception.InvalidAnalyticsRequest;
import com.garmin.analytics.chat.execution.ChatDeadline;
import com.garmin.analytics.chat.model.AnalyticsSelection;
import com.garmin.analytics.chat.model.ChatAnswer;
import com.garmin.analytics.chat.model.QueryEvidence;
import java.time.LocalDate;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** Executes the validated selection once, then asks the model to explain its evidence. */
@Component
public class GarminAnalysisWorkflow {

  private final ChatClient client;
  private final GarminChatPromptFactory prompts;
  private final GarminAnalyticsService analytics;
  private final PromptBudget budget;
  private final ChatModelTurnAdvisor modelTurn;
  private final ChatCompletion completion;

  public GarminAnalysisWorkflow(ChatClient.Builder client, GarminChatPromptFactory prompts,
      GarminAnalyticsService analytics, PromptBudget budget,
      ChatModelTurnAdvisor modelTurn, ChatCompletion completion) {
    this.client = client.build();
    this.prompts = prompts;
    this.analytics = analytics;
    this.budget = budget;
    this.modelTurn = modelTurn;
    this.completion = completion;
  }

  public ChatAnswer analyze(String question, LocalDate referenceDate, AnalyticsSelection selection,
      ChatDeadline deadline) {
    QueryEvidence evidence = null;
    try {
      deadline.check();
      evidence = analytics.aggregate(selection.metrics(), selection.aggregation(),
          selection.period(), referenceDate);
      deadline.check();
      if (evidence.metrics().stream().allMatch(metric -> metric.value() == null)) {
        return completion.analysis(null, evidence, false);
      }
      var prompt = prompts.analysis(question, evidence);
      if (!budget.fits(prompt)) {
        return completion.failed(
            "The complete question and Garmin evidence exceed the model context. "
                + "Please shorten your question or choose fewer metrics.", evidence);
      }
      var response = client.prompt(prompt).advisors(spec -> spec.advisors(modelTurn)
          .param(ChatModelTurnAdvisor.DEADLINE, deadline)).call().chatResponse();
      deadline.check();
      if (response.hasToolCalls()) {
        return completion.failed("I retrieved Garmin readings but the model did not return "
            + "a completed explanation.", evidence);
      }
      var result = response.getResult();
      return completion.analysis(result.getOutput().getText(), evidence,
          "length".equalsIgnoreCase(result.getMetadata().getFinishReason()));
    } catch (InvalidAnalyticsRequest rejected) {
      return completion.failed(rejected.getMessage(), evidence);
    } catch (RuntimeException failure) {
      completion.executionFailed(evidence != null);
      throw failure;
    }
  }
}
