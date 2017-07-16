package com.digitaltangible.playguard

import java.util.NoSuchElementException
import javax.inject.{Inject, Named, Singleton}

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.ActorMaterializer
import com.digitaltangible.tokenbucket.TokenBucketGroup
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

  private lazy val IpWhitelist = conf.get[Option[Seq[String]]]("playguard.filter.ip.whitelist").map(_.toSet).getOrElse(Set.empty)
  private lazy val IpBlacklist = conf.get[Option[Seq[String]]]("playguard.filter.ip.blacklist").map(_.toSet).getOrElse(Set.empty)


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
  implicit protected val system: ActorSystem
  implicit protected val ec: ExecutionContext
  lazy val tbActorRef: ActorRef = TokenBucketGroup.create(tokenBucketSize, tokenBucketRate)
}

@Singleton
class DefaultIpTokenBucketGroupProvider @Inject()(val conf: Configuration, val system: ActorSystem, val ec: ExecutionContext) extends DefaultTokenBucketGroupProvider {
  lazy val tokenBucketSize: Int = conf.get[Int]("playguard.filter.ip.bucket.size")
  lazy val tokenBucketRate: Int = conf.get[Int]("playguard.filter.ip.bucket.rate")
}

@Singleton
class DefaultGlobalTokenBucketGroupProvider @Inject()(val conf: Configuration, val system: ActorSystem, val ec: ExecutionContext) extends DefaultTokenBucketGroupProvider {
  lazy val tokenBucketSize: Int = conf.get[Int]("playguard.filter.global.bucket.size")
  lazy val tokenBucketRate: Int = conf.get[Int]("playguard.filter.global.bucket.rate")
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
class GuardFilter @Inject()(@Named("ip") ipTokenBucketGroupProvider: TokenBucketGroupProvider,
                            @Named("global") globalTokenBucketGroupProvider: TokenBucketGroupProvider,
                            ipListChecker: IpChecker)(implicit system: ActorSystem, ec: ExecutionContext, conf: Configuration) extends EssentialFilter {

  private val logger = Logger(this.getClass)

  private implicit val materializer: ActorMaterializer = ActorMaterializer()

  private lazy val Enabled = conf.get[Boolean]("playguard.filter.enabled")

  def apply(next: EssentialAction) = EssentialAction { implicit request: RequestHeader =>
    lazy val ip = clientIp(request)
    if (!Enabled) next(request)
    else if (ipListChecker.isWhitelisted(ip)) next(request)
    else if (ipListChecker.isBlacklisted(ip)) done(Forbidden("IP address blocked"))
    else {
      val ts = System.currentTimeMillis()
      Accumulator.flatten(checkRateLimits(ip).map { res =>
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
    if (remaining < 0) logger.warn(s"$prefix rate limit exceeded")
    else if (remaining < bucketSize.toFloat / 2) logger.info(s"$prefix rate limit below 50%: $remaining")
    else logger.debug(s"$prefix bucket level: $remaining")
  }
}
