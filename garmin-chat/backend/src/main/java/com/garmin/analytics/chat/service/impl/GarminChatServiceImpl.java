package com.garmin.analytics.chat.service.impl;

import com.garmin.analytics.chat.ai.GarminChatConversation;
import com.garmin.analytics.chat.execution.ChatRequestExecutor;
import com.garmin.analytics.chat.model.ChatAnswer;
import com.garmin.analytics.chat.service.GarminChatService;
import org.springframework.stereotype.Service;

@Service
public class GarminChatServiceImpl implements GarminChatService {

  private final ChatRequestExecutor requests;
  private final GarminChatConversation conversation;

  public GarminChatServiceImpl(ChatRequestExecutor requests, GarminChatConversation conversation) {
    this.requests = requests;
    this.conversation = conversation;
  }

  @Override
  public ChatAnswer answer(String question) {
    return requests.execute(deadline -> conversation.answer(question, deadline));
  }
}
