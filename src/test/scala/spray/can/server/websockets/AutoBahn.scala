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

    class SocketServer extends Actor{
      def receive = {

        case x: Tcp.Connected =>
          sender ! Register(self) // normal Http server init

        case req: HttpRequest =>
          // Upgrade the connection to websockets if you think the incoming
          // request looks good
          sender ! Sockets.acceptAllFunction(req) // helper to craft http response
          sender ! Sockets.UpgradeServer(self) // upgrade the pipeline


        case Sockets.Upgraded =>
          // do nothing

        case f @ Frame(fin, rsv, Text | Binary, maskingKey, data) =>
          sender ! f.copy(maskingKey = None)

        case x =>
      }
    }

    implicit val sslContext = Util.createSslContext("/ssl-test-keystore.jks", "")
    val server = system.actorOf(Props(new SocketServer))

    IO(Sockets) ! Http.Bind(
      server,
      "192.168.1.4",
      9001,
      settings=Some(ServerSettings(system).copy(sslEncryption = true))
    )

    Thread.sleep(100000000000000000L)
  }
/*  "Client" in {
    implicit val system = ActorSystem()
    implicit val patienceConfig = PatienceConfig(timeout = 2 seconds)
    // Hard-code the websocket request
    val upgradeReq = HttpRequest(HttpMethods.GET,  "/", List(
      Host("192.168.37.128", 9001),
      RawHeader("Upgrade", "websocket"),
      Connection("Upgrade"),
      RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw=="),
      RawHeader("Sec-WebSocket-Protocol", "chat"),
      RawHeader("Sec-WebSocket-Version", "13"),
      RawHeader("Origin", "lihaoyi.com")
    ))


    class SocketClient extends Actor{
      var result: Frame = null
      var count = 0
      def receive = {
        case x: Tcp.Connected =>
          println("Client Connected")
          sender ! Register(self) // normal Http client init
          sender ! upgradeReq.copy(uri="/runCase?case=" + count)// send an upgrade request immediately when connected
          count += 1
        case resp: HttpResponse =>
          println("Client Response")
          println(resp)
          // when the response comes back, upgrade the connnection pipeline
          sender ! Sockets.UpgradeClient(self)

        case Sockets.Upgraded =>
          println("Client Upgraded")
          sender ! Frame(opcode=OpCode.Ping, data = ByteString("hello"), maskingKey = Some(123))
          // send a websocket frame when the upgrade is complete


        case f: Frame =>

          result = f // save the result
      }
    }

    implicit val client = system.actorOf(Props(new SocketClient))
    IO(Sockets) ! Http.Connect(
      "192.168.37.128",
      9001
    )


    Thread.sleep(100000000000000000L)
  }*/
}
