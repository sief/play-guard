package com.sief.play.guard

import scala.concurrent.Future
import scala.util.control.NonFatal

import com.sief.ratelimit.TokenBucketGroup
import play.api.Logger
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc._

object RateLimitAction {

  private val logger = Logger(this.getClass)

  object IpRateLimitAction {

    /**
     * Creates an ActionBuilder which holds a TokenBucketGroup with a bucket for each IP address.
     * Every request consumes a token. If no tokens remain, the request is rejected.
     * @param rl RateLimiter instance
     * @param rejectResponse response if request is rejected
     * @return
     */
    def apply(rl: RateLimiter)(rejectResponse: RequestHeader => Result) = new ActionFilter[Request] with ActionBuilder[Request] {
      def filter[A](request: Request[A]) = {
        rl.check(request.remoteAddress).map { res =>
          if (res) None
          else Some(rejectResponse(request))
        }
      }
    }
  }

  object KeyRateLimitAction {

    /**
     * Creates an ActionBuilder which holds a TokenBucketGroup with a bucket for each key.
     * Every request consumes a token. If no tokens remain, the request is rejected.
     * @param rl RateLimiter instance
     * @param rejectResponse response if request is rejected
     * @param key the bucket key
     * @return
     */
    def apply(rl: RateLimiter)(rejectResponse: RequestHeader => Result)(key: Any) = new ActionFilter[Request] with ActionBuilder[Request] {
      def filter[A](request: Request[A]) = {
        rl.check(key).map { res =>
          if (res) None
          else Some(rejectResponse(request))
        }
      }
    }
  }


  object FailureRateLimitAction {

    /**
     * Creates an ActionBuilder which holds a TokenBucketGroup with a bucket for each IP address.
     * Tokens are consumed only by failures. If no tokens remain, the request is rejected.
     * @param size token bucket size
     * @param rate token bucket rate (per second)
     * @param rejectResponse response if request is rejected
     * @param logPrefix prefix for logging
     * @param errorCodes HTTP returns codes which cause a token consumption
     * @return the ActionBuilder instance
     */
    def apply(size: Int, rate: Float, rejectResponse: RequestHeader => Result, logPrefix: String = "", errorCodes: Seq[Int] = 400 to 499) = new ActionBuilder[Request] {

      private lazy val ipTbActorRef = TokenBucketGroup.create(Akka.system, size, rate)

      def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = {

        TokenBucketGroup.consume(ipTbActorRef, request.remoteAddress, 0).flatMap {
          remaining =>
            if (remaining > 0) {
              if (remaining < size.toFloat / 2) logger.warn(s"$logPrefix fail rate limit for ${request.remoteAddress} below 50%: $remaining")
              val res = block(request)
              res.map {
                r =>
                  if (errorCodes contains r.header.status) TokenBucketGroup.consume(ipTbActorRef, request.remoteAddress, 1)
              }
              res
            } else {
              logger.error(s"$logPrefix too many failed attempts from ${request.remoteAddress}")
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

  /**
   * Holds a TokenBucketGroup for rate limiting.
   * @param size bucket size
   * @param rate bucket refill rate per second
   * @param logPrefix prefix for internal logging
   */
  final case class RateLimiter(size: Int, rate: Float, logPrefix: String = "") {

    private lazy val tbActorRef = TokenBucketGroup.create(Akka.system, size, rate)

    /**
     * Checks if the bucket for the given key has at least one token left.
     * If available, the token is consumed.
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