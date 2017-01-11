import com.digitaltangible.playguard.IpChecker
import com.google.inject.AbstractModule
import filters.DummyIpChecker


/**
  * This class is a Guice module that tells Guice how to bind several
  * different types. This Guice module is created when the Play
  * application starts.
  *
  * Play will automatically use any class called `Module` that is in
  * the root package. You can create modules in other locations by
  * adding `play.modules.enabled` settings to the `application.conf`
  * configuration file.
  */
class Module extends AbstractModule {

  override def configure() = {

    // uncomment to bind the DummyIpChecker, for this you have to disable the module com.digitaltangible.playguard.PlayGuardIpCheckerModule in application.conf
//     bind(classOf[IpChecker]).toInstance(new DummyIpChecker)
  }
}
