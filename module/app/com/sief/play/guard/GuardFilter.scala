package com.sief.play.guard

import java.util.NoSuchElementException

import scala.concurrent._
import scala.util.control.NonFatal

import com.sief.ratelimit.TokenBucketGroup
import play.api.{Logger, Play}
import play.api.Play._
import play.api.i18n.{Lang, Messages}
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee.{Done, Iteratee}
import play.api.mvc._
import play.api.mvc.Results._

/**
 * Filter for rate limiting and IP whitelisting/blacklisting
 *
 * Rejects request based on the following rules:
 *
 * 1. if IP is in whitelist => let pass
 * 2. else if IP is in blacklist => reject with ‘403 FORBIDDEN’
 * 3. else if IP rate limit exceeded => reject with ‘429 TOO_MANY_REQUEST’
 * 4. else if global rate limit exceeded => reject with ‘429 TOO_MANY_REQUEST’
 */
class GuardFilter extends EssentialFilter {

  lazy val conf = Play.current.configuration

  lazy val Enabled = conf.getBoolean("guard-filter.enabled").get

  lazy val IpTokenBucketSize = conf.getInt("guard-filter.ip.bucket.size").get
  lazy val IpTokenBucketRate = conf.getInt("guard-filter.ip.bucket.rate").get

  lazy val GlobalTokenBucketSize = conf.getInt("guard-filter.global.bucket.size").get
  lazy val GlobalTokenBucketRate = conf.getInt("guard-filter.global.bucket.rate").get

  lazy val IpWhitelist = conf.getString("guard-filter.ip.whitelist").fold(Vector[String]())(_.split(',').toVector)
  lazy val IpBlacklist = conf.getString("guard-filter.ip.blacklist").fold(Vector[String]())(_.split(',').toVector)

  private lazy val ipTbActorRef = TokenBucketGroup.create(Akka.system, IpTokenBucketSize, IpTokenBucketRate)
  private lazy val globalTbActorRef = TokenBucketGroup.create(Akka.system, GlobalTokenBucketSize, GlobalTokenBucketRate)

  private val logger = Logger(this.getClass)

  def apply(next: EssentialAction) = EssentialAction { request =>
    if (!Enabled) next(request)
    else if (IpWhitelist.contains(request.remoteAddress)) next(request)
    else if (IpBlacklist.contains(request.remoteAddress)) Done(Forbidden)
    else {
      val ts = System.currentTimeMillis()
      Iteratee.flatten(checkRateLimits(request.remoteAddress).map { res =>
        logger.debug(s"rate limit check took ${System.currentTimeMillis() - ts} ms")
        if (res) next(request)
        else Done(TooManyRequest(Messages("error.overload")(request.acceptLanguages.headOption.getOrElse(Lang.defaultLang))))
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

/**
 * Factory companion
 */
object GuardFilter{
  def apply(): GuardFilter = new GuardFilter()
}
