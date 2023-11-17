package com.digitaltangible.playguard

import play.api.http._
import play.api.mvc.request.RemoteConnection
import play.api.mvc.{EssentialFilter, Handler, Headers, RequestHeader}
import play.api.routing.Router
import play.core.DefaultWebCommands

import java.net.InetAddress
import javax.inject.{Inject, Provider}
import scala.util.Try

/**
 * Custom RequestHandler to replace the immediate connection with the last IP address in the X-Forwarded-For header, if available.
 * This is meant for scenarios where you don't know your immediate connection's IP address beforehand
 * (to configure it as a trusted proxy) but can still trust it, e.g. on Heroku.
 * Does not support RFC 7239.
 *
 * @param router
 * @param errorHandler
 * @param configuration
 * @param filters
 */
class XForwardedTrustImmediateConnectionRequestHandler @Inject() (
    router: Provider[Router],
    errorHandler: HttpErrorHandler,
    configuration: HttpConfiguration,
    filters: Seq[EssentialFilter]
) extends DefaultHttpRequestHandler(
      new DefaultWebCommands,
      None,
      router,
      errorHandler,
      configuration,
      filters
    )
    with HeaderNames {

  override def handlerForRequest(rh: RequestHeader): (RequestHeader, Handler) =
    super.handlerForRequest(rh.withConnection(getTrustedXForwardedFor(rh.headers) getOrElse rh.connection))

  private def getTrustedXForwardedFor(headers: Headers): Option[RemoteConnection] =
    for {
      lastForAddr <- h(headers, X_FORWARDED_FOR).lastOption
      inetAddr    <- Try(InetAddress.getByName(lastForAddr)).toOption
      lastProtoO = h(headers, X_FORWARDED_PROTO).lastOption
    } yield RemoteConnection(inetAddr, lastProtoO.map(_.toLowerCase).contains("https"), None)

  private def h(h: Headers, key: String): Seq[String] =
    h.getAll(key).flatMap(s => s.split(",\\s*")).map(unquote)

  private def unquote(s: String): String =
    if (s.length >= 2 && s.charAt(0) == '"' && s.charAt(s.length - 1) == '"') {
      s.substring(1, s.length - 1)
    } else s
}
