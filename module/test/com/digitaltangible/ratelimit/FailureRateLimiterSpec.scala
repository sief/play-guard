package com.digitaltangible.ratelimit

import com.digitaltangible.FakeClock
import com.digitaltangible.tokenbucket.Clock
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.test.Helpers.{await, defaultAwaitTimeout}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FailureRateLimiterSpec extends AnyWordSpec with Matchers {

  sealed trait ErrorResult
  case object DummyError extends ErrorResult

  sealed trait Response
  case object ErrorResponse extends ErrorResult

  "FailureRateLimiter" should {
    "limit failure rate" in {
      val fakeClock = new FakeClock
      val rl        = new RateLimiter(2, 2, "test", fakeClock)

      val testLimiter: FailureRateLimiter[Unit, ErrorResult] = new FailureRateLimiter[Unit, ErrorResult](
        rl,
        keyFromContext = _ => "test",
        resultCheck = {
          case Left(DummyError) => false
          case _                => true
        },
        rejectResponse = _ => Future.successful(ErrorResponse),
        logPrefix = _ => "test"
      )

      var res: Either[ErrorResult, Unit] = await(testLimiter(()) { _ => Future.successful(Right(())) })
      res.toOption.get mustEqual ()
      res = await(testLimiter(()) { _ => Future.successful(Right(())) })
      res.toOption.get mustEqual ()
      res = await(testLimiter(()) { _ => Future.successful(Right(())) })
      res.toOption.get mustEqual ()
      res = await(testLimiter(()) { _ => Future.successful(Left(DummyError)) })
      res.swap.toOption.get mustEqual DummyError
      res = await(testLimiter(()) { _ => Future.successful(Right(())) })
      res.toOption.get mustEqual ()
      res = await(testLimiter(()) { _ => Future.successful(Left(DummyError)) })
      res.swap.toOption.get mustEqual DummyError
      res = await(testLimiter(()) { _ => Future.successful(Left(DummyError)) })
      res.swap.toOption.get mustEqual ErrorResponse
      res = await(testLimiter(()) { _ => Future.successful(Left(DummyError)) })
      res.swap.toOption.get mustEqual ErrorResponse
      res = await(testLimiter(()) { _ => Future.successful(Right(())) })
      res.swap.toOption.get mustEqual ErrorResponse
      fakeClock.ts = 501000000
      res = await(testLimiter(()) { _ => Future.successful(Right(())) })
      res.toOption.get mustEqual ()
    }
  }
}
