package com.digitaltangible.playguard

import akka.actor.ActorSystem
import play.api.Configuration


// for compile-time injection
trait PlayGuardComponents {

  def configuration: Configuration

  def actorSystem: ActorSystem

  lazy val guardFilter = new GuardFilter(configuration, actorSystem)

  lazy val rateLimitActionBuilder = new ActionRateLimiter(configuration, actorSystem)
}




