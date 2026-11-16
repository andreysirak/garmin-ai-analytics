package com.garmin.analytics.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GarminChatControllerTest {

  private final LocalDate start = LocalDate.of(2026, 11, 9);
  private final LocalDate end = LocalDate.of(2026, 11, 15);

  @Test
  void acceptsTheExactQuestionAndReturnsAnswerAndStagedDates() throws Exception {
    var mvc = mvc((profile, from, to) -> List.of(
        new DailySummary(123L, start, "{\"totalSteps\":0}"),
        new DailySummary(123L, end, "{\"totalSteps\":1}")), prompt -> {
      assertThat(prompt.getInstructions().getLast().getText()).endsWith("  Steps?  ");
      return new ChatResponse(List.of(new Generation(new AssistantMessage("0 steps recorded."))));
    });

    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"  Steps?  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value("0 steps recorded."))
        .andExpect(jsonPath("$.startDate").value("2026-11-09"))
        .andExpect(jsonPath("$.endDate").value("2026-11-15"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"message\":null}", "{\"message\":\"\"}",
      "{\"message\":\"  \\n\\t\"}", "{", "null"})
  void rejectsMissingBlankAndMalformedRequestsBeforeLoadingData(String body) throws Exception {
    var loads = new AtomicInteger();
    var mvc = mvc((profile, from, to) -> {
      loads.incrementAndGet();
      return List.of();
    }, prompt -> { throw new AssertionError("Invalid requests must not reach the model"); });

    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Provide a nonblank message in a JSON object."));
    assertThat(loads.get()).isZero();
  }

  @Test
  void returnsSelectedDatesWhenThePeriodHasNoSummaries() throws Exception {
    var mvc = mvc((profile, from, to) -> List.of(), prompt -> {
      throw new AssertionError("Empty data must not reach the model");
    });

    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"Steps?\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.startDate").value("2026-11-09"))
        .andExpect(jsonPath("$.endDate").value("2026-11-15"));
  }

  @Test
  void returnsClarificationAsTextWithNoSelectedDates() throws Exception {
    var service = ChatTestSupport.service((profile, from, to) -> {
      throw new AssertionError("Clarification must not query PostgreSQL");
    }, prompt -> ChatTestSupport.response(
        "{\"type\":\"CLARIFICATION\",\"startDate\":\"\",\"endDate\":\"\",\"days\":0}"));

    mvc(service).perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"How did I sleep?\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value(
            org.hamcrest.Matchers.containsString("Which dates should I use?")))
        .andExpect(jsonPath("$.startDate").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.endDate").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void returns503WithoutLeakingModelFailureDetails() throws Exception {
    var mvc = mvc((profile, from, to) -> List.of(
        new DailySummary(123L, start, "{\"totalSteps\":100}")), prompt -> {
      throw new IllegalStateException("private model details");
    });

    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"Steps?\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "10"))
        .andExpect(jsonPath("$.detail").value(
            "The chat model is initializing or unavailable. Please retry shortly. "
            + "Check the model-pull Job and Ollama status if this persists."));
  }

  @Test
  void returns503WithoutLeakingDatabaseFailureDetails() throws Exception {
    var mvc = mvc((profile, from, to) -> {
      throw new DataAccessResourceFailureException("private connection details");
    }, prompt -> { throw new AssertionError("Failed reads must not reach the model"); });

    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"Steps?\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.detail").value(
            "Garmin readings could not be loaded. Check the database and read-only account."));
  }

  private MockMvc mvc(DailySummaryRepository repository, ChatModel model) throws IOException {
    return mvc(ChatTestSupport.service(repository,
        ChatTestSupport.model(ChatTestSupport.WEEK, model::call)));
  }

  private MockMvc mvc(GarminChatService service) {
    return MockMvcBuilders.standaloneSetup(new GarminChatController(service))
        // Match Spring Boot's ISO date serialization in this standalone MVC test.
        .setMessageConverters(new MappingJackson2HttpMessageConverter(
            Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
        .setControllerAdvice(new ChatExceptionHandler()).build();
  }
}
