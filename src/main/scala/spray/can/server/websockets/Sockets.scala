package spray.can.server.websockets

import spray.can.{Http, HttpManager, HttpExt}
import akka.actor._
import spray.can.client._
import spray.http._
import spray.io._
import scala.concurrent.duration.FiniteDuration
import java.security.MessageDigest
import spray.http.HttpHeaders.RawHeader
import spray.http.HttpResponse
import spray.http.HttpRequest
import spray.can.server.websockets.model.{OpCode, Frame}
import spray.io
import io.RawPipelineStage


import spray.can.client.websockets.SocketClientSettingsGroup
import spray.can.server.BackpressureSettings


/**
 * Sister class to the spray.can.Http class, providing a http server with
 * websocket capabilities
 */
object Sockets extends ExtensionKey[SocketExt]{
  object Filters{
    def hidePings(f: Frame) = f.opcode != OpCode.Ping
    def hidePingPongs(f: Frame) = f.opcode != OpCode.Ping && f.opcode != OpCode.Pong
  }

  type ServerPipelineStage = RawPipelineStage[SslTlsContext]
  type ClientPipelineStage = RawPipelineStage[PipelineContext]
  val EmptyPipelineStage = spray.io.EmptyPipelineStage
  class UpgradeServer(val resp: HttpResponse, val pipeline: ServerPipelineStage) extends Command
  class UpgradeClient(val req: HttpRequest, val pipeline: ClientPipelineStage) extends Command

  object UpgradeServer{
    def apply(resp: HttpResponse,
              frameHandler: ActorRef,
              frameSizeLimit: Int = Int.MaxValue,
              backpressureSettings: BackpressureSettings = BackpressureSettings(1, Int.MaxValue))
             (implicit extraStages: ServerPipelineStage = AutoPong(None)) = {
      new UpgradeServer(
        resp,
        WebsocketFrontEnd(frameHandler) >>
        extraStages >>
        Consolidation(frameSizeLimit, None) >>
        FrameParsing(frameSizeLimit) >>
        BackPressureHandling(backpressureSettings.noAckRate, backpressureSettings.readingLowWatermark)
      )
    }
  }
  object UpgradeClient{
    def apply(req: HttpRequest,
              frameHandler: ActorRef,
              frameSizeLimit: Int = Int.MaxValue,
              maskGen: () => Int = () => util.Random.nextInt(),
              backpressureSettings: BackpressureSettings = BackpressureSettings(1, Int.MaxValue))
             (implicit extraStages: ClientPipelineStage = AutoPong(Some(maskGen))) = {
      new UpgradeClient(
        req,
        WebsocketFrontEnd(frameHandler) >>
        extraStages >>
        Consolidation(frameSizeLimit, Some(maskGen)) >>
        FrameParsing(frameSizeLimit) >>
        BackPressureHandling(backpressureSettings.noAckRate, backpressureSettings.readingLowWatermark)
      )
    }
  }

  /**
   * Sent by Sockets whenever an incoming Pong matches an
   * outgoing Ping, providing the FrameHandler with the round-trip
   * latency of that ping-pong.
   */
  case class RoundTripTime(delta: FiniteDuration) extends Event

  /**
   * Sent by Sockets to the frameHandler when a websocket handshake
   * is complete and the connection upgraded
   */
  case object Upgraded extends Event

  def calculateReturnHash(headers: List[HttpHeader]) = {
    headers.collectFirst{
      case header if(header is "sec-websocket-key") =>
        (header.value + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")
    }.map(MessageDigest.getInstance("SHA-1").digest)
      .map(new sun.misc.BASE64Encoder().encode)
  }

  def socketAcceptHeaders(returnValue: String) = List(
    HttpHeaders.RawHeader("Upgrade", "websocket"),
    HttpHeaders.Connection("Upgrade"),
    HttpHeaders.RawHeader("Sec-WebSocket-Accept", returnValue)
  )

  def acceptAllFunction(x: HttpRequest) = {
    HttpResponse(
      StatusCodes.SwitchingProtocols,
      headers = socketAcceptHeaders(calculateReturnHash(x.headers).get)
    )
  }
}

/**
 * Syster class to HttpExt
 */
class SocketExt(system: ExtendedActorSystem) extends HttpExt(system){
  override val manager = system.actorOf(
    props = Props(new SocketManager(Settings)) withDispatcher Settings.ManagerDispatcher,
    name = "IO-SOCKET"
  )
}

/**
 * Sister class to HttpManagr; I basically copied and pasted the whole source
 * code of HttpManager because it keeps all its stuff private and not open for
 * extension.
 */
private class SocketManager(httpSettings: HttpExt#Settings) extends HttpManager(httpSettings){
  override def newHttpListener(commander: ActorRef, bind: Http.Bind, httpSettings: HttpExt#Settings) = {
    new SocketListener(commander, bind, httpSettings)
  }
  override def newHttpClientSettingsGroup(settings: ClientConnectionSettings, httpSettings: HttpExt#Settings) = {
    new SocketClientSettingsGroup(settings, httpSettings)
  }
}

