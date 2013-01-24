package spray.can.server
package websockets

import spray.io._
import spray.io.IOBridge.Connection
import akka.actor._

import spray.http._
import java.security.MessageDigest

import spray.io.SingletonHandler
import spray.http.HttpHeaders.RawHeader
import spray.http.HttpResponse
import spray.can.HttpCommand
import spray.can.server.StatsSupport.StatsHolder
import spray.can.server.ServerSettings

class SocketServer(handlerMaker: => ActorRef, settings: ServerSettings = ServerSettings())
                  (implicit sslEngineProvider: ServerSSLEngineProvider)
                  extends IOServer with ConnectionActors {

  def createConnectionActor(connection: Connection): ActorRef = {
    context.actorOf(Props(new SocketConnectionActor(connection, handlerMaker)), nextConnectionActorName)
  }
}

object SocketConnectionActor{
  def calculateReturnHash(headers: List[HttpHeader]) = {
    headers.collectFirst{
      case RawHeader("sec-websocket-key", value) => (value + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")
    }.map(MessageDigest.getInstance("SHA-1").digest)
     .map(new sun.misc.BASE64Encoder().encode)
  }

  def socketAcceptHeaders(returnValue: String) = List(
    HttpHeaders.RawHeader("Upgrade", "websocket"),
    HttpHeaders.RawHeader("Connection", "Upgrade"),
    HttpHeaders.RawHeader("Sec-WebSocket-Accept", returnValue)
  )

  def acceptAllFunction(x: HttpRequest) = {
    val response = HttpResponse(
      StatusCodes.SwitchingProtocols,
      headers = socketAcceptHeaders(calculateReturnHash(x.headers).get)
    )
    (response, true)
  }
}

object SocketServer{
  val Frame = model.Frame

  def defaultTimeoutResponse(request: HttpRequest): HttpResponse = HttpResponse(
    status = 500,
    entity = "Ooops! The server was not able to produce a timely response to your request.\n" +
      "Please try again in a short while!"
  )
}

class SocketConnectionActor(val connection: Connection,
                            handlerMaker: => ActorRef,
                            acceptFunction: HttpRequest => (HttpResponse, Boolean) = SocketConnectionActor.acceptAllFunction,
                            settings: ServerSettings = ServerSettings())
                           (implicit sslEngineProvider: ServerSSLEngineProvider) extends IOConnection {

  val acceptHandler = SingletonHandler(context.actorOf(Props(new Actor{
    def receive = {
      case req @ HttpRequest(method, uri, headers, entity, protocol) =>
        val (response, newReady) = acceptFunction(req)
        sender ! response
    }
  })))

  var handler: Option[ActorRef] = None

  val statsHolder: Option[StatsHolder] = if (settings.StatsSupport) Some(new StatsHolder) else None

  var pipelineStage = HttpServer.pipelineStage(settings, acceptHandler, SocketServer.defaultTimeoutResponse, statsHolder)

  var pipelines = createPipelines(connection)

  override def receive: Receive = { case x =>
    super.receive(x)
    x match {
      case Response(_, Response(_, HttpCommand(HttpResponse(StatusCodes.SwitchingProtocols, _, _, _)))) =>
        pipelineStage =
          Consolidation() >>
          FrameParsing() >>
          SslTlsSupport(sslEngineProvider) ? settings.SSLEncryption

        pipelines = createPipelines(connection)
      case _ => ()
    }
  }
}
