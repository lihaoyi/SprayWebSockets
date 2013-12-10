package spray.can.server.websockets

import model._
import model.OpCode.{ConnectionClose, Text}
import org.scalatest.FreeSpec
import akka.actor._
import concurrent.duration._
import scala.concurrent.{Promise, Await}
import akka.util.{ByteString, Timeout}
import spray.http.{HttpResponse, HttpHeaders, HttpMethods, HttpRequest}
import akka.testkit.TestActorRef
import org.scalatest.concurrent.Eventually

import spray.can.Http
import scala.Some
import Sockets.{ClientPipelineStage, ServerPipelineStage, RoundTripTime}
import akka.io.IO
import akka.io.Tcp.Connected
import akka.io.Tcp.Register
import spray.can.server.ServerSettings
import spray.can.client.ClientConnectionSettings
import spray.io.{ServerSSLEngineProvider, ClientSSLEngineProvider, EmptyPipelineStage}
import javax.net.ssl.SSLContext

class SocketsTest extends FreeSpec with Eventually{
  implicit def string2bytestring(s: String) = ByteString(s)
  implicit val system = ActorSystem()
  implicit val timeout = akka.util.Timeout(5 seconds)

  implicit val sslContext: SSLContext = Util.createSslContext("/ssl-test-keystore.jks", "")
  implicit val clientEngineProvider = ClientSSLEngineProvider { engine =>
    engine.setEnabledCipherSuites(Array("TLS_RSA_WITH_AES_128_CBC_SHA"))
    engine.setEnabledProtocols(Array("SSLv3", "TLSv1"))
    engine
  }
  implicit val serverEngineProvider = ServerSSLEngineProvider { engine =>
    engine.setEnabledCipherSuites(Array("TLS_RSA_WITH_AES_128_CBC_SHA"))
    engine.setEnabledProtocols(Array("SSLv3", "TLSv1"))
    engine
  }
  import scala.concurrent.duration.Duration
  /**
   * Webserver which always accepts websocket upgrade requests. Also doubles as
   * a Websocket frameHandler which echoes any frames sent to it, but
   * capitalizes them and keeps count so you know it's alive
   */
  class ServerActor(maxMessageSize: Int = Int.MaxValue,
                    extraStages: ServerPipelineStage = EmptyPipelineStage) extends Actor{
    var count = 0
    def receive = {
      case req: HttpRequest =>
        println("Server Receive")
        sender ! Sockets.UpgradeServer(
          Sockets.acceptAllFunction(req),
          self,
          maxMessageSize
        )(extraStages)

      case f @ Frame(fin, rsv, Text, maskingKey, data) =>
        println("Server Frame Received")
        count = count + 1
        sender ! Frame(fin, rsv, Text, None, f.stringData.toUpperCase + count)

      case x: Connected =>
        println("Server Connected")
        sender ! Register(self)

      case Sockets.Upgraded =>
        println("Server Upgraded")
        sender ! Frame(opcode = Text, data = ByteString("Hello"))

      case x => println("Server Unknown " + x)
    }
  }

  class ClientActor(ssl: Boolean,
                    extraStages: ClientPipelineStage = EmptyPipelineStage) extends Actor{
    var connection: ActorRef = null
    var commander: ActorRef = null

    var ready = false
    def receive = {
      case x: HttpResponse =>
        println("Client Response")
        connection = sender

      case x: Http.Connected =>
        println("Client Connected")
        connection = sender
        connection ! Sockets.UpgradeClient(req, self, maskGen = () => 31337)(extraStages)

      case Sockets.Upgraded =>
        println("Client Upgraded")

      case Util.Send(frame) =>
        println("Client Frame Send")
        commander = sender
        connection ! frame

      case f: Frame =>
        println("Client Frame Received")
        ready = true
        commander ! f

      case "ready?" =>
        sender ! ready
        commander = sender

      case Util.Listen =>
        commander = sender

      case x => println("Client Unknown " + x)
    }
  }

  val req = HttpRequest(HttpMethods.GET,  "/mychat", List(
    HttpHeaders.Host("server.example.com", 80),
    HttpHeaders.Connection("Upgrade"),
    HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw==")
  ))

