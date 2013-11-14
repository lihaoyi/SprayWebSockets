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
import akka.actor.ActorRef
import spray.can.parsing.{Result, HttpResponsePartParser, ParserSettings}
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
  private[this] val settingsX = bind.settings getOrElse ServerSettings(context.system)
  private[this] val statsHolderX = if (settingsX.statsSupport) Some(new StatsHolder) else None
  val pipelineStage = SocketListener.pipelineStage(settingsX, statsHolderX)
}

private object SocketListener{

  def pipelineStage(settings: ServerSettings, statsHolder: Option[StatsHolder]) = {
    import settings._
    Switching(
      ServerFrontend(settings) >>
        RequestChunkAggregation(requestChunkAggregationLimit) ? (requestChunkAggregationLimit > 0) >>
        PipeliningLimiter(pipeliningLimit) ? (pipeliningLimit > 0) >>
        StatsSupport(statsHolder.get) ? statsSupport >>
        RemoteAddressHeaderSupport ? remoteAddressHeader >>
        RequestParsing(settings) >>
        ResponseRendering(settings) >>
        ConnectionTimeouts(timeouts.idleTimeout) ? (reapingCycle.isFinite && timeouts.idleTimeout.isFinite)
    ){case x: Sockets.UpgradeServer =>
      (x.pipeline, x.resp)
    } >>
      SslTlsSupport(maxEncryptionChunkSize, parserSettings.sslSessionInfoHeader) ? sslEncryption >>
      TickGenerator(reapingCycle) ? (reapingCycle.isFinite && (timeouts.idleTimeout.isFinite || timeouts.requestTimeout.isFinite))
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
        case c => commandPLVar(c)
      }
      def eventPipeline: EPL = {
        e => eventPLVar(e)
      }
    }
}
