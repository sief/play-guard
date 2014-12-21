import com.sief.play.guard.GuardFilter
import play.api.mvc._

object Global extends WithFilters(GuardFilter()) {
}


