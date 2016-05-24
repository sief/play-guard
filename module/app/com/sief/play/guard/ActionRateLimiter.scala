package com.sief.play.guard

import akka.actor.ActorSystem
import com.sief.ratelimit.TokenBucketGroup
import play.api.{Configuration, Logger}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc._

import scala.concurrent.Future
import scala.util.control.NonFatal

class ActionRateLimiter(conf: Configuration, system: ActorSystem) {

  private val logger = Logger(this.getClass)

  private implicit val iConf = conf

  /**
    * Creates an ActionBuilder which holds a TokenBucketGroup with a bucket for each IP address.
    * Every request consumes a token. If no tokens remain, the request is rejected.
    *
    * @param size           bucket size
    * @param rate           bucket refill rate per second
    * @param logPrefix      prefix for internal logging
    * @param rejectResponse response if request is rejected
    * @return
    */
  def ipRateLimiterAction(size: Int, rate: Float, logPrefix: String = "")(rejectResponse: RequestHeader => Result) = new ActionFilter[Request] with ActionBuilder[Request] {

    private val rl = RateLimiter(size, rate, logPrefix)

    def filter[A](request: Request[A]) = {
      rl.check(clientIp(request)).map { res =>
        if (res) None
        else Some(rejectResponse(request))
      }
    }
  }

  /**
    * Creates an ActionBuilder which holds a TokenBucketGroup with a bucket for each key.
    * Every request consumes a token. If no tokens remain, the request is rejected.
    *
    * @param size           bucket size
    * @param rate           bucket refill rate per second
    * @param logPrefix      prefix for internal logging
    * @param rejectResponse response if request is rejected
    * @param key            the bucket key
    * @return
    */
  def keyRateLimiterAction(size: Int, rate: Float, logPrefix: String = "")(rejectResponse: RequestHeader => Result)(key: Any) = new ActionFilter[Request] with ActionBuilder[Request] {

    private val rl = RateLimiter(size, rate, logPrefix)

    def filter[A](request: Request[A]) = {
      rl.check(key).map { res =>
        if (res) None
        else Some(rejectResponse(request))
      }
    }
  }


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
  def failureRateLimiterAction(size: Int, rate: Float,
                       rejectResponse: RequestHeader => Result, logPrefix: String = "", errorCodes: Seq[Int] = 400 to 499) = new ActionBuilder[Request] {

    private lazy val ipTbActorRef = TokenBucketGroup.create(system, size, rate)

    def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = {

      TokenBucketGroup.consume(ipTbActorRef, clientIp(request), 0).flatMap {
        remaining =>
          if (remaining > 0) {
            if (remaining < size.toFloat / 2) logger.warn(s"$logPrefix fail rate limit for ${clientIp(request)} below 50%: $remaining")
            val res = block(request)
            res.map {
              r =>
                if (errorCodes contains r.header.status) TokenBucketGroup.consume(ipTbActorRef, clientIp(request), 1)
            }
            res
          } else {
            logger.error(s"$logPrefix too many failed attempts from ${clientIp(request)}")
            Future.successful(rejectResponse(request))
          }
      } recoverWith {
        case NonFatal(ex) =>
          logger.error(s"$logPrefix fail rate limiter failed", ex)
          block(request) // let pass in case of internal failure
      }
    }
  }

  /**
    * Holds a TokenBucketGroup for rate limiting.
    *
    * @param size      bucket size
    * @param rate      bucket refill rate per second
    * @param logPrefix prefix for internal logging
    */
  private final case class RateLimiter(size: Int, rate: Float, logPrefix: String = "") {

    private lazy val tbActorRef = TokenBucketGroup.create(system, size, rate)

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
}