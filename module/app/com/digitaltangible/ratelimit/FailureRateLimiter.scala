package com.digitaltangible.ratelimit

import play.api.Logging

import scala.concurrent.{ExecutionContext, Future}

class FailureRateLimiter[C, E](
    rateLimiter: RateLimiter,
    keyFromContext: C => Any,
    resultCheck: Either[E, _] => Boolean,
    rejectResponse: C => Future[E],
    logPrefix: C => String
) extends Logging {

  def apply[R](c: C)(f: C => Future[Either[E, R]])(implicit executionContext: ExecutionContext): Future[Either[E, R]] = {
    val key: Any = keyFromContext(c)
    if (rateLimiter.check(key)) {
      f(c).map { res =>
        if (!resultCheck(res)) rateLimiter.consume(key)
        res
      }
    } else {
      logger.warn(s"${logPrefix(c)} rejected, failure rate limit for $key exceeded.")
      rejectResponse(c).map(Left(_))
    }
  }
}
