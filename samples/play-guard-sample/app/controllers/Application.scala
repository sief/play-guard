package controllers

import com.sief.play.guard.RateLimitAction
import play.api.mvc._

/**
 * Test dummy app for RateLimitAction
 */
object Application extends Controller {

  def index = Action {
    Ok(views.html.index("Your new application is ready."))
  }


  // allow 3 requests immediately and get a new token every 5 seconds
  private val rateLimiter = RateLimitAction(3, 1f / 5, { implicit r: RequestHeader => BadRequest("rate exceeded")}, "test rate limit")

  def limited = rateLimiter {
    Ok("limited")
  }

  // allow 3 failures immediately and get a new token every 10 seconds
  private val failRateLimiter = RateLimitAction.failLimit(3, 1f / 10, { implicit r: RequestHeader => BadRequest("fail rate exceeded")}, "test fail rate limit")

  def fail(fail: Boolean) = failRateLimiter {
    if (fail) BadRequest("failed")
    else Ok("Ok")
  }
}