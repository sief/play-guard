package com.digitaltangible.playguard

import akka.actor.ActorRef
import akka.stream.Materializer
import com.digitaltangible.FakeClock
import com.digitaltangible.ratelimit.{Clock, TokenBucketGroup}
import com.typesafe.config.ConfigFactory
import org.scalatestplus.play._
import play.api
import play.api.ApplicationLoader.Context
import play.api._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.Results._
import play.api.mvc.{Action, AnyContentAsEmpty}
import play.api.routing.Router
import play.api.test.Helpers._
import play.api.test.{FakeHeaders, FakeRequest}

class GuardFilterSpec extends PlaySpec with OneAppPerSuite {

  override implicit lazy val app: api.Application = {
    val appLoader = new FakeAppLoader
    val context = ApplicationLoader.createContext(Environment.simple())
    appLoader.load(context)
  }

  class FakeAppLoader extends ApplicationLoader {
    override def load(context: Context): api.Application = new FakeApplicationComponents(context).application
  }

  class FakeApplicationComponents(context: Context) extends BuiltInComponentsFromContext(context) {
    override def router: Router = Router.empty

    override lazy val configuration: Configuration = {
      val testConfig = ConfigFactory.load("test.conf")
      Configuration(testConfig)
    }
  }

  implicit lazy val materializer: Materializer = app.materializer

  "GuardFilter" should {

    "not block unlisted IP" in {
      runFake("0.0.0.0") mustEqual OK
    }

    "not block blacklisted but whitelisted IP" in {
      runFake("2.2.2.2") mustEqual OK
    }

    "block blacklisted and not whitelisted IP" in {
      runFake("3.3.3.3") mustEqual FORBIDDEN
    }

    "block unlisted IP after limit exeeded" in {
      val fakeClock = new FakeClock
      val filter = testFilter(app, fakeClock)
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
      val filter = testFilter(app, fakeClock)
      runFake("2.2.2.2", filter) mustEqual OK
      runFake("2.2.2.2", filter) mustEqual OK
      runFake("2.2.2.2", filter) mustEqual OK
    }

    "block after global limit exeeded" in {
      val fakeClock = new FakeClock
      val filter = testFilter(app, fakeClock)
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
      val filter = testFilter(app, fakeClock)
      runFake("0.0.0.0", filter) mustEqual OK
      runFake("0.0.0.2", filter) mustEqual OK
      runFake("0.0.0.3", filter) mustEqual OK
      runFake("0.0.0.4", filter) mustEqual TOO_MANY_REQUESTS
      runFake("2.2.2.2", filter) mustEqual OK
    }
  }

  private def runFake(ip: String, filter: GuardFilter = GuardFilter(app.configuration, app.actorSystem)): Int = {
    val rh = FakeRequest("GET", "/", FakeHeaders(), AnyContentAsEmpty, ip)
    val action = Action(Ok("success"))
    val result = filter(action)(rh).run()
    status(result)
  }

  private def testFilter(app: Application, clock: Clock): GuardFilter = {
    new GuardFilter(
      app.configuration,
      app.actorSystem,
      new DefaultIpTokenBucketGroupProvider(app.configuration, app.actorSystem) {
        override lazy val tbActorRef: ActorRef = TokenBucketGroup.create(system, tokenBucketSize, tokenBucketRate, clock)
      },
      new DefaultGlobalTokenBucketGroupProvider(app.configuration, app.actorSystem) {
        override lazy val tbActorRef: ActorRef = TokenBucketGroup.create(system, tokenBucketSize, tokenBucketRate, clock)
      },
      new DefaultIpChecker(app.configuration))
  }
}

