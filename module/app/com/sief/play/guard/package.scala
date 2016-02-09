package com.sief.play

import play.api.Play
import play.api.mvc.RequestHeader

package object guard {
  def clientIp(request: RequestHeader): String = {
    (for {
      configuredHeader <- Play.current.configuration.getString("play.guard.clientipheader")
      ip <- request.headers.get(configuredHeader)
    } yield ip) getOrElse request.remoteAddress
  }
}
