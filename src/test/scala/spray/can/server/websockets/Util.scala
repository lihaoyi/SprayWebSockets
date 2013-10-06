package spray.can.server.websockets

import akka.actor.{ActorSystem, ActorRef, Actor}
import akka.util.ByteString
import akka.io.{PipePair, SslTlsSupport, TcpPipelineHandler, Tcp}
import akka.io.Tcp.{Register, Connected}
import akka.io.TcpPipelineHandler.WithinActorContext
import akka.testkit.TestActorRef
import spray.can.server.websockets.model.Frame
import spray.io._
import akka.io.Tcp.Connected
import akka.io.Tcp.Register
import spray.can.server.websockets.model.Frame.Successful

import org.scalatest.time.{Seconds, Span}
import org.scalatest.concurrent.Eventually
import javax.net.ssl.{SSLEngine, TrustManagerFactory, KeyManagerFactory, SSLContext}
import java.security.{SecureRandom, KeyStore}
import java.net.InetSocketAddress

/**
 * Created with IntelliJ IDEA.
 * User: Haoyi
 * Date: 10/5/13
 * Time: 11:03 PM
 * To change this template use File | Settings | File Templates.
 */
object Util extends Eventually{
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
  def createSslEngine(remote: InetSocketAddress, keyStoreResource: String, password: String): SSLEngine = {
    val context = createSslContext(keyStoreResource, password)
    val engine = context.createSSLEngine(remote.getHostName, remote.getPort)
    engine.setUseClientMode(true)
    engine
  }
  class TestClientActor(ssl: Boolean)(implicit system: ActorSystem) extends Actor{
    @volatile var buffer = ByteString.empty
    var outbox = Seq[Tcp.Write]()
    var connection: ActorRef = _

    var func: Tcp.Command => Any = null
    def receive = {
      case e: TcpPipelineHandler.Init[_, _, _]#Event =>
        e.evt match{
          case Tcp.Received(data) =>
            buffer = buffer ++ data
          case Tcp.Closed | Tcp.PeerClosed => ()
        }


      case Connected(remote, local) =>
        // http://doc.akka.io/docs/akka/snapshot/scala/io-tcp.html
        val sslEngine = createSslEngine(remote, "/ssl-test-keystore.jks", "")
        val init = TcpPipelineHandler.withLogger(
          this.context.system.log, // NoLogging,
          if (ssl) new SslTlsSupport(sslEngine)
          else new akka.io.PipelineStage[TcpPipelineHandler.WithinActorContext, Tcp.Command, Tcp.Command, Tcp.Event, Tcp.Event]{
            def apply(ctx: WithinActorContext)= new PipePair[Tcp.Command, Tcp.Command, Tcp.Event, Tcp.Event]{
              def commandPipeline: (Tcp.Command) => Iterable[this.type#Result] = x => Seq(Right(x))
              def eventPipeline: (Tcp.Event) => Iterable[this.type#Result] = x => Seq(Left(x))
            }
          }
        )

        val pipeline = TestActorRef(new TcpPipelineHandler(
          init,
          sender,
          self
        ))

        sender ! Register(pipeline)
        connection = pipeline
        func = init.Command

        for (out <- outbox){
          connection ! init.Command(out)
        }

        outbox = Nil

      case write: Tcp.Write =>
        if (connection == null)
          outbox = outbox ++ Seq(write)
        else
          connection ! func(write)

      case x =>
        println("Unknown! " + x)
    }
  }
  /**
   * Provides convenience methods on an ActorRef so you don't need to keep
   * typing Await.result(..., 10 seconds) blah blah blah and other annoying things
   */
  implicit class blockActorRef(a: TestActorRef[TestClientActor]){

    def send(b: Frame) = {
      a ! Tcp.Write(ByteString(Frame.write(b)))
    }

    /**
     * Sends a frame and waits for the reply, buffering up the incoming bytes
     * (since they could come over a period of time) and trying to parse it
     * into an entire frame
     */
    def await(b: Frame): Frame = {
      await(Tcp.Write(ByteString(Frame.write(b))))
    }
    /**
     * Sends a frame and waits for the reply, buffering up the incoming bytes
     * (since they could come over a period of time) and trying to parse it
     * into an entire frame
     */
    def await(c: Command): Frame = {
      a.underlyingActor.buffer = ByteString.empty

      a ! c
      eventually {
        Frame.read(a.underlyingActor.buffer.asByteBuffer)
          .asInstanceOf[Successful]
          .frame
      }(PatienceConfig(Span(5, Seconds)))
    }
  }

}
