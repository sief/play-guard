package com.digitaltangible

import play.api.Configuration
import play.api.mvc.RequestHeader

package object playguard {

  def clientIp(request: RequestHeader)(implicit conf: Configuration): String = {
    (for {
      configuredHeader <- conf.get[Option[String]]("playguard.clientipheader")
      ip <- request.headers.get(configuredHeader)
    } yield ip) getOrElse {

      // Consider X-Forwarded-For as most accurate if it exists
      // Since it is easy to forge an X-Forwarded-For, only consider the last ip added by our proxy as the most accurate
      // https://en.wikipedia.org/wiki/X-Forwarded-For
      request.headers.get("X-Forwarded-For").map(_.split(",").last.trim).getOrElse {
        request.remoteAddress
      }

    }
  }
}
