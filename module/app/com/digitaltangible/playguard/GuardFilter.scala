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


trait IpChecker {
  def isWhitelisted(ip: String): Boolean

  def isBlacklisted(ip: String): Boolean
}

class ConfigIpChecker(conf: Configuration) extends IpChecker {

  private lazy val IpWhitelist = conf.getStringSeq("playguard.filter.ip.whitelist").map(_.toSet).getOrElse(Set.empty)
  private lazy val IpBlacklist = conf.getStringSeq("playguard.filter.ip.blacklist").map(_.toSet).getOrElse(Set.empty)


  override def isWhitelisted(ip: String): Boolean = IpWhitelist.contains(ip)

  override def isBlacklisted(ip: String): Boolean = IpBlacklist.contains(ip)
}

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
class GuardFilter(conf: Configuration, system: ActorSystem, ipListChecker: IpChecker) extends EssentialFilter {

  private val logger = Logger(this.getClass)

  private implicit val implicitConf = conf

  private lazy val Enabled = conf.getBoolean("playguard.filter.enabled").getOrElse(false)

  private lazy val IpTokenBucketSize = requiredConfInt("playguard.filter.ip.bucket.size")
  private lazy val IpTokenBucketRate = requiredConfInt("playguard.filter.ip.bucket.rate")

  private lazy val GlobalTokenBucketSize = requiredConfInt("playguard.filter.global.bucket.size")
  private lazy val GlobalTokenBucketRate = requiredConfInt("playguard.filter.global.bucket.rate")

  private def requiredConfInt(key: String): Int = conf.getInt(key).getOrElse(sys.error(s"missing or invalid config value: $key"))

  private lazy val ipTbActorRef = TokenBucketGroup.create(system, IpTokenBucketSize, IpTokenBucketRate)
  private lazy val globalTbActorRef = TokenBucketGroup.create(system, GlobalTokenBucketSize, GlobalTokenBucketRate)

  private implicit val materializer = ActorMaterializer()(system)

  def apply(next: EssentialAction) = EssentialAction { implicit request: RequestHeader =>
    lazy val ip = clientIp(request)
    if (!Enabled) next(request)
    else if (ipListChecker.isWhitelisted(ip)) next(request)
    else if (ipListChecker.isBlacklisted(ip)) done(Forbidden("IP address blocked"))
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
