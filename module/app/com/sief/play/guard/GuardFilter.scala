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
 */
object GuardFilter extends EssentialFilter {

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

  def apply(next: EssentialAction) = EssentialAction { request =>
    if (!Enabled) next(request)
    else if (IpWhitelist.contains(request.remoteAddress)) next(request)
    else if (IpBlacklist.contains(request.remoteAddress)) Done(Forbidden)
    else {
      Iteratee.flatten(checkRateLimits(request.remoteAddress).map { res =>
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
        Logger.error("rate limiter failed", ex)
        true
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
    if (remaining < 0) Logger.error(s"$prefix rate limit exceeded")
    else if (remaining < bucketSize.toFloat / 2) Logger.warn(s"$prefix rate limit below 50%: $remaining")
    else Logger.debug(s"$prefix bucket level: $remaining")
  }
}
