package spray.can.server.websockets

import org.scalatest.FreeSpec
import org.scalatest.concurrent.Eventually
import akka.io.{Tcp, IO}
import spray.can.Http
import akka.actor.{Props, ActorSystem, Actor}
import spray.can.server.websockets.model.{OpCode, Frame}
import spray.can.server.websockets.model.OpCode.{Binary, Text}
import akka.util.ByteString
import spray.http.{HttpResponse, HttpHeaders, HttpMethods, HttpRequest}
import akka.io.Tcp.Register
import scala.concurrent.duration._
import HttpHeaders._
import spray.can.server.ServerSettings
import akka.testkit.TestActorRef
import java.net.InetSocketAddress
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake

class AutoBahn extends FreeSpec with Eventually{

  "Server" in {
    implicit val system = ActorSystem()
    implicit val ec = system.dispatcher
    implicit val patienceConfig = PatienceConfig(timeout = 2 seconds)
    // Hard-code the websocket request
    val upgradeReq = HttpRequest(HttpMethods.GET,  "/mychat", List(
      Host("server.example.com", 80),
      Connection("Upgrade"),
      RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw==")
    ))

    class SocketServer extends Actor{
      def receive = {

        case x: Tcp.Connected =>
          println("Connected")
          sender ! Register(self) // normal Http server init

        case req: HttpRequest =>
          println("Request")
          // Upgrade the connection to websockets if you think the incoming
          // request looks good
          if (true){
            sender ! Sockets.acceptAllFunction(req) // helper to craft http response
            sender ! Sockets.UpgradeServer(self) // upgrade the pipeline
          }

        case Sockets.Upgraded =>
          println("Upgraded!")
          // do nothing

        case f @ Frame(fin, rsv, Text | Binary, maskingKey, data) =>
          println("Got Frame " + f.opcode)
          // Reply to frames with the text content capitalized

          sender ! f.copy(maskingKey = None)
          system.scheduler.schedule(0 seconds, 1 second){
            sender ! f.copy(maskingKey = None, data = ByteString("i am cow"))
          }

        case x =>
          println("Unknown " + x)
      }
    }

    implicit val sslContext = Util.createSslContext("/ssl-test-keystore.jks", "")
    val server = system.actorOf(Props(new SocketServer))

    IO(Sockets) ! Http.Bind(
      server,
      "192.168.1.4",
      9001/*,
      settings=Some(ServerSettings(system).copy(sslEncryption = true))*/
    )

    Thread.sleep(100000000000000000L)
  }
  /*"Client" in {
    import org.java_websocket.server.WebSocketServer
    val refServer = new WebSocketServer(new InetSocketAddress("localhost", 31337)){
      def onError(conn: WebSocket, ex: Exception) = println("Error " + ex)

      def onMessage(conn: WebSocket, message: String) = {
        println(message)
      }

      def onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) = println("Closed: " + reason)

      def onOpen(conn: WebSocket, handshake: ClientHandshake) = println("Opened")
    }
    refServer.start()

    implicit val system = ActorSystem()
    implicit val patienceConfig = PatienceConfig(timeout = 2 seconds)
    // Hard-code the websocket request
    val upgradeReq = HttpRequest(HttpMethods.GET,  "/mychat", List(
      Host("server.example.com", 80),
      Connection("Upgrade"),
      RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw==")
    ))

    class SocketClient extends Actor{
      var result: Frame = null

      def receive = {
        case x: Tcp.Connected =>
          println("Client Connected")
          sender ! Register(self) // normal Http client init
          sender ! upgradeReq // send an upgrade request immediately when connected

        case resp: HttpResponse =>
          println("Client Response")
          // when the response comes back, upgrade the connnection pipeline
          sender ! Sockets.UpgradeClient(self)

        case Sockets.Upgraded =>
          println("Client Upgraded")
          // send a websocket frame when the upgrade is complete
          sender ! Frame(
            opcode = OpCode.Text,
            maskingKey = Some(12345),
            data = ByteString("i am cow")
          )

        case f: Frame =>
          println("Client Frame")
          result = f // save the result
      }
    }

    implicit val client = system.actorOf(Props(new SocketClient))
    IO(Sockets) ! Http.Connect(
      "localhost",
      31337
    )


    Thread.sleep(100000000000000000L)
  }*/
}
