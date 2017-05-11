# spring-async
Asynchronous REST call with DeferredResult

`http://localhost:8080/time/basic`
```
{
	"time": "2017-05-12T06:35:14.817"
}
```

When we now take a look in the console we should see two log lines similar to these:
```
06:35:14.810 [http-nio-8080-exec-1] Basic time request
06:35:14.810 [http-nio-8080-exec-1] Creating TimeResponse
```

The logger configuration only displays the time, thread name and the message for clarity. As you can see here both of these lines are created by the same thread; http-nio-8080-exec-1.

So where do these lines come from? Let’s take a look at the controller to see where these requests are handled. The 'basic' request is mapped here:

```
@RequestMapping(value = "/basic", method = RequestMethod.GET)
public TimeResponse timeBasic() {
    log.info("Basic time request");
    return now();
}
```

As you can see it logs the call to timeBasic() and then returns a TimeResponse Data Transfer Object; created in a utility function in the same controller class:

```
private static TimeResponse now() {
    log.info("Creating TimeResponse");
    return new TimeResponse(LocalDateTime
            .now()
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
}
```

This function is where the "Creating TimeResponse" message is logged. All four different controller routes use this same function to log the creation of the TimeResponse DTO.

### ResponseEntity implementation

A common approach in Spring REST API’s is to return ResponseEntity’s wrapping the result objects. This makes it easier to instead of a result return for example a 'not found' HTTP response. Other than this wrapper the implementation is very similar:

```
@RequestMapping(value = "/re", method = RequestMethod.GET)
public ResponseEntity<?> timeResponseEntity() {
    log.info("Response entity request");
    return ResponseEntity.ok(now());
}
```

If you call http://localhost:8080/time/re we see log lines similar to the previous route:

```
06:37:23.282 [http-nio-8080-exec-3] Response entity request
06:37:23.282 [http-nio-8080-exec-3] Creating TimeResponse
```

The TimeResponse is still created on the same worker that’s calling the controller function.

So what happens when we call /time/basic or /time/re is that Spring MVC routes this to our SimpleController and timeBasic/timeResponseEntity() methods based on the route. To do this routing it uses one of it’s 'executor' threads. For performance reasons Spring doesn’t just create a new thread for every request but instead uses a pool with worker threads (named 'http-nio-8080-exec-#') that handle these requests. By default it has 10 of these workers allowing it to handle 10 requests in parallel.

This is wonderful for simple short-lived requests like the ones I created but what happens if these requests block for a long time because they’re waiting on external connections or long-running database requests? We’ll easily block all our worker threads causing new requests to be dropped, no matter if some of these are really fast requests. So we should have a mechanism where we don’t block our executors.

### Callable implementation

An easy fix for this is to wrap our response in a Callable. Spring automatically knows that when it receives a callable, it should be considered a 'slow' call and should be executed on a different thread. Let’s take a look at the controller:

```
@RequestMapping(value = "/callable", method = RequestMethod.GET)
public Callable<ResponseEntity<?>> timeCallable() {
    log.info("Callable time request");
    return () -> ResponseEntity.ok(now());
}
```

And yes. It really is that simple. When we now do a GET request for http://localhost:8080/time/callable we see something interesting:

```
{
  "time": "2017-05-12T06:38:54.302"
}
```

```
06:38:54.296 [http-nio-8080-exec-5] Callable time request
06:38:54.302 [MvcAsync1] Creating TimeResponse
```

The request is received on one of the workers but the work is done by a completely different thread named MvcAsync1. This is done for us by Spring MVC. When it receives a Callable from a controller it spins up a new thread to handle it.

By default it actually spins up as many threads as you want. So if we hit the end-point a few more times the number increases sequentially:

```
06:39:55.979 [http-nio-8080-exec-8] Callable time request
06:39:55.980 [MvcAsync2] Creating TimeResponse
06:39:59.847 [http-nio-8080-exec-10] Callable time request
06:39:59.847 [MvcAsync3] Creating TimeResponse
06:40:00.640 [http-nio-8080-exec-3] Callable time request
06:40:00.641 [MvcAsync4] Creating TimeResponse
06:40:01.393 [http-nio-8080-exec-6] Callable time request
06:40:01.394 [MvcAsync5] Creating TimeResponse
```

**IMPORTANT**

