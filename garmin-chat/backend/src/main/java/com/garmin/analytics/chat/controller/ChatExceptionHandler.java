package com.garmin.analytics.chat.controller;

import com.garmin.analytics.chat.ai.exception.ChatModelUnavailableException;
import com.garmin.analytics.chat.execution.exception.ChatRequestInterruptedException;
import com.garmin.analytics.chat.execution.exception.ChatRequestTimeoutException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = GarminChatController.class)
public class ChatExceptionHandler {

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  public ProblemDetail invalidRequest(Exception failure) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST, "Provide a nonblank message in a JSON object.");
  }

  @ExceptionHandler({ChatModelUnavailableException.class, ChatRequestTimeoutException.class,
      ChatRequestInterruptedException.class})
  public ResponseEntity<ProblemDetail> chatUnavailable(RuntimeException failure) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "10")
        .body(ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE, failure.getMessage()));
  }

  @ExceptionHandler(RuntimeException.class)
  public ProblemDetail unexpectedError(RuntimeException failure) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
        "The chat request could not be completed.");
  }

  @ExceptionHandler(DataAccessException.class)
  public ProblemDetail readingsUnavailable(DataAccessException failure) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
        "Garmin readings could not be loaded. Check the database and read-only account.");
  }
}
