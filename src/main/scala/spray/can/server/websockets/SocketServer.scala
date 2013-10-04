package spray.can.server
package websockets

import spray.io._

import spray.http._
import java.security.MessageDigest

import concurrent.duration._
import spray.http.HttpHeaders.RawHeader
import spray.http.HttpResponse

/**
 * Convenience building blocks to deal with the websocket upgrade
 * request (doing the calculate-hash-dance, headers, blah blah)
 */
object SocketServer{


  def calculateReturnHash(headers: List[HttpHeader]) = {
    headers.collectFirst{
      case RawHeader("Sec-WebSocket-Key", value) => (value + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")
    }.map(MessageDigest.getInstance("SHA-1").digest)
      .map(new sun.misc.BASE64Encoder().encode)
  }

  def socketAcceptHeaders(returnValue: String) = List(
    HttpHeaders.RawHeader("Upgrade", "websocket"),
    HttpHeaders.RawHeader("Connection", "Upgrade"),
    HttpHeaders.RawHeader("Sec-WebSocket-Accept", returnValue)
  )

  def acceptAllFunction(x: HttpRequest) = {
    HttpResponse(
      StatusCodes.SwitchingProtocols,
      headers = socketAcceptHeaders(calculateReturnHash(x.headers).get)
    )
  }

  // The messages which are unique to a SocketServer

  /**
   * Sent by the SocketServer whenever an incoming Pong matches an
   * outgoing Ping, providing the FrameHandler with the round-trip
   * latency of that ping-pong.
   */
  case class RoundTripTime(delta: FiniteDuration) extends Event

  /**
   * Tells the SocketServer to take this HTTP connection and swap it
   * out into a websocket connection.
   *
   * @param data a piece of data that can be given to the SocketServer
   *             when telling it to upgrade. This will be used by the
   *             SocketServer's frameHandler to create/find an actor
   *             that will handle the subsequent websocket frames
   */
  case class Upgrade(data: Any) extends Command

  /**
   * Sent by the SocketServer to the frameHandler when a websocket handshake
   * is complete and the connection upgraded
   */
  case object Connected extends Event

  /**
   * The SocketServer exchanges websocket Frames with the frameHandler
   */
  val Frame = model.Frame; type Frame = model.Frame

}

