package com.digitaltangible.playguard

import play.api.Configuration
import play.api.mvc.RequestHeader

trait IpResolver {

  val conf: Configuration

  def clientIp(request: RequestHeader): String = getClientIp(request, conf)
}
