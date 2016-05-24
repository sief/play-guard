package com.sief.play.guard

import java.io.File

import akka.stream.Materializer
import com.typesafe.config.ConfigFactory
import org.scalatestplus.play._
import play.api
import play.api.ApplicationLoader.Context
import play.api._
import play.api.mvc.Action
import play.api.mvc.Results._
import play.api.routing.Router
import play.api.test.FakeRequest
import play.api.test.Helpers._

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

    "todo" in {
      val filter = new GuardFilter(app.configuration, app.actorSystem)
      val rh = FakeRequest()
      val action = Action(Ok("success"))
      val result = filter(action)(rh).run()
      status(result) mustEqual OK
    }
  }
}

