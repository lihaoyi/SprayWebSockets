package spray.can.server.websockets

import spray.can.client._
import spray.io._
import spray.can.server._
import spray.can.server.StatsSupport.StatsHolder
import spray.can.server.websockets.Sockets.Upgraded
import akka.io.Tcp
import spray.can.{Http, HttpExt}
import akka.actor.ActorRef
import spray.can.parsing.{Result, HttpResponsePartParser, ParserSettings}
import spray.http._
import akka.util.CompactByteString


/**
 * Sister class to HttpListener, but with a pipeline that supports websockets
 */
class SocketListener(bindCommander: ActorRef,
                     bind: Http.Bind,
                     httpSettings: HttpExt#Settings) extends HttpListener(bindCommander, bind, httpSettings){

  override val pipelineStage = SocketListener.pipelineStage(settings, statsHolder)
}

object SocketListener{

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
        ConnectionTimeouts(idleTimeout) ? (reapingCycle.isFinite && idleTimeout.isFinite)
    ){case x: Sockets.UpgradeServer => x.pipeline} >>
      SslTlsSupport ? sslEncryption >>
      TickGenerator(reapingCycle) ? (reapingCycle.isFinite && (idleTimeout.isFinite || requestTimeout.isFinite))
  }
}
case class Switching[T <: PipelineContext](stage1: RawPipelineStage[T])
                                          (stage2: PartialFunction[Tcp.Command, RawPipelineStage[T]])
  extends RawPipelineStage[T] {

  def apply(context: T, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {
      val pl1 = stage1(context, commandPL, eventPL)

      var eventPLVar = pl1.eventPipeline
      var commandPLVar = pl1.commandPipeline

      // it is important to introduce the proxy to the var here
      def commandPipeline: CPL = {
        case x if stage2.isDefinedAt(x) =>
          val pl2 = stage2(x)(context, commandPL, eventPL)
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

private[can] class SocketClientSettingsGroup(settings: ClientConnectionSettings,
                                             httpSettings: HttpExt#Settings) extends HttpClientSettingsGroup(settings, httpSettings){

  override val pipelineStage = SocketClientConnection.pipelineStage(settings)
}

object SocketClientConnection{
  def pipelineStage(settings: ClientConnectionSettings): RawPipelineStage[SslTlsContext] = {
    import settings._
    Switching(
      ClientFrontend(requestTimeout) >>
        ResponseChunkAggregation(responseChunkAggregationLimit) ? (responseChunkAggregationLimit > 0) >>
        ResponseParsing(parserSettings) >>
        RequestRendering(settings) >>
        ConnectionTimeouts(idleTimeout) ? (reapingCycle.isFinite && idleTimeout.isFinite)
    ){case x: Sockets.UpgradeClient => x.pipeline >> OneShotResponseParsing(parserSettings)} >>
      SslTlsSupport ? sslEncryption >>
      TickGenerator(reapingCycle) ? (idleTimeout.isFinite || requestTimeout.isFinite)
  }
}

object OneShotResponseParsing {
  def apply(settings: ParserSettings): PipelineStage = {
    new PipelineStage {
      val parser = new HttpResponsePartParser(settings)()
      parser.startResponse(HttpMethods.GET)
      var active = true
      def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
        new Pipelines {
          import context.log

          def parse(data: CompactByteString): Unit =
            parser.parse(data) match {
              case Result.NeedMoreData => // just wait for the next packet
              case Result.ParsingError(_, info) =>
                commandPL(Http.Close)
              case Result.Ok(part, remainingData, _) ⇒
                eventPL(Http.MessageEvent(part))
                active = false
                eventPL(Tcp.Received(remainingData))
              case x =>
            }

          val commandPipeline: CPL = commandPL

          val eventPipeline: EPL = {
            case Tcp.Received(data: CompactByteString) if active => parse(data)
            case ev ⇒ eventPL(ev)
          }
        }
    }
  }
}