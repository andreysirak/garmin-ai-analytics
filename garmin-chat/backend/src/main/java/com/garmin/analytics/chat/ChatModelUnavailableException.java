package com.garmin.analytics.chat;

public class ChatModelUnavailableException extends RuntimeException {

  public ChatModelUnavailableException(Throwable cause) {
    super("The chat model is initializing or unavailable. Please retry shortly. "
        + "Check the model-pull Job and Ollama status if this persists.", cause);
  }
}
