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
import scala.concurrent.Await

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
    keyStore.load(getClass.getResourceAsStream(keyStoreResource), password.toCharArray)
    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(keyStore, password.toCharArray)
    val trustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    trustManagerFactory.init(keyStore)
    val context = SSLContext.getInstance("TLS")
    context.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)
    context
  }
  def createSslEngine(remote: InetSocketAddress, keyStoreResource: String, password: String): SSLEngine = {
    val context = createSslContext(keyStoreResource, password)
    val engine = context.createSSLEngine(remote.getHostName, remote.getPort)
    engine.setUseClientMode(true)
    engine
  }

  case class Send(f: Frame)
  case object Listen
  /**
   * Provides convenience methods on an ActorRef so you don't need to keep
   * typing Await.result(..., 10 seconds) blah blah blah and other annoying things
   */
  implicit class blockActorRef(a: ActorRef){
    import scala.concurrent.duration._
    import akka.pattern._
    implicit val timeout = akka.util.Timeout(5 seconds)
    def send(b: Frame) = {
      a ! Send(b)
    }

    /**
     * Sends a frame and waits for the reply, buffering up the incoming bytes
     * (since they could come over a period of time) and trying to parse it
     * into an entire frame
     */
    def await(b: Frame): Frame = {
      Await.result(a ? Send(b), 10 seconds).asInstanceOf[Frame]
    }

    def listen = {
      Await.result(a ? Listen, 10 seconds).asInstanceOf[Frame]
    }
  }

}
