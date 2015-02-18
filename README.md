Play2 Module for Rate Limiting
==========

Target
----------

This module targets the __Scala__ version of __Play2.3.x__

Rate Limit Algorithm
----------
Based on the token bucket algorithm: http://en.wikipedia.org/wiki/Token_bucket


Getting play-guard
----------

The current stable version is 1.4.1, which is cross-built against Scala 2.10.x and 2.11.x.

Add the following dependency to your build file:

```scala
  "com.digitaltangible" %% "play-guard" % "1.4.1"
```

1. GuardFilter
==========

Filter for rate limiting and IP address whitelisting/blacklisting

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
just include it in your filter list of your Global object, e.g.:

```scala
object Global extends WithFilters(GuardFilter()) {
}
```

Requires configuration in your application.conf.

E.g.:

```
guard-filter {
  enabled = true
  global{
    bucket {
      size = 100
      rate = 100
    }
  }
  ip {
    whitelist = "1.1.1.1,2.2.2.2"
    blacklist = "3.3.3.3,4.4.4.4"
    bucket {
      size = 5
      rate = 10
    }
  }
}
```

2. RateLimitAction
==========

Action wrapper for rate limiting specific actions. Comes in three flavours:

2.1 Simple rate limit
-------

From the sample app:

```scala
  // allow 3 requests immediately and get a new token every 5 seconds
  private val ipRateLimited = IpRateLimitAction(RateLimiter(3, 1f / 5, "test limit by IP address")) {
    implicit r: RequestHeader => BadRequest( s"""rate limit for ${r.remoteAddress} exceeded""")
  }

  def limitedByIp = ipRateLimited {
    Ok("limited by IP")
  }
```

2.2 Simple rate limit based on key parameter
-------

From the sample app:


```scala
  // allow 4 requests immediately and get a new token every 15 seconds
  private val tokenRateLimited = KeyRateLimitAction(RateLimiter(4, 1f / 15, "test by token")) _

  def limitedByKey(key: String) = tokenRateLimited(_ => BadRequest( s"""rate limit for '$key' exceeded"""))(key) {
    Ok("limited by token")
  }

```

2.3 Failure rate limit
-------

This limits access to a specific action based on the failure rate. This is useful if you want to prevent brute force bot attacks on authentication requests.

From the sample app:

```scala
  // allow 2 failures immediately and get a new token every 10 seconds
  private val failRateLimited = FailureRateLimitAction(2, 1f / 10, {
    implicit r: RequestHeader => BadRequest("failure rate exceeded")
  }, "test failure rate limit")

  def failureLimitedByIp(fail: Boolean) = failRateLimited {
    if (fail) BadRequest("failed")
    else Ok("Ok")
  }
```

