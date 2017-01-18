package com.digitaltangible.playguard

import java.util.NoSuchElementException

import akka.actor.ActorSystem
import com.digitaltangible.tokenbucket.{Clock, CurrentTimeClock, TokenBucketGroup}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{ActionBuilder, _}
import play.api.{Configuration, Logger}

import scala.concurrent.Future
import scala.util.control.NonFatal


object KeyRateLimitAction {

  /**
    * Creates an ActionBuilder which holds a RateLimiter with a bucket for each key.
    * Every request consumes a token. If no tokens remain, the request is rejected.
    *
    * @param rl
    * @param key
    * @param rejectResponse
    * @return
    */
  def apply(rl: RateLimiter)(rejectResponse: Request[_] => Result)(key: Any): RateLimitActionFilter[Request] with ActionBuilder[Request] =
    new RateLimitActionFilter[Request](rl)(rejectResponse)(_ => key) with ActionBuilder[Request]
}


object IpRateLimitAction {

  /**
    * Creates an ActionBuilder which holds a RateLimiter with a bucket for each IP address.
    * Every request consumes a token. If no tokens remain, the request is rejected.
    *
    * @param conf
    * @param rl
    * @param rejectResponse
    * @return
    */
  def apply(rl: RateLimiter)(rejectResponse: Request[_] => Result)(implicit conf: Configuration): RateLimitActionFilter[Request] with ActionBuilder[Request] =
    new RateLimitActionFilter[Request](rl)(rejectResponse)(clientIp) with ActionBuilder[Request]
}


/**
  * ActionFilter which holds a RateLimiter with a bucket for each key returned by function f.
  * Can be used with any Request type. Useful if you want to use content from a wrapped request, e.g. User ID
  *
  * @param rl
  * @param rejectResponse
  * @param f
  * @tparam R
  * @return
  */
class RateLimitActionFilter[R[_] <: Request[_]](rl: RateLimiter)(rejectResponse: R[_] => Result)(f: R[_] => Any) extends ActionFilter[R] {

  private val logger = Logger(this.getClass)

  def filter[A](request: R[A]): Future[Option[Result]] = {
    val key = f(request)
    rl.consumeAndCheck(key).map { res =>
      if (res) None
      else {
        logger.warn(s"${request.method} ${request.uri} rejected, rate limit for $key exceeded.")
        Some(rejectResponse(request))
      }
    }
  }
}


object FailureRateLimitAction {

  /**
    * Creates an Action which holds a RateLimiter with a bucket for each IP address.
    * Tokens are consumed only by failures determined by HTTP error codes. If no tokens remain, the request is rejected.
    *
    * @param frl
    * @param rejectResponse
    * @param errorCodes
    * @param conf
    * @return
    */
  def apply(frl: RateLimiter)(rejectResponse: Request[_] => Result,
                              errorCodes: Seq[Int] = 400 to 499)(implicit conf: Configuration) =

    new FailureRateLimitFunction[Request](frl)(rejectResponse)(clientIp, r => !(errorCodes contains r.header.status)) with ActionBuilder[Request]
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
  * @tparam R
  */
class FailureRateLimitFunction[R[_] <: Request[_]](rl: RateLimiter)(rejectResponse: R[_] => Result)(keyFromRequest: R[_] => Any, resultCheck: Result => Boolean) extends ActionFunction[R, R] {

  private val logger = Logger(this.getClass)

  def invokeBlock[A](request: R[A], block: (R[A]) => Future[Result]): Future[Result] = {

    val key = keyFromRequest(request)

    (for {
      ok <- rl.check(key)
      if ok
      res <- block(request)
      _ = if (!resultCheck(res)) rl.consume(key)
    } yield res).recover {
      case ex: NoSuchElementException =>
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
  * @param system
  */
class RateLimiter(size: Int, rate: Float, logPrefix: String = "", clock: Clock = CurrentTimeClock)(implicit system: ActorSystem) {

  private val logger = Logger(this.getClass)

  private lazy val tbActorRef = TokenBucketGroup.create(size, rate, clock)

  /**
    * Checks if the bucket for the given key has at least one token left.
    * If available, the token is consumed.
    *
    * @param key
    * @return
    */
  def consumeAndCheck(key: Any): Future[Boolean] = consumeAndCheck(key, 1, _ >= 0)

  /**
    * Checks if the bucket for the given key has at least one token left.
    *
    * @param key bucket key
    * @return
    */
  def check(key: Any): Future[Boolean] = consumeAndCheck(key, 0, _ > 0)


  private def consumeAndCheck(key: Any, amount: Int, check: Int => Boolean): Future[Boolean] = {
    TokenBucketGroup.consume(tbActorRef, key, amount).map { remaining =>
      if (check(remaining)) {
        if (remaining < size.toFloat / 2) logger.info(s"$logPrefix remaining tokens for $key below 50%: $remaining")
        true
      } else {
        logger.warn(s"$logPrefix rate limit for $key exceeded")
        false
      }
    } recover {
      case NonFatal(ex) =>
        logger.error(s"$logPrefix rate limiter failed", ex)
        true // let pass in case of internal failure
    }
  }

  /**
    * Consumes one token for the given key
    *
    * @param key
    * @return
    */
  def consume(key: Any): Future[Int] = TokenBucketGroup.consume(tbActorRef, key, 1)
}
