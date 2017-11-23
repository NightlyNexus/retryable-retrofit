package com.nightlynexus.retryable;

import java.io.IOException;
import retrofit2.Response;

public interface RetryableCallback<T> {
  void onResponse(RetryableCall<T> call, Response<T> response);

  void onFailure(RetryableCall<T> call, Throwable t);

  void onFailureButCanRetry(RetryableCall<T> call, IOException e);
}
