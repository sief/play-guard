package com.digitaltangible.playguard

import java.util.NoSuchElementException
import javax.inject.{Inject, Named, Singleton}

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.ActorMaterializer
import com.digitaltangible.tokenbucket.TokenBucketGroup
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

@Singleton
class DefaultIpChecker @Inject()(conf: Configuration) extends IpChecker {

  private lazy val IpWhitelist = conf.getStringSeq("playguard.filter.ip.whitelist").map(_.toSet).getOrElse(Set.empty)
  private lazy val IpBlacklist = conf.getStringSeq("playguard.filter.ip.blacklist").map(_.toSet).getOrElse(Set.empty)


  override def isWhitelisted(ip: String): Boolean = IpWhitelist.contains(ip)

  override def isBlacklisted(ip: String): Boolean = IpBlacklist.contains(ip)
}


trait TokenBucketGroupProvider {
  val tokenBucketSize: Int
  val tokenBucketRate: Int
  val tbActorRef: ActorRef
}

trait DefaultTokenBucketGroupProvider extends TokenBucketGroupProvider {
  protected val conf: Configuration
  protected val system: ActorSystem
  lazy val tbActorRef: ActorRef = TokenBucketGroup.create(system, tokenBucketSize, tokenBucketRate)

  protected def requiredConfInt(key: String): Int = conf.getInt(key).getOrElse(sys.error(s"missing or invalid config value: $key"))
}

@Singleton
class DefaultIpTokenBucketGroupProvider @Inject()(val conf: Configuration, val system: ActorSystem) extends DefaultTokenBucketGroupProvider {
  lazy val tokenBucketSize: Int = requiredConfInt("playguard.filter.ip.bucket.size")
  lazy val tokenBucketRate: Int = requiredConfInt("playguard.filter.ip.bucket.rate")
}

@Singleton
class DefaultGlobalTokenBucketGroupProvider @Inject()(val conf: Configuration, val system: ActorSystem) extends DefaultTokenBucketGroupProvider {
  lazy val tokenBucketSize: Int = requiredConfInt("playguard.filter.global.bucket.size")
  lazy val tokenBucketRate: Int = requiredConfInt("playguard.filter.global.bucket.rate")
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
  */
@Singleton
class GuardFilter @Inject()(val conf: Configuration,
                            system: ActorSystem,
                            @Named("ip") ipTokenBucketGroupProvider: TokenBucketGroupProvider,
                            @Named("global") globalTokenBucketGroupProvider: TokenBucketGroupProvider,
                            ipListChecker: IpChecker) extends EssentialFilter with IpResolver {

  private val logger = Logger(this.getClass)

  private implicit val implicitConf = conf

  private implicit val materializer: ActorMaterializer = ActorMaterializer()(system)

  private lazy val Enabled = conf.getBoolean("playguard.filter.enabled").getOrElse(false)

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
    TokenBucketGroup.consume(ipTokenBucketGroupProvider.tbActorRef, ip, 1).map { remaining =>
      logBucketLevel(s"IP $ip", remaining, ipTokenBucketGroupProvider.tokenBucketSize)
      remaining >= 0
    }
  }

  private def checkGlobalRate() = {
    TokenBucketGroup.consume(globalTokenBucketGroupProvider.tbActorRef, "G", 1).map { remaining =>
      logBucketLevel("Global", remaining, globalTokenBucketGroupProvider.tokenBucketSize)
      remaining >= 0
    }
  }

  private def logBucketLevel(prefix: String, remaining: Int, bucketSize: Int): Unit = {
    if (remaining < 0) logger.error(s"$prefix rate limit exceeded")
    else if (remaining < bucketSize.toFloat / 2) logger.warn(s"$prefix rate limit below 50%: $remaining")
    else logger.debug(s"$prefix bucket level: $remaining")
  }
}
