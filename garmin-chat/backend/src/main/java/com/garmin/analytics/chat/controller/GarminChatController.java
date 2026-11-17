package com.garmin.analytics.chat.controller;

import com.garmin.analytics.chat.model.ChatAnswer;
import com.garmin.analytics.chat.service.GarminChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class GarminChatController {

  private final GarminChatService service;

  public GarminChatController(GarminChatService service) {
    this.service = service;
  }

  @PostMapping
  public ChatAnswer chat(@Valid @RequestBody ChatRequest request) {
    return service.answer(request.message());
  }

  public record ChatRequest(@NotBlank String message) {}
}
