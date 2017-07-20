# Play2 Guard Module


Play2 module for blocking and throttling abusive requests.

- IP address whitelisting/blacklisting
- general request throttling
- throttling specific Actions based on IP address or other request attribute
- throttling specific Actions based on IP address or other request attribute and failure rate, e.g. wrong credentials or any other Result attribute

## Target

This module targets the __Scala__ version of __Play 2.x.x__

## Rate Limit Algorithm

Based on the token bucket algorithm: http://en.wikipedia.org/wiki/Token_bucket


## Getting play-guard

For Play 2.6.x:
```scala
  "com.digitaltangible" %% "play-guard" % "2.1.0"
```


For Play 2.5.x:
```scala
  "com.digitaltangible" %% "play-guard" % "2.0.0"
```


For Play 2.4.x:
```scala
  "com.digitaltangible" %% "play-guard" % "1.6.0"
```


For Play 2.3.x:
```scala
  "com.digitaltangible" %% "play-guard" % "1.4.1"
```




# 1. GuardFilter


Filter for global rate limiting and IP address whitelisting/blacklisting.

__Note: this global filter is only useful if you don't have access to a reverse proxy like nginx where you can handle these kind of things__

1.1 Rules
----------
Rejects requests based on the following rules:

```
if IP address is in whitelist => let pass
else if IP address is in blacklist => reject with ‘403 FORBIDDEN’
else if IP address rate limit exceeded => reject with ‘429 TOO_MANY_REQUEST’
else if global rate limit exceeded => reject with ‘429 TOO_MANY_REQUEST’
```

1.2 Usage
----------

For compile time DI:

```scala
class ApplicationComponents(context: Context) extends BuiltInComponentsFromContext(context) with PlayGuardComponents {

  override lazy val httpFilters: Seq[EssentialFilter] = Seq(guardFilter)
}
```

Runtime DI with Guice:

```scala
@Singleton
class Filters @Inject()(env: Environment, guardFilter: GuardFilter) extends DefaultHttpFilters(guardFilter)
```


Requires configuration in your application.conf.

__Note: the config format has changed with v2.0.0__


```
playguard {

  # the http header to use for the client IP address.
  # If not set, RequestHeader.remoteAddress will be used
  clientipheader = "X-Forwarded-For"

  filter {
    enabled = true
    global {
      bucket {
        size = 100
        rate = 100
      }
    }
    ip {
      whitelist = ["1.1.1.1", "2.2.2.2"]
      blacklist = ["3.3.3.3", "4.4.4.4"]
      bucket {
        size = 50
        rate = 50
      }
    }
  }
}
```

The filter uses the black/whitelists from the configuration by default. You can also plug in you own IpChecker implementation. With runtime time DI you have to disable the default module in your application.conf and bind your implementation in your app's module:

 ```
 play {
   modules {
     disabled += "com.digitaltangible.playguard.PlayGuardIpCheckerModule"
   }
 }
 ```



# 2. RateLimitAction

Action function/filter for request and failure rate limiting specific actions. You can derive the bucket key from the request.

The rate limit functions/filters all take a RateLimiter instance as the first parameter:

```scala
 class RateLimiter(size: Int, rate: Float, logPrefix: String = "", clock: Clock = CurrentTimeClock)(implicit system: ActorSystem)
```

It holds the token bucket group with the specified size and rate and can be shared between actions if you want to use the same bucket group for various actions.



2.1 Request rate limit
-------

There is a general ActionFilter for handling any type of request so you can chain it behind you own ActionTransformer:

```scala
/**
  * ActionFilter which holds a RateLimiter with a bucket for each key returned by function f.
  * Can be used with any Request type. Useful if you want to use content from a wrapped request, e.g. User ID
  *
  * @param rl
  * @param rejectResponse
  * @param f
  * @tparam R
  * @param executionContext
  * @return
  */
class RateLimitActionFilter[R[_] <: Request[_]](rl: RateLimiter)(rejectResponse: R[_] => Result, f: R[_] => Any)(implicit val executionContext: ExecutionContext) extends ActionFilter[R] {
```

There are also two convenience filters:

__IP address as key__ (from the sample app):

```scala
  // allow 3 requests immediately and get a new token every 5 seconds
  private val ipRateLimitFilter = IpRateLimitFilter(new RateLimiter(3, 1f / 5, "test limit by IP address")) {
    implicit r: RequestHeader => TooManyRequests( s"""rate limit for ${r.remoteAddress} exceeded""")
  }

  def limitedByIp = (Action andThen ipRateLimitFilter) {
    Ok("limited by IP")
  }
```

__Action parameter as key__ (from the sample app):

```scala
  // allow 4 requests immediately and get a new token every 15 seconds
  private val keyRateLimitFilter = KeyRateLimitFilter(new RateLimiter(4, 1f / 15, "test by token")) _

  def limitedByKey(key: String) = (Action andThen keyRateLimitFilter(_ => TooManyRequests( s"""rate limit for '$key' exceeded"""), key)) {
    Ok("limited by token")
  }
```

2.3 Error rate limit
-------

There is a general ActionFunction for handling any type of request so you can chain it behind you own ActionTransformer and determine failure from the Result:

```scala
/**
  * ActionFunction which holds a RateLimiter with a bucket for each key returned by function keyFromRequest.
  * Tokens are consumed only by failures determined by function resultCheck. If no tokens remain, requests with this key are rejected.
  * Can be used with any Request type. Useful if you want to use content from a wrapped request, e.g. User ID
  *
  * @param rl
  * @param rejectResponse
  * @param keyFromRequest
  * @param resultCheck
  * @param executionContext
  * @tparam R
  */
class FailureRateLimitFunction[R[_] <: Request[_]](rl: RateLimiter)(rejectResponse: R[_] => Result, keyFromRequest: R[_] => Any, resultCheck: Result => Boolean)(implicit val executionContext: ExecutionContext) extends ActionFunction[R, R] 
```

The convenience action HttpErrorRateLimitAction __limits the HTTP error rate for each IP address__. This is for example useful if you want to prevent brute force bot attacks on authentication requests.

From the sample app:

```scala
  // allow 2 failures immediately and get a new token every 10 seconds
  private val httpErrorRateLimitFunction = HttpErrorRateLimitFunction(new RateLimiter(2, 1f / 10, "test failure rate limit")) {
    implicit r: RequestHeader => BadRequest("failure rate exceeded")
  }

  def failureLimitedByIp(fail: Boolean) = (Action andThen httpErrorRateLimitFunction) {
    if (fail) BadRequest("failed")
    else Ok("Ok")
  }
```
