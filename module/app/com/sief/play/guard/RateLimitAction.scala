package com.sief.play.guard

import com.sief.ratelimit.TokenBucketGroup
import play.api.Logger
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc._

import scala.concurrent.Future


object RateLimitAction {

  /**
   * Creates an ActionBuilder which holds a TokenBucketGroup with a bucket for each IP address.
   * Every request consumes a token. If no tokens remain, the request is rejected.
   * @param size token bucket size
   * @param rate token bucket rate (per second)
   * @param rejectedResponse response if request is rejected
   * @param logPrefix prefix for logging
   * @return the ActionBuilder instance
   */
  def apply(size: Int, rate: Float, rejectedResponse: RequestHeader => Result, logPrefix: String = "") = new ActionBuilder[Request] {

    private lazy val ipTbActorRef = TokenBucketGroup.create(Akka.system, size, rate)

    def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = {
      TokenBucketGroup.consume(ipTbActorRef, request.remoteAddress, 1).flatMap { remaining =>
        if (remaining >= 0) {
          if (remaining < size.toFloat / 2) Logger.warn(s"$logPrefix rate limit for ${request.remoteAddress} below 50%: $remaining")
          block(request)
        } else {
          Logger.error(s"$logPrefix rate limit for ${request.remoteAddress} exceeded")
          Future.successful(rejectedResponse(request))
        }
      }
    }
  }

  /**
   * Creates an ActionBuilder which holds a TokenBucketGroup with a bucket for each IP address.
   * Tokens are consumed only by failures. If no tokens remain, the request is rejected.
   * @param size token bucket size
   * @param rate token bucket rate (per second)
   * @param rejectedResponse response if request is rejected
   * @param logPrefix prefix for logging
   * @param errorCodes HTTP returns codes which cause a token consumption
   * @return the ActionBuilder instance
   */
  def failLimit(size: Int, rate: Float, rejectedResponse: RequestHeader => Result, logPrefix: String = "", errorCodes: Seq[Int] = 400 to 499) = new ActionBuilder[Request] {

    private lazy val ipTbActorRef = TokenBucketGroup.create(Akka.system, size, rate)

    def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = {

      TokenBucketGroup.consume(ipTbActorRef, request.remoteAddress, 0).flatMap { remaining =>
        if (remaining > 0) {
          if (remaining < size.toFloat / 2) Logger.warn(s"$logPrefix fail rate limit for ${request.remoteAddress} below 50%: $remaining")
          val res = block(request)
          res.map { r =>
            if (errorCodes contains r.header.status) TokenBucketGroup.consume(ipTbActorRef, request.remoteAddress, 1)
          }
          res
        } else {
          Logger.error(s"$logPrefix too many failed attempts from ${request.remoteAddress}")
          Future.successful(rejectedResponse(request))
        }
      }
    }
  }
}
