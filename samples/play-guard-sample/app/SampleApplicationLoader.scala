import com.digitaltangible.playguard._
import controllers.{Assets, SampleController}
import play.api.ApplicationLoader.Context
import play.api._
import play.api.mvc.EssentialFilter
import router.Routes


class SampleApplicationLoader extends ApplicationLoader {
  def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }
    new ApplicationComponents(context).application
  }
}

class ApplicationComponents(context: Context) extends BuiltInComponentsFromContext(context){

  lazy val controller = new SampleController(rlActionBuilder)
  lazy val assets = new Assets(httpErrorHandler)
  lazy val router = new Routes(httpErrorHandler, controller, assets)

  lazy val rlActionBuilder = new ActionRateLimiter(configuration, actorSystem)

  lazy val guardFilter = GuardFilter(configuration, actorSystem)

  override lazy val httpFilters: Seq[EssentialFilter] = Seq(guardFilter)
}