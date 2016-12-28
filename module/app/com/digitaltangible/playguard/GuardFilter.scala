package com.digitaltangible.playguard

import java.util.NoSuchElementException

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.digitaltangible.ratelimit.TokenBucketGroup
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.streams.Accumulator
import play.api.libs.streams.Accumulator._
import play.api.mvc.Results._
import play.api.mvc._
import play.api.{Configuration, Logger}

import scala.concurrent._
import scala.util.control.NonFatal

/**
  * Filter for rate limiting and IP whitelisting/blacklisting
  *
  * Rejects request based on the following rules:
  *
  * 1. if IP is in whitelist => let pass
  * 2. else if IP is in blacklist => reject with ‘403 FORBIDDEN’
  * 3. else if IP rate limit exceeded => reject with ‘429 TOO_MANY_REQUEST’
  * 4. else if global rate limit exceeded => reject with ‘429 TOO_MANY_REQUEST’
  *
  * @param conf
  * @param system
  */
class GuardFilter(conf: Configuration, system: ActorSystem) extends EssentialFilter {

  private val logger = Logger(this.getClass)

  private implicit val implicitConf = conf

  private lazy val Enabled = conf.getBoolean("play.guard.filter.enabled").get

  private lazy val IpTokenBucketSize = conf.getInt("play.guard.filter.ip.bucket.size").get
  private lazy val IpTokenBucketRate = conf.getInt("play.guard.filter.ip.bucket.rate").get

  private lazy val GlobalTokenBucketSize = conf.getInt("play.guard.filter.global.bucket.size").get
  private lazy val GlobalTokenBucketRate = conf.getInt("play.guard.filter.global.bucket.rate").get

  private lazy val IpWhitelist = conf.getString("play.guard.filter.ip.whitelist").fold(Vector[String]())(_.split(',').toVector)
  private lazy val IpBlacklist = conf.getString("play.guard.filter.ip.blacklist").fold(Vector[String]())(_.split(',').toVector)

  private lazy val ipTbActorRef = TokenBucketGroup.create(system, IpTokenBucketSize, IpTokenBucketRate)
  private lazy val globalTbActorRef = TokenBucketGroup.create(system, GlobalTokenBucketSize, GlobalTokenBucketRate)

  private implicit val materializer = ActorMaterializer()(system)

  def apply(next: EssentialAction) = EssentialAction { implicit request: RequestHeader =>
    if (!Enabled) next(request)
    else if (IpWhitelist.contains(clientIp(request))) next(request)
    else if (IpBlacklist.contains(clientIp(request))) done(Forbidden("IP address blocked"))
    else {
      val ts = System.currentTimeMillis()
      Accumulator.flatten(checkRateLimits(clientIp(request)).map { res =>
        logger.debug(s"rate limit check took ${System.currentTimeMillis() - ts} ms")
        if (res) next(request)
        else done(TooManyRequests("too many requests"))
      })
    }
  }

  private def checkRateLimits(ip: String): Future[Boolean] = {
    (for {
      ipRateOk <- checkIpRate(ip)
      if ipRateOk
      globalRateOk <- checkGlobalRate()
    } yield {
      globalRateOk
    }).recover {
      case ex: NoSuchElementException =>
        false

      case NonFatal(ex) =>
        logger.error("rate limiter failed", ex)
        true // let pass in case of internal failure
    }
  }

  private def checkIpRate(ip: String): Future[Boolean] = {
    TokenBucketGroup.consume(ipTbActorRef, ip, 1).map { remaining =>
      logBucketLevel(s"IP $ip", remaining, IpTokenBucketSize)
      remaining >= 0
    }
  }

  private def checkGlobalRate() = {
    TokenBucketGroup.consume(globalTbActorRef, "G", 1).map { remaining =>
      logBucketLevel("Global", remaining, GlobalTokenBucketSize)
      remaining >= 0
    }
  }

  private def logBucketLevel(prefix: String, remaining: Int, bucketSize: Int): Unit = {
    if (remaining < 0) logger.error(s"$prefix rate limit exceeded")
    else if (remaining < bucketSize.toFloat / 2) logger.warn(s"$prefix rate limit below 50%: $remaining")
    else logger.debug(s"$prefix bucket level: $remaining")
  }
}
