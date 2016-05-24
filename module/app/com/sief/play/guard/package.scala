package com.sief.play

import play.api.Configuration
import play.api.mvc.RequestHeader

package object guard {

  def clientIp(request: RequestHeader)(implicit conf: Configuration): String = {
    (for {
      configuredHeader <- conf.getString("play.guard.clientipheader")
      ip <- request.headers.get(configuredHeader)
    } yield ip) getOrElse request.remoteAddress
  }

}
