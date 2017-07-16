package controllers

import akka.actor.ActorSystem
import com.digitaltangible.playguard._
import play.api.Configuration
import play.api.mvc._

import scala.concurrent.ExecutionContext

/**
  * Sample app for rate limit actions
  */
class SampleController(components: ControllerComponents)(implicit system: ActorSystem, ec: ExecutionContext, conf: Configuration) extends AbstractController(components) {

  def index = Action {
    Ok(views.html.index("Your new application is ready."))
  }

  // allow 3 requests immediately and get a new token every 5 seconds
  private val ipRateLimitFilter = IpRateLimitFilter(new RateLimiter(3, 1f / 5, "test limit by IP address")) {
    implicit r: RequestHeader => TooManyRequests( s"""rate limit for ${r.remoteAddress} exceeded""")
  }

  def limitedByIp = (Action andThen ipRateLimitFilter) {
    Ok("limited by IP")
  }

  // allow 4 requests immediately and get a new token every 15 seconds
  private val keyRateLimitFilter = KeyRateLimitFilter(new RateLimiter(4, 1f / 15, "test by token")) _

  def limitedByKey(key: String) = (Action andThen keyRateLimitFilter(_ => TooManyRequests( s"""rate limit for '$key' exceeded"""), key)) {
    Ok("limited by token")
  }


  // allow 2 failures immediately and get a new token every 10 seconds
  private val httpErrorRateLimitFunction = HttpErrorRateLimitFunction(new RateLimiter(2, 1f / 10, "test failure rate limit")) {
    implicit r: RequestHeader => BadRequest("failure rate exceeded")
  }

  def failureLimitedByIp(fail: Boolean) = (Action andThen httpErrorRateLimitFunction) {
    if (fail) BadRequest("failed")
    else Ok("Ok")
  }

  // combine tokenRateLimited and httpErrorRateLimited
  def limitByKeyAndHttpErrorByIp(key: String, fail: Boolean) =
    (Action andThen keyRateLimitFilter(_ => TooManyRequests( s"""rate limit for '$key' exceeded"""), key) andThen httpErrorRateLimitFunction) {

      if (fail) BadRequest("failed")
      else Ok("Ok")
    }
}
