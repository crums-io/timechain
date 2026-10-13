/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc.except;

import io.crums.util.api.FactoryException;

/**
 * Base of the time chain exception hierarchy.
 * Unchecked.
 */
@SuppressWarnings("serial")
public class TimeChainException extends FactoryException {

  public TimeChainException() {  }

  public TimeChainException(String message) {
    super(message);
  }

  public TimeChainException(Throwable cause) {
    super(cause);
  }

  public TimeChainException(String message, Throwable cause) {
    super(message, cause);
  }

  public TimeChainException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
    super(message, cause, enableSuppression, writableStackTrace);
  }

}