  /**
   * Sets up a SocketServer and a IOClientConnection talking to it.
   */
  def setupConnection(port: Int = 10000 + util.Random.nextInt(1000),
                      serverActor: => Actor,
                      clientActor: Boolean => Actor,
                      ssl: Boolean) = {
    val reqHandler = system.actorOf(Props(serverActor))

    IO(Sockets).!(
      Http.Bind(
        reqHandler,
        "localhost",
        port,
        settings=Some(ServerSettings(system).copy(sslEncryption = ssl))
      )
    )(reqHandler)

    import akka.pattern._

    val client = system.actorOf(Props(clientActor(ssl)))
    Thread.sleep(10)
    IO(Sockets).!(
      Http.Connect(
        "localhost",
        port,
        settings = Some(ClientConnectionSettings(system)),
        sslEncryption = ssl
      )
    )(client)



    eventually{
      assert(Await.result(client ? "ready?", 0.1 seconds) == true)
    }(PatienceConfig(timeout=10 seconds))

    client
  }

  /**
   * Registers the given test twice, with and without SSL.
   */
  def doTwice(serverActor: => Actor = new ServerActor(Int.MaxValue),
              clientActor: Boolean => Actor = ssl => new ClientActor(ssl = ssl),
              port: Int = 1000 + util.Random.nextInt(10000))
             (test: ActorRef => Unit) = {
    "basic" in test(setupConnection(port, serverActor, clientActor, ssl=false))
    "ssl" in test(setupConnection(port + 1, serverActor, clientActor, ssl=true))
  }
  import Util.blockActorRef
  "Echo Server Tests" - {
    "hello world with echo server" - doTwice(){ connection =>
      def frame = Frame(true, 0, OpCode.Text, Some(12345123), "i am cow")

      val r3 = connection await frame
      assert(r3.stringData === "I AM COW1")
      val r4 = connection await frame
      assert(r4.stringData === "I AM COW2")
    }

    "Testing ability to receive fragmented message" - doTwice(){ connection =>
      println("akka.loglevel is " + com.typesafe.config.ConfigFactory.load().getString("akka.loglevel"))
      val result1 = {
        connection send Frame(FIN = false, opcode = OpCode.Text, maskingKey = Some(12345123), data = "i am cow ")
        connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(2139), data = "hear me moo ")
        connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(-23), data = "i weigh twice as much as you ")
        connection await Frame(opcode = OpCode.Continuation, maskingKey = Some(-124123212), data = "and i look good on the barbecue ")
      }
      assert(result1.stringData === "I AM COW HEAR ME MOO I WEIGH TWICE AS MUCH AS YOU AND I LOOK GOOD ON THE BARBECUE 1")

