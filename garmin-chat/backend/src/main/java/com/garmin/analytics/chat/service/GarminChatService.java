package com.garmin.analytics.chat.service;

import com.garmin.analytics.chat.model.ChatAnswer;

/** Chat entry point for a single user question. */
public interface GarminChatService {

  ChatAnswer answer(String question);
}
