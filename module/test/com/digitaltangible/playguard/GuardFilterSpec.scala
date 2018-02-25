package com.digitaltangible.playguard

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.digitaltangible.FakeClock
import com.digitaltangible.tokenbucket.{Clock, CurrentTimeClock, TokenBucketGroup}
import com.typesafe.config.ConfigFactory
import org.scalatestplus.play._
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api._
import play.api.mvc.Results._
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test.{FakeHeaders, FakeRequest}

import scala.concurrent.ExecutionContext

class GuardFilterSpec extends PlaySpec with GuiceOneAppPerSuite {

  implicit lazy val materializer: Materializer = app.materializer

  implicit lazy val actorSystem: ActorSystem = app.actorSystem

  implicit lazy val executionContext: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  "GuardFilter" should {

    "not block unlisted IP" in {
      val filter = testFilter(CurrentTimeClock)
      runFake("0.0.0.0", filter) mustEqual OK
    }

    "not block blacklisted but whitelisted IP" in {
      val filter = testFilter(CurrentTimeClock)
      runFake("2.2.2.2", filter) mustEqual OK
    }

    "block blacklisted and not whitelisted IP" in {
      val filter = testFilter(CurrentTimeClock)
      runFake("3.3.3.3", filter) mustEqual FORBIDDEN
    }

    "block unlisted IP after limit exeeded" in {
      val fakeClock = new FakeClock
      val filter = testFilter(fakeClock)
      runFake("0.0.0.0", filter) mustEqual OK
      runFake("0.0.0.0", filter) mustEqual OK
      runFake("0.0.0.0", filter) mustEqual TOO_MANY_REQUESTS
      runFake("0.0.0.1", filter) mustEqual OK
      runFake("0.0.0.0", filter) mustEqual TOO_MANY_REQUESTS
      fakeClock.ts = 501
      runFake("0.0.0.0", filter) mustEqual OK
    }

    "not limit whitelisted IPs" in {
      val fakeClock = new FakeClock
      val filter = testFilter(fakeClock)
      runFake("2.2.2.2", filter) mustEqual OK
      runFake("2.2.2.2", filter) mustEqual OK
      runFake("2.2.2.2", filter) mustEqual OK
    }

    "block after global limit exeeded" in {
      val fakeClock = new FakeClock
      val filter = testFilter(fakeClock)
      runFake("0.0.0.0", filter) mustEqual OK
      runFake("0.0.0.2", filter) mustEqual OK
      runFake("0.0.0.3", filter) mustEqual OK
      runFake("0.0.0.4", filter) mustEqual TOO_MANY_REQUESTS
      runFake("0.0.0.5", filter) mustEqual TOO_MANY_REQUESTS
      fakeClock.ts = 401
      runFake("0.0.0.0", filter) mustEqual OK
      runFake("0.0.0.4", filter) mustEqual OK
    }

    "bypass global limit for whitelisted IPs" in {
      val fakeClock = new FakeClock
      val filter = testFilter(fakeClock)
      runFake("0.0.0.0", filter) mustEqual OK
      runFake("0.0.0.2", filter) mustEqual OK
      runFake("0.0.0.3", filter) mustEqual OK
      runFake("0.0.0.4", filter) mustEqual TOO_MANY_REQUESTS
      runFake("2.2.2.2", filter) mustEqual OK
    }

    "ignore spoofed X-Forwarded-For header by default" in {
      val fakeClock = new FakeClock
      val filter = testFilter(fakeClock)
      runFake("0.0.0.0", filter, FakeHeaders(List("X-Forwarded-For" -> "3.3.3.3"))) mustEqual OK
      runFake("3.3.3.3", filter, FakeHeaders(List("X-Forwarded-For" -> "0.0.0.0"))) mustEqual FORBIDDEN
    }
  }

  private def runFake(ip: String, filter: GuardFilter, headers: FakeHeaders = FakeHeaders()): Int = {
    val bodyParsers = app.injector.instanceOf[PlayBodyParsers]
    val actionBuilder: DefaultActionBuilder = DefaultActionBuilder(bodyParsers.anyContent)
    val rh = FakeRequest("GET", "/", headers, AnyContentAsEmpty, ip)
    val action = actionBuilder(Ok("success"))
    val result = filter(action)(rh).run()
    status(result)
  }

  private def testFilter(clock: Clock): GuardFilter = {

    implicit val testConfig = Configuration(ConfigFactory.load("test.conf"))

    new GuardFilter(
      new DefaultIpTokenBucketGroupProvider(testConfig) {
        override lazy val tokenBucketGroup = new TokenBucketGroup(tokenBucketSize, tokenBucketRate, clock)
      },
      new DefaultGlobalTokenBucketGroupProvider(testConfig) {
        override lazy val tokenBucketGroup = new TokenBucketGroup(tokenBucketSize, tokenBucketRate, clock)
      },
      new DefaultIpChecker(testConfig)
    )
  }
}
