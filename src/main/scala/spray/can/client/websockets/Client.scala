package spray.can.client
package websockets

import spray.can.client.{RequestRendering, HttpClientSettingsGroup, ClientConnectionSettings}
import spray.can.{Http, HttpExt}
import spray.io._
import spray.can.server.websockets.{Sockets, Switching, OneShotResponseParsing}
import akka.actor.{Terminated, ActorRef, Props}
import akka.io.Tcp
import spray.can.parsing.SSLSessionInfoSupport


private[can] class SocketClientSettingsGroup(settings: ClientConnectionSettings,
                                             httpSettings: HttpExt#Settings)
                                             extends HttpClientSettingsGroup(settings, httpSettings){
  override val pipelineStage = SocketClientConnection.pipelineStage(settings)
  override def receive = {
    case connect: Http.Connect ⇒
      val commander = sender
      context.actorOf(
        props =
          Props(new HttpClientConnection(commander, connect, pipelineStage, settings))
            .withDispatcher(httpSettings.ConnectionDispatcher),
        name = connectionCounter.next().toString)

    case Http.CloseAll(cmd) ⇒
      val children = context.children.toSet
      if (children.isEmpty) {
        sender ! Http.ClosedAll
        context.stop(self)
      } else {
        children foreach { _ ! cmd }
        context.become(closing(children, Set(sender)))
      }

  }
}

object SocketClientConnection{
  def pipelineStage(settings: ClientConnectionSettings): RawPipelineStage[SslTlsContext] = {

    import settings._
    Switching(
      ClientFrontend(requestTimeout) >>
        ResponseChunkAggregation(responseChunkAggregationLimit) ? (responseChunkAggregationLimit > 0) >>
        SSLSessionInfoSupport ? parserSettings.sslSessionInfoHeader >>
        ResponseParsing(parserSettings) >>
        RequestRendering(settings) >>
        ConnectionTimeouts(idleTimeout) ? (reapingCycle.isFinite && idleTimeout.isFinite)
    ){case x: Sockets.UpgradeClient =>
      (x.pipeline >> OneShotResponseParsing(parserSettings), x.req)
    } >>
      SslTlsSupport(maxEncryptionChunkSize, parserSettings.sslSessionInfoHeader) >>
      TickGenerator(reapingCycle) ? (idleTimeout.isFinite || requestTimeout.isFinite)
  }
}

