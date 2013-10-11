package spray.can.server.websockets

import spray.can.{Http, HttpManager, HttpExt}
import akka.actor._
import spray.can.server._
import spray.can.client._
import spray.http._
import scala.util.control.NonFatal
import spray.can.server.StatsSupport.StatsHolder
import spray.io._
import akka.actor.Terminated
import scala.concurrent.duration.{FiniteDuration, Duration}
import spray.can.server.websockets.Sockets.{UpgradeServer, UpgradeClient, Upgraded}
import akka.io.Tcp
import java.security.MessageDigest
import spray.http.HttpHeaders.RawHeader
import spray.http.HttpResponse
import spray.http.HttpRequest
import akka.actor.Terminated
import spray.can.server.websockets.model.{OpCode, Frame}
import spray.io
import io.RawPipelineStage
import spray.can.server.ServerFrontend.Context
import akka.util.ByteString

/**
 * Sister class to the spray.can.Http class, providing a http server with
 * websocket capabilities
 */
object Sockets extends ExtensionKey[SocketExt]{
  object Filters{
    def hidePings(f: Frame) = f.opcode != OpCode.Ping
    def hidePingPongs(f: Frame) = f.opcode != OpCode.Ping && f.opcode != OpCode.Pong
  }

  type ServerPipelineStage = RawPipelineStage[SslTlsContext with Context]
  type ClientPipelineStage = RawPipelineStage[PipelineContext]
  val EmptyPipelineStage = spray.io.EmptyPipelineStage
  class UpgradeServer(val resp: HttpResponse, val pipeline: ServerPipelineStage) extends Command
  class UpgradeClient(val req: HttpRequest, val pipeline: ClientPipelineStage) extends Command



  object UpgradeServer{
    def apply(resp: HttpResponse,
              frameHandler: ActorRef,
              frameSizeLimit: Int = Int.MaxValue)
             (implicit extraStages: ServerPipelineStage = AutoPong(None)) = {
      new UpgradeServer(
        resp,
        WebsocketFrontEnd(frameHandler) >>
        extraStages >>
        Consolidation(frameSizeLimit, None) >>
        FrameParsing(frameSizeLimit)
      )
    }
  }
  object UpgradeClient{
    def apply(req: HttpRequest,
              frameHandler: ActorRef,
              frameSizeLimit: Int = Int.MaxValue,
              maskGen: () => Int = () => util.Random.nextInt())
             (implicit extraStages: ClientPipelineStage = AutoPong(Some(maskGen))) = {
      new UpgradeClient(
        req,
        WebsocketFrontEnd(frameHandler) >>
        extraStages >>
        Consolidation(frameSizeLimit, Some(maskGen)) >>
        FrameParsing(frameSizeLimit)

      )
    }
  }

  /**
   * Sent by Sockets whenever an incoming Pong matches an
   * outgoing Ping, providing the FrameHandler with the round-trip
   * latency of that ping-pong.
   */
  case class RoundTripTime(delta: FiniteDuration) extends Event

  /**
   * Sent by Sockets to the frameHandler when a websocket handshake
   * is complete and the connection upgraded
   */
  case object Upgraded extends Event

