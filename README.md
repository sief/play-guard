# Play Framework Guard Module

[![Maven](https://img.shields.io/maven-central/v/com.digitaltangible/play-guard_2.10.svg?label=latest%20release%20for%202.10)](http://mvnrepository.com/artifact/com.digitaltangible/play-guard_2.10)
[![Maven](https://img.shields.io/maven-central/v/com.digitaltangible/play-guard_2.11.svg?label=latest%20release%20for%202.11)](http://mvnrepository.com/artifact/com.digitaltangible/play-guard_2.11)
[![Maven](https://img.shields.io/maven-central/v/com.digitaltangible/play-guard_2.12.svg?label=latest%20release%20for%202.12)](http://mvnrepository.com/artifact/com.digitaltangible/play-guard_2.12)
[![Maven](https://img.shields.io/maven-central/v/com.digitaltangible/play-guard_2.13.svg?label=latest%20release%20for%202.13)](http://mvnrepository.com/artifact/com.digitaltangible/play-guard_2.13)
[![Maven](https://img.shields.io/maven-central/v/com.digitaltangible/play-guard_3.svg?label=latest%20release%20for%203)](http://mvnrepository.com/artifact/com.digitaltangible/play-guard_3)


Play module for blocking and throttling abusive requests.

- throttling specific Actions based on request attributes (e.g. IP address)
- throttling specific Actions based on request attributes (e.g. IP address) and failure rate (e.g. HTTP status or any other Result attribute)

- global IP address whitelisting/blacklisting
- global request throttling

## Target

This module targets the __Scala__ version of __Play 2.x.x__ and __3.x.x__

## Rate Limit Algorithm

Based on the token bucket algorithm: http://en.wikipedia.org/wiki/Token_bucket


## Getting play-guard

For Play 3.0.x:
```scala
  "com.digitaltangible" %% "play-guard" % "3.0.0"
```

For Play 2.9.x:
```scala
  "com.digitaltangible" %% "play-guard" % "2.6.0"
```

For Play 2.8.x:
```scala
  "com.digitaltangible" %% "play-guard" % "2.5.0"
```

For Play 2.7.x:
```scala
  "com.digitaltangible" %% "play-guard" % "2.4.0"
```


For Play 2.6.x:
```scala
  "com.digitaltangible" %% "play-guard" % "2.2.0"
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





# 1. RateLimitAction

Action function/filter for request and failure rate limiting specific actions. You can derive the bucket key from the request.

The rate limit functions/filters all take a RateLimiter instance as the first parameter:

```scala
class RateLimiter(val size: Long, val rate: Double, logPrefix: String = "", clock: Clock = CurrentTimeClock)
```

It holds the token bucket group with the specified size and rate and can be shared between actions if you want to use the same bucket group for various actions.



1.1 Request rate limit
-------

There is a general ActionFilter for handling any type of request so you can chain it behind you own ActionTransformer:

```scala
/**
 * ActionFilter which holds a RateLimiter with a bucket for each key returned by `keyFromRequest`.
 * Can be used with any Request type. Useful if you want to use content from a wrapped request, e.g. User ID
 *
 * @param rateLimiter
 * @tparam R
 */
abstract class RateLimitActionFilter[R[_] <: Request[_]](rateLimiter: RateLimiter)(
  implicit val executionContext: ExecutionContext
) extends ActionFilter[R] {

  def keyFromRequest[A](implicit request: R[A]): Any

  def rejectResponse[A](implicit request: R[A]): Future[Result]

  def bypass[A](implicit request: R[A]): Boolean = false
  
  // ...
}
```

There are also two convenience filters:

__IP address as key__ (from the sample app):

```scala
// allow 3 requests immediately and get a new token every 5 seconds
private val ipRateLimitFilter: IpRateLimitFilter[Request] = new IpRateLimitFilter[Request](new RateLimiter(3, 1f / 5, "test limit by IP address")) {
  override def rejectResponse[A](implicit request: Request[A]): Future[Result] =
    Future.successful(TooManyRequests(s"""rate limit for ${request.remoteAddress} exceeded"""))
}

def limitedByIp: Action[AnyContent] = (Action andThen ipRateLimitFilter) {
  Ok("limited by IP")
}
```

__Action parameter as key__ (from the sample app):

```scala
// allow 4 requests immediately and get a new token every 15 seconds
private val keyRateLimitFilter: KeyRateLimitFilter[String, Request] =
  new KeyRateLimitFilter[String, Request](new RateLimiter(4, 1f / 15, "test by token")) {
    override def rejectResponse4Key[A](key: String): Request[A] => Future[Result] =
      _ => Future.successful(TooManyRequests(s"""rate limit for '$key' exceeded"""))
  }

def limitedByKey(key: String): Action[AnyContent] =
  (Action andThen keyRateLimitFilter(key)) {
    Ok("limited by token")
  }
```

1.2 Error rate limit
-------

There is a general ActionFunction for handling any type of request so you can chain it behind your own ActionTransformer and determine failure from the Result:

```scala
/**
 * ActionFunction which holds a RateLimiter with a bucket for each key returned by method keyFromRequest.
 * Tokens are consumed only by failures determined by function resultCheck. If no tokens remain, requests with this key are rejected.
 * Can be used with any Request type. Useful if you want to use content from a wrapped request, e.g. User ID
 *
 * @param rateLimiter
 * @param resultCheck
 * @param executionContext
 * @tparam R
 */
abstract class FailureRateLimitFunction[R[_] <: Request[_]](
     rateLimiter: RateLimiter,
     resultCheck: Result => Boolean,
)(implicit val executionContext: ExecutionContext)
  extends ActionFunction[R, R] {

  def keyFromRequest[A](implicit request: R[A]): Any

  def rejectResponse[A](implicit request: R[A]): Future[Result]

  def bypass[A](implicit request: R[A]): Boolean = false
  
  // ...
}
```

The convenience action HttpErrorRateLimitAction __limits the HTTP error rate for each IP address__. This is for example useful if you want to prevent brute force bot attacks on authentication requests.

From the sample app:

```scala
// allow 2 failures immediately and get a new token every 10 seconds
private val httpErrorRateLimitFunction: HttpErrorRateLimitFunction[Request] =
new HttpErrorRateLimitFunction[Request](new RateLimiter(2, 1f / 10, "test failure rate limit")) {
  override def rejectResponse[A](implicit request: Request[A]): Future[Result] = Future.successful(BadRequest("failure rate exceeded"))
}

def failureLimitedByIp(fail: Boolean): Action[AnyContent] =
(Action andThen httpErrorRateLimitFunction) {
  if (fail) BadRequest("failed")
  else Ok("Ok")
}
```

1.3 Integration with Silhouette
-------

https://www.silhouette.rocks/docs/rate-limiting





# 2. GuardFilter


Filter for global rate limiting and IP address whitelisting/blacklisting.

__Note: this global filter is only useful if you don't have access to a reverse proxy like nginx where you can handle these kind of things__

2.1 Rules
----------
Rejects requests based on the following rules:

```
if IP address is in whitelist => let pass
else if IP address is in blacklist => reject with ‘403 FORBIDDEN’
else if IP address rate limit exceeded => reject with ‘429 TOO_MANY_REQUEST’
else if global rate limit exceeded => reject with ‘429 TOO_MANY_REQUEST’
```

2.2 Usage
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


The filter uses the black/whitelists from the configuration by default. You can also plug in you own IpChecker implementation. With runtime time DI you have to disable the default module in your application.conf and bind your implementation in your app's module:

 ```
 play {
   modules {
     disabled += "com.digitaltangible.playguard.PlayGuardIpCheckerModule"
   }
 }
 ```


2.2 Configuration
----------


```
playguard {
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

# 3. Configuring the remote IP address

IP-based rate limits will use `RequestHeader.remoteAddress` as the address of the client. Depending on [how you have configured Play](https://www.playframework.com/documentation/2.6.x/HTTPServer#Configuring-trusted-proxies) this may be the actual remote address of clients connecting directly, or it may be read from the common `X-Forwarded-For` or `Forwarded` headers that are set by proxies and load balancers.

If you are using a reverse proxy (e.g. [nginx](https://www.nginx.com/resources/wiki/start/topics/examples/forwarded/), HAProxy or an [AWS ELB](https://docs.aws.amazon.com/elasticloadbalancing/latest/classic/x-forwarded-headers.html)) in front of your application, you should take care to configure the [`play.http.forwarded.trustedProxies`](https://www.playframework.com/documentation/2.6.x/HTTPServer#Configuring-trusted-proxies) setting, otherwise all requests will be rate-limited against the IP address of the upstream proxy (definitely not what you want).

For scenarios where you don't know your immediate connection's IP address beforehand (to configure it as a trusted proxy) but can still trust it, e.g. on Heroku, there is a custom RequestHandler `XForwardedTrustImmediateConnectionRequestHandler` which replaces the immediate connection with the last IP address in the X-Forwarded-For header (RFC 7239 is not supported). This handler can be configured as described [here](https://www.playframework.com/documentation/2.6.x/ScalaHttpRequestHandlers#Implementing-a-custom-request-handler) 

