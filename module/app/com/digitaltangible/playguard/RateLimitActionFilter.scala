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
   * @param rateLimiter
   * @param rejectResponse
   * @param bucketKey
   * @param bypass
   * @param ec
   * @tparam R
   * @return
   */
  def apply[R[_] <: Request[_]](
    rateLimiter: RateLimiter
  )(rejectResponse: R[_] => Future[Result], bucketKey: Any, bypass: R[_] => Boolean = (_: R[_]) => false)(
    implicit ec: ExecutionContext
  ): RateLimitActionFilter[R] =
    new RateLimitActionFilter[R](rateLimiter)(rejectResponse, _ => bucketKey, bypass)
}

object IpRateLimitFilter {

  /**
   * Creates an ActionFilter which holds a RateLimiter with a bucket for each IP address.
   * Every request consumes a token. If no tokens remain, the request is rejected.
   *
   * @param rateLimiter
   * @param rejectResponse
   * @param ipWhitelist
   * @return
   */
  def apply[R[_] <: Request[_]](
    rateLimiter: RateLimiter
  )(rejectResponse: R[_] => Future[Result], ipWhitelist: Set[String] = Set.empty)(implicit ec: ExecutionContext): RateLimitActionFilter[R] =
    new RateLimitActionFilter[R](rateLimiter)(rejectResponse, _.remoteAddress, req => ipWhitelist.contains(req.remoteAddress))
}

/**
 * ActionFilter which holds a RateLimiter with a bucket for each key returned by function f.
 * Can be used with any Request type. Useful if you want to use content from a wrapped request, e.g. User ID
 *
 * @param rateLimiter
 * @param rejectResponse
 * @param keyFromRequest
 * @param bypass
 * @param executionContext
 * @tparam R
 */
class RateLimitActionFilter[R[_] <: Request[_]](rateLimiter: RateLimiter)(
  rejectResponse: R[_] => Future[Result],
  keyFromRequest: R[_] => Any,
  bypass: R[_] => Boolean = (_: R[_]) => false
)(
  implicit val executionContext: ExecutionContext
) extends ActionFilter[R] {

  private val logger = Logger(this.getClass)

  def filter[A](request: R[A]): Future[Option[Result]] = {
    val key = keyFromRequest(request)
    if (bypass(request) || rateLimiter.consumeAndCheck(key)) Future.successful(None)
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
   * @param rateLimiter
   * @param rejectResponse
   * @param errorCodes
   * @param ipWhitelist
   * @param ec
   * @tparam R
   * @return
   */
  def apply[R[_] <: Request[_]](
    rateLimiter: RateLimiter
  )(rejectResponse: R[_] => Future[Result], errorCodes: Seq[Int] = 400 to 499, ipWhitelist: Set[String] = Set.empty)(
    implicit ec: ExecutionContext
  ): FailureRateLimitFunction[R] =
    new FailureRateLimitFunction[R](rateLimiter)(
      rejectResponse,
      _.remoteAddress,
      r => !errorCodes.contains(r.header.status),
      req => ipWhitelist.contains(req.remoteAddress)
    )
}

/**
 * ActionFunction which holds a RateLimiter with a bucket for each key returned by function keyFromRequest.
 * Tokens are consumed only by failures determined by function resultCheck. If no tokens remain, requests with this key are rejected.
 * Can be used with any Request type. Useful if you want to use content from a wrapped request, e.g. User ID
 *
 * @param rateLimiter
 * @param rejectResponse
 * @param keyFromRequest
 * @param resultCheck
 * @param bypass
 * @param executionContext
 * @tparam R
 */
class FailureRateLimitFunction[R[_] <: Request[_]](rateLimiter: RateLimiter)(
  rejectResponse: R[_] => Future[Result],
  keyFromRequest: R[_] => Any,
  resultCheck: Result => Boolean,
  bypass: R[_] => Boolean = (_: R[_]) => false
)(implicit val executionContext: ExecutionContext)
    extends ActionFunction[R, R] {

  private val logger = Logger(this.getClass)

  def invokeBlock[A](request: R[A], block: (R[A]) => Future[Result]): Future[Result] = {
    val key = keyFromRequest(request)
    if (bypass(request)) {
      block(request)
    } else if (rateLimiter.check(key)) {
      val res = block(request)
      res.map { res =>
        if (!resultCheck(res)) rateLimiter.consume(key)
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
class RateLimiter(size: Int, rate: Float, logPrefix: String = "", clock: Clock = CurrentTimeClock) extends Serializable {

  @transient private lazy val logger = Logger(this.getClass)

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
