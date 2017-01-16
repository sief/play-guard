package com.digitaltangible.playguard

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.digitaltangible.FakeClock
import org.scalatest.MustMatchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import play.api.mvc.Results._
import play.api.mvc.{ActionBuilder, EssentialAction, Request, Result}
import play.api.test.FakeRequest
import play.api.test.Helpers._


class RateLimitActionFilterSpec extends PlaySpec with OneAppPerSuite with ScalaFutures with MustMatchers {

  implicit lazy val system: ActorSystem = app.actorSystem

  implicit lazy val materializer: Materializer = app.materializer

  "RateLimiter" should {
    "consumeAndCheck for rate limiting" in {
      val fakeClock = new FakeClock
      val rateLimiter = new RateLimiter(2, 2, "test", fakeClock)
      rateLimiter.consumeAndCheck("1").futureValue mustBe true
      rateLimiter.consumeAndCheck("1").futureValue mustBe true
      rateLimiter.consumeAndCheck("1").futureValue mustBe false
      rateLimiter.consumeAndCheck("2").futureValue mustBe true
      fakeClock.ts = 501
      rateLimiter.consumeAndCheck("1").futureValue mustBe true
      rateLimiter.consumeAndCheck("1").futureValue mustBe false
    }

    "check and consume for failure rate limiting" in {
      val fakeClock = new FakeClock
      val rateLimiter = new RateLimiter(2, 2, "test", fakeClock)
      rateLimiter.check("1").futureValue mustBe true
      rateLimiter.check("1").futureValue mustBe true
      rateLimiter.check("1").futureValue mustBe true
      rateLimiter.consume("1").futureValue mustBe 1
      rateLimiter.check("1").futureValue mustBe true
      rateLimiter.consume("1").futureValue mustBe 0
      rateLimiter.check("1").futureValue mustBe false
      fakeClock.ts = 501
      rateLimiter.check("1").futureValue mustBe true
      rateLimiter.consume("1").futureValue mustBe 0
      rateLimiter.check("1").futureValue mustBe false
      rateLimiter.consume("2").futureValue mustBe 1
      rateLimiter.check("2").futureValue mustBe true
    }
  }

  "RateLimitActionFilter" should {
    "limit request rate" in {
      val fakeClock = new FakeClock
      val rl = new RateLimiter(2, 2, "test", fakeClock)
      val rejectResponse = (_: Request[_]) => TooManyRequests("test")

      val action: EssentialAction = (new RateLimitActionFilter[Request](rl)(rejectResponse)(_ => "key") with ActionBuilder[Request]) {
        Ok("ok")
      }
      val request = FakeRequest(GET, "/")

      var result = call(action, request)
      status(result) mustEqual OK
      result = call(action, request)
      status(result) mustEqual OK
      result = call(action, request)
      status(result) mustEqual TOO_MANY_REQUESTS
      fakeClock.ts = 501
      result = call(action, request)
      status(result) mustEqual OK
    }
  }

  "FailureRateLimitFunction" should {
    "limit failure rate" in {
      val fakeClock = new FakeClock
      val rl = new RateLimiter(2, 2, "test", fakeClock)
      val rejectResponse = (_: Request[_]) => BadRequest("test")
      val failFunc = (r: Result) => r.header.status == OK

      val action: EssentialAction = (new FailureRateLimitFunction[Request](rl)(rejectResponse)(_ => "key", failFunc) with ActionBuilder[Request]) { request =>
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
      fakeClock.ts = 501
      result = call(action, requestOk)
      status(result) mustEqual OK
    }
  }
}