      val result2 = {
        println("5")
        connection send Frame(FIN = false, opcode = OpCode.Text, maskingKey = Some(12345123), data = "yoghurt curds cream cheese and butter ")
        println("6")
        connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(2139), data = "comes from liquids from my udder ")
        println("7")
        connection await Frame(opcode = OpCode.Continuation, maskingKey = Some(-23), data = "i am cow, i am cow, hear me moooo ")
      }
      assert(result2.stringData === "YOGHURT CURDS CREAM CHEESE AND BUTTER COMES FROM LIQUIDS FROM MY UDDER I AM COW, I AM COW, HEAR ME MOOOO 2")

    }

    "Closing Tests" - {
      "Clean Close" - doTwice(){ connection =>
        println("Sending one frame")
        val res1 = connection await Frame(opcode = OpCode.ConnectionClose, maskingKey = Some(0))
        println("Frame sent and reply received")
        assert(res1.opcode === OpCode.ConnectionClose)

      }
      "The server MUST close the connection upon receiving a frame that is not masked" - doTwice(){ connection =>
        val res1 = connection await Frame(opcode = OpCode.Text, data = ByteString(1000 toShort))
        assert(res1.opcode === OpCode.ConnectionClose)
        assert(res1.data.asByteBuffer.getShort === CloseCode.ProtocolError.statusCode)

      }

      "The server must close the connection if the frame is too large" - {
        "single large frame" - doTwice(new ServerActor(maxMessageSize = 1024)){ connection =>
        // just below the limit works
          val res1 = connection await Frame(opcode = OpCode.Text, data = ByteString("l" * 1024), maskingKey = Some(0))
          assert(res1.opcode === OpCode.Text)
          assert(res1.stringData === ("L" * 1024 + "1"))

          // just above the limit
          val res2 = connection await Frame(opcode = OpCode.Text, data = ByteString("l" * 1025), maskingKey = Some(0))
          assert(res2.data.asByteBuffer.getShort === CloseCode.MessageTooBig.statusCode)

        }
        "fragmented large frame" - doTwice(new ServerActor(maxMessageSize = 1024)){ connection =>
          // just below the limit works
          connection send Frame(FIN = false, opcode = OpCode.Text, data = ByteString("l" * 256), maskingKey = Some(0))
          connection send Frame(FIN = false, opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          connection send Frame(FIN = false, opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          val res1 = connection await Frame(opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))

          assert(res1.opcode === OpCode.Text)
          assert(res1.stringData === ("L" * 1024 + "1"))

          // just above the limit
          connection send Frame(FIN = false, opcode = OpCode.Text, data = ByteString("l" * 257), maskingKey = Some(0))
          connection send Frame(FIN = false, opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          connection send Frame(FIN = false, opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          val res2 = connection await Frame(opcode = OpCode.Continuation, data = ByteString("l" * 256), maskingKey = Some(0))
          assert(res2.data.asByteBuffer.getShort === CloseCode.MessageTooBig.statusCode)
        }
      }
    }
    "pinging" - {
      "auto ping" - doTwice(new ServerActor(extraStages=AutoPing(interval = 100 millis)),
                            ssl => new ClientActor(ssl = ssl, EmptyPipelineStage)){connection =>
        val res1 = connection.listen
        assert(res1.opcode === OpCode.Ping)
        val res2 = connection.listen
        assert(res2.opcode === OpCode.Ping)
      }

      class TimingEchoActor(maxMessageSize: Int = Int.MaxValue, extraStages: ServerPipelineStage)
        extends ServerActor(maxMessageSize, extraStages){

        var lastDuration = Duration.Zero

        def newReceive: PartialFunction[Any, Unit] = {
          case f @ Frame(fin, rsv, Text, maskingKey, data) =>
            sender ! Frame(fin, rsv, Text, None, lastDuration.toMillis.toString)
          case RoundTripTime(duration) =>
            lastDuration = duration
        }
        override def receive = newReceive orElse super.receive

      }
      "responding in middle of fragmented message" - doTwice(new ServerActor(extraStages=AutoPing(interval = 100 millis)),
                                                            ssl => new ClientActor(ssl = ssl, EmptyPipelineStage)){connection =>
        val result1 = {
          connection send Frame(FIN = false, opcode = OpCode.Text, maskingKey = Some(12345123), data = "i am cow ")
          connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(2139), data = "hear me moo ")

          val res1 = connection.listen
          assert(res1.opcode === OpCode.Ping)

          connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(-23), data = "i weigh twice as much as you ")

          val res2 = connection.listen
          assert(res2.opcode === OpCode.Ping)

          connection await Frame(opcode = OpCode.Continuation, maskingKey = Some(-124123212), data = "and i look good on the barbecue ")
        }
        assert(result1.stringData === "I AM COW HEAR ME MOO I WEIGH TWICE AS MUCH AS YOU AND I LOOK GOOD ON THE BARBECUE 1")
      }
      "latency numbers" - doTwice(new TimingEchoActor(extraStages=AutoPing(interval = 100 millis)),
                                  ssl => new ClientActor(ssl = ssl, EmptyPipelineStage)){connection =>
        {
          //initial latency should be 0
          val res1 = connection await Frame(opcode = OpCode.Text, data = ByteString("hello ping"), maskingKey = Some(12345))
          assert(res1.stringData === "0")

          // wait for ping and send pong
          val res2 = connection.listen
          assert(res2.opcode === OpCode.Ping)
          Thread.sleep(200)

          connection send Frame(opcode = OpCode.Pong, data = res2.data, maskingKey = Some(12345))

          //new latency should be non zero
          val res3 = connection await Frame(opcode = OpCode.Text, data = ByteString("hello ping"), maskingKey = Some(12345))
          assert(res3.stringData.toLong > 200)
        }
        {
          // wait for ping and send pong
          val res2 = connection.listen
          assert(res2.opcode === OpCode.Ping)
          Thread.sleep(2)
          connection send Frame(opcode = OpCode.Pong, data = res2.data, maskingKey = Some(12345))

          //new latency should be non zero
          val res3 = connection await Frame(opcode = OpCode.Text, data = ByteString("hello ping"), maskingKey = Some(12345))
          assert(res3.stringData.toLong > 0)
        }
      }
    }
  }

}