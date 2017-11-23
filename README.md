Retryable-Retrofit
====================

TODO: A way to retry many failed Retrofit calls. Useful in combination with listening to connectivity changes on Android.


Download
--------

Download [the latest JAR][jar] or grab via Maven:
```xml
<dependency>
  <groupId>com.nightlynexus.retryable-retrofit</groupId>
  <artifactId>retryable</artifactId>
  <version>0.1.0</version>
</dependency>
```
or Gradle:
```groovy
compile 'com.nightlynexus.retryable-retrofit:retryable:0.1.0'
```

TODO: Snapshots of the development version are available in [Sonatype's `snapshots` repository][snap].



Usage
-----

TODO
```java
RetryableCalls retryableCalls = new RetryableCalls();
Retrofit retrofit = new Retrofit.Builder()
    .addCallAdapterFactory(retryableCalls.getFactory())
    ...
    .build();

// Listen for Android's connectivity changes.
ConnectivityAutoRetryer autoRetryer = new ConnectivityAutoRetryer(retryableCalls, context);
autoRetryer.register();

class AndroidView {
  RetryableCall<Void> call;
  
  void attach() {
    call = retrofitService.get();
    call.enqueue(new RetryableCallback<Void>() {
      ...
    });
    // call will be retried automatically until canceled or completed.
  }
  
  void detach() {
    // Cancel the call and release it and its callback from the RetryableCalls instance if it has not be completed yet.
    call.cancel();
  }
}
```


License
-------

    Copyright 2017 Eric Cochran

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.



 [jar]: https://search.maven.org/remote_content?g=com.nightlynexus.retryable-retrofit&a=retryable&v=LATEST
 [snap]: https://oss.sonatype.org/content/repositories/snapshots/
