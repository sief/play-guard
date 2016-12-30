import com.digitaltangible.playguard.{ActionRateLimiter, ConfigIpChecker, GuardFilter}
import controllers.{Assets, SampleController}
import play.api.ApplicationLoader.Context
import play.api._
import play.api.mvc.EssentialFilter
import router.Routes


class SampleApplicationLoader extends ApplicationLoader {
  def load(context: Context) = {
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

  lazy val ipChecker = new ConfigIpChecker(configuration)
  lazy val guardFilter = new GuardFilter(configuration, actorSystem, ipChecker)

  override lazy val httpFilters: Seq[EssentialFilter] = Seq(guardFilter)

}