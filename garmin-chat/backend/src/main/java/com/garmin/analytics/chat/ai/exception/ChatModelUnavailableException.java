package com.garmin.analytics.chat.ai.exception;

public class ChatModelUnavailableException extends RuntimeException {

  public ChatModelUnavailableException(Throwable cause) {
    super("The chat model is initializing or unavailable. Please retry shortly. "
        + "Check the model-pull Job and Ollama status if this persists.", cause);
  }

  private ChatModelUnavailableException(String message) {
    super(message);
  }

  public static ChatModelUnavailableException toolsUnsupported() {
    return new ChatModelUnavailableException(
        "Ollama does not report tool calling for the configured model. "
            + "Check the installed model's capabilities.");
  }
}
