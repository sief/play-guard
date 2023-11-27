package com.digitaltangible.playguard

import akka.stream.Materializer
import com.digitaltangible.FakeClock
import com.digitaltangible.ratelimit.RateLimiter
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.mvc.Results._
import play.api.mvc._
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.{ExecutionContext, Future}

class RateLimitActionFilterSpec extends PlaySpec with GuiceOneAppPerSuite {

  implicit lazy val materializer: Materializer = app.materializer

  implicit lazy val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  lazy val bodyParsers: PlayBodyParsers = app.injector.instanceOf[PlayBodyParsers]

  lazy val actionBuilder: DefaultActionBuilder = DefaultActionBuilder(bodyParsers.anyContent)

  "RateLimitActionFilter" should {
    "limit request rate" in {
      val fakeClock = new FakeClock
      val rl        = new RateLimiter(2, 2, "test", fakeClock)
      val action: Action[AnyContent] = (actionBuilder andThen new RateLimitActionFilter[Request](rl) {
        override def keyFromRequest[A](implicit request: Request[A]): Any            = "key"
        override def rejectResponse[A](implicit request: Request[A]): Future[Result] = Future.successful(TooManyRequests("test"))
        override def bypass[A](implicit request: Request[A]): Boolean                = request.path === "/bp"
      }) { Ok("ok") }
      val request: FakeRequest[AnyContentAsEmpty.type]       = FakeRequest(GET, "/")
      val bypassRequest: FakeRequest[AnyContentAsEmpty.type] = FakeRequest(GET, "/bp")
      var result: Future[Result]                             = call(action, request)
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
      val fakeClock: FakeClock = new FakeClock
      val rl: RateLimiter      = new RateLimiter(2, 2, "test", fakeClock)
      val action: Action[AnyContent] = (actionBuilder andThen new IpRateLimitFilter[Request](rl) {
        override def rejectResponse[A](implicit request: Request[A]): Future[Result] = Future.successful(TooManyRequests("test"))
      }) { Ok("ok") }
      val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest(GET, "/")
      var result: Future[Result]                       = call(action, request)
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
      val rl: RateLimiter = new RateLimiter(2, 2, "test", new FakeClock)
      val action: Action[AnyContent] = (actionBuilder andThen new IpRateLimitFilter[Request](rl, Set("127.0.0.1")) {
        override def rejectResponse[A](implicit request: Request[A]): Future[Result] = Future.successful(TooManyRequests("test"))
      }) { Ok("ok") }
      val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest(GET, "/")
      var result: Future[Result]                       = call(action, request)
      status(result) mustEqual OK
      result = call(action, request)
      status(result) mustEqual OK
      result = call(action, request)
      status(result) mustEqual OK
    }
  }

  "KeyRateLimitFilter" should {
    "limit request rate" in {
      val fakeClock: FakeClock = new FakeClock
      val rl: RateLimiter      = new RateLimiter(2, 2, "test", fakeClock)
      val keyRateLimitFilter: KeyRateLimitFilter[Int, Request] = new KeyRateLimitFilter[Int, Request](rl) {
        override def rejectResponse4Key[A](key: Int): Request[A] => Future[Result] =
          _ => Future.successful(TooManyRequests(s"""rate limit for '$key' exceeded"""))
      }
      def action(key: Int): Action[AnyContent]         = (actionBuilder andThen keyRateLimitFilter(key)) { Ok("ok") }
      val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest(GET, "/")
      var result: Future[Result]                       = call(action(1), request)
      status(result) mustEqual OK
      result = call(action(1), request)
      status(result) mustEqual OK
      result = call(action(2), request)
      status(result) mustEqual OK
      result = call(action(1), request)
      status(result) mustEqual TOO_MANY_REQUESTS
      fakeClock.ts = 501000000
      result = call(action(1), request)
      status(result) mustEqual OK
    }
  }

  "FailureRateLimitFunction" should {
    "limit failure rate" in {
      val fakeClock: FakeClock = new FakeClock
      val rl: RateLimiter      = new RateLimiter(2, 2, "test", fakeClock)
      val action: Action[AnyContent] =
        (actionBuilder andThen new FailureRateLimitFunction[Request](rl, _.header.status == OK) {
          override def keyFromRequest[A](implicit request: Request[A]): Any = "key"

          override def rejectResponse[A](implicit request: Request[A]): Future[Result] = Future.successful(TooManyRequests)

          override def bypass[A](implicit request: Request[A]): Boolean = request.path === "/bp"
        }) { request =>
          if (request.path == "/") Ok
          else Unauthorized
        }
      val requestOk: FakeRequest[AnyContentAsEmpty.type]         = FakeRequest(GET, "/")
      val requestFail: FakeRequest[AnyContentAsEmpty.type]       = FakeRequest(GET, "/x")
      val bypassRequestFail: FakeRequest[AnyContentAsEmpty.type] = FakeRequest(GET, "/bp")

      var result: Future[Result] = call(action, requestOk)
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

      val fakeClock: FakeClock = new FakeClock
      val rl: RateLimiter      = new RateLimiter(1, 2, "test", fakeClock)
      val action: Action[AnyContent] =
        (actionBuilder andThen new HttpErrorRateLimitFunction[Request](rl) {
          override def rejectResponse[A](implicit request: Request[A]): Future[Result] = Future.successful(TooManyRequests)
        }) { (request: RequestHeader) =>
          if (request.path == "/") Ok
          else Unauthorized
        }
      val requestOk: FakeRequest[AnyContentAsEmpty.type]   = FakeRequest(GET, "/")
      val requestFail: FakeRequest[AnyContentAsEmpty.type] = FakeRequest(GET, "/x")
      var result: Future[Result]                           = call(action, requestOk)
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
      val rl: RateLimiter = new RateLimiter(1, 2, "test", new FakeClock)
      val action: Action[AnyContent] =
        (actionBuilder andThen new HttpErrorRateLimitFunction[Request](rl, Set(UNAUTHORIZED), Set("127.0.0.1")) {
          override def rejectResponse[A](implicit request: Request[A]): Future[Result] = Future.successful(TooManyRequests)
        }) { Unauthorized }
      val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest(GET, "/")
      var result: Future[Result]                       = call(action, request)
      status(result) mustEqual UNAUTHORIZED
      result = call(action, request)
      status(result) mustEqual UNAUTHORIZED
    }
  }
}
