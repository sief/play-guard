play2 module for rate limiting and IP black/white listing
==========

Target
----------

This module targets the __Scala__ version of __Play2.3.x__

Installation
----------

Not available from central repos yet. Just clone the project and run "activator publish-local". 
This will allow the sample app to fetch it from your local repo. 
To include it in your app, copy the play-guard_2.11-x.x.jar file to your lib folder.

1. GuardFilter
==========

Filter for rate limiting and IP whitelisting/blacklisting

Rejects request based on the following rules:

if IP is in whitelist => let pass
else if IP is in blacklist => reject with ‘403 FORBIDDEN’
else if IP rate limit exceeded => reject with ‘429 TOO_MANY_REQUEST’
else if global rate limit exceeded => reject with ‘429 TOO_MANY_REQUEST’

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

