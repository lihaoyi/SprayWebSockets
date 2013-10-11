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
import spray.can.server.websockets.Sockets.{ClientPipelineStage, ServerPipelineStage, RoundTripTime}
import akka.io.IO
import akka.io.Tcp.Connected
import akka.io.Tcp.Register
import spray.can.server.ServerSettings
import spray.can.client.ClientConnectionSettings
import spray.io.EmptyPipelineStage

class SocketsTest extends FreeSpec with Eventually{
  implicit def string2bytestring(s: String) = ByteString(s)
  implicit val system = ActorSystem()
  implicit val timeout = akka.util.Timeout(5 seconds)

  implicit val sslContext = Util.createSslContext("/ssl-test-keystore.jks", "")

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
        sender ! Sockets.UpgradeServer(
          Sockets.acceptAllFunction(req),
          self,
          maxMessageSize
        )(extraStages)

      case f @ Frame(fin, rsv, Text, maskingKey, data) =>
        count = count + 1
        sender ! Frame(fin, rsv, Text, None, f.stringData.toUpperCase + count)

      case x: Connected =>
        sender ! Register(self)

      case Sockets.Upgraded =>
        println("Server Upgraded")
        sender ! Frame(opcode = Text, data = ByteString("Hello"))
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
        connection = sender
        connection ! Sockets.UpgradeClient(req, self, maskGen = () => 31337)(extraStages)

      case Sockets.Upgraded => println("Client Upgraded")

      case Util.Send(frame) =>
        println("Client Send " + frame)
        commander = sender
        connection ! frame

      case f: Frame =>
        println("Client Frame " + f)
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

    IO(Sockets) ! Http.Bind(
      reqHandler,
      "localhost",
      port,
      settings=Some(ServerSettings(system).copy(sslEncryption = ssl))
    )

    import akka.pattern._

    val client = system.actorOf(Props(clientActor(ssl)))

    IO(Sockets).!(Http.Connect("localhost", port, settings=Some(ClientConnectionSettings(system).copy(sslEncryption = ssl))))(client)



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

      val result1 = {
        connection send Frame(FIN = false, opcode = OpCode.Text, maskingKey = Some(12345123), data = "i am cow ")
        connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(2139), data = "hear me moo ")
        connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(-23), data = "i weigh twice as much as you ")
        connection await Frame(opcode = OpCode.Continuation, maskingKey = Some(-124123212), data = "and i look good on the barbecue ")
      }
      assert(result1.stringData === "I AM COW HEAR ME MOO I WEIGH TWICE AS MUCH AS YOU AND I LOOK GOOD ON THE BARBECUE 1")

      val result2 = {
        connection send Frame(FIN = false, opcode = OpCode.Text, maskingKey = Some(12345123), data = "yoghurt curds cream cheese and butter ")
        connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(2139), data = "comes from liquids from my udder ")
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
//
//Testing started at 12:40 AM ...
//Client Connected
//FP Command Down Tell(Actor[akka://default/user/$a#-597652527],Upgraded,Actor[akka://default/user/IO-SOCKET/group-0/0/$a#616726173])
//Client Upgraded
//FP Command Down Tell(Actor[akka://default/user/$a#-597652527],HttpResponse(101 Switching Protocols,EmptyEntity,List(Sec-WebSocket-Accept: HSmrc0sMlYUkAGmm5OPpG2HaGWk=, Connection: Upgrade, Upgrade: WebSocket, Server: AutobahnTestSuite/0.6.0-0.6.3),HTTP/1.1),Actor[akka://default/user/IO-SOCKET/group-0/0/$a#616726173])
//Client Response
//FP Frame Up Frame(true,0,Text,None,ByteString())
//FP Command Down Tell(Actor[akka://default/user/$a#-597652527],Frame(true,0,Text,None,ByteString()),Actor[akka://default/user/IO-SOCKET/group-0/0/$a#616726173])
//Client Frame
//FP Frame Down FrameCommand(Frame(true,0,Text,Some(31337),ByteString()))
//FP Frame Up Frame(true,0,ConnectionClose,None,ByteString(3, -24))
//FP Command Down Write(ByteString(-120, 2, 3, -24),NoAck(null))
//FP Command Down Close
//FP Command Down Tell(Actor[akka://default/user/$a#-597652527],Frame(true,0,ConnectionClose,None,ByteString(3, -24)),Actor[akka://default/user/IO-SOCKET/group-0/0/$a#616726173])
//Client Unknown Frame(true,0,ConnectionClose,None,ByteString(3, -24))