You still need to figure out what threading pattern fits your use case. Creating threads is relatively expensive and with an unbounded maximum your application server can run out of memory and crash.

This behavior, as well as the name, can be configured through a WebMvcConfigurer bean.

### DeferredResult implementation

So we can now more easily handle long running results. But the Callable interface only allows us to return a result. We can’t inform the executor that we are done. This is fine for simple results but in some situations we need more control. This is where the DeferredResult class comes in. DeferredResult is a Future that allows us to signal completion. Let’s take a look at the controller method:

```
@RequestMapping(value = "/deferred", method = RequestMethod.GET)
public DeferredResult<ResponseEntity<?>> timeDeferred() {
    log.info("Deferred time request");
    DeferredResult<ResponseEntity<?>> result = new DeferredResult<>();

    new Thread(() -> {
        result.setResult(ResponseEntity.ok(now()));
    }, "MyThread-" + counter.incrementAndGet()).start();

    return result;
}
```

It is similar to the Callable version in that we wrap our result in a class. But what we do with it is different. Where the Callable is simply returned the DeferredResult need to be 'completed' by setting a result.

In the example above I’m spinning up my own thread ('MyThread-') the same way Spring does it with Callables. I pass a runnable to the thread that sets calls the setResult with a new ResponseEntity<TimeResponse> and return the DeferredResult.

When we GET the http://localhost:8080/time/deferred route we see the following log lines:

```
{
  "time": "2017-05-12T06:41:24.6"
}
```

```
06:41:24.599 [http-nio-8080-exec-10] Deferred time request
06:41:24.600 [MyThread-1] Creating TimeResponse
```

Again as we can see the work is handled on a different thread than where the request came in.

### Testing asynchronous controllers

One thing to keep in mind when you are testing controllers with async calls through MockMvc that the async aspect requires you to slightly alter your tests. How to set up the tests is demonstrated in SimpleControllerIntegrationTest. A normal synchronous call (like /time/basic and /time/re) is tested through MockMvc like so:

```
private void testSync(String route) throws Exception {
    mockMvc.perform(get(route))
            .andExpect(status().is2xxSuccessful())
            .andExpect(jsonPath("$.time").isString());
}
```

This won’t work with asynchronous calls since they need to be dispatched and handled first, so we need to wait for that. Fortunately MockMvc, with a few more lines of code, can handle that for us:

```
private void testAsync(String route) throws Exception {
    MvcResult resultActions = mockMvc.perform(get(route))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc.perform(asyncDispatch(resultActions))
            .andExpect(status().is2xxSuccessful())
            .andExpect(jsonPath("$.time").isString());
}
```

### A real example

If you have checked out the source you might also notice that we have another controller. This is based on a real life example where I used DeferredResults to aggregate results from a number of different REST API’s into a single result. It uses OkHttp’s Async capabilities to perform multiple requests in parallel.

An example of such a request would be:

```
POST localhost:8080/aggregate

{"urls": [
	"https://api.ipify.org?format=json",
	"http://ip-api.com/json",
	"https://jsonplaceholder.typicode.com/posts/1"
]}
```

The underlying service calls these endpoints and combines them into a single JSON result:

```
{
  "responses": [
    {
      "body": {
        "ip": "123.456.123.456"
      },
      "status": 200,
      "duration": 1799
    },
    {
      "body": {
        "as": "ANONYMIZED"
      },
      "status": 200,
      "duration": 123
    },
    {
      "body": {
        "userId": 1,
        "id": 1,
        "title": "sunt aut facere repellat provident occaecati excepturi optio reprehenderit",
        "body": "quia et suscipit\nsuscipit recusandae consequuntur expedita et cum\nreprehenderit molestiae ut ut quas totam\nnostrum rerum est autem sunt rem eveniet architecto"
      },
      "status": 200,
      "duration": 1911
    }
  ],
  "duration": 2149
}
```

What is in my opinion really cool is that it’s as fast as the slowest API call demonstrating that such an asynchronous call brings benefits over doing the calls in sequence.

It also comes with an integration test that uses WireMock to create service stubs that are then called in parallel.

### Conclusion

Spring makes it incredibly easy to handle long running processes from our controller. We can return a Callable with almost zero effort when we want to let spring handle the threading or we can used DeferredResults when we need to be in full control.

I hope you enjoyed this post as much as I enjoyed writing it. Feel free to play around with the example and please let me know if you have comments or questions!