  def calculateReturnHash(headers: List[HttpHeader]) = {
    headers.collectFirst{
      case RawHeader("Sec-WebSocket-Key", value) => (value + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")
    }.map(MessageDigest.getInstance("SHA-1").digest)
      .map(new sun.misc.BASE64Encoder().encode)
  }

  def socketAcceptHeaders(returnValue: String) = List(
    HttpHeaders.RawHeader("Upgrade", "websocket"),
    HttpHeaders.Connection("Upgrade"),
    HttpHeaders.RawHeader("Sec-WebSocket-Accept", returnValue)
  )

  def acceptAllFunction(x: HttpRequest) = {
    HttpResponse(
      StatusCodes.SwitchingProtocols,
      headers = socketAcceptHeaders(calculateReturnHash(x.headers).get)
    )
  }
}

/**
 * Syster class to HttpExt
 */
class SocketExt(system: ExtendedActorSystem) extends HttpExt(system){
  override val manager = system.actorOf(
    props = Props(new SocketManager(Settings)) withDispatcher Settings.ManagerDispatcher,
    name = "IO-SOCKET")
}

/**
 * Sister class to HttpManagr; I basically copied and pasted the whole source
 * code of HttpManager because it keeps all its stuff private and not open for
 * extension.
 */
class SocketManager(httpSettings: HttpExt#Settings) extends HttpManager(httpSettings){
  import httpSettings._
  private[this] val listenerCounter = Iterator from 0
  private[this] val groupCounter = Iterator from 0
  private[this] val hostConnectorCounter = Iterator from 0

  private[this] var settingsGroups = Map.empty[ClientConnectionSettings, ActorRef]
  private[this] var hostConnectors = Map.empty[Http.HostConnectorSetup, ActorRef]
  private[this] var listeners = Seq.empty[ActorRef]

  override def receive = withTerminationManagement {
    case request: HttpRequest ⇒
      try {
        val req = request.withEffectiveUri(securedConnection = false)
        val Uri.Authority(host, port, _) = req.uri.authority
        val effectivePort = if (port == 0) Uri.defaultPorts(req.uri.scheme) else port
        val connector = hostConnectorFor(Http.HostConnectorSetup(host.toString, effectivePort, req.uri.scheme == "https"))
        // never render absolute URI here
        connector.forward(req.copy(uri = req.uri.copy(scheme = "", authority = Uri.Authority.Empty)))
      } catch {
        case NonFatal(e) ⇒
          log.error("Illegal request: {}", e.getMessage)
          sender ! Status.Failure(e)
      }

    case (request: HttpRequest, setup: Http.HostConnectorSetup) ⇒
      hostConnectorFor(setup).forward(request)

    case setup: Http.HostConnectorSetup ⇒
      val connector = hostConnectorFor(setup)
      sender.tell(Http.HostConnectorInfo(connector, setup), connector)

    case connect: Http.Connect ⇒
      settingsGroupFor(ClientConnectionSettings(connect.settings)).forward(connect)

    case bind: Http.Bind ⇒
      val commander = sender
      listeners :+= context.watch {
        context.actorOf(
          // The one thing I needed to change: HttpListener -> SocketListener
          props = Props(new SocketListener(commander, bind, httpSettings)) withDispatcher ListenerDispatcher,
          name = "listener-" + listenerCounter.next())
      }

    case cmd: Http.CloseAll ⇒ shutdownSettingsGroups(cmd, Set(sender))
  }

  override def withTerminationManagement(behavior: Receive): Receive = ({
    case ev @ Terminated(child) ⇒
      if (listeners contains child)
        listeners = listeners filter (_ != child)
      else if (hostConnectors exists (_._2 == child))
        hostConnectors = hostConnectors filter { _._2 != child }
      else
        settingsGroups = settingsGroups filter { _._2 != child }
      behavior.applyOrElse(ev, (_: Terminated) ⇒ ())

    case HttpHostConnector.DemandIdleShutdown ⇒
      hostConnectors = hostConnectors filter { _._2 != sender }
      sender ! PoisonPill
  }: Receive) orElse behavior

  override def shutdownSettingsGroups(cmd: Http.CloseAll, commanders: Set[ActorRef]): Unit =
    if (!settingsGroups.isEmpty) {
      val running: Set[ActorRef] = settingsGroups.map { x ⇒ x._2 ! cmd; x._2 }(collection.breakOut)
      context.become(closingSettingsGroups(cmd, running, commanders))
    } else shutdownHostConnectors(cmd, commanders)

  override def closingSettingsGroups(cmd: Http.CloseAll, running: Set[ActorRef], commanders: Set[ActorRef]): Receive =
    withTerminationManagement {
      case _: Http.CloseAll ⇒ context.become(closingSettingsGroups(cmd, running, commanders + sender))

      case Http.ClosedAll ⇒
        val stillRunning = running - sender
        if (stillRunning.isEmpty) shutdownHostConnectors(cmd, commanders)
        else context.become(closingSettingsGroups(cmd, stillRunning, commanders))

      case Terminated(child) if running contains child ⇒ self.tell(Http.ClosedAll, child)
    }

  override def shutdownHostConnectors(cmd: Http.CloseAll, commanders: Set[ActorRef]): Unit =
    if (!hostConnectors.isEmpty) {
      val running: Set[ActorRef] = hostConnectors.map { x ⇒ x._2 ! cmd; x._2 }(collection.breakOut)
      context.become(closingHostConnectors(running, commanders))
    } else shutdownListeners(commanders)

  override def closingHostConnectors(running: Set[ActorRef], commanders: Set[ActorRef]): Receive =
    withTerminationManagement {
      case cmd: Http.CloseCommand ⇒ context.become(closingHostConnectors(running, commanders + sender))

      case Http.ClosedAll ⇒
        val stillRunning = running - sender
        if (stillRunning.isEmpty) shutdownListeners(commanders)
        else context.become(closingHostConnectors(stillRunning, commanders))

      case Terminated(child) if running contains child ⇒ self.tell(Http.ClosedAll, child)
    }

  override def shutdownListeners(commanders: Set[ActorRef]): Unit = {
    listeners foreach { x ⇒ x ! Http.Unbind }
    context.become(unbinding(listeners.toSet, commanders))
    if (listeners.isEmpty) self ! Http.Unbound
  }

  override def unbinding(running: Set[ActorRef], commanders: Set[ActorRef]): Receive =
    withTerminationManagement {
      case cmd: Http.CloseCommand ⇒ context.become(unbinding(running, commanders + sender))

      case Http.Unbound ⇒
        val stillRunning = running - sender
        if (stillRunning.isEmpty) {
          commanders foreach (_ ! Http.ClosedAll)
          context.become(receive)
        } else context.become(unbinding(stillRunning, commanders))

      case Terminated(child) if running contains child ⇒ self.tell(Http.Unbound, child)
    }

  override def hostConnectorFor(setup: Http.HostConnectorSetup): ActorRef = {
    val normalizedSetup = setup.normalized

    def createAndRegisterHostConnector = {
      import normalizedSetup._
      val settingsGroup = settingsGroupFor(settings.get.connectionSettings) // must not be moved into the Props(...)!
      val hostConnector = context.actorOf(
          props = Props(new HttpHostConnector(normalizedSetup, settingsGroup)) withDispatcher HostConnectorDispatcher,
          name = "host-connector-" + hostConnectorCounter.next())
      hostConnectors = hostConnectors.updated(normalizedSetup, hostConnector)
      context.watch(hostConnector)
    }
    hostConnectors.getOrElse(normalizedSetup, createAndRegisterHostConnector)
  }

  override def settingsGroupFor(settings: ClientConnectionSettings): ActorRef = {
    def createAndRegisterSettingsGroup = {
      val group = context.actorOf(
        props = Props(new SocketClientSettingsGroup(settings, httpSettings)) withDispatcher SettingsGroupDispatcher,
        name = "group-" + groupCounter.next())
      settingsGroups = settingsGroups.updated(settings, group)
      context.watch(group)
    }
    settingsGroups.getOrElse(settings, createAndRegisterSettingsGroup)
  }
}

