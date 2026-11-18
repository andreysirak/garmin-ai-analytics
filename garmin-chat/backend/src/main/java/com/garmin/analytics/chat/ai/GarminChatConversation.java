package com.garmin.analytics.chat.ai;

import com.garmin.analytics.chat.execution.ChatDeadline;
import com.garmin.analytics.chat.model.ChatAnswer;

/** Runs a chat conversation within the caller's execution deadline. */
public interface GarminChatConversation {

  ChatAnswer answer(String question, ChatDeadline deadline);
}
