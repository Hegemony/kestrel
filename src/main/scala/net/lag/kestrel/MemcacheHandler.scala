/*
 * Copyright 2009 Twitter, Inc.
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lag.kestrel

import java.net.InetSocketAddress
import scala.collection.mutable
import com.twitter.concurrent.ChannelSource
import com.twitter.conversions.time._
import com.twitter.finagle.{ClientConnection, Service}
import com.twitter.logging.Logger
import com.twitter.naggati.{Codec, LatchedChannelSource, ProtocolError}
import com.twitter.naggati.codec.{MemcacheRequest, MemcacheResponse}
import com.twitter.ostrich.stats.Stats
import com.twitter.util.{Future, Duration, Time}

/**
 * Memcache protocol handler for a kestrel connection.
 */
class MemcacheHandler(
  connection: ClientConnection,
  queueCollection: QueueCollection,
  maxOpenReads: Int
) extends Service[MemcacheRequest, MemcacheResponse] {
  val log = Logger.get(getClass.getName)

  val sessionId = Kestrel.sessionId.incrementAndGet()
  protected val handler = new KestrelHandler(queueCollection, maxOpenReads, clientDescription, sessionId)
  log.debug("New session %d from %s", sessionId, clientDescription)

  override def release() {
    handler.finish()
    super.release()
  }

  protected def clientDescription: String = {
    val address = connection.remoteAddress.asInstanceOf[InetSocketAddress]
    "%s:%d".format(address.getAddress.getHostAddress, address.getPort)
  }

  protected def disconnect() = {
    Future(new MemcacheResponse("") then Codec.Disconnect)
  }

  final def apply(request: MemcacheRequest): Future[MemcacheResponse] = {
    request.line(0) match {
      case "get" =>
        get(request.line(1))
      case "monitor" =>
        val maxItems = if (request.line.size > 3) request.line(3).toInt else maxOpenReads
        Future(monitor(request.line(1), request.line(2).toInt, maxItems))
      case "confirm" =>
        if (handler.closeReads(request.line(1), request.line(2).toInt)) {
          Future(new MemcacheResponse("END"))
        } else {
          Future(new MemcacheResponse("ERROR"))
        }
      case "set" =>
        val now = Time.now
        val expiry = request.line(3).toInt
        val normalizedExpiry: Option[Time] = if (expiry == 0) {
          None
        } else if (expiry < 1000000) {
          Some(now + expiry.seconds)
        } else {
          Some(Time.epoch + expiry.seconds)
        }
        try {
          if (handler.setItem(request.line(1), request.line(2).toInt, normalizedExpiry, request.data.get)) {
            Future(new MemcacheResponse("STORED"))
          } else {
            Future(new MemcacheResponse("NOT_STORED"))
          }
        } catch {
          case e: NumberFormatException =>
            Future(new MemcacheResponse("CLIENT_ERROR"))
        }
      case "shutdown" =>
        handler.shutdown()
        disconnect()
      case "reload" =>
        Kestrel.kestrel.reload()
        Future(new MemcacheResponse("Reloaded config."))
      case "flush" =>
        handler.flush(request.line(1))
        Future(new MemcacheResponse("END"))
      case "flush_all" =>
        handler.flushAllQueues()
        Future(new MemcacheResponse("Flushed all queues."))
      case "delete" =>
        handler.delete(request.line(1))
        Future(new MemcacheResponse("END"))
      case "flush_expired" =>
        Future(new MemcacheResponse(handler.flushExpired(request.line(1)).toString))
      case "flush_all_expired" =>
        val flushed = queueCollection.flushAllExpired()
        Future(new MemcacheResponse(flushed.toString))
      case "version" =>
        Future(version())
      case "quit" =>
        disconnect()
      case x =>
        Future(new MemcacheResponse("CLIENT_ERROR") then Codec.Disconnect)
    }
  }

  private def get(name: String): Future[MemcacheResponse] = {
    var key = name
    var timeout: Option[Time] = None
    var closing = false
    var opening = false
    var aborting = false
    var peeking = false

    if (name contains '/') {
      val options = name.split("/")
      key = options(0)
      for (i <- 1 until options.length) {
        val opt = options(i)
        if (opt startsWith "t=") {
          timeout = Some(opt.substring(2).toInt.milliseconds.fromNow)
        }
        if (opt == "close") closing = true
        if (opt == "open") opening = true
        if (opt == "abort") aborting = true
        if (opt == "peek") peeking = true
      }
    }

    if ((key.length == 0) || ((peeking || aborting) && (opening || closing)) || (peeking && aborting)) {
      return Future(new MemcacheResponse("CLIENT_ERROR") then Codec.Disconnect)
    }

    if (aborting) {
      handler.abortRead(key)
      Future(new MemcacheResponse("END"))
    } else {
      if (closing) {
        handler.closeRead(key)
      }
      if (opening || !closing) {
        if (handler.pendingReads.size(key) > 0 && !peeking && !opening) {
          log.warning("Attempt to perform a non-transactional fetch with an open transaction on " +
                      " '%s' (sid %d, %s)", key, sessionId, clientDescription)
          return Future(new MemcacheResponse("ERROR") then Codec.Disconnect)
        }
        try {
          handler.getItem(key, timeout, opening, peeking).map { itemOption =>
            itemOption match {
              case None =>
                new MemcacheResponse("END")
              case Some(item) =>
                new MemcacheResponse("VALUE %s 0 %d".format(key, item.data.length), Some(item.data))
            }
          }
        } catch {
          case e: TooManyOpenReadsException =>
            Future(new MemcacheResponse("ERROR") then Codec.Disconnect)
        }
      } else {
        Future(new MemcacheResponse("END"))
      }
    }
  }

  private def monitor(key: String, timeout: Int, maxItems: Int): MemcacheResponse = {
    val channel = new LatchedChannelSource[MemcacheResponse]
    handler.monitorUntil(key, Some(Time.now + timeout.seconds), maxItems, true) {
      case None =>
        channel.send(new MemcacheResponse("END"))
        channel.close()
      case Some(item) =>
        channel.send(new MemcacheResponse("VALUE %s 0 %d".format(key, item.data.length), Some(item.data)))
    }
    new MemcacheResponse("") then Codec.Stream(channel)
  }

  private def version() = {
    new MemcacheResponse("VERSION " + Kestrel.runtime.jarVersion)
  }
}
