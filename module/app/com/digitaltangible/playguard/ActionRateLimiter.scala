package com.digitaltangible.playguard

import akka.actor.ActorSystem
import com.digitaltangible.ratelimit.TokenBucketGroup
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc._
import play.api.{Configuration, Logger}

import scala.concurrent.Future
import scala.util.control.NonFatal

class ActionRateLimiter(conf: Configuration, system: ActorSystem) {

  private val logger = Logger(this.getClass)

  private implicit val iConf = conf

  /**
    * Creates an ActionBuilder with a bucket for each IP address.
    *
    * @param rl
    * @param rejectResponse
    * @return
    */
  def ipRateLimiterAction(rl: RateLimiter)(rejectResponse: RequestHeader => Result) = new ActionFilter[Request] with ActionBuilder[Request] {
    def filter[A](request: Request[A]): Future[Option[Result]] = {
      rl.check(clientIp(request)).map { res =>
        if (res) None
        else Some(rejectResponse(request))
      }
    }
  }

  /**
    * Creates an ActionBuilder with a bucket for each key.
    *
    * @param rl
    * @param key
    * @param rejectResponse
    * @return
    */
  def keyRateLimiterAction(rl: RateLimiter)(rejectResponse: RequestHeader => Result)(key: Any) = new ActionFilter[Request] with ActionBuilder[Request] {

    def filter[A](request: Request[A]): Future[Option[Result]] = {
      rl.check(key).map { res =>
        if (res) None
        else Some(rejectResponse(request))
      }
    }
  }

  /**
    * Creates an ActionFilter to chain behind a Transformer/Refiner to use custom request attributes as key.
    *
    * @param rl
    * @param rejectResponse
    * @param f
    * @tparam R
    * @return
    */
  def customRequestRateLimiterFilter[R[_]](rl: RateLimiter)(rejectResponse: R[_] => Result)(f: R[_] => Any) = new ActionFilter[R] {

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
    * @param size      bucket size
    * @param rate      bucket refill rate per second
    * @param logPrefix prefix for internal logging
    */
  case class RateLimiter(size: Int, rate: Float, logPrefix: String = "") {

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

    def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]): Future[Result] = {

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
}