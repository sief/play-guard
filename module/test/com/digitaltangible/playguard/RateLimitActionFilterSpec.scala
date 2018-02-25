package com.digitaltangible.playguard

import com.digitaltangible.FakeClock
import org.scalatest.MustMatchers
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.mvc.Results._
import play.api.mvc._
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.ExecutionContext

class RateLimitActionFilterSpec extends PlaySpec with GuiceOneAppPerSuite with MustMatchers {

  implicit lazy val system = app.actorSystem

  implicit lazy val materializer = app.materializer

  implicit lazy val conf = app.configuration

  implicit lazy val ec = app.injector.instanceOf[ExecutionContext]

  lazy val bodyParsers = app.injector.instanceOf[PlayBodyParsers]

  lazy val actionBuilder: DefaultActionBuilder = DefaultActionBuilder(bodyParsers.anyContent)

  "RateLimiter" should {
    "consumeAndCheck for rate limiting" in {
      val fakeClock = new FakeClock
      val rateLimiter = new RateLimiter(2, 2, "test", fakeClock)
      rateLimiter.consumeAndCheck("1") mustBe true
      rateLimiter.consumeAndCheck("1") mustBe true
      rateLimiter.consumeAndCheck("1") mustBe false
      rateLimiter.consumeAndCheck("2") mustBe true
      fakeClock.ts = 501000000
      rateLimiter.consumeAndCheck("1") mustBe true
      rateLimiter.consumeAndCheck("1") mustBe false
    }

    "check and consume for failure rate limiting" in {
      val fakeClock = new FakeClock
      val rateLimiter = new RateLimiter(2, 2, "test", fakeClock)
      rateLimiter.check("1") mustBe true
      rateLimiter.check("1") mustBe true
      rateLimiter.check("1") mustBe true
      rateLimiter.consume("1") mustBe 1
      rateLimiter.check("1") mustBe true
      rateLimiter.consume("1") mustBe 0
      rateLimiter.check("1") mustBe false
      fakeClock.ts = 501000000
      rateLimiter.check("1") mustBe true
      rateLimiter.consume("1") mustBe 0
      rateLimiter.check("1") mustBe false
      rateLimiter.consume("2") mustBe 1
      rateLimiter.check("2") mustBe true
    }
  }

  "RateLimitActionFilter" should {
    "limit request rate" in {
      val fakeClock = new FakeClock
      val rl = new RateLimiter(2, 2, "test", fakeClock)
      val rejectResponse = (_: Request[_]) => TooManyRequests("test")

      val action = (actionBuilder andThen new RateLimitActionFilter[Request](rl)(rejectResponse, _ => "key")) {
        Ok("ok")
      }
      val request = FakeRequest(GET, "/")

      var result = call(action, request)
      status(result) mustEqual OK
      result = call(action, request)
      status(result) mustEqual OK
      result = call(action, request)
      status(result) mustEqual TOO_MANY_REQUESTS
      fakeClock.ts = 501000000
      result = call(action, request)
      status(result) mustEqual OK
    }
  }

  "FailureRateLimitFunction" should {
    "limit failure rate" in {
      val fakeClock = new FakeClock
      val rl = new RateLimiter(2, 2, "test", fakeClock)
      val failFunc = (r: Result) => r.header.status == OK

      val action =
        (actionBuilder andThen new FailureRateLimitFunction[Request](rl)(_ => BadRequest, _ => "key", failFunc)) {
          request =>
            if (request.path == "/") Ok
            else BadRequest
        }
      val requestOk = FakeRequest(GET, "/")
      val requestFail = FakeRequest(GET, "/x")

      var result = call(action, requestOk)
      status(result) mustEqual OK
      result = call(action, requestOk)
      status(result) mustEqual OK
      result = call(action, requestOk)
      status(result) mustEqual OK
      result = call(action, requestFail)
      status(result) mustEqual BAD_REQUEST
      result = call(action, requestOk)
      status(result) mustEqual OK
      result = call(action, requestFail)
      status(result) mustEqual BAD_REQUEST
      result = call(action, requestOk)
      status(result) mustEqual BAD_REQUEST
      fakeClock.ts = 501000000
      result = call(action, requestOk)
      status(result) mustEqual OK
    }
  }

  "HttpErrorRateLimitFunction" should {
    "limit failure rate" in {

      val fakeClock = new FakeClock
      val rl = new RateLimiter(1, 2, "test", fakeClock)

      val action = (actionBuilder andThen HttpErrorRateLimitFunction[Request](rl)(_ => BadRequest, Seq(UNAUTHORIZED))) {
        request: RequestHeader =>
          if (request.path == "/") Ok
          else Unauthorized
      }

      val requestOk = FakeRequest(GET, "/")
      val requestFail = FakeRequest(GET, "/x")

      var result = call(action, requestOk)
      status(result) mustEqual OK
      result = call(action, requestFail)
      status(result) mustEqual UNAUTHORIZED
      result = call(action, requestOk)
      status(result) mustEqual BAD_REQUEST
      fakeClock.ts = 501000000
      result = call(action, requestOk)
      status(result) mustEqual OK
    }
  }
}
