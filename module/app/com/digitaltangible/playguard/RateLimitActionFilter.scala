package com.digitaltangible.playguard

import com.digitaltangible.tokenbucket.{Clock, CurrentTimeClock, TokenBucketGroup}
import play.api.Logger
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

object KeyRateLimitFilter {

  /**
   * Creates an ActionFilter which holds a RateLimiter with a bucket for each key.
   * Every request consumes a token. If no tokens remain, the request is rejected.
   *
   * @param rl
   * @param key
   * @param rejectResponse
   * @return
   */
  def apply[R[_] <: Request[_]](
    rl: RateLimiter
  )(rejectResponse: R[_] => Future[Result], key: Any)(implicit ec: ExecutionContext): RateLimitActionFilter[R] =
    new RateLimitActionFilter[R](rl)(rejectResponse, _ => key)
}

object IpRateLimitFilter {

  /**
   * Creates an ActionFilter which holds a RateLimiter with a bucket for each IP address.
   * Every request consumes a token. If no tokens remain, the request is rejected.
   *
   * @param rl
   * @param rejectResponse
   * @return
   */
  def apply[R[_] <: Request[_]](
    rl: RateLimiter
  )(rejectResponse: R[_] => Future[Result])(implicit ec: ExecutionContext): RateLimitActionFilter[R] =
    new RateLimitActionFilter[R](rl)(rejectResponse, _.remoteAddress)
}

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
class RateLimitActionFilter[R[_] <: Request[_]](rl: RateLimiter)(rejectResponse: R[_] => Future[Result], f: R[_] => Any)(
  implicit val executionContext: ExecutionContext
) extends ActionFilter[R] {

  private val logger = Logger(this.getClass)

  def filter[A](request: R[A]): Future[Option[Result]] = {
    val key = f(request)
    if (rl.consumeAndCheck(key)) Future.successful(None)
    else {
      logger.warn(s"${request.method} ${request.uri} rejected, rate limit for $key exceeded.")
      rejectResponse(request).map(Some.apply)
    }
  }
}

object HttpErrorRateLimitFunction {

  /**
   * Creates an ActionFunction which holds a RateLimiter with a bucket for each IP address.
   * Tokens are consumed only by failures determined by HTTP error codes. If no tokens remain, the request is rejected.
   *
   * @param rl
   * @param rejectResponse
   * @param errorCodes
   * @param ec
   * @return
   */
  def apply[R[_] <: Request[_]](
    rl: RateLimiter
  )(rejectResponse: R[_] => Future[Result], errorCodes: Seq[Int] = 400 to 499)(
    implicit ec: ExecutionContext
  ): FailureRateLimitFunction[R] =
    new FailureRateLimitFunction[R](rl)(
      rejectResponse,
      _.remoteAddress,
      r => !(errorCodes contains r.header.status)
    )
}

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
class FailureRateLimitFunction[R[_] <: Request[_]](rl: RateLimiter)(
  rejectResponse: R[_] => Future[Result],
  keyFromRequest: R[_] => Any,
  resultCheck: Result => Boolean
)(implicit val executionContext: ExecutionContext)
    extends ActionFunction[R, R] {

  private val logger = Logger(this.getClass)

  def invokeBlock[A](request: R[A], block: (R[A]) => Future[Result]): Future[Result] = {

    val key = keyFromRequest(request)

    if (rl.check(key)) {
      val res = block(request)
      res.map { res =>
        if (!resultCheck(res)) rl.consume(key)
        res
      }
    } else {
      logger.warn(s"${request.method} ${request.uri} rejected, failure rate limit for $key exceeded.")
      rejectResponse(request)
    }
  }
}

/**
 * Holds a TokenBucketGroup for rate limiting. You can share an instance if you want different Actions to use the same TokenBucketGroup.
 *
 * @param size
 * @param rate
 * @param logPrefix
 * @param clock
 */
class RateLimiter(size: Int, rate: Float, logPrefix: String = "", clock: Clock = CurrentTimeClock) {

  private val logger = Logger(this.getClass)

  private lazy val tokenBucketGroup = new TokenBucketGroup(size, rate, clock)

  /**
   * Checks if the bucket for the given key has at least one token left.
   * If available, the token is consumed.
   *
   * @param key
   * @return
   */
  def consumeAndCheck(key: Any): Boolean = consumeAndCheck(key, 1, _ >= 0)

  /**
   * Checks if the bucket for the given key has at least one token left.
   *
   * @param key bucket key
   * @return
   */
  def check(key: Any): Boolean = consumeAndCheck(key, 0, _ > 0)

  private def consumeAndCheck(key: Any, amount: Int, check: Long => Boolean): Boolean = {
    val remaining = tokenBucketGroup.consume(key, amount)
    if (check(remaining)) {
      if (remaining < size.toFloat / 2) logger.info(s"$logPrefix remaining tokens for $key below 50%: $remaining")
      true
    } else {
      logger.warn(s"$logPrefix rate limit for $key exceeded")
      false
    }
  }

  /**
   * Consumes one token for the given key
   *
   * @param key
   * @return
   */
  def consume(key: Any): Long = tokenBucketGroup.consume(key, 1)
}
