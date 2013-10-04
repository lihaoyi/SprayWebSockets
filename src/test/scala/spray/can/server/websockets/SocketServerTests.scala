package spray.can.server.websockets

import model._
import model.Frame.Successful
import model.OpCode.{ConnectionClose, Text}
import org.scalatest.FreeSpec
import akka.actor._
import concurrent.duration._
import spray.io._
import scala.concurrent.{Promise, Await}
import akka.util.{ByteString, Timeout}
import spray.http.HttpRequest
import akka.testkit.TestActorRef
import org.scalatest.concurrent.Eventually

import javax.net.ssl.{TrustManagerFactory, KeyManagerFactory, SSLContext}
import java.security.{SecureRandom, KeyStore}

import scala.Some

import org.scalatest.time.{Seconds, Span}
import akka.io.Tcp
import akka.io.IO
import spray.can.Http
import java.net.InetSocketAddress
import akka.io.Tcp.{Register, Write, Connected}

class SocketServerTests extends FreeSpec with Eventually{
  implicit def byteArrayToBytestring(array: Array[Byte]) = ByteString(array)
  implicit val system = ActorSystem()
  implicit val timeout = akka.util.Timeout(5 seconds)

  implicit val sslContext = createSslContext("/ssl-test-keystore.jks", "")
  def createSslContext(keyStoreResource: String, password: String): SSLContext = {
    val keyStore = KeyStore.getInstance("jks")
    val res = getClass.getResourceAsStream(keyStoreResource)
    require(res != null)
    keyStore.load(res, password.toCharArray)
    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(keyStore, password.toCharArray)
    val trustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    trustManagerFactory.init(keyStore)
    val context = SSLContext.getInstance("SSL")
    context.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)
    context
  }

  /**
   * Provides convenience methods on an ActorRef so you don't need to keep
   * typing Await.result(..., 10 seconds) blah blah blah and other annoying things
   */
  implicit class blockActorRef(a: TestActorRef[ReadingActor]){

    def send(b: Frame) = {
      a ! Tcp.Write(Frame.write(b))
    }

    /**
     * Sends a frame and waits for the reply, buffering up the incoming bytes
     * (since they could come over a period of time) and trying to parse it
     * into an entire frame
     */
    def await(b: Frame): Frame = {
      await(Tcp.Write(Frame.write(b)))
    }
    /**
     * Sends a frame and waits for the reply, buffering up the incoming bytes
     * (since they could come over a period of time) and trying to parse it
     * into an entire frame
     */
    def await(c: Command): Frame = {
      println("Awaiting Command")
      a.underlyingActor.buffer = ByteString.empty

      a ! c
      eventually {
        Frame.read(a.underlyingActor.buffer.asByteBuffer)
             .asInstanceOf[Successful]
             .frame
      }(PatienceConfig(Span(5, Seconds)))

    }
  }

  val websocketClientHandshake =
    "GET /mychat HTTP/1.1\r\n" +
    "Host: server.example.com\r\n" +
    "Upgrade: websocket\r\n" +
    "Connection: Upgrade\r\n" +
    "Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==\r\n\r\n"

  /**
   * HttpHandler which always accepts websocket upgrade requests
   */
  class AcceptActor extends Actor{
    def receive = {
      case req: HttpRequest =>
        println("Http Received")
        sender ! SocketServer.acceptAllFunction(req)
        sender ! Sockets.Upgrade(TestActorRef(new EchoActor()), Duration.Inf, () => Array(), Int.MaxValue)
      case x: Connected =>
        println("x: " + x)
        sender ! Register(self)

    }
  }

  /**
   * Websocket frameHandler which echoes any frames sent to it, but
   * capitalizes them and keeps count so you know it's alive
   */
  class EchoActor extends Actor{
    var count = 0
    def receive = {
      case f @ Frame(fin, rsv, Text, maskingKey, data) =>
        count = count + 1
        sender ! Frame(fin, rsv, Text, None, (f.stringData.toUpperCase + count).getBytes)
      case x =>
    }
  }
  class ReadingActor extends Actor{
    @volatile var buffer = ByteString.empty
    var outbox = Seq[Tcp.Write]()
    var connection: ActorRef = _
    def receive = {
      case Tcp.Received(data) =>
          println("AWAIT RECEIVED: " + data)
        buffer = buffer ++ data

      case reply: Connected =>
        println("connected!")
        connection = sender
        connection ! Register(self)
        for (out <- outbox){
          println("flushing " + out.data.decodeString("UTF-8"))
          connection ! out
          outbox = Nil
        }
      case write: Tcp.Write =>
        println("write!")
        if (connection == null)
          outbox = outbox ++ Seq(write)
        else
          connection ! write
    }
  }
  /**
   * Sets up a SocketServer and a IOClientConnection talking to it.
   */
  def setupConnection(port: Int = 10000 + util.Random.nextInt(1000),
                      serverActor: () => Actor = () => new AcceptActor,
                      echoActor: => () => Actor = () => new EchoActor,
                      maxMessageLength: Long = Long.MaxValue,
                      autoPingInterval: Duration = 5 seconds) = {
    val reqHandler = system.actorOf(Props(serverActor()))
    val frameHandler = system.actorOf(Props(echoActor()))
    IO(Sockets) ! Http.Bind(reqHandler, "localhost", port)

    val client = TestActorRef(new ReadingActor)
    IO(Tcp).!(Tcp.Connect(new InetSocketAddress("localhost", port)))(client)
    println("c")
    client await Tcp.Write(ByteString(websocketClientHandshake.getBytes))
    println("d")
    client
  }

  /**
   * Registers the given test twice, with and without SSL.
   */
  def doTwice(port: Int = 1000 + util.Random.nextInt(10000),
              serverActor: () => Actor = () => new AcceptActor,
              echoActor: => () => Actor = () => new EchoActor,
              maxMessageLength: Long = Long.MaxValue,
              autoPingInterval: Duration = 5 seconds)
             (test: TestActorRef[ReadingActor]   => Unit) = {
    "basic" in test(setupConnection(port, serverActor, echoActor, maxMessageLength, autoPingInterval))
  }

  "Echo Server Tests" - {
    "hello world with echo server" - doTwice(){ connection =>
      def frame = Frame(true, (false, false, false), OpCode.Text, Some(12345123), "i am cow".getBytes)
      val r3 = connection await frame
      assert(r3.stringData === "I AM COW1")

      val r4 = connection await frame
      assert(r4.stringData === "I AM COW2")
    }

    "Testing ability to receive fragmented message" - doTwice(){ connection =>

      val result1 = {
        connection send Frame(FIN = false, opcode = OpCode.Text, maskingKey = Some(12345123), data = "i am cow ".getBytes)
        connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(2139), data = "hear me moo ".getBytes)
        connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(-23), data = "i weigh twice as much as you ".getBytes)
        connection await Frame(opcode = OpCode.Continuation, maskingKey = Some(-124123212), data = "and i look good on the barbecue ".getBytes)
      }
      assert(result1.stringData === "I AM COW HEAR ME MOO I WEIGH TWICE AS MUCH AS YOU AND I LOOK GOOD ON THE BARBECUE 1")

      val result2 = {
        connection send Frame(FIN = false, opcode = OpCode.Text, maskingKey = Some(12345123), data = "yoghurt curds cream cheese and butter ".getBytes)
        connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(2139), data = "comes from liquids from my udder ".getBytes)
        connection await Frame(opcode = OpCode.Text, maskingKey = Some(-23), data = "i am cow, i am cow, hear me moooo ".getBytes)
      }
      assert(result2.stringData === "YOGHURT CURDS CREAM CHEESE AND BUTTER COMES FROM LIQUIDS FROM MY UDDER I AM COW, I AM COW, HEAR ME MOOOO 2")
      
    }
    "Ping/Pong" - {
      "simple responses" - doTwice(){ connection =>
        
        val res1 = connection await Frame(opcode = OpCode.Ping, maskingKey = Some(123456), data = "i am cow".getBytes)
        assert(res1.stringData === "i am cow")
        val res2 = connection await Frame(opcode = OpCode.Ping, maskingKey = Some(123456), data = "i am cow".getBytes)
        assert(res2.stringData === "i am cow")
      
      }
      "responding in middle of fragmented message" - doTwice(){connection =>
        val result1 = {
          connection send Frame(FIN = false, opcode = OpCode.Text, maskingKey = Some(12345123), data = "i am cow ".getBytes)
          connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(2139), data = "hear me moo ".getBytes)

          val res1 = connection await Frame(opcode = OpCode.Ping, maskingKey = Some(123456), data = "i am cow".getBytes)
          assert(res1.stringData === "i am cow")

          connection send Frame(FIN = false, opcode = OpCode.Continuation, maskingKey = Some(-23), data = "i weigh twice as much as you ".getBytes)

          val res2 = connection await Frame(opcode = OpCode.Ping, maskingKey = Some(123456), data = "i am cow".getBytes)
          assert(res2.stringData === "i am cow")

          connection await Frame(opcode = OpCode.Continuation, maskingKey = Some(-124123212), data = "and i look good on the barbecue ".getBytes)
        }
        assert(result1.stringData === "I AM COW HEAR ME MOO I WEIGH TWICE AS MUCH AS YOU AND I LOOK GOOD ON THE BARBECUE 1")
        
      }
    }

    "Closing Tests" - {
      "Clean Close" - doTwice(){ connection =>
        val res1 = connection await Frame(opcode = OpCode.ConnectionClose, maskingKey = Some(0))
        assert(res1.opcode === OpCode.ConnectionClose)

      }
      "The server MUST close the connection upon receiving a frame that is not masked" - doTwice(){ connection =>
        val res1 = connection await Frame(opcode = OpCode.Text, data = ByteString("lol"))
        assert(res1.opcode === OpCode.ConnectionClose)
        assert(res1.data.asByteBuffer.getShort === CloseCode.ProtocolError.statusCode)

      }

      "The server must close the connection if the frame is too large" - {
        "single large frame" - doTwice(maxMessageLength = 1024){ connection =>
        // just below the limit works
          val res1 = connection await Frame(opcode = OpCode.Text, data = ByteString("l" * 1024), maskingKey = Some(0))
          assert(res1.opcode === OpCode.Text)
          assert(res1.stringData === ("L" * 1024 + "1"))

          // just above the limit
          val res2 = connection await Frame(opcode = OpCode.Text, data = ByteString("l" * 1025), maskingKey = Some(0))
          assert(res2.data.asByteBuffer.getShort === CloseCode.MessageTooBig.statusCode)

        }
        "fragmented large frame" - doTwice(maxMessageLength = 1024){ connection =>
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
/*    "pinging" - {

      "ping pong" - doTwice(){connection =>
        val res1 = connection await Frame(opcode = OpCode.Ping, data = ByteString("hello ping"), maskingKey = Some(12345))
        assert(res1.stringData === "hello ping")
        assert(res1.opcode === OpCode.Pong)

        val res2 = connection await Frame(opcode = OpCode.Ping, data = ByteString("hello ping again"), maskingKey = Some(12345))
        assert(res2.stringData === "hello ping again")
        assert(res2.opcode === OpCode.Pong)

      }
      "auto ping" - doTwice(autoPingInterval = 100 milliseconds){connection =>
        val res1 = connection await Tell(TestActorRef(new Actor{def receive = {case x =>}}), "lol", null)
        assert(res1.opcode === OpCode.Ping)
        val res2 = connection await Tell(TestActorRef(new Actor{def receive = {case x =>}}), "lol", null)
        assert(res2.opcode === OpCode.Ping)
      }

      class TimingEchoActor extends Actor{
        var lastDuration = Duration.Zero

        def receive = {
          case f @ Frame(fin, rsv, Text, maskingKey, data) =>
            sender ! Frame(fin, rsv, Text, None, lastDuration.toMillis.toString.getBytes)
          case RoundTripTime(duration) =>
            lastDuration = duration
        }
      }
      "latency numbers" - doTwice(echoActor = () => new TimingEchoActor(), autoPingInterval = 100 milliseconds){connection =>
        {
          //initial latency should be 0
          val res1 = connection await Frame(opcode = OpCode.Text, data = ByteString("hello ping"), maskingKey = Some(12345))
          assert(res1.stringData === "0")

          // wait for ping and send pong
          val res2 = connection await Tell(TestActorRef(new Actor{def receive = {case x =>}}), "lol", null)
          assert(res2.opcode === OpCode.Ping)
          connection send Frame(opcode = OpCode.Pong, data = res2.data, maskingKey = Some(12345))

          //new latency should be non zero
          val res3 = connection await Frame(opcode = OpCode.Text, data = ByteString("hello ping"), maskingKey = Some(12345))
          assert(res3.stringData.toLong > 0)
        }
        {
          // wait for ping and send pong
          val res2 = connection await Tell(TestActorRef(new Actor{def receive = {case x =>}}), "lol", null)
          assert(res2.opcode === OpCode.Ping)
          Thread.sleep(200)
          connection send Frame(opcode = OpCode.Pong, data = res2.data, maskingKey = Some(12345))

          //new latency should be non zero
          val res3 = connection await Frame(opcode = OpCode.Text, data = ByteString("hello ping"), maskingKey = Some(12345))
          assert(res3.stringData.toLong > 200)
        }
      }
    }*/
  }

}
