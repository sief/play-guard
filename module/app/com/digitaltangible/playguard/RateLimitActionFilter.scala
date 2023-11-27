package com.digitaltangible.playguard

import com.digitaltangible.tokenbucket.{Clock, CurrentTimeClock, TokenBucketGroup}
import play.api.Logger
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

/**
 * ActionFilter which holds a RateLimiter with a bucket for each key.
 * Every request consumes a token. If no tokens remain, the request is rejected.

 * @param rateLimiter
 * @param ec
 * @tparam K
 * @tparam R
 */
abstract class KeyRateLimitFilter[K, R[_] <: Request[_]](rateLimiter: RateLimiter)(
    implicit ec: ExecutionContext
) {
  def rejectResponse4Key[A](key: K): R[A] => Future[Result]

  def bypass4Key[A](key: K): R[A] => Boolean = (_: R[A]) => false

  def apply(key: K): RateLimitActionFilter[R] = new RateLimitActionFilter[R](rateLimiter) {
    override def keyFromRequest[A](implicit request: R[A]): Any = key

    override def rejectResponse[A](implicit request: R[A]): Future[Result] = rejectResponse4Key(key)(request)

    override def bypass[A](implicit request: R[A]): Boolean = bypass4Key(key)(request)
  }
}

/**
 * ActionFilter which holds a RateLimiter with a bucket for each IP address.
 * Every request consumes a token. If no tokens remain, the request is rejected.
 * @param rateLimiter
 * @param ipWhitelist
 * @param ec
 * @tparam R
 */
abstract class IpRateLimitFilter[R[_] <: Request[_]](rateLimiter: RateLimiter, ipWhitelist: Set[String] = Set.empty)(
    implicit ec: ExecutionContext
) extends RateLimitActionFilter[R](rateLimiter) {

  override def keyFromRequest[A](implicit request: R[A]): String = request.remoteAddress

  override def bypass[A](implicit request: R[A]): Boolean = ipWhitelist.contains(request.remoteAddress)
}

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

  private val logger: Logger = Logger(this.getClass)

  def keyFromRequest[A](implicit request: R[A]): Any

  def rejectResponse[A](implicit request: R[A]): Future[Result]

  def bypass[A](implicit request: R[A]): Boolean = false

  def filter[A](request: R[A]): Future[Option[Result]] = {
    val key: Any = keyFromRequest(request)
    if (bypass(request) || rateLimiter.consumeAndCheck(key)) Future.successful(None)
    else {
      logger.warn(s"${request.method} ${request.uri} rejected, rate limit for $key exceeded.")
      rejectResponse(request).map(Some.apply)
    }
  }
}

/**
 * Creates an ActionFunction which holds a RateLimiter with a bucket for each IP address.
 * Tokens are consumed only by failures determined by HTTP error codes. If no tokens remain, the request is rejected.
 *
 * @param rateLimiter
 * @param errorCodes
 * @param ipWhitelist
 * @param ec
 * @tparam R
 */
abstract class HttpErrorRateLimitFunction[R[_] <: Request[_]](
    rateLimiter: RateLimiter,
    errorCodes: Set[Int] = (400 to 499).toSet,
    ipWhitelist: Set[String] = Set.empty
)(
    implicit ec: ExecutionContext
) extends FailureRateLimitFunction[R](rateLimiter, r => !errorCodes.contains(r.header.status)) {

  override def keyFromRequest[A](implicit request: R[A]): String = request.remoteAddress

  override def bypass[A](implicit request: R[A]): Boolean = ipWhitelist.contains(request.remoteAddress)
}

/**
 * ActionFunction which holds a RateLimiter with a bucket for each key returned by function keyFromRequest.
 * Tokens are consumed only by failures determined by function resultCheck. If no tokens remain, requests with this key are rejected.
 * Can be used with any Request type. Useful if you want to use content from a wrapped request, e.g. User ID
 *
 * @param rateLimiter
 * @param keyFromRequest
 * @param resultCheck
 * @param rejectResponse
 * @param bypass
 * @param executionContext
 * @tparam R
 */
abstract class FailureRateLimitFunction[R[_] <: Request[_]](
    rateLimiter: RateLimiter,
    resultCheck: Result => Boolean,
)(implicit val executionContext: ExecutionContext)
    extends ActionFunction[R, R] {

  private val logger: Logger = Logger(this.getClass)

  def keyFromRequest[A](implicit request: R[A]): Any

  def rejectResponse[A](implicit request: R[A]): Future[Result]

  def bypass[A](implicit request: R[A]): Boolean = false

  def invokeBlock[A](request: R[A], block: R[A] => Future[Result]): Future[Result] = {
    val key: Any = keyFromRequest(request)
    if (bypass(request)) {
      block(request)
    } else if (rateLimiter.check(key)) {
      val res: Future[Result] = block(request)
      res.map { (res: Result) =>
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
class RateLimiter(val size: Long, val rate: Double, logPrefix: String = "", clock: Clock = CurrentTimeClock) extends Serializable {

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
