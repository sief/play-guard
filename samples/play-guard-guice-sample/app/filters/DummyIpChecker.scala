package filters

import com.digitaltangible.playguard.IpChecker

class DummyIpChecker extends IpChecker{
  override def isWhitelisted(ip: String): Boolean = false

  override def isBlacklisted(ip: String): Boolean = "127.0.0.1" == ip
}
