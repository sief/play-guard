package com.digitaltangible.playguard

import play.api.{Configuration, Environment}
import play.api.inject.Module

class PlayGuardModule  extends Module {
  def bindings(environment: Environment,
               configuration: Configuration) = Seq(

    bind[IpChecker].to[DefaultIpChecker],
    bind[TokenBucketGroupProvider].qualifiedWith("ip").to[DefaultIpTokenBucketGroupProvider],
    bind[TokenBucketGroupProvider].qualifiedWith("global").to[DefaultGlobalTokenBucketGroupProvider]
  )
}
