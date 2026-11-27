package com.horacio.unleashed;

/** Only request validation uses this type. Android may also throw IllegalArgumentException. */
final class InvalidToolArgumentsException extends IllegalArgumentException {
  InvalidToolArgumentsException(String message) {
    super(message);
  }
}
