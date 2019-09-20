package com.digitaltangible.playguard

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

import com.digitaltangible.FakeClock
import org.scalatest.MustMatchers
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.mvc.Results._
import play.api.mvc._
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.{ExecutionContext, Future}

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

    "serialize MyRateLimiter" in {
      val limiter = new RateLimiter(1, 2)

      val baos = new ByteArrayOutputStream()
      val oos = new ObjectOutputStream(baos)
      oos.writeObject(limiter)
      oos.close()

      val ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))
      val limiter2 = ois.readObject.asInstanceOf[RateLimiter]
      ois.close()

      limiter2.consume("") mustBe 0 //not really interested in the result just that it doesn't throw
    }
  }

  "RateLimitActionFilter" should {
    "limit request rate" in {
      val fakeClock = new FakeClock
      val rl = new RateLimiter(2, 2, "test", fakeClock)
      val rejectResponse = (_: Request[_]) => Future.successful(TooManyRequests("test"))
      val action = (actionBuilder andThen new RateLimitActionFilter[Request](rl)(rejectResponse, _ => "key", _.path == "/bp")) { Ok("ok") }
      val request = FakeRequest(GET, "/")
      val bypassRequest = FakeRequest(GET, "/bp")
      var result = call(action, request)
      status(result) mustEqual OK
      result = call(action, request)
      status(result) mustEqual OK
      result = call(action, request)
      status(result) mustEqual TOO_MANY_REQUESTS
      result = call(action, bypassRequest)
      status(result) mustEqual OK
      fakeClock.ts = 501000000
      result = call(action, request)
      status(result) mustEqual OK
    }
  }

  "IpRateLimitFilter" should {
    "limit request rate" in {
      val fakeClock = new FakeClock
      val rl = new RateLimiter(2, 2, "test", fakeClock)
      val rejectResponse = (_: Request[_]) => Future.successful(TooManyRequests("test"))
      val action = (actionBuilder andThen IpRateLimitFilter[Request](rl)(rejectResponse)) { Ok("ok") }
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

    "not limit request rate for whitelist" in {
      val rl = new RateLimiter(2, 2, "test", new FakeClock)
      val rejectResponse = (_: Request[_]) => Future.successful(TooManyRequests("test"))
      val action = (actionBuilder andThen IpRateLimitFilter[Request](rl)(rejectResponse, Set("127.0.0.1"))) { Ok("ok") }
      val request = FakeRequest(GET, "/")
      var result = call(action, request)
      status(result) mustEqual OK
      result = call(action, request)
      status(result) mustEqual OK
      result = call(action, request)
      status(result) mustEqual OK
    }
  }

  "FailureRateLimitFunction" should {
    "limit failure rate" in {
      val fakeClock = new FakeClock
      val rl = new RateLimiter(2, 2, "test", fakeClock)
      val action =
        (actionBuilder andThen new FailureRateLimitFunction[Request](rl)(
          _ => Future.successful(TooManyRequests),
          _ => "key",
          _.header.status == OK,
          _.path == "/bp"
        )) { request =>
          if (request.path == "/") Ok
          else Unauthorized
        }
      val requestOk = FakeRequest(GET, "/")
      val requestFail = FakeRequest(GET, "/x")
      val bypassRequestFail = FakeRequest(GET, "/bp")

      var result = call(action, requestOk)
      status(result) mustEqual OK
      result = call(action, requestOk)
      status(result) mustEqual OK
      result = call(action, requestOk)
      status(result) mustEqual OK
      result = call(action, requestFail)
      status(result) mustEqual UNAUTHORIZED
      result = call(action, requestOk)
      status(result) mustEqual OK
      result = call(action, requestFail)
      status(result) mustEqual UNAUTHORIZED
      result = call(action, requestFail)
      status(result) mustEqual TOO_MANY_REQUESTS
      result = call(action, requestOk)
      status(result) mustEqual TOO_MANY_REQUESTS
      result = call(action, bypassRequestFail)
      status(result) mustEqual UNAUTHORIZED
      fakeClock.ts = 501000000
      result = call(action, requestOk)
      status(result) mustEqual OK
    }
  }

  "HttpErrorRateLimitFunction" should {
    "limit failure rate" in {

      val fakeClock = new FakeClock
      val rl = new RateLimiter(1, 2, "test", fakeClock)
      val action =
        (actionBuilder andThen HttpErrorRateLimitFunction[Request](rl)(_ => Future.successful(TooManyRequests), Set(UNAUTHORIZED))) { request: RequestHeader =>
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
      status(result) mustEqual TOO_MANY_REQUESTS
      result = call(action, requestFail)
      status(result) mustEqual TOO_MANY_REQUESTS
      fakeClock.ts = 501000000
      result = call(action, requestOk)
      status(result) mustEqual OK
      result = call(action, requestFail)
      status(result) mustEqual UNAUTHORIZED
    }

    "not limit failure rate for whitelist" in {
      val rl = new RateLimiter(1, 2, "test", new FakeClock)
      val action =
        (actionBuilder andThen HttpErrorRateLimitFunction[Request](rl)(_ => Future.successful(TooManyRequests), Set(UNAUTHORIZED), Set("127.0.0.1"))) { Unauthorized }
      val request = FakeRequest(GET, "/")
      var result = call(action, request)
      status(result) mustEqual UNAUTHORIZED
      result = call(action, request)
      status(result) mustEqual UNAUTHORIZED
    }
  }
}
