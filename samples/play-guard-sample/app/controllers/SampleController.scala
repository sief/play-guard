package controllers

import com.sief.play.guard.ActionRateLimiter
import play.api.mvc._

/**
 * Test dummy app for rate limit actions
 */
class SampleController(rlActionBuilder: ActionRateLimiter) extends Controller {

  def index = Action {
    Ok(views.html.index("Your new application is ready."))
  }


  // allow 3 requests immediately and get a new token every 5 seconds
  private val ipRateLimited = rlActionBuilder.ipRateLimiterAction(3, 1f / 5, "test limit by IP address") {
    implicit r: RequestHeader => BadRequest( s"""rate limit for ${r.remoteAddress} exceeded""")
  }

  def limitedByIp = ipRateLimited {
    Ok("limited by IP")
  }


  // allow 4 requests immediately and get a new token every 15 seconds
  private val tokenRateLimited = rlActionBuilder.keyRateLimiterAction(4, 1f / 15, "test by token") _

  def limitedByKey(key: String) = tokenRateLimited(_ => BadRequest( s"""rate limit for '$key' exceeded"""))(key) {
    Ok("limited by token")
  }


  // allow 2 failures immediately and get a new token every 10 seconds
  private val failRateLimited = rlActionBuilder.failureRateLimiterAction(2, 1f / 10, {
    implicit r: RequestHeader => BadRequest("failure rate exceeded")
  }, "test failure rate limit")

  def failureLimitedByIp(fail: Boolean) = failRateLimited {
    if (fail) BadRequest("failed")
    else Ok("Ok")
  }

  // combine tokenRateLimited and failRateLimited
  def limitByKeyAndFailureLimitedByIp(key: String, fail: Boolean) =
    (tokenRateLimited(_ => BadRequest( s"""rate limit for '$key' exceeded"""))(key) andThen failRateLimited){

    if (fail) BadRequest("failed")
    else Ok("Ok")
  }
}
