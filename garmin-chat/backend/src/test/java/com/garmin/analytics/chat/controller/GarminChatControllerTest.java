package com.garmin.analytics.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.garmin.analytics.chat.service.GarminChatService;
import com.garmin.analytics.chat.support.ChatTestSupport;
import com.garmin.analytics.chat.support.ChatTestSupport.AggregationLoader;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.web.client.ResourceAccessException;

class GarminChatControllerTest {

  @Test
  void acceptsTheExactQuestionAndReturnsAnswerAndResolvedDates() throws Exception {
    var mvc = mvc(query -> List.of(ChatTestSupport.result(query, "0")), prompt -> {
      try {
        var evidence = new ObjectMapper().readTree(ChatTestSupport.toolEvidence(prompt));
        assertThat(prompt.getUserMessage().getText()).isEqualTo("  Total steps last week?  ");
        assertThat(evidence.at("/metrics/0/value").intValue()).isZero();
      } catch (IOException failure) {
        throw new AssertionError(failure);
      }
      return new ChatResponse(List.of(new Generation(new AssistantMessage("0 steps recorded."))));
    });

    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"  Total steps last week?  \"}"))
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
    var mvc = mvc(query -> {
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
    var mvc = mvc(query -> List.of(ChatTestSupport.result(query, null)), prompt -> {
      try {
        var evidence = new ObjectMapper().readTree(ChatTestSupport.toolEvidence(prompt));
        assertThat(evidence.at("/metrics/0/value").isNull()).isTrue();
        assertThat(evidence.at("/metrics/0/coverage/missingSummaryDays").intValue()).isEqualTo(7);
      } catch (IOException failure) {
        throw new AssertionError(failure);
      }
      return ChatTestSupport.response("No Garmin daily summaries are available.");
    });

    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"Steps?\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.startDate").value("2026-11-09"))
        .andExpect(jsonPath("$.endDate").value("2026-11-15"));
  }

  @Test
  void returnsClarificationAsTextWithNoSelectedDates() throws Exception {
    var service = ChatTestSupport.service((AggregationLoader) query -> {
      throw new AssertionError("Clarification must not query PostgreSQL");
    }, prompt -> ChatTestSupport.response("Please specify the sleep measurement and time period."));

    mvc(service).perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"How did I sleep?\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value(
            org.hamcrest.Matchers.containsString("Please specify")))
        .andExpect(jsonPath("$.startDate").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.endDate").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void returns503WithoutLeakingModelFailureDetails() throws Exception {
    var mvc = mvc(ChatTestSupport.service((AggregationLoader) query -> {
      throw new AssertionError("Model failure must not query PostgreSQL");
    }, prompt -> {
      throw new ResourceAccessException("private model details");
    }));

    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"Steps?\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "10"))
        .andExpect(jsonPath("$.detail").value(
            "The chat model is initializing or unavailable. Please retry shortly. "
            + "Check the model-pull Job and Ollama status if this persists."));
  }

  @Test
  void returnsSafe500ForAnUnexpectedApplicationFailure() throws Exception {
    var mvc = mvc(ChatTestSupport.service((AggregationLoader) query -> {
      throw new AssertionError("Model failure must not query PostgreSQL");
    }, prompt -> {
      throw new IllegalStateException("private application details");
    }));

    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"Steps?\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(header().doesNotExist("Retry-After"))
        .andExpect(jsonPath("$.detail").value("The chat request could not be completed."));
  }

  @ParameterizedTest
  @MethodSource("timeouts")
  void explainsModelTimeoutsWithoutLeakingTransportDetails(IOException timeout) throws Exception {
    var mvc = mvc(ChatTestSupport.service((AggregationLoader) query -> {
      throw new AssertionError("Model failure must not query PostgreSQL");
    }, prompt -> {
      throw new ResourceAccessException("private model URL and request details", timeout);
    }));

    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"How did I sleep last week?\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "10"))
        .andExpect(jsonPath("$.detail").value(
            "The local model took too long to respond. Please choose a shorter period "
            + "or retry when the model is less busy."));
  }

  private static Stream<IOException> timeouts() {
    return Stream.of(new HttpTimeoutException("private transport details"),
        new SocketTimeoutException("private transport details"));
  }

  @Test
  void returns503WithoutLeakingDatabaseFailureDetails() throws Exception {
    var mvc = mvc(query -> {
      throw new DataAccessResourceFailureException("private connection details");
    }, prompt -> { throw new AssertionError("Failed reads must not reach the model"); });

    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"Steps?\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.detail").value(
            "Garmin readings could not be loaded. Check the database and read-only account."));
  }

  private MockMvc mvc(AggregationLoader repository, ChatModel model) throws IOException {
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
