package com.digitaltangible.playguard

import akka.actor.ActorSystem
import play.api.Configuration


// for compile-time injection
trait PlayGuardComponents {

  def configuration: Configuration

  def actorSystem: ActorSystem

  lazy val ipChecker = new ConfigIpChecker(configuration)

  lazy val guardFilter = new GuardFilter(configuration, actorSystem, ipChecker)

  lazy val rateLimitActionBuilder = new ActionRateLimiter(configuration, actorSystem)
}




