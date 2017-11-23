package com.nightlynexus.retryable;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import retrofit2.Converter;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.http.GET;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.fail;

@RunWith(JUnit4.class)
public final class RetryableCallsTest {
  private interface Service {
    @GET("/") RetryableCall<String> getString();
  }

  @Test public void responseOnTheFirstTry() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    final AtomicReference<Response<String>> responseRef = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setBody("Hi"));

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        responseRef.set(response);
        latch.countDown();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        throw new AssertionError();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        throw new AssertionError();
      }
    });
    assertThat(latch.await(10, SECONDS)).isTrue();

    Response<String> response = responseRef.get();
    assertThat(response.isSuccessful()).isTrue();
    assertThat(response.body()).isEqualTo("Hi");
  }

  @Test public void canRetryMultipleTimes() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    final AtomicInteger responseCount = new AtomicInteger();
    final AtomicInteger failureCanRetryCount = new AtomicInteger();
    final CountDownLatch responseLatch = new CountDownLatch(1);
    final CountDownLatch failureCanRetryLatch = new CountDownLatch(1);
    final CountDownLatch failureCanRetryTwiceLatch = new CountDownLatch(2);

    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        responseCount.incrementAndGet();
        responseLatch.countDown();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        throw new AssertionError();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        failureCanRetryCount.incrementAndGet();
        failureCanRetryLatch.countDown();
        failureCanRetryTwiceLatch.countDown();
      }
    });
    assertThat(failureCanRetryLatch.await(10, SECONDS)).isTrue();
    assertThat(failureCanRetryCount.get()).isEqualTo(1);
    assertThat(responseCount.get()).isEqualTo(0);

    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

    retryableCalls.retryAllCalls();
    assertThat(failureCanRetryTwiceLatch.await(10, SECONDS)).isTrue();
    assertThat(failureCanRetryCount.get()).isEqualTo(2);
    assertThat(responseCount.get()).isEqualTo(0);

    server.enqueue(new MockResponse().setBody("Hi"));

    retryableCalls.retryAllCalls();
    assertThat(responseLatch.await(10, SECONDS)).isTrue();
    assertThat(failureCanRetryCount.get()).isEqualTo(2);
    assertThat(responseCount.get()).isEqualTo(1);
  }

  @Test public void failureRemovesCall() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new Converter.Factory() {
          @Override public Converter<ResponseBody, ?> responseBodyConverter(Type type,
              Annotation[] annotations,
              Retrofit retrofit) {
            return new Converter<ResponseBody, Object>() {
              @Override public Object convert(ResponseBody value) throws IOException {
                throw new RuntimeException("Broken!");
              }
            };
          }
        })
        .build();
    Service service = retrofit.create(Service.class);

    final AtomicInteger failureCount = new AtomicInteger();
    final AtomicInteger failureCanRetryCount = new AtomicInteger();
    final CountDownLatch failureLatch = new CountDownLatch(1);
    final CountDownLatch failureTwiceLatch = new CountDownLatch(2);
    final CountDownLatch failureCanRetryLatch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        throw new AssertionError();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        RuntimeException runtimeException = (RuntimeException) t;
        assertThat(runtimeException).hasMessageThat().isEqualTo("Broken!");
        failureCount.incrementAndGet();
        failureLatch.countDown();
        failureTwiceLatch.countDown();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        failureCanRetryCount.incrementAndGet();
        failureCanRetryLatch.countDown();
      }
    });
    assertThat(failureCanRetryLatch.await(10, SECONDS)).isTrue();
    assertThat(failureCanRetryCount.get()).isEqualTo(1);
    assertThat(failureCount.get()).isEqualTo(0);

    server.enqueue(new MockResponse().setBody("Hi"));

    retryableCalls.retryAllCalls();
    assertThat(failureLatch.await(10, SECONDS)).isTrue();
    assertThat(failureCanRetryCount.get()).isEqualTo(1);
    assertThat(failureCount.get()).isEqualTo(1);

    server.enqueue(new MockResponse().setBody("Hi"));

    retryableCalls.retryAllCalls();
    assertThat(failureTwiceLatch.await(10, SECONDS)).isFalse();
    assertThat(failureCanRetryCount.get()).isEqualTo(1);
    assertThat(failureCount.get()).isEqualTo(1);
  }

  @Test public void retryingMultipleTimesDoesNothing() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    final AtomicInteger responseCount = new AtomicInteger();
    final AtomicInteger failureCanRetryCount = new AtomicInteger();
    final CountDownLatch responseLatch = new CountDownLatch(1);
    final CountDownLatch responseTwiceLatch = new CountDownLatch(2);
    final CountDownLatch failureCanRetryLatch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        responseCount.incrementAndGet();
        responseLatch.countDown();
        responseTwiceLatch.countDown();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        throw new AssertionError();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        failureCanRetryCount.incrementAndGet();
        failureCanRetryLatch.countDown();
      }
    });
    assertThat(failureCanRetryLatch.await(10, SECONDS)).isTrue();
    assertThat(failureCanRetryCount.get()).isEqualTo(1);
    assertThat(responseCount.get()).isEqualTo(0);

    server.enqueue(new MockResponse().setBody("Hi"));

    retryableCalls.retryAllCalls();
    retryableCalls.retryAllCalls();
    assertThat(responseLatch.await(10, SECONDS)).isTrue();
    assertThat(failureCanRetryCount.get()).isEqualTo(1);
    assertThat(responseCount.get()).isEqualTo(1);

    server.enqueue(new MockResponse().setBody("Hi"));

    retryableCalls.retryAllCalls();
    assertThat(responseTwiceLatch.await(10, SECONDS)).isFalse();
    assertThat(failureCanRetryCount.get()).isEqualTo(1);
    assertThat(responseCount.get()).isEqualTo(1);
  }

  @Test public void cancelRemovesCall() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    final AtomicInteger responseCount = new AtomicInteger();
    final AtomicInteger failureCanRetryCount = new AtomicInteger();
    final CountDownLatch responseLatch = new CountDownLatch(1);
    final CountDownLatch responseTwiceLatch = new CountDownLatch(2);
    final CountDownLatch failureCanRetryLatch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        responseCount.incrementAndGet();
        responseLatch.countDown();
        responseTwiceLatch.countDown();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        throw new AssertionError();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        failureCanRetryCount.incrementAndGet();
        failureCanRetryLatch.countDown();
      }
    });
    assertThat(failureCanRetryLatch.await(10, SECONDS)).isTrue();
    assertThat(failureCanRetryCount.get()).isEqualTo(1);
    assertThat(responseCount.get()).isEqualTo(0);

    server.enqueue(new MockResponse().setBody("Hi"));

    call.cancel();

    retryableCalls.retryAllCalls();
    assertThat(responseTwiceLatch.await(10, SECONDS)).isFalse();
    assertThat(failureCanRetryCount.get()).isEqualTo(1);
    assertThat(responseCount.get()).isEqualTo(0);
  }

  @Test public void cancelGoesToOnFailureWhenInFlight() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    final CountDownLatch failureLatch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setBody("Hi").setBodyDelay(1, SECONDS));

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        throw new AssertionError();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        assertThat(t).isInstanceOf(IOException.class);
        assertThat(call.isCanceled()).isTrue();
        failureLatch.countDown();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        throw new AssertionError();
      }
    });
    call.cancel();
    assertThat(failureLatch.await(10, SECONDS)).isTrue();
  }

  @Test public void cancelBetweenOnResponseAndExecutorRunGoesToOnFailure()
      throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .callbackExecutor(new Executor() {
          @Override public void execute(Runnable command) {
            try {
              Thread.sleep(SECONDS.toMillis(2));
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
            command.run();
          }
        })
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    final CountDownLatch failureLatch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setBody("Hi"));

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        throw new AssertionError();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        assertThat(t).isInstanceOf(IOException.class);
        assertThat(call.isCanceled()).isTrue();
        failureLatch.countDown();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        throw new AssertionError();
      }
    });
    Thread.sleep(SECONDS.toMillis(1));
    call.cancel();
    assertThat(failureLatch.await(10, SECONDS)).isTrue();
  }

  @Test public void cancelBetweenOnFailureCanRetryAndExecutorRunGoesToOnFailure()
      throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .callbackExecutor(new Executor() {
          @Override public void execute(Runnable command) {
            try {
              Thread.sleep(SECONDS.toMillis(2));
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
            command.run();
          }
        })
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    final CountDownLatch failureLatch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        throw new AssertionError();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        assertThat(t).isInstanceOf(IOException.class);
        assertThat(call.isCanceled()).isTrue();
        failureLatch.countDown();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        throw new AssertionError();
      }
    });
    Thread.sleep(SECONDS.toMillis(1));
    call.cancel();
    assertThat(failureLatch.await(10, SECONDS)).isTrue();
  }

  @Test public void cancelBetweenOnFailureAndExecutorRunDoesNothing()
      throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .callbackExecutor(new Executor() {
          @Override public void execute(Runnable command) {
            try {
              Thread.sleep(SECONDS.toMillis(2));
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
            command.run();
          }
        })
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new Converter.Factory() {
          @Override public Converter<ResponseBody, ?> responseBodyConverter(Type type,
              Annotation[] annotations,
              Retrofit retrofit) {
            return new Converter<ResponseBody, Object>() {
              @Override public Object convert(ResponseBody value) throws IOException {
                throw new RuntimeException("Broken!");
              }
            };
          }
        })
        .build();
    Service service = retrofit.create(Service.class);

    final CountDownLatch failureLatch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setBody("Hi"));

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        throw new AssertionError();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        RuntimeException runtimeException = (RuntimeException) t;
        assertThat(runtimeException).hasMessageThat().isEqualTo("Broken!");
        assertThat(call.isCanceled()).isTrue();
        failureLatch.countDown();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        throw new AssertionError();
      }
    });
    Thread.sleep(SECONDS.toMillis(1));
    call.cancel();
    assertThat(failureLatch.await(10, SECONDS)).isTrue();
  }

  @Test public void clearCalls() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    final AtomicInteger responseCount = new AtomicInteger();
    final AtomicInteger failureCanRetryCount = new AtomicInteger();
    final CountDownLatch responseLatch = new CountDownLatch(1);
    final CountDownLatch responseTwiceLatch = new CountDownLatch(2);
    final CountDownLatch failureCanRetryLatch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        responseCount.incrementAndGet();
        responseLatch.countDown();
        responseTwiceLatch.countDown();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        throw new AssertionError();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        failureCanRetryCount.incrementAndGet();
        failureCanRetryLatch.countDown();
      }
    });
    assertThat(failureCanRetryLatch.await(10, SECONDS)).isTrue();
    assertThat(failureCanRetryCount.get()).isEqualTo(1);
    assertThat(responseCount.get()).isEqualTo(0);

    server.enqueue(new MockResponse().setBody("Hi"));

    retryableCalls.clearCalls();

    retryableCalls.retryAllCalls();
    assertThat(responseTwiceLatch.await(10, SECONDS)).isFalse();
    assertThat(failureCanRetryCount.get()).isEqualTo(1);
    assertThat(responseCount.get()).isEqualTo(0);
  }

  @Test public void onlyIOExceptionsAreRetried() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new Converter.Factory() {
          @Override public Converter<ResponseBody, ?> responseBodyConverter(Type type,
              Annotation[] annotations,
              Retrofit retrofit) {
            return new Converter<ResponseBody, Object>() {
              @Override public Object convert(ResponseBody value) throws IOException {
                throw new RuntimeException("Broken!");
              }
            };
          }
        })
        .build();
    Service service = retrofit.create(Service.class);

    final AtomicInteger responseCount = new AtomicInteger();
    final AtomicInteger failureCount = new AtomicInteger();
    final CountDownLatch responseLatch = new CountDownLatch(1);
    final CountDownLatch failureLatch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setBody("Hi"));

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        responseCount.incrementAndGet();
        responseLatch.countDown();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        RuntimeException runtimeException = (RuntimeException) t;
        assertThat(runtimeException).hasMessageThat().isEqualTo("Broken!");
        failureCount.incrementAndGet();
        failureLatch.countDown();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        throw new AssertionError();
      }
    });
    assertThat(failureLatch.await(10, SECONDS)).isTrue();
    assertThat(failureCount.get()).isEqualTo(1);
    assertThat(responseCount.get()).isEqualTo(0);

    server.enqueue(new MockResponse().setBody("Hi"));

    retryableCalls.retryAllCalls();
    assertThat(responseLatch.await(10, SECONDS)).isFalse();
    assertThat(failureCount.get()).isEqualTo(1);
    assertThat(responseCount.get()).isEqualTo(0);
  }

  @Test public void callbackOnExecutorResponse() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    final AtomicInteger callbackExecutorRuns = new AtomicInteger();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .callbackExecutor(new Executor() {
          @Override public void execute(Runnable command) {
            callbackExecutorRuns.incrementAndGet();
            command.run();
          }
        })
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    final CountDownLatch responseLatch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setBody("Hi"));

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        responseLatch.countDown();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        throw new AssertionError();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        throw new AssertionError();
      }
    });
    assertThat(responseLatch.await(10, SECONDS)).isTrue();
    assertThat(callbackExecutorRuns.get()).isEqualTo(1);
  }

  @Test public void callbackOnExecutorFailures() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    final AtomicInteger callbackExecutorRuns = new AtomicInteger();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .callbackExecutor(new Executor() {
          @Override public void execute(Runnable command) {
            callbackExecutorRuns.incrementAndGet();
            command.run();
          }
        })
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new Converter.Factory() {
          @Override public Converter<ResponseBody, ?> responseBodyConverter(Type type,
              Annotation[] annotations,
              Retrofit retrofit) {
            return new Converter<ResponseBody, Object>() {
              @Override public Object convert(ResponseBody value) throws IOException {
                throw new RuntimeException("Broken!");
              }
            };
          }
        })
        .build();
    Service service = retrofit.create(Service.class);

    final CountDownLatch failureLatch = new CountDownLatch(1);
    final CountDownLatch failureCanRetryLatch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        throw new AssertionError();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        failureLatch.countDown();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        failureCanRetryLatch.countDown();
      }
    });

    assertThat(failureCanRetryLatch.await(10, SECONDS)).isTrue();
    assertThat(callbackExecutorRuns.get()).isEqualTo(1);

    server.enqueue(new MockResponse().setBody("Hi"));

    retryableCalls.retryAllCalls();
    assertThat(failureLatch.await(10, SECONDS)).isTrue();
    assertThat(callbackExecutorRuns.get()).isEqualTo(2);
  }

  @Test public void callbackHasOriginalCallResponse() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    final CountDownLatch responseLatch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setBody("Hi"));

    final AtomicReference<RetryableCall<String>> responseCallRef = new AtomicReference<>();

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        responseCallRef.set(call);
        responseLatch.countDown();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        throw new AssertionError();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        throw new AssertionError();
      }
    });
    assertThat(responseLatch.await(10, SECONDS)).isTrue();
    assertThat(responseCallRef.get()).isSameAs(call);
  }

  @Test public void callbackHasOriginalCallFailures() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new Converter.Factory() {
          @Override public Converter<ResponseBody, ?> responseBodyConverter(Type type,
              Annotation[] annotations,
              Retrofit retrofit) {
            return new Converter<ResponseBody, Object>() {
              @Override public Object convert(ResponseBody value) throws IOException {
                throw new RuntimeException("Broken!");
              }
            };
          }
        })
        .build();
    Service service = retrofit.create(Service.class);

    final CountDownLatch failureLatch = new CountDownLatch(1);
    final CountDownLatch failureCanRetryLatch = new CountDownLatch(1);
    final AtomicReference<RetryableCall<String>> failureCallRef = new AtomicReference<>();
    final AtomicReference<RetryableCall<String>> failureCanRetryCallRef = new AtomicReference<>();

    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        throw new AssertionError();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        failureCallRef.set(call);
        failureLatch.countDown();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        failureCanRetryCallRef.set(call);
        failureCanRetryLatch.countDown();
      }
    });

    assertThat(failureCanRetryLatch.await(10, SECONDS)).isTrue();
    assertThat(failureCanRetryCallRef.get()).isSameAs(call);

    server.enqueue(new MockResponse().setBody("Hi"));

    retryableCalls.retryAllCalls();
    assertThat(failureLatch.await(10, SECONDS)).isTrue();
    assertThat(failureCallRef.get()).isSameAs(call);
  }

  @Test public void retryableCallRequest() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);
    RetryableCall<String> d = service.getString();
    assertThat(d.request()).isNotNull();
  }

  @Test public void retryableCallClone() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    final CountDownLatch callLatch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setBody("Hi"));

    RetryableCall<String> call = service.getString();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        callLatch.countDown();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        throw new AssertionError();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        throw new AssertionError();
      }
    });
    assertThat(callLatch.await(10, SECONDS)).isTrue();

    RetryableCall<String> clone = call.clone();
    assertThat(clone).isNotSameAs(call);
    assertThat(clone.isExecuted()).isFalse();

    final CountDownLatch cloneLatch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setBody("Hi"));

    clone.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        cloneLatch.countDown();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        throw new AssertionError();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        throw new AssertionError();
      }
    });
    assertThat(cloneLatch.await(10, SECONDS)).isTrue();
  }

  @Test public void retryableCallIsExecuted() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    final CountDownLatch latch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setBody("Hi"));

    RetryableCall<String> call = service.getString();
    assertThat(call.isExecuted()).isFalse();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        latch.countDown();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        throw new AssertionError();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        throw new AssertionError();
      }
    });
    assertThat(call.isExecuted()).isTrue();
    assertThat(latch.await(10, SECONDS)).isTrue();
  }

  @Test public void retryableCallIsCanceledBeforeEnqueue() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    final CountDownLatch latch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setBody("Hi"));

    RetryableCall<String> call = service.getString();
    assertThat(call.isCanceled()).isFalse();
    call.cancel();
    assertThat(call.isCanceled()).isTrue();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        throw new AssertionError();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        latch.countDown();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        throw new AssertionError();
      }
    });
    assertThat(latch.await(10, SECONDS)).isTrue();
  }

  @Test public void retryableCallIsCanceledAfterEnqueue() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    Service service = retrofit.create(Service.class);

    final CountDownLatch latch = new CountDownLatch(1);

    server.enqueue(new MockResponse().setBody("Hi").setBodyDelay(1, SECONDS));

    RetryableCall<String> call = service.getString();
    assertThat(call.isCanceled()).isFalse();
    call.enqueue(new RetryableCallback<String>() {
      @Override public void onResponse(RetryableCall<String> call, Response<String> response) {
        throw new AssertionError();
      }

      @Override public void onFailure(RetryableCall<String> call, Throwable t) {
        latch.countDown();
      }

      @Override public void onFailureButCanRetry(RetryableCall<String> call, IOException e) {
        throw new AssertionError();
      }
    });
    call.cancel();
    assertThat(call.isCanceled()).isTrue();
    assertThat(latch.await(10, SECONDS)).isTrue();
  }

  private interface UnparameterizedService {
    @SuppressWarnings("rawtypes") @GET("/") RetryableCall get();
  }

  @Test public void retryableCallMustBeParameterized() throws InterruptedException {
    RetryableCalls retryableCalls = new RetryableCalls();
    MockWebServer server = new MockWebServer();
    Retrofit retrofit = new Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addCallAdapterFactory(retryableCalls.getFactory())
        .addConverterFactory(new ToStringConverterFactory())
        .build();
    UnparameterizedService service = retrofit.create(UnparameterizedService.class);
    try {
      service.get();
      fail();
    } catch (IllegalArgumentException serviceMethodException) {
      assertThat(serviceMethodException.getCause()).hasMessageThat().isEqualTo(
          "RetryableCall return type must be parameterized "
              + "as RetryableCall<Foo> or RetryableCall<? extends Foo>");
    }
  }
}
