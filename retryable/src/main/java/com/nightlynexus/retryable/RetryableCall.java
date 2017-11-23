package com.nightlynexus.retryable;

import okhttp3.Request;

public interface RetryableCall<T> extends Cloneable {
  void enqueue(RetryableCallback<T> callback);

  boolean isExecuted();

  void cancel();

  boolean isCanceled();

  RetryableCall<T> clone();

  Request request();
}
