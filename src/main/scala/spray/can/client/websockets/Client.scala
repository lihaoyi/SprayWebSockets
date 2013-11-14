package spray.can.client
package websockets

import spray.can.client.{RequestRendering, HttpClientSettingsGroup, ClientConnectionSettings}
import spray.can.{HttpExt}
import spray.io._
import spray.can.server.websockets.{Sockets, Switching, OneShotResponseParsing}


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
    ){case x: Sockets.UpgradeClient =>
      println("UPGRADING CLIENT")
      (x.pipeline >> OneShotResponseParsing(parserSettings), x.req)
    } >>
      SslTlsSupport(maxEncryptionChunkSize, parserSettings.sslSessionInfoHeader)
      SslTlsSupport(maxEncryptionChunkSize, parserSettings.sslSessionInfoHeader) >>
      TickGenerator(reapingCycle) ? (idleTimeout.isFinite || requestTimeout.isFinite)
  }
}

