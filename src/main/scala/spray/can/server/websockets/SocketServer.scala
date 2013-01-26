package spray.can.server
package websockets

import model.Frame
import spray.io._
import spray.io.IOBridge.Connection
import akka.actor._

import spray.http._
import java.security.MessageDigest

import spray.can.server.StatsSupport.StatsHolder
import spray.can.server.ServerSettings
import spray.io.SingletonHandler
import spray.can.server.Response
import spray.http.HttpHeaders.RawHeader
import scala.Some
import spray.http.HttpResponse
import spray.can.HttpCommand

class SocketServer(handlerMaker: => ActorRef,
                   accepter: Either[MessageHandler, HttpRequest => HttpResponse],
                   settings: ServerSettings = ServerSettings())
                  (implicit sslEngineProvider: ServerSSLEngineProvider)
                   extends IOServer with ConnectionActors {
  HttpServer
  val acceptHandler = accepter.fold(x => x, func => SingletonHandler(context.actorOf(Props(new Actor{
    def receive = {
      case req @ HttpRequest(method, uri, headers, entity, protocol) =>
        val response = func(req)
        sender ! response
    }
  }))))

  def createConnectionActor(connection: Connection): ActorRef = {
    context.actorOf(Props(new SocketConnectionActor(connection, handlerMaker, acceptHandler)), nextConnectionActorName)
  }
}

object SocketServer{

  def apply(handlerMaker: => ActorRef,
            acceptFunction: HttpRequest => HttpResponse = SocketConnectionActor.acceptAllFunction,
            settings: ServerSettings = ServerSettings())
           (implicit sslEngineProvider: ServerSSLEngineProvider): SocketServer = {
    new SocketServer(handlerMaker, Right(acceptFunction), settings)
  }
  def fromHandler(handlerMaker: => ActorRef,
            acceptHandler: MessageHandler,
            settings: ServerSettings = ServerSettings())
           (implicit sslEngineProvider: ServerSSLEngineProvider): SocketServer = {
    new SocketServer(handlerMaker, Left(acceptHandler), settings)
  }

  def defaultTimeoutResponse(request: HttpRequest): HttpResponse = HttpResponse(
    status = 500,
    entity = "Ooops! The server was not able to produce a timely response to your request.\n" +
      "Please try again in a short while!"
  )
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
    HttpResponse(
      StatusCodes.SwitchingProtocols,
      headers = socketAcceptHeaders(calculateReturnHash(x.headers).get)
    )
  }
}

class SocketConnectionActor(val connection: Connection,
                            handlerMaker: => ActorRef,
                            acceptHandler: MessageHandler,
                            settings: ServerSettings = ServerSettings())
                           (implicit sslEngineProvider: ServerSSLEngineProvider) extends IOConnection {

  val statsHolder = if (settings.StatsSupport) Some(new StatsHolder) else None

  var pipelineStage = HttpServer.pipelineStage(settings, acceptHandler, SocketServer.defaultTimeoutResponse, statsHolder)

  var pipelines = createPipelines(connection)

  override def receive: Receive = {
    case f: Frame =>
      pipelines.commandPipeline(FrameCommand(f))

    case msg @ Response(_, Response(_, HttpCommand(HttpResponse(StatusCodes.SwitchingProtocols, _, _, _)))) =>
      super.receive(msg)
      // if the MessageHandler returns a 101: SwitchingProtocols response, it means the
      // upgrade is accepted and we can swap out the HTTP pipeline for the WS pipeline
      pipelineStage =
        WebsocketFrontEnd(handlerMaker) >>
        Consolidation() >>
        FrameParsing() >>
        SslTlsSupport(sslEngineProvider) ? settings.SSLEncryption

      pipelines = createPipelines(connection)
    case msg => super.receive(msg)

  }
}