//C:\Runtimes\Java\bin\java -Dvisualvm.id=98109893530530 -Didea.launcher.port=7537 -Didea.launcher.bin.path=C:\Programs\IntelliJ\bin -Dfile.encoding=UTF-8 -classpath C:\Users\Haoyi\.IntelliJIdea12\config\plugins\Scala\lib\scala-plugin-runners.jar;C:\Runtimes\Java\jre\lib\charsets.jar;C:\Runtimes\Java\jre\lib\deploy.jar;C:\Runtimes\Java\jre\lib\javaws.jar;C:\Runtimes\Java\jre\lib\jce.jar;C:\Runtimes\Java\jre\lib\jfr.jar;C:\Runtimes\Java\jre\lib\jfxrt.jar;C:\Runtimes\Java\jre\lib\jsse.jar;C:\Runtimes\Java\jre\lib\management-agent.jar;C:\Runtimes\Java\jre\lib\plugin.jar;C:\Runtimes\Java\jre\lib\resources.jar;C:\Runtimes\Java\jre\lib\rt.jar;C:\Runtimes\Java\jre\lib\ext\access-bridge-64.jar;C:\Runtimes\Java\jre\lib\ext\dnsns.jar;C:\Runtimes\Java\jre\lib\ext\jaccess.jar;C:\Runtimes\Java\jre\lib\ext\localedata.jar;C:\Runtimes\Java\jre\lib\ext\sunec.jar;C:\Runtimes\Java\jre\lib\ext\sunjce_provider.jar;C:\Runtimes\Java\jre\lib\ext\sunmscapi.jar;C:\Runtimes\Java\jre\lib\ext\zipfs.jar;C:\Dropbox\Workspace\WebSockets\target\scala-2.10\test-classes;C:\Dropbox\Workspace\WebSockets\target\scala-2.10\classes;C:\Users\Haoyi\.sbt\boot\scala-2.10.2\lib\scala-library.jar;C:\Users\Haoyi\.ivy2\cache\io.spray\spray-can\jars\spray-can-1.2-M8.jar;C:\Users\Haoyi\.ivy2\cache\io.spray\spray-io\jars\spray-io-1.2-M8.jar;C:\Users\Haoyi\.ivy2\cache\io.spray\spray-util\jars\spray-util-1.2-M8.jar;C:\Users\Haoyi\.ivy2\cache\io.spray\spray-http\jars\spray-http-1.2-M8.jar;C:\Users\Haoyi\.ivy2\cache\org.parboiled\parboiled-scala_2.10\jars\parboiled-scala_2.10-1.1.5.jar;C:\Users\Haoyi\.ivy2\cache\org.parboiled\parboiled-scala_2.10\bundles\parboiled-scala_2.10-1.1.5.jar;C:\Users\Haoyi\.ivy2\cache\org.parboiled\parboiled-core\jars\parboiled-core-1.1.5.jar;C:\Users\Haoyi\.ivy2\cache\org.parboiled\parboiled-core\bundles\parboiled-core-1.1.5.jar;C:\Users\Haoyi\.ivy2\cache\io.spray\spray-routing\jars\spray-routing-1.2-M8.jar;C:\Users\Haoyi\.ivy2\cache\io.spray\spray-httpx\jars\spray-httpx-1.2-M8.jar;C:\Users\Haoyi\.ivy2\cache\org.jvnet.mimepull\mimepull\jars\mimepull-1.9.2.jar;C:\Users\Haoyi\.ivy2\cache\com.chuusai\shapeless_2.10\jars\shapeless_2.10-1.2.4.jar;C:\Users\Haoyi\.ivy2\cache\com.typesafe.akka\akka-actor_2.10\jars\akka-actor_2.10-2.2.0-RC1.jar;C:\Users\Haoyi\.ivy2\cache\com.typesafe\config\jars\config-1.0.1.jar;C:\Users\Haoyi\.ivy2\cache\com.typesafe\config\bundles\config-1.0.1.jar;C:\Users\Haoyi\.ivy2\cache\org.java-websocket\Java-WebSocket\jars\Java-WebSocket-1.3.0.jar;C:\Users\Haoyi\.ivy2\cache\io.spray\spray-testkit\jars\spray-testkit-1.2-M8.jar;C:\Users\Haoyi\.ivy2\cache\com.typesafe.akka\akka-testkit_2.10\jars\akka-testkit_2.10-2.2.0-RC1.jar;C:\Users\Haoyi\.ivy2\cache\com.typesafe.akka\akka-testkit_2.10\bundles\akka-testkit_2.10-2.2.0-RC1.jar;C:\Users\Haoyi\.ivy2\cache\org.scalatest\scalatest_2.10\jars\scalatest_2.10-2.0.RC1.jar;C:\Users\Haoyi\.ivy2\cache\org.scala-lang\scala-reflect\jars\scala-reflect-2.10.0.jar;C:\Programs\IntelliJ\lib\idea_rt.jar com.intellij.rt.execution.application.AppMain org.jetbrains.plugins.scala.testingSupport.scalaTest.ScalaTestRunner -s spray.can.server.websockets.AutoBahn -showProgressMessages true -C org.jetbrains.plugins.scala.testingSupport.scalaTest.ScalaTestReporter
//Testing started at 12:47 AM ...
//Client Connected
//FP Command Down Tell(Actor[akka://default/user/$a#1004654038],Upgraded,Actor[akka://default/user/IO-SOCKET/group-0/0/$a#187839070])
//Client Upgraded
//FP Command Down Tell(Actor[akka://default/user/$a#1004654038],HttpResponse(101 Switching Protocols,EmptyEntity,List(Sec-WebSocket-Accept: HSmrc0sMlYUkAGmm5OPpG2HaGWk=, Connection: Upgrade, Upgrade: WebSocket, Server: AutobahnTestSuite/0.6.0-0.6.3),HTTP/1.1),Actor[akka://default/user/IO-SOCKET/group-0/0/$a#187839070])
//Client Response
//FP Frame Up Frame(true,0,Text,None,ByteString())
//FP Command Down Tell(Actor[akka://default/user/$a#1004654038],Frame(true,0,Text,None,ByteString()),Actor[akka://default/user/IO-SOCKET/group-0/0/$a#187839070])
//Client Frame
//FP Frame Down FrameCommand(Frame(true,0,Text,Some(31337),ByteString()))
//FP Frame Up Frame(true,0,ConnectionClose,None,ByteString(3, -24))
//FP Command Down Write(ByteString(-120, 2, 3, -24),NoAck(null))
//FP Command Down Close
//FP Command Down Tell(Actor[akka://default/user/$a#1004654038],Frame(true,0,ConnectionClose,None,ByteString(3, -24)),Actor[akka://default/user/IO-SOCKET/group-0/0/$a#187839070])
//Client Unknown Frame(true,0,ConnectionClose,None,ByteString(3, -24))
//FP Command Down Tell(Actor[akka://default/user/$a#1004654038],Closed,Actor[akka://default/user/IO-SOCKET/group-0/0/$a#187839070])
//Client Closed