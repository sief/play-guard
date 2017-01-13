package controllers

import javax.inject._

import akka.actor.ActorSystem
import com.digitaltangible.playguard.{FailureRateLimitAction, IpRateLimitAction, KeyRateLimitAction, RateLimiter}
import play.api.Configuration
import play.api.mvc._

/**
  * This controller creates an `Action` to handle HTTP requests to the
  * application's home page.
  */
@Singleton
class SampleController @Inject()(implicit system: ActorSystem, conf: Configuration) extends Controller {

  def index = Action {
    Ok(views.html.index("Your new application is ready."))
  }


  // allow 3 requests immediately and get a new token every 5 seconds
  private val ipRateLimitedAction = IpRateLimitAction(new RateLimiter(3, 1f / 5, "test by IP")) {
    implicit r: RequestHeader => TooManyRequests( s"""rate limit for ${r.remoteAddress} exceeded""")
  }

  def limitedByIp: Action[AnyContent] = ipRateLimitedAction {
    Ok("limited by IP")
  }


  // allow 4 requests immediately and get a new token every 15 seconds
  private val tokenRateLimitedAction = KeyRateLimitAction(new RateLimiter(4, 1f / 15, "test by token")) _

  def limitedByKey(key: String): Action[AnyContent] = tokenRateLimitedAction(_ => TooManyRequests( s"""rate limit for '$key' exceeded"""))(key) {
    Ok("limited by token")
  }


  // allow 2 failures immediately and get a new token every 10 seconds
  private val failRateLimitedAction = FailureRateLimitAction(2, 1f / 10, {
    implicit r: RequestHeader => BadRequest("failure rate exceeded")
  }, "test failure rate limit")

  def failureLimitedByIp(fail: Boolean): Action[AnyContent] = failRateLimitedAction {
    if (fail) BadRequest("failed")
    else Ok("Ok")
  }

  // combine tokenRateLimited and failRateLimited
  def limitByKeyAndFailureLimitedByIp(key: String, fail: Boolean): Action[AnyContent] =
    (tokenRateLimitedAction(_ => TooManyRequests( s"""rate limit for '$key' exceeded"""))(key) andThen failRateLimitedAction) {

      if (fail) BadRequest("failed")
      else Ok("Ok")
    }
}
