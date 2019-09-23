package controllers

import akka.actor.ActorSystem
import com.digitaltangible.playguard._
import play.api.Configuration
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

/**
 * Sample app for rate limit actions
 */
class SampleController(components: ControllerComponents)(implicit system: ActorSystem, ec: ExecutionContext, conf: Configuration) extends AbstractController(components) {

  def index: Action[AnyContent] = Action {
    Ok(views.html.index("Your new application is ready."))
  }

  // allow 3 requests immediately and get a new token every 5 seconds
  private val ipRateLimitFilter = IpRateLimitFilter[Request](
    new RateLimiter(3, 1f / 5, "test limit by IP address"), { r: RequestHeader =>
      Future.successful(TooManyRequests(s"""rate limit for ${r.remoteAddress} exceeded"""))
    }
  )

  def limitedByIp: Action[AnyContent] = (Action andThen ipRateLimitFilter) {
    Ok("limited by IP")
  }

  // allow 4 requests immediately and get a new token every 15 seconds
  private val keyRateLimitFilter: String => RateLimitActionFilter[Request] =
    KeyRateLimitFilter[String, Request](
      new RateLimiter(4, 1f / 15, "test by token"),
      key => _ => Future.successful(TooManyRequests(s"""rate limit for '$key' exceeded"""))
    )

  def limitedByKey(key: String): Action[AnyContent] =
    (Action andThen keyRateLimitFilter(key)) {
      Ok("limited by token")
    }

  // allow 2 failures immediately and get a new token every 10 seconds
  private val httpErrorRateLimitFunction =
    HttpErrorRateLimitFunction[Request](new RateLimiter(2, 1f / 10, "test failure rate limit"), _ => Future.successful(BadRequest("failure rate exceeded")))

  def failureLimitedByIp(fail: Boolean): Action[AnyContent] =
    (Action andThen httpErrorRateLimitFunction) {
      if (fail) BadRequest("failed")
      else Ok("Ok")
    }

  // combine keyRateLimitFilter and httpErrorRateLimited
  def limitByKeyAndHttpErrorByIp(key: String, fail: Boolean): Action[AnyContent] =
    (Action andThen keyRateLimitFilter(key) andThen httpErrorRateLimitFunction) {
      if (fail) BadRequest("failed")
      else Ok("Ok")
    }
}
