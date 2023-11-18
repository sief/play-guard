package com.digitaltangible.playguard

import org.scalatestplus.play.PlaySpec
import play.api.http.{DefaultHttpErrorHandler, HttpConfiguration}
import play.api.mvc.Headers
import play.api.mvc.request.RemoteConnection
import play.api.routing.Router
import play.api.test.FakeRequest

import javax.inject.Provider

class XForwardedTrustImmediateConnectionRequestHandlerSpec extends PlaySpec {

  val handler = new XForwardedTrustImmediateConnectionRequestHandler(
    new Provider[Router]() {
      override def get(): Router = Router.empty
    },
    DefaultHttpErrorHandler,
    HttpConfiguration(),
    Nil
  )

  "XForwardedTrustImmediateConnectionRequestHandler" should {
    "get remote connection from header" in {
      val req = FakeRequest().withHeaders(Headers("X-Forwarded-For" -> "1.1.1.1", "X-Forwarded-Proto" -> "https"))
      handler.handlerForRequest(req)._1.remoteAddress mustBe "1.1.1.1"
      handler.handlerForRequest(req)._1.secure mustBe true
    }

    "get remote connection from last entry in header" in {
      val req =
        FakeRequest().withHeaders(Headers("X-Forwarded-For" -> "1.1.1.1,2.2.2.2", "X-Forwarded-Proto" -> "http,https"))
      handler.handlerForRequest(req)._1.remoteAddress mustBe "2.2.2.2"
      handler.handlerForRequest(req)._1.secure mustBe true
    }

    "get remote connection from last entry in header in quotes" in {
      val req = FakeRequest().withHeaders(
        Headers("X-Forwarded-For" -> """"1.1.1.1","2.2.2.2"""", "X-Forwarded-Proto" -> """"http","https"""")
      )
      handler.handlerForRequest(req)._1.remoteAddress mustBe "2.2.2.2"
      handler.handlerForRequest(req)._1.secure mustBe true
    }

    "get remote connection from multiple headers in different cases" in {
      val req = FakeRequest().withHeaders(
        Headers(
          "X-Forwarded-For"   -> "1.1.1.1",
          "x-forwarded-for"   -> "2.2.2.2",
          "x-forwarded-proto" -> "http",
          "X-Forwarded-Proto" -> "https"
        )
      )
      handler.handlerForRequest(req)._1.remoteAddress mustBe "2.2.2.2"
      handler.handlerForRequest(req)._1.secure mustBe true
    }

    "keep remote connection if no forwarded header in request" in {
      val req =
        FakeRequest().withConnection(RemoteConnection("1.1.1.1", false, None))
      handler.handlerForRequest(req)._1.remoteAddress mustBe "1.1.1.1"
      handler.handlerForRequest(req)._1.secure mustBe false
    }
  }
}
