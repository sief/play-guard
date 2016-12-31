package com.digitaltangible.playguard

import akka.stream.Materializer
import com.typesafe.config.ConfigFactory
import org.scalatestplus.play._
import play.api
import play.api.ApplicationLoader.Context
import play.api._
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
      val filter = GuardFilter(app.configuration, app.actorSystem)
      val rh = FakeRequest("GET", "/", FakeHeaders(), AnyContentAsEmpty, "0.0.0.0")
      val action = Action(Ok("success"))
      val result = filter(action)(rh).run()
      status(result) mustEqual OK
    }


    "block blacklisted IPs" in {
      val filter = GuardFilter(app.configuration, app.actorSystem)
      val rh = FakeRequest("GET", "/", FakeHeaders(), AnyContentAsEmpty, "3.3.3.3")
      val action = Action(Ok("success"))
      val result = filter(action)(rh).run()
      status(result) mustEqual FORBIDDEN
    }
  }
}

