package lila.memo

import com.github.blemale.scaffeine.{ Cache, Scaffeine }

import ornicar.scalalib.Zero
import scala.concurrent.duration.Duration

/**
 * side effect throttler that allows X ops per Y unit of time
 */
final class RateLimit[K](
    credits: Int,
    duration: Duration,
    name: String,
    key: String,
    enabled: Boolean
) {
  import RateLimit._

  private val storage = Scaffeine()
    .expireAfterWrite(duration)
    .build[K, (Cost, ClearAt)]()

  private def makeClearAt = nowMillis + duration.toMillis

  private val logger = lila.log("ratelimit")
  private val monitor = lila.mon.security.rateLimit.generic(key)

  logger.info(s"[start] $name ($credits/$duration)")

  def chargeable[A](k: K, cost: Cost = 1, msg: => String = "")(op: Charge => A)(implicit default: Zero[A]): A =
    apply(k, cost, msg) { op(c => apply(k, c, s"charge: $msg")(())) }

  def apply[A](k: K, cost: Cost = 1, msg: => String = "")(op: => A)(implicit default: Zero[A]): A =
    storage getIfPresent k match {
      case None =>
        storage.put(k, cost -> makeClearAt)
        op
      case Some((a, clearAt)) if a <= credits =>
        storage.put(k, (a + cost) -> clearAt)
        op
      case Some((_, clearAt)) if nowMillis > clearAt =>
        storage.put(k, cost -> makeClearAt)
        op
      case _ if enabled =>
        logger.info(s"$name ($credits/$duration) $k cost: $cost $msg")
        monitor()
        default.zero
      case _ =>
        op
    }
}

object RateLimit {

  final class Builder(enabled: Boolean) {
    def apply(
      credits: Int,
      duration: Duration,
      name: String,
      key: String
    ) = new RateLimit(
      credits = credits,
      duration = duration,
      name = name,
      key = key,
      enabled = enabled
    )
  }

  type Charge = Cost => Unit
  type Cost = Int

  private type ClearAt = Long
}
