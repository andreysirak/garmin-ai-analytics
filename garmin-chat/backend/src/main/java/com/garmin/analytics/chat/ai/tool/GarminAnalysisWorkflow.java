package com.garmin.analytics.chat.ai.tool;

import com.garmin.analytics.chat.ai.ChatCompletion;
import com.garmin.analytics.chat.ai.ChatModelTurnAdvisor;
import com.garmin.analytics.chat.ai.prompt.GarminChatPromptFactory;
import com.garmin.analytics.chat.ai.prompt.PromptBudget;
import com.garmin.analytics.chat.analytics.exception.InvalidAnalyticsRequest;
import com.garmin.analytics.chat.execution.ChatDeadline;
import com.garmin.analytics.chat.model.AnalyticsSelection;
import com.garmin.analytics.chat.model.ChatAnswer;
import java.time.LocalDate;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Component;

/** Runs the existing Spring AI tool workflow for an already routed analysis. */
@Component
public class GarminAnalysisWorkflow {

  private final ChatClient client;
  private final GarminChatPromptFactory prompts;
  private final GarminAnalyticsToolFactory tools;
  private final PromptBudget budget;
  private final GarminToolArguments arguments;
  private final ChatModelTurnAdvisor modelTurn;
  private final ChatCompletion completion;

  public GarminAnalysisWorkflow(ChatClient.Builder client, GarminChatPromptFactory prompts,
      GarminAnalyticsToolFactory tools, PromptBudget budget, GarminToolArguments arguments,
      ChatModelTurnAdvisor modelTurn, ChatCompletion completion) {
    this.client = client.build();
    this.prompts = prompts;
    this.tools = tools;
    this.budget = budget;
    this.arguments = arguments;
    this.modelTurn = modelTurn;
    this.completion = completion;
  }

  public ChatAnswer analyze(String question, LocalDate referenceDate, AnalyticsSelection selection,
      ChatDeadline deadline) {
    var tool = tools.create(deadline, referenceDate, selection);
    var prompt = prompts.analysis(question, selection, ToolCallbacks.from(tool));
    if (!budget.fits(prompt)) {
      return completion.contextLimit();
    }
    var toolAdvisor = new GarminToolCallAdvisor(arguments, deadline);
    try {
      var result = client.prompt(prompt).advisors(spec -> spec.advisors(toolAdvisor, modelTurn)
          .param(ChatModelTurnAdvisor.DEADLINE, deadline)).call().chatResponse().getResult();
      return completion.analysis(result.getOutput().getText(), tool.evidence(),
          "length".equalsIgnoreCase(result.getMetadata().getFinishReason()));
    } catch (InvalidAnalyticsRequest rejected) {
      return completion.failed(rejected.getMessage(), tool.evidence());
    } catch (RuntimeException failure) {
      completion.executionFailed(tool.evidence() != null);
      throw failure;
    }
  }
}
