import javax.inject._

import com.digitaltangible.playguard.GuardFilter
import filters.ExampleFilter
import play.api._
import play.api.http.DefaultHttpFilters

/**
  * This class configures filters that run on every request. This
  * class is queried by Play to get a list of filters.
  *
  * Play will automatically use filters from any class called
  * `Filters` that is placed the root package. You can load filters
  * from a different class by adding a `play.http.filters` setting to
  * the `application.conf` configuration file.
  *
  * @param env           Basic environment settings for the current application.
  * @param exampleFilter A demonstration filter that adds a header to
  *                      each response.
  */
@Singleton
class Filters @Inject()(
                         env: Environment,
                         exampleFilter: ExampleFilter,
                         guardFilter: GuardFilter) extends DefaultHttpFilters(exampleFilter, guardFilter)