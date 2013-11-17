package spray.can.server
package websockets

import spray.can.client._
import spray.io._
import spray.can.server._
import spray.can.server.StatsSupport.StatsHolder
import spray.can.{Http, HttpExt}
import Sockets.Upgraded
import akka.io.Tcp
import spray.can.{Http, HttpExt}
import akka.actor.{Props, ActorRef}
import spray.can.parsing.{SSLSessionInfoSupport, Result, HttpResponsePartParser, ParserSettings}
import spray.http._
import akka.util.CompactByteString
import spray.can.Http.MessageCommand
import spray.can.server.StatsSupport.StatsHolder

/**
 * Sister class to HttpListener, but with a pipeline that supports websockets
 */
private class SocketListener(bindCommander: ActorRef,
                     bind: Http.Bind,
                     httpSettings: HttpExt#Settings) extends HttpListener(bindCommander, bind, httpSettings){
  import bind._
  private val connectionCounterX = Iterator from 0
  private[this] val settingsX = bind.settings getOrElse ServerSettings(context.system)
  private[this] val statsHolderX = if (settingsX.statsSupport) Some(new StatsHolder) else None
  val pipelineStage = SocketListener.pipelineStage(settingsX, statsHolderX)
  override def connected(tcpListener: ActorRef): Receive = {
    case Tcp.Connected(remoteAddress, localAddress) ⇒
      val conn = sender
      context.actorOf(
        props = Props(new HttpServerConnection(conn, bind.listener, pipelineStage, remoteAddress, localAddress, settingsX))
          .withDispatcher(httpSettings.ConnectionDispatcher),
        name = connectionCounterX.next().toString)

    case Http.GetStats            ⇒ statsHolderX foreach { holder ⇒ sender ! holder.toStats }
    case Http.ClearStats          ⇒ statsHolderX foreach { _.clear() }

    case Http.Unbind(timeout)     ⇒ unbind(tcpListener, Set(sender), timeout)

    case _: Http.ConnectionClosed ⇒
    // ignore, we receive this event when the user didn't register the handler within the registration timeout period
  }
}

private object SocketListener{

  def pipelineStage(settings: ServerSettings, statsHolder: Option[StatsHolder]) = {
    import settings._
    import timeouts._
    Switching(
      ServerFrontend(settings) >>
        RequestChunkAggregation(requestChunkAggregationLimit) ? (requestChunkAggregationLimit > 0) >>
        PipeliningLimiter(pipeliningLimit) ? (pipeliningLimit > 0) >>
        StatsSupport(statsHolder.get) ? statsSupport >>
        RemoteAddressHeaderSupport ? remoteAddressHeader >>
        SSLSessionInfoSupport ? parserSettings.sslSessionInfoHeader >>
        RequestParsing(settings) >>
        ResponseRendering(settings) >>
        ConnectionTimeouts(idleTimeout) ? (reapingCycle.isFinite && idleTimeout.isFinite) >>
        PreventHalfClosedConnections(sslEncryption)
    ){case x: Sockets.UpgradeServer =>
      (x.pipeline, x.resp)
    } >>
      SslTlsSupport(maxEncryptionChunkSize, parserSettings.sslSessionInfoHeader) ? sslEncryption >>
      TickGenerator(reapingCycle) ? (reapingCycle.isFinite && (idleTimeout.isFinite || requestTimeout.isFinite)) >>
      BackPressureHandling(backpressureSettings.get.noAckRate, backpressureSettings.get.readingLowWatermark) ? autoBackPressureEnabled
  }
}
case class Switching[T <: PipelineContext](stage1: RawPipelineStage[T])
                                          (stage2: PartialFunction[Tcp.Command, (RawPipelineStage[T], HttpMessage)])
  extends RawPipelineStage[T] {

  def apply(context: T, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {
      val pl1 = stage1(context, commandPL, eventPL)

      var eventPLVar = pl1.eventPipeline
      var commandPLVar = pl1.commandPipeline

      // it is important to introduce the proxy to the var here
      def commandPipeline: CPL = {
        case x if stage2.isDefinedAt(x) =>
          val (pl20, msg) = stage2(x)
          val pl2 = pl20(context, commandPL, eventPL)
          pl1.commandPipeline(MessageCommand(msg))
          eventPLVar = pl2.eventPipeline
          commandPLVar = pl2.commandPipeline
          eventPLVar(Upgraded)
        case c =>
          commandPLVar(c)
      }
      def eventPipeline: EPL = {
        e => eventPLVar(e)
      }
    }
}
