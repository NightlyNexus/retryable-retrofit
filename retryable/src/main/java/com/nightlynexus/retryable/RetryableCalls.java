package com.nightlynexus.retryable;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.CallAdapter;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;

public final class RetryableCalls {
  private final CallAdapter.Factory factory = new RetryingCallAdapterFactory(this);
  private final Map<RealRetryableCall, Callback<Object>> failedCalls = new LinkedHashMap<>();
  private final Object lock = new Object();

  public CallAdapter.Factory getFactory() {
    return factory;
  }

  public void retryAllCalls() {
    Map<Call<Object>, Callback<Object>> calls;
    synchronized (lock) {
      calls = new LinkedHashMap<>(failedCalls.size());
      for (Map.Entry<RealRetryableCall, Callback<Object>> entry : failedCalls.entrySet()) {
        calls.put(entry.getKey().cloned(), entry.getValue());
      }
      failedCalls.clear();
    }
    for (Map.Entry<Call<Object>, Callback<Object>> entry : calls.entrySet()) {
      entry.getKey().enqueue(entry.getValue());
    }
  }

  public void clearCalls() {
    synchronized (lock) {
      failedCalls.clear();
    }
  }

  void addCall(RealRetryableCall call, Callback<Object> callback) {
    synchronized (lock) {
      failedCalls.put(call, callback);
    }
  }

  void removeCall(RealRetryableCall call) {
    synchronized (lock) {
      failedCalls.remove(call);
    }
  }

  private static final class RetryingCallAdapterFactory extends CallAdapter.Factory {
    RetryingCallAdapterFactory(RetryableCalls retryingThing) {
      this.retryableCalls = retryingThing;
    }

    final RetryableCalls retryableCalls;

    @Override public CallAdapter<?, ?> get(Type type, Annotation[] annotations, Retrofit retrofit) {
      if (getRawType(type) != RetryableCall.class) return null;
      if (!(type instanceof ParameterizedType)) {
        throw new IllegalArgumentException("RetryableCall return type must be parameterized "
            + "as RetryableCall<Foo> or RetryableCall<? extends Foo>");
      }
      final Type responseType = getParameterUpperBound(0, (ParameterizedType) type);
      final Executor executor = retrofit.callbackExecutor();

      return new CallAdapter<Object, RetryableCall<Object>>() {
        @Override public Type responseType() {
          return responseType;
        }

        @Override public RetryableCall<Object> adapt(Call<Object> call) {
          return new RealRetryableCall(call, executor, retryableCalls);
        }
      };
    }
  }

  private static final class RealRetryableCall implements RetryableCall<Object> {
    final Call<Object> delegate;
    final Executor callbackExecutor;
    final RetryableCalls retryableCalls;
    private Call<Object> cloned;

    RealRetryableCall(Call<Object> delegate, Executor callbackExecutor,
        RetryableCalls retryableCalls) {
      this.delegate = delegate;
      this.callbackExecutor = callbackExecutor;
      this.retryableCalls = retryableCalls;
    }

    Call<Object> cloned() {
      cloned = delegate.clone();
      return cloned;
    }

    @Override public void enqueue(final RetryableCallback<Object> callback) {
      delegate.enqueue(new Callback<Object>() {
        @Override public void onResponse(Call<Object> call, final Response<Object> response) {
          retryableCalls.removeCall(RealRetryableCall.this);
          if (callbackExecutor == null) {
            callback.onResponse(RealRetryableCall.this, response);
          } else {
            callbackExecutor.execute(new Runnable() {
              @Override public void run() {
                if (delegate.isCanceled()) {
                  // Emulate OkHttp's behavior of delivering an IOException on cancellation.
                  callback.onFailure(RealRetryableCall.this, new IOException("Canceled"));
                } else {
                  callback.onResponse(RealRetryableCall.this, response);
                }
              }
            });
          }
        }

        @Override public void onFailure(Call<Object> call, final Throwable t) {
          if (delegate.isCanceled()) {
            if (callbackExecutor == null) {
              callback.onFailure(RealRetryableCall.this, t);
            } else {
              callbackExecutor.execute(new Runnable() {
                @Override public void run() {
                  callback.onFailure(RealRetryableCall.this, t);
                }
              });
            }
          } else {
            if (t instanceof IOException) {
              retryableCalls.addCall(RealRetryableCall.this, this);
              if (callbackExecutor == null) {
                callback.onFailureButCanRetry(RealRetryableCall.this, (IOException) t);
              } else {
                callbackExecutor.execute(new Runnable() {
                  @Override public void run() {
                    if (delegate.isCanceled()) {
                      callback.onFailure(RealRetryableCall.this, t);
                    } else {
                      callback.onFailureButCanRetry(RealRetryableCall.this, (IOException) t);
                    }
                  }
                });
              }
            } else {
              retryableCalls.removeCall(RealRetryableCall.this);
              if (callbackExecutor == null) {
                callback.onFailure(RealRetryableCall.this, t);
              } else {
                callbackExecutor.execute(new Runnable() {
                  @Override public void run() {
                    callback.onFailure(RealRetryableCall.this, t);
                  }
                });
              }
            }
          }
        }
      });
    }

    @Override public boolean isExecuted() {
      return delegate.isExecuted();
    }

    @Override public void cancel() {
      retryableCalls.removeCall(RealRetryableCall.this);
      delegate.cancel();
      if (cloned != null) cloned.cancel();
    }

    @Override public boolean isCanceled() {
      return delegate.isCanceled();
    }

    @Override public RealRetryableCall clone() {
      return new RealRetryableCall(delegate.clone(), callbackExecutor, retryableCalls);
    }

    @Override public Request request() {
      return delegate.request();
    }
  }
}
