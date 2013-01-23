package websockets

import model.Frame
import spray.io._
import spray.can.server._
import spray.io.IOBridge.Connection
import akka.actor.{Actor, Props, ActorRef}

import spray.http._
import java.security.MessageDigest

import java.nio.ByteBuffer

import spray.io.SingletonHandler
import spray.http.HttpHeaders.RawHeader
import spray.io.PerConnectionHandler
import scala.Some
import spray.http.HttpResponse

class SocketServer(settings: ServerSettings = ServerSettings())
                  (implicit sslEngineProvider: ServerSSLEngineProvider)
extends IOServer with ConnectionActors {

  def createConnectionActor(connection: Connection): ActorRef = {
    context.actorOf(Props(new SocketConnectionActor(connection)), nextConnectionActorName)
  }
}

class SocketConnectionActor(val connection: Connection, settings: ServerSettings = ServerSettings())
                           (implicit sslEngineProvider: ServerSSLEngineProvider) extends IOConnection {

  var ready = false
  val acceptHandler = SingletonHandler(context.actorOf(Props(new Actor{
    def receive = {
      case HttpRequest(method, uri, headers, entity, protocol) =>
        println("Request coming in!")
        val byteHash = MessageDigest.getInstance("SHA-1")
          .digest(
          headers.collectFirst{ case RawHeader("sec-websocket-key", value) =>
            (value + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")
          }.get
        )

        val returnValue = new sun.misc.BASE64Encoder().encode(byteHash)

        val response = HttpResponse(
          StatusCodes.SwitchingProtocols,
          headers = List(
            HttpHeaders.RawHeader("Upgrade", "websocket"),
            HttpHeaders.RawHeader("Connection", "Upgrade"),
            HttpHeaders.RawHeader("Sec-WebSocket-Accept", returnValue)
          )
        )
        HttpResponsePart
        sender ! response
        ready = true
    }
  })))

  var frags: List[Frame] = Nil

  val socketStage = new PipelineStage {
    def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
      new Pipelines {
        val commandPipeline = commandPL
        val eventPipeline: EPL = {
          case IOBridge.Received(connection, buffer) if ready =>
            import model.OpCode._
            val frame = model.Frame.make(buffer.array)
            println(frame)
            frame match{
              case f @ Frame(_, _, ConnectionCloseFrame, _, _) =>
                val newF = f.copy(maskingKey = None)
                commandPL(IOConnection.Send(ByteBuffer.wrap(newF.write)))
              case Frame(_, _, PingFrame, _, _) => ()
              case Frame(_, _, PongFrame, _, _) => ()
              case f @ Frame(false, _, opcode, _, _)
                if opcode == TextFrame || opcode == BinaryFrame => // start frag
                frags = f :: frags
              case f @ Frame(false, _, ContinuationFrame, _, _) => // continue frag
                frags = f :: frags
              case f @ Frame(true, _, opcode, _, _)
                if opcode == TextFrame || opcode == BinaryFrame =>
                val newF = f.copy(maskingKey = None, data = f.stringData.toUpperCase.getBytes("UTF-8"))
                commandPL(IOConnection.Send(ByteBuffer.wrap(newF.write)))
            }

          case x => eventPL(x)
        }
      }
  }

  def timeoutResponse(request: HttpRequest): HttpResponse = HttpResponse(
    status = 500,
    entity = "Ooops! The server was not able to produce a timely response to your request.\n" +
      "Please try again in a short while!"
  )
  import settings._
  val pipelineStage = ServerFrontend(settings, acceptHandler, timeoutResponse) >>
    RequestChunkAggregation(RequestChunkAggregationLimit.toInt) ? (RequestChunkAggregationLimit > 0) >>
    PipeliningLimiter(settings.PipeliningLimit) ? (PipeliningLimit > 0) >>
    RemoteAddressHeaderSupport() ? RemoteAddressHeader >>
    RequestParsing(ParserSettings, VerboseErrorMessages) >>
    ResponseRendering(settings) >>
    ConnectionTimeouts(IdleTimeout) ? (ReapingCycle > 0 && IdleTimeout > 0) >>
    socketStage >>
    SslTlsSupport(sslEngineProvider) ? SSLEncryption >>
    TickGenerator(ReapingCycle) ? (ReapingCycle > 0 && (IdleTimeout > 0 || RequestTimeout > 0))

  val pipelines = createPipelines(connection)
}
