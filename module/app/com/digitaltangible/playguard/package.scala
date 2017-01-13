package com.digitaltangible

import play.api.Configuration
import play.api.mvc.RequestHeader

package object playguard {

  def getClientIp(request: RequestHeader)(implicit conf: Configuration): String = {
    (for {
      configuredHeader <- conf.getString("playguard.clientipheader")
      ip <- request.headers.get(configuredHeader)
    } yield ip) getOrElse request.remoteAddress
  }

}
