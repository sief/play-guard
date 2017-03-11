package com.digitaltangible.playguard

import akka.actor.ActorSystem
import play.api.inject.Module
import play.api.{Configuration, Environment}


// for compile-time DI
trait PlayGuardComponents {

  implicit def configuration: Configuration

  implicit def actorSystem: ActorSystem

  lazy val ipTokenBucketGroupProvider = new DefaultIpTokenBucketGroupProvider(configuration, actorSystem)
  lazy val globalTokenBucketGroupProvider = new DefaultGlobalTokenBucketGroupProvider(configuration, actorSystem)
  lazy val ipChecker = new DefaultIpChecker(configuration)

  lazy val guardFilter = new GuardFilter(
    ipTokenBucketGroupProvider,
    globalTokenBucketGroupProvider,
    ipChecker)
}


// for runtime DI
class PlayGuardIpCheckerModule extends Module {
  def bindings(environment: Environment,
               configuration: Configuration) = Seq(

    bind[IpChecker].to[DefaultIpChecker]
  )
}

class PlayGuardTokenBucketGroupProviderModule extends Module {
  def bindings(environment: Environment,
               configuration: Configuration) = Seq(

    bind[TokenBucketGroupProvider].qualifiedWith("ip").to[DefaultIpTokenBucketGroupProvider],
    bind[TokenBucketGroupProvider].qualifiedWith("global").to[DefaultGlobalTokenBucketGroupProvider]
  )
}





