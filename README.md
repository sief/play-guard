play2 module for IP based rate limiting
==========

Target
----------

This module targets the __Scala__ version of __Play2.3.x__

Rate Limit Algorithm
----------
Based on the token bucket algorithm: http://en.wikipedia.org/wiki/Token_bucket


Installation
----------

Not available from central repos yet. Just clone the project and run "activator publish-local" in "module". 
This will allow the sample app to fetch it from your local repo. 
To include it in your app, copy the play-guard_2.11-x.x.jar file to your lib folder.

1. GuardFilter
==========

Filter for rate limiting and IP whitelisting/blacklisting

Rejects request based on the following rules:

```
if IP is in whitelist => let pass
else if IP is in blacklist => reject with ‘403 FORBIDDEN’
else if IP rate limit exceeded => reject with ‘429 TOO_MANY_REQUEST’
else if global rate limit exceeded => reject with ‘429 TOO_MANY_REQUEST’
```

1.2 Usage
----------
just include it in your filter list of your Global object, e.g.:

```scala
object Global extends WithFilters(GuardFilter()) {
}
```

Don't forget the configuration, otherwise your app won't start, in your application.conf e.g.:
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

Action wrapper for rate limiting specific actions. Comes in two flavours:

2.1 Simple rate limit
-------

From the sample app:

```scala
// allow 3 requests immediately and get a new token every 5 seconds
private val rateLimiter = RateLimitAction(3, 1f / 5, { implicit r: RequestHeader => BadRequest("rate exceeded")}, "test rate limit")

  def limited = rateLimiter {
    Ok("limited")
  }
```

2.2 Failure rate limit
-------

This limits access to a specific request based on the failure rate. This is useful if you want to pretent brute force bot attacks on authentication requests, for example.

From the sample app:

```scala
// allow 3 failures immediately and get a new token every 10 seconds
private val failRateLimiter = RateLimitAction.failLimit(3, 1f / 10, { implicit r: RequestHeader => BadRequest("fail rate exceeded")}, "test fail rate limit")

def fail(fail: Boolean) = failRateLimiter {
  if (fail) BadRequest("failed")
  else Ok("Ok")
}
```

