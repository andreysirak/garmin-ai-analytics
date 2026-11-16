package com.garmin.analytics.chat;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ChatExceptionHandler {

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  public ProblemDetail invalidRequest(Exception failure) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST, "Provide a nonblank message in a JSON object.");
  }

  @ExceptionHandler(ChatModelUnavailableException.class)
  public ResponseEntity<ProblemDetail> modelUnavailable(ChatModelUnavailableException failure) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "10")
        .body(ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE, failure.getMessage()));
  }

  @ExceptionHandler(DataAccessException.class)
  public ProblemDetail readingsUnavailable(DataAccessException failure) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
        "Garmin readings could not be loaded. Check the database and read-only account.");
  }
}
