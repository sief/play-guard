package controllers

import com.digitaltangible.playguard._
import com.digitaltangible.ratelimit.RateLimiter
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

/**
 * Sample app for rate limit actions
 */
class SampleController(components: ControllerComponents)(implicit ec: ExecutionContext)
    extends AbstractController(components) {

  def index: Action[AnyContent] = Action {
    Ok(views.html.index("Your new application is ready."))
  }

  // allow 3 requests immediately and get a new token every 5 seconds
  private val ipRateLimitFilter: IpRateLimitFilter[Request] = new IpRateLimitFilter[Request](new RateLimiter(3, 1f / 5, "test limit by IP address")) {
    override def rejectResponse[A](implicit request: Request[A]): Future[Result] =
      Future.successful(TooManyRequests(s"""rate limit for ${request.remoteAddress} exceeded"""))
  }

  def limitedByIp: Action[AnyContent] = (Action andThen ipRateLimitFilter) {
    Ok("limited by IP")
  }

  // allow 4 requests immediately and get a new token every 15 seconds
  private val keyRateLimitFilter: KeyRateLimitFilter[String, Request] =
    new KeyRateLimitFilter[String, Request](new RateLimiter(4, 1f / 15, "test by token")) {
      override def rejectResponse4Key[A](key: String): Request[A] => Future[Result] =
        _ => Future.successful(TooManyRequests(s"""rate limit for '$key' exceeded"""))
    }

  def limitedByKey(key: String): Action[AnyContent] =
    (Action andThen keyRateLimitFilter(key)) {
      Ok("limited by token")
    }

  // allow 2 failures immediately and get a new token every 10 seconds
  private val httpErrorRateLimitFunction: HttpErrorRateLimitFunction[Request] =
    new HttpErrorRateLimitFunction[Request](new RateLimiter(2, 1f / 10, "test failure rate limit")) {
      override def rejectResponse[A](implicit request: Request[A]): Future[Result] = Future.successful(BadRequest("failure rate exceeded"))
    }

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
