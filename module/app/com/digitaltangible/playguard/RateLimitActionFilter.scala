package com.digitaltangible.playguard

import akka.actor.ActorSystem
import com.digitaltangible.tokenbucket.{Clock, CurrentTimeClock, TokenBucketGroup}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{ActionBuilder, _}
import play.api.{Configuration, Logger}

import scala.concurrent.Future
import scala.util.control.NonFatal


object KeyRateLimitAction {

  /**
    * Creates an ActionBuilder which holds a TokenBucketGroup with a bucket for each key.
    * Every request consumes a token. If no tokens remain, the request is rejected.
    *
    * @param rl
    * @param key
    * @param rejectResponse
    * @return
    */
  def apply(rl: RateLimiter)(rejectResponse: Request[_] => Result)(key: Any): RateLimitActionFilter[Request] with ActionBuilder[Request] =
    new RateLimitActionFilter[Request](rl)(rejectResponse)((_: Request[_]) => key) with ActionBuilder[Request]
}


object IpRateLimitAction {

  /**
    * Creates an ActionBuilder which holds a TokenBucketGroup with a bucket for each IP address.
    * Every request consumes a token. If no tokens remain, the request is rejected.
    *
    * @param conf
    * @param rl
    * @param rejectResponse
    * @return
    */
  def apply(conf: Configuration)(rl: RateLimiter)(rejectResponse: Request[_] => Result): RateLimitActionFilter[Request] with ActionBuilder[Request] =
    new RateLimitActionFilter[Request](rl)(rejectResponse)(getClientIp(_: RequestHeader, conf)) with ActionBuilder[Request]
}


/**
  * ActionFilter to be used on any Request type. Useful if you want to use content from a wrapped request, e.g. User ID
  *
  * @param rl
  * @param rejectResponse
  * @param f
  * @tparam R
  * @return
  */
class RateLimitActionFilter[R[_]](rl: RateLimiter)(rejectResponse: R[_] => Result)(f: R[_] => Any) extends ActionFilter[R] {
  def filter[A](request: R[A]): Future[Option[Result]] = {
    rl.check(f(request)).map { res =>
      if (res) None
      else Some(rejectResponse(request))
    }
  }
}

/**
  * Holds a TokenBucketGroup for rate limiting.
  *
  * @param system
  * @param size
  * @param rate
  * @param logPrefix
  */
case class RateLimiter(system: ActorSystem)(size: Int, rate: Float, logPrefix: String = "", clock: Clock = CurrentTimeClock) {

  private val logger = Logger(this.getClass)

  private lazy val tbActorRef = TokenBucketGroup.create(system, size, rate, clock)

  /**
    * Checks if the bucket for the given key has at least one token left.
    * If available, the token is consumed.
    *
    * @param key bucket key
    * @return
    */
  def check(key: Any): Future[Boolean] = {
    TokenBucketGroup.consume(tbActorRef, key, 1).map { remaining =>
      if (remaining >= 0) {
        if (remaining < size.toFloat / 2) logger.warn(s"$logPrefix rate limit for $key below 50%: $remaining")
        true
      } else {
        logger.error(s"$logPrefix rate limit for $key exceeded")
        false
      }
    } recover {
      case NonFatal(ex) =>
        logger.error(s"$logPrefix rate limiter failed", ex)
        true
    }
  }
}


object FailureRateLimitAction {

  private val logger = Logger(this.getClass)

  /**
    * Creates an ActionBuilder which holds a TokenBucketGroup with a bucket for each IP address.
    * Tokens are consumed only by failures. If no tokens remain, the request is rejected.
    *
    * @param size           token bucket size
    * @param rate           token bucket rate (per second)
    * @param rejectResponse response if request is rejected
    * @param logPrefix      prefix for logging
    * @param errorCodes     HTTP returns codes which cause a token consumption
    * @return the ActionBuilder instance
    */
  def apply(conf: Configuration, system: ActorSystem)(size: Int, rate: Float,
                                                      rejectResponse: Request[_] => Result, logPrefix: String = "", errorCodes: Seq[Int] = 400 to 499) = new ActionBuilder[Request] {

    private lazy val ipTbActorRef = TokenBucketGroup.create(system, size, rate)

    def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]): Future[Result] = {

      TokenBucketGroup.consume(ipTbActorRef, getClientIp(request, conf), 0).flatMap {
        remaining =>
          if (remaining > 0) {
            if (remaining < size.toFloat / 2) logger.warn(s"$logPrefix fail rate limit for ${getClientIp(request, conf)} below 50%: $remaining")
            val res = block(request)
            res.map {
              r =>
                if (errorCodes contains r.header.status) TokenBucketGroup.consume(ipTbActorRef, getClientIp(request, conf), 1)
            }
            res
          } else {
            logger.error(s"$logPrefix too many failed attempts from ${getClientIp(request, conf)}")
            Future.successful(rejectResponse(request))
          }
      } recoverWith {
        case NonFatal(ex) =>
          logger.error(s"$logPrefix fail rate limiter failed", ex)
          block(request) // let pass in case of internal failure
      }
    }
  }
}
